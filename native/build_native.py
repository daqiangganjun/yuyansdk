import argparse
import os
from pathlib import Path
import platform
import shutil

from prepare_sources import NATIVE, LOCK, prepare, run
from prepare_assets import prepare as prepare_assets


def main():
    parser = argparse.ArgumentParser(description="Build the pinned Android Rime engine and dictionaries")
    parser.add_argument("--source-root", type=Path, default=NATIVE / ".cache")
    parser.add_argument("--sdk", type=Path, required=True)
    parser.add_argument("--skip-fetch", action="store_true")
    args = parser.parse_args()
    root = args.source_root.resolve()
    if not args.skip_fetch:
        prepare(root)
    sdk = args.sdk.resolve()
    suffix = ".exe" if os.name == "nt" else ""
    cmake = sdk / "cmake" / LOCK["cmake"] / "bin" / ("cmake" + suffix)
    ninja = cmake.parent / ("ninja" + suffix)
    ndk = sdk / "ndk" / LOCK["ndk"]
    build = NATIVE / "build/arm64-v8a"
    generated = NATIVE.parent / "build/generated/rime"
    run(cmake, "-S", NATIVE, "-B", build, "-G", "Ninja", f"-DCMAKE_MAKE_PROGRAM={ninja}",
        f"-DCMAKE_TOOLCHAIN_FILE={ndk}/build/cmake/android.toolchain.cmake", "-DANDROID_ABI=arm64-v8a",
        "-DANDROID_PLATFORM=android-23", "-DANDROID_STL=c++_static", "-DCMAKE_BUILD_TYPE=Release",
        f"-DDEPS_ROOT={root}")
    run(cmake, "--build", build, "--target", "selfopt_rime", "-j", max(1, (os.cpu_count() or 2) * 2 // 3))
    output = generated / "jniLibs/arm64-v8a/libselfopt_rime.so"
    output.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(build / "libselfopt_rime.so", output)
    host = "windows-x86_64" if os.name == "nt" else ("darwin-x86_64" if platform.system() == "Darwin" else "linux-x86_64")
    run(ndk / "toolchains/llvm/prebuilt" / host / "bin" / ("llvm-strip" + suffix), "--strip-unneeded", output)
    prepare_assets(root, generated)
    print(f"Native library generated: {output.stat().st_size} bytes")


if __name__ == "__main__":
    main()
