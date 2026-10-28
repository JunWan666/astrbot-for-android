"""Read-only release inventory and public-source/notice collection. No device access.

This is an evidence collector, not a license-compliance or redistribution approval.
Only pinned build images, resolved Maven artifacts and allowlisted manager sources
are read. Never extracts upstream archives onto the host filesystem.
"""
from __future__ import annotations

import argparse
from email.parser import Parser
import hashlib
import io
import json
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import tarfile
import tempfile
import xml.etree.ElementTree as ET
import zipfile
from repository_layout import is_public_document

ROOT = Path(__file__).resolve().parents[2]
NAPCAT = "opt/qq/resources/app/app_launcher/napcat"
PYTHON = "opt/astrbot-venv/lib/python3.12/site-packages/"
MAX_TEXT = 16 * 1024 * 1024
MAX_TOTAL_TEXT = 128 * 1024 * 1024
FIXED_RUNTIME = {
    "var/lib/dpkg/status", "var/lib/astrbot-manager/system-packages.lock",
    "var/lib/astrbot-manager/astrbot-python.lock.txt", "opt/astrbot/LICENSE",
    "opt/astrbot/dashboard/package.json", "opt/qq/LICENSE.electron.txt",
    "opt/qq/LICENSES.chromium.html", "opt/qq/resources/app/package.original.json",
}
SOURCE_ROOT_FILES = {
    ".gitignore", ".gitattributes", ".github/workflows/ci.yml", ".github/CODEOWNERS",
    ".github/workflows/renew-updates.yml", "runtime/update-public-key.der",
    "app/src/main/res/drawable-nodpi/logo_napcat.webp",
    "app/src/main/res/drawable-nodpi/app_logo.webp",
    "app/src/main/assets/terminal/vendor/xterm-LICENSE",
    "app/src/main/assets/terminal/vendor/addon-fit-LICENSE",
    "app/src/main/assets/runtime/desktop/guest-compat",
    *{f"app/src/main/res/mipmap-{density}/ic_launcher.png" for density in ("mdpi", "hdpi", "xhdpi", "xxhdpi", "xxxhdpi")},
    "README.md", "LICENSE",
    "build.gradle.kts", "settings.gradle.kts", "gradle.properties", "gradlew", "gradlew.bat", "build.bat",
}
SOURCE_EXTENSIONS = {
    ".kt", ".kts", ".java", ".xml", ".py", ".c", ".h", ".sh", ".js",
    ".json", ".yaml", ".yml", ".txt", ".md", ".properties", ".ps1", ".html", ".css",
}


def json_bytes(value) -> bytes:
    return (json.dumps(value, ensure_ascii=False, indent=2, sort_keys=True) + "\n").encode("utf-8")


def digest(path: Path) -> str:
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def verify(path: Path, expected: str) -> str:
    actual = digest(path)
    if actual != expected:
        raise ValueError(f"SHA-256 mismatch: {path.name}")
    return actual


def clean_path(name: str) -> str:
    # Some tar producers prefix all members with ./; no other traversal is accepted.
    while name.startswith("./"):
        name = name[2:]
    name = name.rstrip("/")
    if (not name or "\\" in name or "\x00" in name or ":" in name or
            name.startswith("/") or any(p in ("", ".", "..") for p in name.split("/"))):
        raise ValueError("Unsafe archive path")
    return name


def notice_name(name: str) -> bool:
    return bool(re.match(r"(?i)^(licen[cs]e|copying|copyright|notice)([._-].*)?$", PurePosixPath(name).name))


def selected_runtime(name: str) -> bool:
    return (name in FIXED_RUNTIME or name.startswith("usr/share/common-licenses/") or
            (name.startswith("usr/share/doc/") and name.endswith("/copyright")) or
            (name.startswith(PYTHON) and ".dist-info/" in name and
             (name.endswith("/METADATA") or "/licenses/" in name or notice_name(name))) or
            (name.startswith(NAPCAT + "/") and
             (name.endswith("/package.json") or notice_name(name))))


def link_target(name: str, target: str, hardlink: bool = False) -> str:
    if "\\" in target or "\x00" in target or ":" in target:
        raise ValueError("Unsafe archive link")
    parts = [] if hardlink or target.startswith("/") else name.split("/")[:-1]
    for part in target.split("/"):
        if part in ("", "."):
            continue
        if part == "..":
            if not parts:
                raise ValueError("Archive link escapes root")
            parts.pop()
        else:
            parts.append(part)
    result = clean_path("/".join(parts))
    if not result.startswith(("usr/share/doc/", "usr/share/common-licenses/")):
        raise ValueError("Copyright link leaves public notice roots")
    return result


def resolve_notice(name: str, files: dict[str, bytes], links: dict[str, str]) -> str | None:
    visited = set()
    for _ in range(32):
        if name in visited:
            raise ValueError("Copyright link cycle")
        visited.add(name)
        # A symlink on any ancestor takes precedence over a descendant pathname.
        parts = name.split("/")
        for end in range(1, len(parts) + 1):
            prefix = "/".join(parts[:end])
            if prefix in links:
                name = links[prefix] + ("/" + "/".join(parts[end:]) if end < len(parts) else "")
                break
        else:
            return name if name in files else None
    raise ValueError("Copyright link depth exceeded")


def scan_runtime(path: Path) -> tuple[dict[str, bytes], dict[str, str], int]:
    files: dict[str, bytes] = {}
    links: dict[str, str] = {}
    entries = total = 0
    with tarfile.open(path, "r|gz") as archive:
        for member in archive:
            if member.name in (".", "./"):
                continue
            # Linux systemd legitimately uses literal backslashes in unit filenames.
            # Unselected paths stay in memory only; selected ZIP paths remain portable.
            name = member.name
            while name.startswith("./"):
                name = name[2:]
            name = name.rstrip("/")
            if (not name or name.startswith("/") or "\x00" in name or
                    any(part in ("", ".", "..") for part in name.split("/"))):
                raise ValueError("Unsafe archive path")
            entries += 1
            public_link = (name.startswith("usr/share/common-licenses/") or
                           (name.startswith("usr/share/doc/") and
                            (name.count("/") == 3 or name.endswith("/copyright"))))
            if public_link and (member.issym() or member.islnk()):
                clean_path(name)
                if name in links or name in files:
                    raise ValueError("Duplicate notice entry")
                links[name] = link_target(name, member.linkname, member.islnk())
            elif selected_runtime(name):
                clean_path(name)
                if not member.isfile():
                    if member.isdir():
                        continue
                    raise ValueError(f"Non-regular public metadata: {name}")
                if member.size > MAX_TEXT or total + member.size > MAX_TOTAL_TEXT:
                    raise ValueError("Public metadata exceeds collection limit")
                if name in files or name in links:
                    raise ValueError("Duplicate notice entry")
                files[name] = archive.extractfile(member).read()
                total += member.size
    for required in FIXED_RUNTIME:
        if required not in files:
            raise ValueError(f"Missing runtime metadata: {required}")
    return files, links, entries


def debian_fields(text: str) -> list[dict[str, str]]:
    result = []
    for block in re.split(r"\n\s*\n", text.replace("\r\n", "\n").strip()):
        row: dict[str, str] = {}
        previous = None
        for line in block.splitlines():
            if line.startswith((" ", "\t")) and previous:
                row[previous] += "\n" + line[1:]
            elif ":" in line:
                previous, value = line.split(":", 1)
                row[previous] = value.strip()
        if row:
            result.append(row)
    return result


def system_packages(files: dict[str, bytes], links: dict[str, str]) -> list[dict]:
    result = []
    for fields in debian_fields(files["var/lib/dpkg/status"].decode("utf-8")):
        if fields.get("Status") != "install ok installed":
            continue
        name, version = fields["Package"], fields["Version"]
        source = fields.get("Source", name)
        match = re.fullmatch(r"([^\s()]+)(?:\s+\(([^()]+)\))?", source)
        if not match:
            raise ValueError("Invalid Debian Source field")
        copyright_path = resolve_notice(f"usr/share/doc/{name}/copyright", files, links)
        result.append({"name": name, "version": version, "architecture": fields["Architecture"],
                       "sourceName": match[1], "sourceVersion": match[2] or version,
                       "copyright": "runtime/" + copyright_path if copyright_path else None,
                       "correspondingSource": "not-collected"})
    return sorted(result, key=lambda row: row["name"])


def python_packages(files: dict[str, bytes]) -> list[dict]:
    result = []
    for path, data in sorted(files.items()):
        if not path.startswith(PYTHON) or not path.endswith(".dist-info/METADATA"):
            continue
        message = Parser().parsestr(data.decode("utf-8"))
        parent = path.rsplit("/", 1)[0] + "/"
        result.append({"name": message["Name"], "version": message["Version"],
                       "licenseExpression": message["License-Expression"], "licenseField": message["License"],
                       "licenseClassifiers": [c for c in message.get_all("Classifier", []) if c.startswith("License ::")],
                       "projectUrls": message.get_all("Project-URL", []),
                       "metadata": "runtime/" + path,
                       "notices": ["runtime/" + p for p in sorted(files) if p.startswith(parent) and p != path],
                       "correspondingSource": "not-collected; wheel may contain vendored native code"})
    return result


def npm_packages(files: dict[str, bytes]) -> list[dict]:
    result = []
    for path, data in sorted(files.items()):
        if not path.startswith(NAPCAT + "/") or not path.endswith("/package.json"):
            continue
        fields = json.loads(data)
        parent = path.rsplit("/", 1)[0] + "/"
        # Nested dependencies own their notices. Do not assign their license to the parent.
        own_notices = ["runtime/" + p for p in sorted(files) if p.startswith(parent) and
                       "node_modules" not in PurePosixPath(p[len(parent):]).parts and notice_name(p)]
        result.append({"name": fields.get("name"), "version": fields.get("version"),
                       "licenseField": fields.get("license", fields.get("licenses")),
                       "repository": fields.get("repository"), "metadata": "runtime/" + path,
                       "notices": own_notices,
                       "correspondingSource": "not-collected; inventory covers external package.json files only"})
    return result


def add_bytes(target: dict[str, bytes], name: str, data: bytes) -> None:
    name = clean_path(name)
    if len(data) > MAX_TEXT or name in target:
        raise ValueError("Oversize or duplicate collected text")
    target[name] = data


def archive_notices(path: Path, prefix: str, target: dict[str, bytes]) -> None:
    # Keep source-relative paths, never flatten separate upstream LICENSE files.
    if zipfile.is_zipfile(path):
        with zipfile.ZipFile(path) as archive:
            for item in archive.infolist():
                name = clean_path(item.filename)
                if not item.is_dir() and notice_name(name):
                    if item.file_size > MAX_TEXT:
                        raise ValueError("Oversize source notice")
                    add_bytes(target, prefix + "/" + name, archive.read(item))
    else:
        with tarfile.open(path, "r|*") as archive:
            for item in archive:
                if item.name in (".", "./"):
                    continue
                name = clean_path(item.name)
                if item.isfile() and notice_name(name):
                    if item.size > MAX_TEXT:
                        raise ValueError("Oversize source notice")
                    add_bytes(target, prefix + "/" + name, archive.extractfile(item).read())
                # Preserve license grants in library source headers as evidence.
                if prefix == "sources/talloc" and name.endswith(("/talloc.c", "/talloc.h")):
                    add_bytes(target, prefix + "/" + name, archive.extractfile(item).read())


def maven_metadata(metadata_dir: Path, notices: dict[str, bytes]) -> tuple[list[dict], dict[str, Path]]:
    source_map: dict[str, list[Path]] = {}
    for line in (metadata_dir / "sources.tsv").read_text("utf-8").splitlines():
        coordinate, path, status = line.split("\t")
        if status == "resolved":
            source_map.setdefault(coordinate, []).append(Path(path))
    pom_map: dict[str, list[Path]] = {}
    for line in (metadata_dir / "poms.tsv").read_text("utf-8").splitlines():
        coordinate, path, status = line.split("\t")
        if status == "resolved":
            pom_map.setdefault(coordinate, []).append(Path(path))
    artifacts = []
    source_files = {}
    for line in dict.fromkeys((metadata_dir / "artifacts.tsv").read_text("utf-8").splitlines()):
        group, name, version, kind, expected, local_path = line.split("\t")
        artifact = Path(local_path)
        verify(artifact, expected)
        coordinate = f"{group}:{name}:{version}"
        if group == "AstrBotAndroid" and name == "core":
            artifacts.append({"coordinate": coordinate, "kind": kind, "sha256": expected,
                              "origin": "manager-source; MIT; copyright 2026 JunWan666"})
            continue
        base = f"android/{group}/{name}/{version}"
        poms = sorted(set(pom_map.get(coordinate, [])))
        if len(poms) != 1:
            raise ValueError(f"Expected one resolved POM: {coordinate}")
        pom_data = poms[0].read_bytes()
        add_bytes(notices, base + "/metadata.pom", pom_data)
        pom = ET.fromstring(pom_data)
        ns = {"m": "http://maven.apache.org/POM/4.0.0"}
        licenses = [{"name": item.findtext("m:name", namespaces=ns),
                     "url": item.findtext("m:url", namespaces=ns)}
                    for item in pom.findall("m:licenses/m:license", ns)]
        with zipfile.ZipFile(artifact) as archive:
            for entry in archive.infolist():
                entry_name = clean_path(entry.filename)
                if not entry.is_dir() and notice_name(entry_name):
                    add_bytes(notices, base + "/artifact/" + entry_name, archive.read(entry))
            # AAR licenses are sometimes in the classes.jar rather than at AAR level.
            if "classes.jar" in archive.namelist():
                with zipfile.ZipFile(io.BytesIO(archive.read("classes.jar"))) as classes:
                    for entry in classes.infolist():
                        entry_name = clean_path(entry.filename)
                        if not entry.is_dir() and notice_name(entry_name):
                            add_bytes(notices, base + "/classes/" + entry_name, classes.read(entry))
        sources = []
        for source in sorted(set(source_map.get(coordinate, []))):
            key = f"android/{group}/{name}/{version}/{source.name}"
            source_files[key] = source
            sources.append({"path": key, "sha256": digest(source), "bytes": source.stat().st_size})
            archive_notices(source, base + "/source", notices)
        artifacts.append({"coordinate": coordinate, "kind": kind, "sha256": expected,
                          "origin": "resolved-release-runtime-classpath", "licenseMetadata": licenses,
                          "projectUrl": pom.findtext("m:url", namespaces=ns),
                          "scmUrl": pom.findtext("m:scm/m:url", namespaces=ns),
                          "pom": base + "/metadata.pom", "sourceArtifacts": sources,
                          "sourceStatus": "available-source-jar; build completeness unverified" if sources else "unavailable"})
    return artifacts, source_files


def manager_source_paths(root: Path) -> list[Path]:
    chosen = []
    for directory, directories, filenames in os.walk(root, followlinks=False):
        current = Path(directory)
        relative = current.relative_to(root)
        # Traverse only named source roots. Unknown future roots are excluded by default.
        directories[:] = sorted(d for d in directories if (
            (len(relative.parts) > 0 or d in {"app", "core", "docs", "tools", "native", "runtime", "gradle", ".github"})
            and d not in {"build", ".cache", ".gradle", ".git", "__pycache__", "node_modules",
                          "jniLibs", "bundles", "apk-assets", "distribution-assets", "work"}
            and not (relative.as_posix() == "app/src/main/assets" and d == "bootstrap")
            and not (relative.as_posix() == "app/src" and d in {"androidTest", "test", "debug"})
            and not (relative.as_posix() == "core/src" and d == "test")
            and not (current / d).is_symlink()
            and not getattr(current / d, "is_junction", lambda: False)()))
        for filename in sorted(filenames):
            path = current / filename
            rel = path.relative_to(root).as_posix()
            if path.is_symlink() or getattr(path, "is_junction", lambda: False)():
                continue
            if rel in SOURCE_ROOT_FILES or rel in {"app/build.gradle.kts", "core/build.gradle.kts",
                                                   "gradle/wrapper/gradle-wrapper.jar"}:
                chosen.append(path)
            elif is_public_document(rel):
                chosen.append(path)
            elif path.suffix in SOURCE_EXTENSIONS and (
                    rel.startswith(("app/src/", "core/src/", "tools/", "native/", "gradle/wrapper/")) or
                    (len(path.relative_to(root).parts) == 2 and rel.startswith("runtime/"))):
                # A text extension alone is never permission to include local credentials.
                if not any(p.startswith(".") or re.fullmatch(r"(?i)(secrets?\.json|credentials?\.json|private[-_]?key\..*|keystore\..*|local\.properties)", p)
                           for p in path.relative_to(root).parts):
                    chosen.append(path)
    return sorted(chosen, key=lambda path: path.relative_to(root).as_posix())


def zip_entry(name: str) -> zipfile.ZipInfo:
    info = zipfile.ZipInfo(clean_path(name), (1980, 1, 1, 0, 0, 0))
    info.compress_type = zipfile.ZIP_DEFLATED
    info.create_system = 3
    info.external_attr = (0o100755 if name == "gradlew" or name.endswith(".sh") else 0o100644) << 16
    return info


def write_zip(path: Path, texts: dict[str, bytes], sources: dict[str, Path] | None = None) -> dict:
    manifest = []
    with zipfile.ZipFile(path, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=6) as archive:
        for name in sorted(set(texts) | set(sources or {})):
            if name in texts:
                data = texts[name]
                sha, length = hashlib.sha256(data).hexdigest(), len(data)
                archive.writestr(zip_entry(name), data)
            else:
                original = sources[name]
                before = digest(original)
                hasher = hashlib.sha256()
                length = 0
                with original.open("rb") as incoming, archive.open(zip_entry(name), "w", force_zip64=True) as outgoing:
                    while block := incoming.read(1024 * 1024):
                        outgoing.write(block)
                        hasher.update(block)
                        length += len(block)
                sha = hasher.hexdigest()
                if sha != before:
                    raise ValueError("Source changed during collection")
            manifest.append({"path": name, "sha256": sha, "bytes": length})
        archive.writestr(zip_entry("COLLECTION-MANIFEST.json"), json_bytes({"schemaVersion": 1, "files": manifest}))
    return {"file": path.name, "sha256": digest(path), "bytes": path.stat().st_size, "entries": len(manifest) + 1}


def ubuntu_source_collection(root: Path, runtime_sha: str, packages: list[dict], notices: dict[str, bytes], cache_dir: Path | None = None) -> dict | None:
    metadata = (cache_dir if cache_dir is not None else root / ".cache") / "distribution/release/ubuntu-source-collection.json"
    if not metadata.is_file():
        return None
    record = json.loads(metadata.read_text("utf-8"))
    if record["file"] != "ubuntu-source-packages.zip" or record["runtimeSha256"] != runtime_sha:
        raise ValueError("Ubuntu source collection belongs to another runtime")
    if not record["allRequiredVersionsCollected"] or not record["allArchiveEntriesVerified"]:
        raise ValueError("Ubuntu source collection is incomplete")
    archive_path = metadata.parent / record["file"]
    verify(archive_path, record["sha256"])
    if archive_path.stat().st_size != record["bytes"]:
        raise ValueError("Ubuntu source ZIP length differs")
    with zipfile.ZipFile(archive_path) as archive:
        data = archive.read("plan.json")
        if hashlib.sha256(data).hexdigest() != record["planSha256"]:
            raise ValueError("Ubuntu source plan digest differs")
        plan = json.loads(data)
        pairs = {(p["sourceName"], p["sourceVersion"]) for p in packages}
        if plan["missing"] or {(p["name"], p["version"]) for p in plan["packages"]} != pairs:
            raise ValueError("Ubuntu source collection does not cover installed versions")
        if plan["runtimeSha256"] != runtime_sha or len(pairs) != record["sourcePackagesVerified"]:
            raise ValueError("Ubuntu source collection count or runtime differs")
        if len(archive.namelist()) != record["entries"] or record["sourceFilesVerified"] != len({f["path"] for p in plan["packages"] for f in p["files"]}):
            raise ValueError("Ubuntu source collection file count differs")
    add_bytes(notices, "sources/ubuntu/source-plan.json", data)
    add_bytes(notices, "sources/ubuntu/source-collection.json", metadata.read_bytes())
    for package in packages:
        package["correspondingSource"] = "ubuntu-source-packages.zip; exact source archives collected; binary rebuild unverified"
    return record


def python_source_collection(root: Path, runtime_sha: str, packages: list[dict], notices: dict[str, bytes], directory: Path | None) -> dict | None:
    if directory is None:
        return None
    # Imported lazily: the source collector reuses this module's deterministic ZIP writer.
    from collect_python_sources import load_plan, verify_archive
    plan_path = root / "runtime/python-sources.lock.json"
    lock_path = root / "runtime/astrbot-python.lock.txt"
    plan = load_plan(plan_path, lock_path)
    canonical = lambda name: re.sub(r"[-_.]+", "-", name).lower()
    if ({(p["name"], p["version"]) for p in plan["packages"]} !=
            {(canonical(p["name"]), p["version"]) for p in packages}):
        raise ValueError("Python source collection does not cover actual installed versions")
    record = verify_archive(directory / "python-source-packages.zip", plan_path, lock_path)
    if record["runtimeSha256"] != runtime_sha or not record["allRequiredVersionsCollected"]:
        raise ValueError("Python source collection is incomplete or belongs to another runtime")
    add_bytes(notices, "sources/python/source-plan.json", plan_path.read_bytes())
    add_bytes(notices, "sources/python/source-collection.json", json_bytes(record))
    origins = {p["name"]: "PyPI sdist" if p["source"] else "upstream tag/commit archive" for p in plan["packages"]}
    for package in packages:
        package["correspondingSource"] = "python-source-packages.zip; " + origins[canonical(package["name"])] + "; wheel/native build correspondence unverified"
    return record


def retain_source_archive(source: Path, destination: Path, record: dict) -> None:
    """Keep collection outputs self-contained when build caches live elsewhere."""
    if destination.is_file():
        verify(destination, record["sha256"])
        if destination.stat().st_size != record["bytes"]:
            raise ValueError("Source archive length differs")
        return
    destination.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="source-", dir=destination.parent) as stage_name:
        staged = Path(stage_name) / destination.name
        shutil.copyfile(source, staged)
        verify(staged, record["sha256"])
        if staged.stat().st_size != record["bytes"]:
            raise ValueError("Source archive length differs")
        staged.replace(destination)


def collect(root: Path, image_dir: Path, metadata_dir: Path, destination: Path, python_source_directory: Path | None = None,
            cache_dir: Path | None = None, native_dir: Path | None = None) -> dict:
    cache_dir = cache_dir if cache_dir is not None else root / ".cache"
    native_dir = native_dir if native_dir is not None else root / "app/src/main/jniLibs"
    image = image_dir / "ubuntu.tar.bin"
    image_manifest = json.loads((image_dir / "manifest.json").read_text("utf-8"))
    verify(image, image_manifest["sha256"])
    if image_manifest.get("kind") != "complete" or image_manifest.get("abi") != "arm64-v8a":
        raise ValueError("This inventory currently supports the complete ARM64 image only")
    project_lock = json.loads((root / "runtime/projects.lock.json").read_text("utf-8"))
    if image_manifest["components"] != project_lock:
        raise ValueError("Image and project lock differ")
    files, links, entry_count = scan_runtime(image)
    # Root directory is not counted by the collector, but is included by the image validator.
    if entry_count not in (image_manifest["entryCount"], image_manifest["entryCount"] - 1):
        raise ValueError("Runtime entry count differs")
    notices = {"runtime/" + path: data for path, data in files.items()}
    for name, source in (("LICENSE", "LICENSE"), ("NOTICE", "docs/legal/NOTICE")):
        notices["manager/" + name] = (root / source).read_bytes()
    terminal_renderer = json.loads((root / "runtime/terminal-assets.lock.json").read_text("utf-8"))
    for package in terminal_renderer["packages"]:
        for entry in package["files"]:
            verify(root / entry["path"], entry["sha256"])
            if entry["path"].endswith("-LICENSE"):
                notices["manager/terminal/" + Path(entry["path"]).name] = (root / entry["path"]).read_bytes()
    notices["manager/terminal/terminal-assets.lock.json"] = json_bytes(terminal_renderer)
    dpkg = system_packages(files, links)
    ubuntu_collection = ubuntu_source_collection(root, image_manifest["sha256"], dpkg, notices, cache_dir)
    python = python_packages(files)
    npm = npm_packages(files)
    python_lock = dict(re.findall(r"^([^#\s=]+)==([^\s]+)$", (root / "runtime/astrbot-python.lock.txt").read_text(), re.M))
    canonical = lambda name: re.sub(r"[-_.]+", "-", name).lower()
    if {canonical(p["name"]): p["version"] for p in python} != {canonical(k): v for k, v in python_lock.items()}:
        raise ValueError("Installed Python metadata differs from lock")
    python_collection = python_source_collection(root, image_manifest["sha256"], python, notices, python_source_directory)
    android, source_files = maven_metadata(metadata_dir, notices)
    upstream = []
    extra_lock = json.loads((root / "runtime/distribution-sources.lock.json").read_text("utf-8"))
    for source in extra_lock["sources"]:
        path = cache_dir / "distribution/sources" / source["filename"]
        verify(path, source["sha256"])
        if path.stat().st_size != source["bytes"]:
            raise ValueError("Original source length differs")
        upstream.append(source)
        source_files["upstream/" + source["filename"]] = path
        archive_notices(path, "sources/" + source["name"], notices)
    astrbot = cache_dir / f"upstream/astrbot-v{project_lock['astrbot']['version']}.tar.gz"
    verify(astrbot, project_lock["astrbot"]["source"]["sha256"])
    source_files["upstream/" + astrbot.name] = astrbot
    upstream.append({"name": "astrbot-source", "version": project_lock["astrbot"]["version"],
                     **project_lock["astrbot"]["source"], "filename": astrbot.name, "bytes": astrbot.stat().st_size})
    archive_notices(astrbot, "sources/astrbot", notices)
    native = {}
    for abi in ("arm64-v8a", "x86_64"):
        hashes = json.loads((root / f"runtime/native-{abi}.json").read_text("utf-8"))
        native[abi] = []
        for filename, expected in sorted(hashes.items()):
            verify(native_dir / abi / filename, expected)
            native[abi].append({"file": filename, "sha256": expected, "origin": "pinned-termux-package; ELF talloc name adjusted"})
        for name in ("process", "terminal"):
            runner = native_dir / abi / f"lib{name}-runner.so"
            native[abi].append({"file": runner.name, "sha256": digest(runner), "origin": f"native/{name}_runner.c; docs/构建指南.md"})
    gaps = [
        {"id": "linux-qq-redistribution", "status": "unconfirmed", "detail": "Electron/Chromium notices do not grant redistribution rights to Linux QQ itself."},
        {"id": "napcat-permission", "status": "requires-review", "detail": "Exact 4.18.41 Limited Redistribution License retained; noncommercial and modified-source restrictions apply. No broader permission inferred."},
        {"id": "ubuntu-corresponding-source", "status": "source-archives-collected; binary-build-provenance-unverified" if ubuntu_collection else "not-collected", "detail": "Exact source packages, Debian build/patch archives and signed archive metadata are in ubuntu-source-packages.zip; no binary rebuild claimed." if ubuntu_collection else "Installed binary/source package versions and copyright aliases recorded. Exact Ubuntu source archives and build recipes are not included."},
        {"id": "python-corresponding-source", "status": "source-archives-collected; wheel-native-build-provenance-unverified" if python_collection else "not-collected-in-this-inventory", "detail": "Exact PyPI sdists and separately identified upstream source archives are in the external python-source-packages.zip; wheel provenance, native/vendored dependencies and binary rebuilds remain unverified." if python_collection else "150 pinned distributions; pass --python-source-directory to bind a verified separate source companion. This inventory contains no Python source archive evidence."},
        {"id": "bundled-js-and-native", "status": "incomplete", "detail": "npm inventory covers external package.json files only. Bundled/minified JS, static/native binaries, fonts and QQ internals need separate dependency/source mapping."},
        {"id": "android-source-completeness", "status": "unverified", "detail": "Resolved source jars retained where available; source jars alone are not proof of complete corresponding source or build reproducibility."},
        {"id": "native-build-provenance", "status": "unverified", "detail": "Original native sources and Termux recipes retained; exact toolchain/binary rebuild provenance remains unverified."},
        {"id": "other-build-tools", "status": "incomplete", "detail": "Inventory covers release runtime graph, not every Gradle/SDK/NDK build tool or generated/vendored code."},
    ]
    inventory = {"schemaVersion": 1, "scope": "partial release evidence; not a complete SBOM or redistribution clearance",
                 "runtime": image_manifest, "runtimeEntriesScanned": entry_count, "native": native,
                 "ubuntuPackages": dpkg, "pythonPackages": python, "npmPackages": npm,
                 "androidArtifacts": android, "originalSources": upstream, "terminalRenderer": terminal_renderer,
                 "copyrightAliases": {"runtime/" + k: "runtime/" + v for k, v in sorted(links.items())},
                 "releaseReady": False, "openItems": gaps}
    if ubuntu_collection:
        inventory["ubuntuSourceCollection"] = ubuntu_collection
    if python_collection:
        inventory["pythonSourceCollection"] = python_collection
    summary = {"ubuntuBinaryPackages": len(dpkg),
               "ubuntuSourceVersions": len({(p['sourceName'], p['sourceVersion']) for p in dpkg}),
               "ubuntuMissingCopyright": [p["name"] for p in dpkg if p["copyright"] is None],
               "pythonDistributions": len(python), "npmExternalPackages": len(npm),
               "androidExternalArtifacts": sum(a["origin"].startswith("resolved-") for a in android),
               "androidArtifactsWithoutSourceJar": [a["coordinate"] for a in android if a.get("sourceStatus") == "unavailable"],
               "collectedPublicMetadataFiles": len(notices)}
    inventory["summary"] = summary
    notices["release-inventory.json"] = json_bytes(inventory)
    notices["README.txt"] = ("Public license/metadata evidence from the pinned build inputs.\n"
        "Original paths and license texts are preserved. See release-inventory.json openItems.\n"
        "This is not a complete SBOM, corresponding-source delivery, or redistribution authorization.\n"
        "Runtime inventory is bound to the image SHA-256, not an APK or a later updated device.\n").encode()
    source_readme = ("Available original upstream sources and resolved Maven source jars only.\n"
        "Includes native upstream sources, Termux recipes, AstrBot, NapCat and source jars.\n"
        "This ZIP does not include Ubuntu/Python sources or complete bundled dependency mapping.\n"
        "When available, Ubuntu source packages are a separate ubuntu-source-packages.zip; see inventory.\n"
        "Python source companions are separate external outputs; their directory must be supplied for verification.\n"
        "The manager source is in manager-source.zip, under MIT; copyright 2026 JunWan666.\n"
        "Refer to release-inventory.json; this archive is not complete corresponding source.\n").encode()
    manager_files = {p.relative_to(root).as_posix(): p for p in manager_source_paths(root)}
    manager_readme = {"SOURCE-COLLECTION.txt": ("Allowlisted manager sources/build scripts/docs.\n"
        "Excludes binary runtime images, native prebuilts, device/cache data, keys and build outputs.\n"
        "Prepare pinned runtime/native inputs using README.md before building.\n"
        "Original manager code is MIT licensed; see LICENSE and docs/legal/NOTICE. Upstream terms remain separate.\n"
        "Fixed ZIP timestamps and file digests enable comparing identical input collections.\n").encode()}
    destination.mkdir(parents=True, exist_ok=True)
    if ubuntu_collection:
        retain_source_archive(cache_dir / "distribution/release" / ubuntu_collection["file"],
                              destination / ubuntu_collection["file"], ubuntu_collection)
    with tempfile.TemporaryDirectory(prefix="collect-", dir=destination) as stage_name:
        stage = Path(stage_name)
        outputs = [write_zip(stage / "third-party-notices.zip", notices),
                   write_zip(stage / "available-sources.zip", {"README.txt": source_readme}, source_files),
                   write_zip(stage / "manager-source.zip", manager_readme, manager_files)]
        (stage / "release-inventory.json").write_bytes(json_bytes(inventory))
        outputs.append({"file": "release-inventory.json", "sha256": digest(stage / "release-inventory.json"),
                        "bytes": (stage / "release-inventory.json").stat().st_size})
        collection_outputs = outputs + ([{key: ubuntu_collection[key] for key in ("file", "sha256", "bytes", "entries")}] if ubuntu_collection else [])
        result = {"schemaVersion": 1, "runtimeSha256": image_manifest["sha256"], "releaseReady": False,
                  "summary": summary, "outputs": collection_outputs}
        if python_collection:
            result["externalOutputs"] = [{key: python_collection[key] for key in ("file", "sha256", "bytes", "entries")}]
        (stage / "collection.json").write_bytes(json_bytes(result))
        for item in outputs:
            (stage / item["file"]).replace(destination / item["file"])
        (stage / "collection.json").replace(destination / "collection.json")
    return result


def prepare_apk_assets(collection_dir: Path, asset_root: Path) -> None:
    result = json.loads((collection_dir / "collection.json").read_text("utf-8"))
    record = next(item for item in result["outputs"] if item["file"] == "third-party-notices.zip")
    source = collection_dir / record["file"]
    verify(source, record["sha256"])
    inventory = json.loads((collection_dir / "release-inventory.json").read_text("utf-8"))
    with zipfile.ZipFile(source) as archive:
        names = sorted(archive.namelist())
    # Only public notice material enters APK assets, never source archives or device data.
    target = asset_root / "third-party"
    target.mkdir(parents=True, exist_ok=True)
    manifest = {"schemaVersion": 1, "runtimeSha256": result["runtimeSha256"],
                "noticesSha256": record["sha256"], "names": names,
                "androidArtifacts": sorted({f"{item['coordinate']}:{item['kind']}:{item['sha256']}"
                                            for item in inventory["androidArtifacts"]})}
    temporary = target / "notices.zip.partial"
    shutil.copyfile(source, temporary)
    verify(temporary, record["sha256"])
    temporary.replace(target / "notices.zip")
    temporary = target / "manifest.json.partial"
    temporary.write_bytes(json_bytes(manifest))
    temporary.replace(target / "manifest.json")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--runtime-dir", type=Path, default=ROOT / "runtime/apk-assets/bootstrap/arm64-v8a")
    parser.add_argument("--android-metadata", type=Path, default=ROOT / "app/build/distribution/android-dependencies")
    parser.add_argument("--output", type=Path, default=ROOT / ".cache/distribution/release")
    parser.add_argument("--apk-assets", type=Path, help="Optionally prepare public notice assets for a bundled APK")
    parser.add_argument("--python-source-directory", type=Path, help="Bind a verified external Python source companion without copying it into APK assets or this directory")
    parser.add_argument("--cache-dir", type=Path, help="Read collected build inputs from an external .cache directory")
    parser.add_argument("--native-dir", type=Path, help="Read prepared JNI inputs from an external jniLibs directory")
    args = parser.parse_args()
    result = collect(ROOT, args.runtime_dir, args.android_metadata, args.output, args.python_source_directory, args.cache_dir, args.native_dir)
    if args.apk_assets:
        prepare_apk_assets(args.output, args.apk_assets)
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
