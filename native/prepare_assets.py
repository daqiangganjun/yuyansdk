import copy
import hashlib
import heapq
import json
from pathlib import Path
import re
import shutil
import zipfile

import yaml

from prepare_sources import NATIVE, LOCK


def read_yaml(path):
    return yaml.safe_load(path.read_text(encoding="utf-8-sig").replace("\t", "    "))


def write_yaml(path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(yaml.safe_dump(data, allow_unicode=True, sort_keys=False, width=120), encoding="utf-8", newline="\n")


def dictionary(repo, name, output, prefix, target=None):
    target = target or prefix + "_" + name.replace("/", "_")
    destination = output / (target + ".dict.yaml")
    if destination.exists():
        return target
    source = (repo / (name + ".dict.yaml")).read_text(encoding="utf-8-sig")
    header, body = re.split(r"^\.\.\.\s*$", source, maxsplit=1, flags=re.MULTILINE)
    data = yaml.safe_load(header)
    data["name"] = target
    if "import_tables" in data:
        data["import_tables"] = [dictionary(repo, child, output, prefix) for child in data["import_tables"]]
    destination.write_text(yaml.safe_dump(data, allow_unicode=True, sort_keys=False) + "...\n" + body,
                           encoding="utf-8")
    return target


def base_schema(schema_id, name, dictionary_name):
    return {
        "schema": {"schema_id": schema_id, "name": name, "version": "1"},
        "switches": [
            {"name": "ascii_mode", "reset": 0, "states": ["中文", "英文"]},
            {"name": "emoji", "reset": 1, "states": ["文字", "表情"]},
            {"name": "traditionalization", "reset": 0, "states": ["简体", "繁体"]}],
        "engine": {
            "processors": ["ascii_composer", "recognizer", "key_binder", "speller", "punctuator",
                           "selector", "navigator", "express_editor"],
            "segmentors": ["ascii_segmentor", "matcher", "abc_segmentor", "punct_segmentor", "fallback_segmentor"],
            "translators": ["punct_translator", "script_translator", "table_translator@melt_eng", "table_translator@cn_en"],
            "filters": ["lua_filter@*autocap_filter", "lua_filter@*reduce_english_filter",
                        "simplifier@emoji", "simplifier@traditionalize", "uniquifier"]},
        "menu": {"page_size": 100},
        "ascii_composer": {"good_old_caps_lock": False, "switch_key": {"Shift_L": "noop", "Shift_R": "noop"}},
        "speller": {"alphabet": "abcdefghijklmnopqrstuvwxyz", "delimiter": " '", "algebra": []},
        "translator": {"dictionary": dictionary_name, "prism": schema_id, "user_dict": dictionary_name,
                       "enable_user_dict": True, "enable_completion": True, "spelling_hints": 100,
                       "comment_format": [], "preedit_format": [], "initial_quality": 1.2},
        "melt_eng": {"dictionary": "melt_eng", "prism": "selfopt_english", "enable_sentence": False, "enable_user_dict": False,
                     "initial_quality": 0.7, "comment_format": ["xform/.*//"]},
        "cn_en": {"dictionary": "", "user_dict": "selfopt_cn_en", "db_class": "stabledb",
                  "enable_completion": True, "enable_sentence": False, "initial_quality": 0.1,
                  "comment_format": ["xform/.*//"]},
        "emoji": {"option_name": "emoji", "opencc_config": "emoji.json", "inherit_comment": False},
        "traditionalize": {"option_name": "traditionalization", "opencc_config": "s2t.json"},
        "reduce_english_filter": {"mode": "all", "idx": 2},
        "punctuator": {"import_preset": "default"},
        "key_binder": {"bindings": []},
    }


def prepare(root, generated):
    output = generated / "dictionary"
    if not output.resolve().is_relative_to((NATIVE.parent / "build/generated/rime").resolve()):
        raise ValueError("Generated dictionary path is outside the build workspace")
    output.mkdir(parents=True, exist_ok=True)
    # 只清理专属生成目录，生成物不能混入用户数据。
    for entry in output.iterdir():
        if entry.is_dir():
            shutil.rmtree(entry)
        else:
            entry.unlink()
    frost = root / "rime-frost"
    shutil.copytree(frost / "opencc", output / "opencc")
    shutil.copytree(frost / "lua", output / "lua")
    shutil.copy2(NATIVE / "data/selfopt_stroke.lua", output / "lua/selfopt_stroke.lua")
    shutil.copy2(frost / "essay.txt", output / "essay.txt")
    shutil.copy2(frost / "zh-moqi.gram", output / "zh-moqi.gram")
    dictionary(frost, "melt_eng", output, "english", "melt_eng")
    shutil.copy2(frost / "en_dicts/cn_en.txt", output / "selfopt_cn_en.txt")
    lx_source = (NATIVE.parent / "src/main/java/com/yuyan/inputmethod/util/LX17PinYinUtils.kt").read_text(encoding="utf-8")
    lx_entries = re.findall(r'lx17PinyinMap\.put\("([A-Z]+)", "([a-z,]+)"\)', lx_source)
    lx_rules = [f"derive/^{syllable}$/{keys}/" for keys, syllables in lx_entries for syllable in syllables.split(",")]
    schemas = []
    for family, repo, dict_name, label in (("frost", frost, "rime_frost", "白霜"),
                                           ("ice", root / "rime-ice", "rime_ice", "雾凇")):
        dictionary(repo, dict_name, output, family, dict_name)
        for logical in ("pinyin", "t9_pinyin", "double_pinyin_ls17", "double_pinyin_flypy",
                        "double_pinyin_natural", "double_pinyin_abc", "double_pinyin_mspy",
                        "double_pinyin_sogou", "double_pinyin_ziguang"):
            schema_id = f"selfopt_{family}_{logical}"
            config = base_schema(schema_id, f"{label} {logical}", dict_name)
            config["schema"]["dependencies"] = ["selfopt_english"]
            if family == "frost":
                config["grammar"] = {"language": "zh-moqi", "non_collocation_penalty": -4}
                config["translator"].update(grammar_penalty=-4, max_sentences=3, max_homophones=4, max_homographs=4)
            if logical == "t9_pinyin":
                rules = ["abbrev/^([a-z]).+$/$1/", "abbrev/^([zcs]h).+$/$1/"]
                rules += [f"derive/[{letters}]/{key}/" for letters, key in
                          (("abc", "A"), ("def", "D"), ("ghi", "G"), ("jkl", "J"),
                           ("mno", "M"), ("pqrs", "P"), ("tuv", "T"), ("wxyz", "W"))]
                config["speller"]["alphabet"] += "ADGJMPTW"
            elif logical == "double_pinyin_ls17":
                rules = ["abbrev/^([a-z]).+$/$1/", "abbrev/^([zcs]h).+$/$1/"] + lx_rules
                config["speller"]["alphabet"] += "HSZBXMLDYWJN CQGFT".replace(" ", "")
            else:
                if logical == "pinyin":
                    schema_file = repo / (dict_name + ".schema.yaml")
                else:
                    stem = logical.replace("_natural", "")
                    schema_file = repo / (("rime_frost_" if family == "frost" else "") + stem + ".schema.yaml")
                source = read_yaml(schema_file)
                config["speller"].update(copy.deepcopy(source["speller"]))
                rules = config["speller"].get("algebra", [])
            config["speller"]["algebra"] = ["__SELFOPT_FUZZY__"] + rules
            write_yaml(output / "templates" / (schema_id + ".schema.yaml"), config)
            schemas.append(schema_id)
    english = base_schema("selfopt_english", "英文", "melt_eng")
    english["engine"]["translators"] = ["punct_translator", "table_translator"]
    english["engine"]["filters"] = ["uniquifier"]
    english["speller"].update(alphabet="abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ-'", algebra=[])
    english_source = read_yaml(frost / "melt_eng.schema.yaml")
    english.update({key: value for key, value in english_source.items() if key.startswith("algebra_")})
    english["speller"]["algebra"] = copy.deepcopy(english_source["speller"]["algebra"])
    english["translator"].update(enable_sentence=False, spelling_hints=0)
    write_yaml(output / "selfopt_english.schema.yaml", english)
    dictionary(frost, "stroke", output, "stroke", "stroke")
    stroke = base_schema("selfopt_stroke", "五笔画", "stroke")
    stroke["engine"]["translators"] = ["punct_translator", "lua_translator@*selfopt_stroke", "table_translator"]
    stroke["engine"]["filters"] = ["simplifier@traditionalize", "uniquifier"]
    stroke["speller"].update(alphabet="hspnzxHSPNZX", algebra=[r"derive/^(.+)$/\U$1/"])
    stroke["translator"].update(preedit_format=["xlit/HSPNZhspnz/一丨丿丶乙一丨丿丶乙/"], enable_sentence=False)
    write_yaml(output / "selfopt_stroke.schema.yaml", stroke)
    frequencies = {}
    for line in (frost / "cn_dicts/8105.dict.yaml").read_text(encoding="utf-8-sig").splitlines():
        parts = line.split("\t")
        if len(parts) >= 3 and len(parts[0]) == 1:
            try:
                frequencies[parts[0]] = max(frequencies.get(parts[0], 0), float(parts[2]))
            except ValueError:
                continue
    strokes = []
    for line in (frost / "stroke.dict.yaml").read_text(encoding="utf-8-sig").splitlines():
        parts = line.split("\t")
        if len(parts) >= 2 and re.fullmatch("[hspnz]+", parts[1]):
            strokes.append((parts[0], parts[1]))
    strokes.sort(key=lambda entry: (len(entry[1]), -frequencies.get(entry[0], 0), entry[0]))
    (output / "selfopt_stroke.tsv").write_text("".join(f"{word}\t{code}\n" for word, code in strokes),
                                                encoding="utf-8", newline="\n")
    schemas += ["selfopt_english", "selfopt_stroke"]
    write_yaml(output / "default.yaml", {"config_version": "1", "schema_list": [{"schema": key} for key in schemas],
                                         "menu": {"page_size": 100}, "punctuator": {"half_shape": {}, "full_shape": {}}})
    # 由真实基础词频生成预测表，限制单前缀候选数以控制手机初始化峰值内存。
    def words():
        for repo in (frost, root / "rime-ice"):
            with (repo / "cn_dicts/base.dict.yaml").open(encoding="utf-8-sig") as stream:
                for line in stream:
                    parts = line.rstrip().split("\t")
                    if len(parts) >= 3 and re.fullmatch(r"[\u3400-\u9fff]{2,5}", parts[0]):
                        try:
                            yield float(parts[2]), parts[0]
                        except ValueError:
                            continue
    predictions = {}
    for weight, word in heapq.nlargest(40000, words()):
        for split in range(1, len(word)):
            values = predictions.setdefault(word[:split], {})
            suffix = word[split:]
            if suffix in values:
                values[suffix] = max(values[suffix], weight)
            elif len(values) < 16:
                values[suffix] = weight
    with (output / "predict.tsv").open("w", encoding="utf-8", newline="\n") as stream:
        for key, values in sorted(predictions.items()):
            for word, weight in sorted(values.items(), key=lambda item: (-item[1], item[0])):
                stream.write(f"{key}\t{word}\t{weight}\n")
    for repo in LOCK["repositories"]:
        source = root / repo["name"]
        for license_file in source.glob("LICENSE*"):
            target = output / "licenses" / repo["name"] / license_file.name
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(license_file, target)
    for name, folder in [("boost", root / "boost"), ("lua", root / "librime-lua-deps/lua5.4")] + [
        (name, root / "librime/deps" / name) for name in ("glog", "yaml-cpp", "leveldb", "marisa-trie", "opencc")
    ]:
        for pattern in ("LICENSE*", "COPYING*", "COPYRIGHT*"):
            for source in folder.glob(pattern):
                if source.is_file():
                    destination = output / "licenses" / name / source.name
                    destination.parent.mkdir(parents=True, exist_ok=True)
                    shutil.copy2(source, destination)
    # Lua 的完整 MIT 声明位于头文件中，随源依赖一并保留。
    lua_header = root / "librime-lua-deps/lua5.4/lua.h"
    (output / "licenses/lua").mkdir(parents=True, exist_ok=True)
    shutil.copy2(lua_header, output / "licenses/lua/lua.h")
    (output / "sources.json").write_text(json.dumps(LOCK, indent=2), encoding="utf-8")
    assets = generated / "assets"
    assets.mkdir(parents=True, exist_ok=True)
    archive_path = assets / "rime-selfopt.zip"
    with zipfile.ZipFile(archive_path, "w", zipfile.ZIP_DEFLATED, compresslevel=6) as archive:
        for path in sorted(output.rglob("*")):
            if path.is_file():
                entry = zipfile.ZipInfo(path.relative_to(output).as_posix(), (2026, 1, 1, 0, 0, 0))
                entry.compress_type = zipfile.ZIP_DEFLATED
                archive.writestr(entry, path.read_bytes())
    with archive_path.open("rb") as stream:
        revision = hashlib.file_digest(stream, "sha256").hexdigest()
    (assets / "rime-selfopt.version").write_text(revision, encoding="ascii")
    print(f"Dictionary bundle generated: {archive_path.stat().st_size} bytes, {len(schemas)} schemas")
