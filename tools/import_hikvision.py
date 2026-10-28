#!/usr/bin/env python3
"""Import an already acquired, licensed Android HCNetSDK archive or directory.
Never download or accept a license on the user's behalf.
"""
import argparse
import hashlib
import io
import json
import os
from pathlib import Path
import shutil
import tempfile
import zipfile

ABIS = {"armeabi-v7a", "arm64-v8a", "x86", "x86_64"}
REQUIRED_CLASS = "com/hikvision/netsdk/HCNetSDK.class"

def collect(source):
    if source.is_dir():
        for path in sorted(source.rglob("*")):
            if path.is_file() and not path.is_symlink() and path.suffix.lower() in {".so", ".jar"}:
                yield path.relative_to(source).as_posix(), path.read_bytes()
    else:
        with zipfile.ZipFile(source) as archive:
            total = 0
            for member in archive.infolist():
                if Path(member.filename).suffix.lower() not in {".so", ".jar"}:
                    continue
                total += member.file_size
                if total > 1024 * 1024 * 1024:
                    raise ValueError("SDK libraries exceed 1 GiB; inspect archive manually")
                yield member.filename, archive.read(member)

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("sdk", type=Path)
    args = parser.parse_args()
    source = args.sdk.resolve()
    target = Path(__file__).resolve().parents[1] / "vendor" / "hikvision" / "sdk"
    if target.exists():
        raise ValueError(f"{target} already exists; move the existing SDK aside before replacing it")
    selected = {}
    has_sdk = False
    for name, content in collect(source):
        path = Path(name)
        if path.suffix.lower() == ".jar":
            try:
                with zipfile.ZipFile(io.BytesIO(content)) as jar:
                    names = jar.namelist()
                    has_sdk |= REQUIRED_CLASS in names
                    # Only import vendor SDK classes, not demo support or test libraries.
                    if not any(n.startswith(("com/hikvision/", "org/MediaPlayer/", "com/sun/jna/")) for n in names):
                        continue
            except zipfile.BadZipFile:
                raise ValueError(f"Invalid JAR: {name}")
            destination = "libs/" + path.name
        else:
            abi = next((part for part in path.parts if part in ABIS), None)
            if abi is None:
                continue
            if content[:4] != b"\x7fELF":
                raise ValueError(f"Not an ELF shared library: {name}")
            destination = "jniLibs/" + abi + "/" + path.name
        if destination in selected and selected[destination] != content:
            raise ValueError(f"Conflicting versions of {destination}; provide a single SDK library directory")
        selected[destination] = content
    if not has_sdk:
        raise ValueError("Android JAR containing com/hikvision/netsdk/HCNetSDK.class not found")
    if not any(name.startswith("jniLibs/") and "hcnetsdk" in name.lower() for name in selected):
        raise ValueError("Android HCNetSDK native libraries not found")
    target.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="hikvision-import-", dir=target.parent) as temporary:
        staging = Path(temporary) / "sdk"
        manifest = {}
        for name, content in selected.items():
            dest = staging / name
            dest.parent.mkdir(parents=True, exist_ok=True)
            dest.write_bytes(content)
            manifest[name] = hashlib.sha256(content).hexdigest()
        (staging / "sha256.json").write_text(json.dumps(manifest, indent=2) + "\n")
        staging.rename(target)
    print(f"Imported {len(selected)} library files into {target}")
    print("Next: inspect JAR signatures, verify ABI and ELF page alignment, build, and test on a physical device.")

if __name__ == "__main__":
    main()
