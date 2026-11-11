#!/usr/bin/env python3
"""Privately rebuild the exact pinned Companion from verified official source.

Downloads source, never a replacement binary. Does not publish or modify NPShips
dependencies. Existing differing output files are refused; failed work is retained.
"""

import argparse
import hashlib
import io
import json
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import stat
import subprocess
import sys
import urllib.request
import zipfile


def sha(path):
    result = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            result.update(block)
    return result.hexdigest()


def matches(path, record):
    return path.is_file() and not path.is_symlink() and path.stat().st_size == record["bytes"] and sha(path) == record["sha256"]


def check_destinations(directory, records):
    for record in records:
        name = record["file"]
        if Path(name).name != name or name in ("", ".", "..") or any(c in name for c in "/\\:"):
            raise ValueError("Unsafe output filename in bootstrap manifest")
        path = directory / name
        if (path.exists() or path.is_symlink()) and not matches(path, record):
            raise ValueError("Refusing differing existing output: " + str(path))


def source_inputs(data, config):
    if hashlib.sha256(data).hexdigest() != config["sourceArchiveSha256"]:
        raise ValueError("Official source archive SHA-256 mismatch; no source executed")
    with zipfile.ZipFile(io.BytesIO(data)) as archive:
        names = archive.namelist()
        if len(names) != len(set(names)):
            raise ValueError("Duplicate ZIP entry")
        for entry in archive.infolist():
            path = PurePosixPath(entry.filename)
            if path.is_absolute() or ".." in path.parts or "\\" in entry.filename or ":" in entry.filename or stat.S_ISLNK(entry.external_attr >> 16):
                raise ValueError("Unsafe source archive entry")
        result = []
        total = 0
        for record in config["sourceFiles"]:
            name = record["path"]
            path = PurePosixPath(name)
            if path.is_absolute() or ".." in path.parts or any(c in name for c in "\\:"):
                raise ValueError("Unsafe source allowlist path")
            info = archive.getinfo(config["archivePrefix"] + name)
            if info.file_size != record["bytes"] or info.file_size > 2 * 1024 * 1024:
                raise ValueError("Source size mismatch: " + name)
            total += info.file_size
            if total > 16 * 1024 * 1024:
                raise ValueError("Source allowlist exceeds bounded size")
            content = archive.read(info)
            if hashlib.sha256(content).hexdigest() != record["sha256"]:
                raise ValueError("Source hash mismatch: " + name)
            result.append((name, content))
        return result


def build(work, output, config, java_home):
    if work.exists() or work.is_symlink():
        raise ValueError("Use a new work directory; existing work is never cleaned: " + str(work))
    check_destinations(output, config["outputs"])
    javac = java_home / "bin" / ("javac.exe" if os.name == "nt" else "javac")
    result = subprocess.run([str(javac), "-version"], capture_output=True, text=True, check=True)
    if not re.search(r"\bjavac 21(?:\.|\s|$)", result.stdout + result.stderr):
        raise ValueError("Companion bootstrap requires a Java 21 JDK in JAVA_HOME")
    request = urllib.request.Request(config["sourceUrl"], headers={"User-Agent": "NPShips-exact-source-bootstrap/1.0"})
    with urllib.request.urlopen(request, timeout=60) as response:
        data = response.read(16 * 1024 * 1024 + 1)
    if len(data) > 16 * 1024 * 1024:
        raise ValueError("Source archive exceeds 16 MiB; no source executed")
    files = source_inputs(data, config)
    work.mkdir(parents=True, exist_ok=False)
    for name, content in files:
        target = work / name
        target.parent.mkdir(parents=True, exist_ok=True)
        with target.open("xb") as stream:
            stream.write(content)
    catalog = work / "libs.versions.toml"
    text = catalog.read_text(encoding="utf-8")
    if text.count('loom = "1.16.+"') != 1:
        raise ValueError("Unexpected upstream Loom declaration")
    catalog.write_text(text.replace('loom = "1.16.+"', 'loom = "' + config["loomVersion"] + '"'), encoding="utf-8")
    wrapper = work / "gradle/wrapper/gradle-wrapper.properties"
    with wrapper.open("a", encoding="utf-8") as stream:
        stream.write("\ndistributionSha256Sum=" + config["gradleDistributionSha256"] + "\n")
    command = [str(work / "gradlew.bat")] if os.name == "nt" else ["bash", str(work / "gradlew")]
    command += [":sable-companion-common:build", "--no-parallel", "--no-build-cache", "--console=plain"]
    environment = dict(os.environ, JAVA_HOME=str(java_home))
    print("Building verified official source in " + str(work), flush=True)
    with (work / "bootstrap-build.log").open("xb") as log:
        completed = subprocess.run(command, cwd=work, env=environment, stdout=log, stderr=subprocess.STDOUT)
    if completed.returncode:
        raise RuntimeError("Companion build failed; preserved log: " + str(work / "bootstrap-build.log"))
    built = work / "common/build/libs"
    for record in config["outputs"]:
        if not matches(built / record["file"], record):
            raise ValueError("Rebuilt output differs from the required pin; no replacement accepted: " + record["file"])
    output.mkdir(parents=True, exist_ok=True)
    check_destinations(output, config["outputs"])
    for record in config["outputs"]:
        target = output / record["file"]
        if target.exists():
            continue
        # Exclusive create also protects against a destination appearing after preflight.
        with (built / record["file"]).open("rb") as source, target.open("xb") as destination:
            shutil.copyfileobj(source, destination, 1024 * 1024)
        if not matches(target, record):
            raise ValueError("Output copy failed verification; retained for inspection: " + str(target))
    print("Exact Companion binary and sources SHA-256 verified; private outputs: " + str(output), flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--work-dir", required=True, type=Path, help="New disposable source/build directory")
    parser.add_argument("--output-dir", required=True, type=Path, help="Private provisioned-input directory; no overwrite")
    args = parser.parse_args()
    home = os.environ.get("JAVA_HOME")
    if not home:
        parser.error("Set JAVA_HOME to a Java 21 JDK")
    config = json.loads((Path(__file__).resolve().parents[1] / "gradle/companion-bootstrap.json").read_text(encoding="utf-8"))
    build(args.work_dir.absolute(), args.output_dir.absolute(), config, Path(home).resolve())


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError, RuntimeError, zipfile.BadZipFile, subprocess.CalledProcessError) as error:
        print("Companion bootstrap: " + str(error), file=sys.stderr)
        sys.exit(1)
