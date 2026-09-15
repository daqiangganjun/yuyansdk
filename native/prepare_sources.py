import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import tarfile
import urllib.request


NATIVE = Path(__file__).resolve().parent
LOCK = json.loads((NATIVE / "dependencies.lock.json").read_text(encoding="utf-8"))


def run(*args, cwd=None):
    subprocess.run([str(arg) for arg in args], cwd=cwd, check=True)


def prepare(root):
    root.mkdir(parents=True, exist_ok=True)
    for repo in LOCK["repositories"]:
        folder = root / repo["name"]
        if not (folder / ".git").exists():
            folder.mkdir(parents=True, exist_ok=True)
            run("git", "init", folder)
        current = subprocess.run(["git", "-C", str(folder), "rev-parse", "HEAD"],
                                 capture_output=True, text=True)
        if current.returncode or current.stdout.strip() != repo["revision"]:
            run("git", "-C", folder, "fetch", "--depth", "1", repo["url"], repo["revision"])
            run("git", "-C", folder, "checkout", "--detach", repo["revision"])
        if repo.get("recursive"):
            run("git", "-C", folder, "submodule", "update", "--init", "--recursive", "--depth", "1")
    if not (root / "boost/CMakeLists.txt").is_file():
        archive = root / "boost-1.89.0.tar.xz"
        if not archive.exists():
            partial = archive.with_suffix(".download")
            with urllib.request.urlopen(LOCK["boost"]["url"], timeout=120) as response, partial.open("wb") as out:
                shutil.copyfileobj(response, out)
            with partial.open("rb") as stream:
                if hashlib.file_digest(stream, "sha256").hexdigest() != LOCK["boost"]["sha256"]:
                    raise RuntimeError("Downloaded Boost archive checksum mismatch")
            partial.replace(archive)
        with archive.open("rb") as stream:
            checksum = hashlib.file_digest(stream, "sha256").hexdigest()
        if checksum != LOCK["boost"]["sha256"]:
            raise RuntimeError("Boost archive checksum mismatch")
        with tarfile.open(archive) as tar:
            tar.extractall(root, filter="data")
        (root / "boost-1.89.0").rename(root / "boost")
    # 插件属于固定提交的构建输入，复制到 librime 约定的插件目录。
    for name, target in (("librime-lua", "lua"), ("librime-octagram", "librime-octagram"),
                         ("librime-predict", "librime-predict")):
        destination = root / "librime/plugins" / target
        revision = next(repo["revision"] for repo in LOCK["repositories"] if repo["name"] == name)
        if target == "lua":
            revision += ":" + next(repo["revision"] for repo in LOCK["repositories"] if repo["name"] == "librime-lua-deps")
        stamp = destination / ".selfopt-revision"
        if stamp.exists() and stamp.read_text() == revision:
            continue
        shutil.copytree(root / name, destination, dirs_exist_ok=True,
                        ignore=shutil.ignore_patterns(".git", "build", "__pycache__"))
        if target == "lua":
            shutil.copytree(root / "librime-lua-deps", destination / "thirdparty", dirs_exist_ok=True,
                            ignore=shutil.ignore_patterns(".git"))
        stamp.write_text(revision)
    return root
