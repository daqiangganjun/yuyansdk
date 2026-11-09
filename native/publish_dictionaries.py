import argparse
import copy
import hashlib
import json
import os
from pathlib import Path
import subprocess
import urllib.request
import zipfile

import yaml

import prepare_assets
from prepare_sources import LOCK, NATIVE


def github(path):
    headers = {"User-Agent": "YuyanSelfOpt-Dictionaries"}
    if os.environ.get("GITHUB_TOKEN"):
        headers["Authorization"] = "Bearer " + os.environ["GITHUB_TOKEN"]
    request = urllib.request.Request("https://api.github.com/" + path, headers=headers)
    with urllib.request.urlopen(request, timeout=60) as response:
        return json.load(response)


def main():
    parser = argparse.ArgumentParser(description="Build independent dictionary release packages")
    parser.add_argument("--latest", action="store_true")
    parser.add_argument("--check-current", action="store_true")
    parser.add_argument("--source-root", type=Path, default=NATIVE / ".cache")
    parser.add_argument("--output", type=Path, default=NATIVE.parent / "build/generated/rime/release")
    parser.add_argument("--release-base", default="https://github.com/daqiangganjun/YuyanSelfOpt/releases/download/dictionaries-v1")
    args = parser.parse_args()
    source = args.source_root.resolve()
    lock = copy.deepcopy(LOCK)
    descriptions = {}
    recipe = hashlib.sha256()
    for path in (NATIVE / "prepare_assets.py", NATIVE / "publish_dictionaries.py", NATIVE / "data/selfopt_stroke.lua",
                 NATIVE.parent / "src/main/java/com/yuyan/inputmethod/util/LX17PinYinUtils.kt"):
        recipe.update(path.read_text(encoding="utf-8").replace("\r\n", "\n").encode("utf-8"))
    for repo in lock["repositories"]:
        if repo["name"] not in ("rime-frost", "rime-ice"):
            continue
        project = repo["url"].removeprefix("https://github.com/").removesuffix(".git")
        if args.latest:
            commit = github("repos/" + project + "/commits/HEAD")
            repo["revision"] = commit["sha"]
            message = commit["commit"]["message"]
            descriptions[repo["name"]] = (message.split("\n\n")[-1] if message.startswith("Merge ") else message).split("\n")[0]
    if args.check_current:
        try:
            with urllib.request.urlopen(args.release_base.rstrip("/") + "/index.json", timeout=30) as response:
                current = json.load(response)
            desired = {repo["name"].removeprefix("rime-"): repo["revision"] for repo in lock["repositories"]
                       if repo["name"] in ("rime-frost", "rime-ice")}
            if current.get("recipe") == recipe.hexdigest() and {item["family"]: item["revision"] for item in current["packages"]} == desired:
                print("Dictionary sources and recipe are unchanged")
                return
        except (OSError, ValueError, KeyError):
            pass
    for repo in lock["repositories"]:
        if repo["name"] not in ("rime-frost", "rime-ice"):
            continue
        folder = source / repo["name"]
        folder.mkdir(parents=True, exist_ok=True)
        if not (folder / ".git").exists():
            subprocess.run(["git", "init", str(folder)], check=True)
        current = subprocess.run(["git", "-C", str(folder), "rev-parse", "HEAD"], capture_output=True, text=True)
        if current.returncode or current.stdout.strip() != repo["revision"]:
            subprocess.run(["git", "-C", str(folder), "fetch", "--depth", "1", repo["url"], repo["revision"]], check=True)
            subprocess.run(["git", "-C", str(folder), "checkout", "--detach", repo["revision"]], check=True)
    prepare_assets.LOCK = lock
    generated = NATIVE.parent / "build/generated/rime/release-work"
    prepare_assets.prepare(source, generated, include_ice=True)
    root = generated / "dictionary"
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    index = {"format": 1, "compatibility": "selfopt-1", "minAppVersion": 50300, "recipe": recipe.hexdigest(), "packages": []}
    for family in ("frost", "ice"):
        revision = next(repo["revision"] for repo in lock["repositories"] if repo["name"] == "rime-" + family)
        metadata = {"format": 1, "family": family, "revision": revision, "compatibility": "selfopt-1",
                    "updatedAt": int(subprocess.check_output(["git", "-C", str(source / ("rime-" + family)), "show", "-s", "--format=%ct", "HEAD"], text=True).strip()) * 1000}
        target = output / (family + ".zip")
        with zipfile.ZipFile(target, "w", zipfile.ZIP_DEFLATED, compresslevel=6) as archive:
            def put(name, content):
                entry = zipfile.ZipInfo(name, (2026, 1, 1, 0, 0, 0))
                entry.compress_type = zipfile.ZIP_DEFLATED
                archive.writestr(entry, content)
            for path in sorted(root.rglob("*")):
                if not path.is_file():
                    continue
                relative = path.relative_to(root).as_posix()
                ice_file = (path.name.startswith("ice_") or path.name.startswith("selfopt_ice_") or
                            path.name == "rime_ice.dict.yaml" or relative.startswith("licenses/rime-ice/"))
                if relative in ("bundle.json", "default.yaml", "sources.json") or (family == "ice") != ice_file:
                    continue
                put(relative, path.read_bytes())
            put("bundle.json", json.dumps(metadata))
            if family == "frost":
                default = yaml.safe_load((root / "default.yaml").read_text(encoding="utf-8"))
                default["schema_list"] = [item for item in default["schema_list"] if not item["schema"].startswith("selfopt_ice_")]
                put("default.yaml", yaml.safe_dump(default))
            subset = copy.deepcopy(lock)
            subset["repositories"] = [repo for repo in subset["repositories"] if repo["name"] != "rime-" + ("ice" if family == "frost" else "frost")]
            put("sources.json", json.dumps(subset))
        with target.open("rb") as stream:
            digest = hashlib.file_digest(stream, "sha256").hexdigest()
        final = output / (family + "-" + digest + ".zip")
        target.replace(final)
        index["packages"].append({"family": family, "revision": revision, "sha256": digest,
                                   "size": final.stat().st_size, "compatibility": "selfopt-1",
                                   "updatedAt": metadata["updatedAt"],
                                   "summary": descriptions.get("rime-" + family, "词库数据更新"),
                                   "url": args.release_base.rstrip("/") + "/" + final.name})
    (output / "index.json").write_text(json.dumps(index, ensure_ascii=False, indent=2), encoding="utf-8")
    print("Dictionary release packages generated")


if __name__ == "__main__":
    main()
