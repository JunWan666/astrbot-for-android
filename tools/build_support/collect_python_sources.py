"""Collect exact sdists and explicitly supported upstream source supplements.

This is a separate source companion, not wheel provenance or redistribution approval.
No package code is installed, extracted or executed. Missing PyPI sdists remain
explicit even when a matching upstream source archive is separately collected.
"""
from __future__ import annotations

import argparse
from concurrent.futures import ThreadPoolExecutor
import hashlib
import json
from pathlib import Path
import re
import shutil
import stat
import tarfile
import tempfile
import urllib.parse
import urllib.request
import zipfile

from collect_distribution import ROOT, clean_path, digest, json_bytes, python_packages, scan_runtime, write_zip

MAX_INDEX = 4 * 1024 * 1024
MAX_SOURCE = 512 * 1024 * 1024
FORMAT = "astrbot-python-source-companion"
UPSTREAM = {
    "dingtalk-stream": ("open-dingtalk/dingtalk-stream-sdk-python", "dingtalk_stream/version.py"),
    "faiss-cpu": ("facebookresearch/faiss", "CMakeLists.txt"),
}


def canonical(name: str) -> str:
    if not isinstance(name, str) or not re.fullmatch(r"[A-Za-z0-9](?:[A-Za-z0-9._-]*[A-Za-z0-9])?", name):
        raise ValueError("Invalid Python distribution name")
    return re.sub(r"[-_.]+", "-", name).lower()


def version_string(version: str) -> str:
    if not isinstance(version, str) or not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._+!\-]{0,99}", version):
        raise ValueError("Invalid fixed Python version")
    return version


def required_packages(lock: Path) -> dict[str, str]:
    packages = {}
    for line in lock.read_text("utf-8").splitlines():
        if not line.strip() or line.startswith("#"):
            continue
        match = re.fullmatch(r"([^\s=]+)==([^\s=]+)", line)
        if not match:
            raise ValueError("Python requirements must pin exact versions")
        name, version = canonical(match[1]), version_string(match[2])
        if name in packages:
            raise ValueError("Duplicate normalized Python distribution")
        packages[name] = version
    if not packages:
        raise ValueError("Empty Python dependency lock")
    return packages


def safe_url(url: str, host: str) -> str:
    parsed = urllib.parse.urlsplit(url)
    if (parsed.scheme != "https" or parsed.hostname != host or parsed.port is not None or
            parsed.username is not None or parsed.password is not None or parsed.query or parsed.fragment or
            "\\" in url or any(ord(char) < 33 for char in url)):
        raise ValueError("Unexpected public source URL")
    return url


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        raise ValueError("Source collection does not follow redirects")


def public_bytes(url: str, maximum: int) -> bytes:
    request = urllib.request.Request(url, headers={"User-Agent": "AstrBot-Android-source-collector/0.1"})
    with urllib.request.build_opener(NoRedirect()).open(request, timeout=30) as response:
        if response.status != 200:
            raise ValueError("Unexpected public source response")
        data = response.read(maximum + 1)
    if len(data) > maximum:
        raise ValueError("Public metadata exceeds limit")
    return data


def local_path(base: Path, relative: str) -> Path:
    path = base / clean_path(relative)
    # No reparse points in the caller-selected cache/output or any existing child.
    for candidate in (base, *(base / parent for parent in path.relative_to(base).parents)):
        if candidate.is_symlink() or getattr(candidate, "is_junction", lambda: False)():
            raise ValueError("Refusing linked source directory")
    if path.is_symlink() or getattr(path, "is_junction", lambda: False)():
        raise ValueError("Refusing linked source file")
    if not path.resolve().is_relative_to(base.resolve()):
        raise ValueError("Source path leaves selected directory")
    return path


def write_atomic(path: Path, data: bytes) -> None:
    if path.is_symlink() or getattr(path, "is_junction", lambda: False)():
        raise ValueError("Refusing linked output file")
    path.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="source-write-", dir=path.parent) as directory:
        temporary = Path(directory) / "data"
        temporary.write_bytes(data)
        temporary.replace(path)


def select_sdist(data: bytes, name: str, version: str) -> dict | None:
    if len(data) > MAX_INDEX:
        raise ValueError("PyPI metadata exceeds limit")
    record = json.loads(data)
    if canonical(record["info"]["name"]) != name or record["info"]["version"] != version:
        raise ValueError("PyPI metadata identity differs from the fixed package")
    sources = [entry for entry in record["urls"] if entry["packagetype"] == "sdist"]
    if not sources:
        return None
    if len(sources) != 1:
        raise ValueError("Ambiguous PyPI source distribution")
    return source_record(sources[0])


def source_record(source: dict) -> dict:
    filename = source["filename"]
    if (not isinstance(filename, str) or not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._+!\-]*", filename) or
            not filename.endswith((".tar.gz", ".zip"))):
        raise ValueError("Unexpected source filename")
    sha = source["digests"]["sha256"]
    size = source["size"]
    if not re.fullmatch(r"[a-f0-9]{64}", sha) or type(size) is not int or not 0 < size <= MAX_SOURCE:
        raise ValueError("Invalid source length or digest")
    url = safe_url(source["url"], "files.pythonhosted.org")
    if urllib.parse.unquote(urllib.parse.urlsplit(url).path).rsplit("/", 1)[-1] != filename:
        raise ValueError("Source URL filename differs")
    yanked = source.get("yanked", False)
    if type(yanked) is not bool:
        raise ValueError("Invalid PyPI withdrawal flag")
    return {"filename": filename, "url": url, "bytes": size, "sha256": sha,
            "path": f"sources/{sha}/{filename}", "yanked": yanked}


def upstream_record(package: dict) -> dict | None:
    upstream = package.get("upstream")
    if upstream is None:
        return None
    if package["source"] is not None or package["name"] not in UPSTREAM:
        raise ValueError("Upstream supplement is only supported for known packages without a PyPI sdist")
    repo, member = UPSTREAM[package["name"]]
    commit = upstream["commit"]
    if (upstream["repository"] != repo or upstream["tag"] != "v" + package["version"] or
            not re.fullmatch(r"[a-f0-9]{40}", commit) or upstream["versionMember"] != member):
        raise ValueError("Invalid upstream identity or fixed release tag")
    source = upstream["source"]
    filename = repo.rsplit("/", 1)[-1] + "-" + commit + ".tar.gz"
    sha = source["sha256"]
    if (not re.fullmatch(r"[a-f0-9]{64}", sha) or source["filename"] != filename or
            source["path"] != f"sources/{sha}/{filename}" or type(source["bytes"]) is not int or
            not 0 < source["bytes"] <= MAX_SOURCE or
            source["url"] != f"https://codeload.github.com/{repo}/tar.gz/{commit}"):
        raise ValueError("Invalid pinned upstream archive")
    safe_url(source["url"], "codeload.github.com")
    proofs = upstream["proofs"]
    if not 1 <= len(proofs) <= 2 or len({p["path"] for p in proofs}) != len(proofs):
        raise ValueError("Invalid upstream tag proof count")
    for index, proof in enumerate(proofs):
        if (proof["path"] != f"upstream-metadata/{package['name']}/tag-{index}.json" or
                not re.fullmatch(r"[a-f0-9]{64}", proof["sha256"]) or
                type(proof["bytes"]) is not int or not 0 < proof["bytes"] <= MAX_INDEX):
            raise ValueError("Invalid upstream tag proof")
        safe_url(proof["url"], "api.github.com")
    return upstream


def checked_upstream(plan: dict, read) -> None:
    for package in plan["packages"]:
        upstream = upstream_record(package)
        if upstream is None:
            continue
        proofs = upstream["proofs"]
        parsed = []
        for proof in proofs:
            data = read(proof["path"])
            if len(data) != proof["bytes"] or hashlib.sha256(data).hexdigest() != proof["sha256"]:
                raise ValueError("Upstream tag metadata digest differs")
            parsed.append(json.loads(data))
        repo, tag = upstream["repository"], upstream["tag"]
        if (proofs[0]["url"] != f"https://api.github.com/repos/{repo}/git/ref/tags/{tag}" or
                parsed[0]["ref"] != "refs/tags/" + tag):
            raise ValueError("Upstream tag reference differs")
        target = parsed[0]["object"]
        if target["type"] == "tag":
            if (len(proofs) != 2 or not re.fullmatch(r"[a-f0-9]{40}", target["sha"]) or
                    proofs[1]["url"] != f"https://api.github.com/repos/{repo}/git/tags/{target['sha']}" or
                    parsed[1]["sha"] != target["sha"] or parsed[1]["tag"] != tag):
                raise ValueError("Annotated upstream tag differs")
            target = parsed[1]["object"]
        elif len(proofs) != 1:
            raise ValueError("Unexpected extra upstream tag metadata")
        if target["type"] != "commit" or target["sha"] != upstream["commit"]:
            raise ValueError("Upstream release commit differs")


def check_upstream_version(package: dict, stream) -> None:
    upstream = upstream_record(package)
    repo_name = upstream["repository"].rsplit("/", 1)[-1]
    wanted = f"{repo_name}-{upstream['commit']}/{upstream['versionMember']}"
    total = 0
    with tarfile.open(fileobj=stream, mode="r|gz") as archive:
        for index, member in enumerate(archive):
            total += member.size
            if index > 100_000 or total > 2 * 1024 * 1024 * 1024:
                raise ValueError("Upstream source inspection exceeds limit")
            if member.name.removeprefix("./") != wanted:
                continue
            if not member.isfile() or member.size > 64 * 1024:
                raise ValueError("Invalid upstream version member")
            text = archive.extractfile(member).read().decode("utf-8")
            pattern = (r"VERSION_STRING\s*=\s*['\"]([^'\"]+)['\"]" if package["name"] == "dingtalk-stream" else
                       r"project\(\s*faiss\s+VERSION\s+([0-9.]+)\b")
            versions = re.findall(pattern, text)
            if versions != [package["version"]]:
                raise ValueError("Upstream declared version differs from installed distribution")
            return
    raise ValueError("Upstream source has no expected version declaration")


def supplement_sources(plan_path: Path, lock: Path, cache: Path) -> dict:
    plan = load_plan(plan_path, lock)
    checked_metadata(plan, lambda name: local_path(cache, name).read_bytes())
    checked_upstream(plan, lambda name: local_path(cache, name).read_bytes())
    for package in plan["packages"]:
        if package["source"] is not None or package.get("upstream") or package["name"] not in UPSTREAM:
            continue
        repo, member = UPSTREAM[package["name"]]
        tag = "v" + package["version"]
        proofs = []

        def proof(url):
            safe_url(url, "api.github.com")
            data = public_bytes(url, MAX_INDEX)
            relative = f"upstream-metadata/{package['name']}/tag-{len(proofs)}.json"
            write_atomic(local_path(cache, relative), data)
            proofs.append({"url": url, "path": relative, "bytes": len(data), "sha256": hashlib.sha256(data).hexdigest()})
            return json.loads(data)

        reference = proof(f"https://api.github.com/repos/{repo}/git/ref/tags/{tag}")
        target = reference["object"]
        if reference["ref"] != "refs/tags/" + tag:
            raise ValueError("Requested upstream tag differs")
        if target["type"] == "tag":
            if not re.fullmatch(r"[a-f0-9]{40}", target["sha"]):
                raise ValueError("Invalid annotated tag object")
            target = proof(f"https://api.github.com/repos/{repo}/git/tags/{target['sha']}")["object"]
        if target["type"] != "commit" or not re.fullmatch(r"[a-f0-9]{40}", target["sha"]):
            raise ValueError("Upstream release does not resolve to a commit")
        commit = target["sha"]
        url = safe_url(f"https://codeload.github.com/{repo}/tar.gz/{commit}", "codeload.github.com")
        # The archive has no publisher-provided tarball hash. Pin the HTTPS response,
        # tag->commit chain and exact version member; do not claim a binary rebuild.
        if shutil.disk_usage(cache).free < MAX_SOURCE + 64 * 1024 * 1024:
            raise ValueError("Insufficient upstream collection space")
        with tempfile.TemporaryDirectory(prefix="upstream-source-", dir=cache) as directory:
            temporary = Path(directory) / "source.tar.gz"
            request = urllib.request.Request(url, headers={"User-Agent": "AstrBot-Android-source-collector/0.1"})
            with urllib.request.build_opener(NoRedirect()).open(request, timeout=30) as incoming, temporary.open("wb") as outgoing:
                if incoming.status != 200:
                    raise ValueError("Unexpected upstream archive response")
                length = 0
                while block := incoming.read(1024 * 1024):
                    length += len(block)
                    if length > MAX_SOURCE:
                        raise ValueError("Upstream archive exceeds limit")
                    outgoing.write(block)
            sha = digest(temporary)
            filename = repo.rsplit("/", 1)[-1] + "-" + commit + ".tar.gz"
            source = {"filename": filename, "path": f"sources/{sha}/{filename}", "url": url, "bytes": length, "sha256": sha}
            package["upstream"] = {"repository": repo, "tag": tag, "commit": commit, "proofs": proofs,
                                   "versionMember": member, "source": source}
            checked_upstream(plan, lambda name: local_path(cache, name).read_bytes())
            with temporary.open("rb") as stream:
                check_upstream_version(package, stream)
            destination = local_path(cache, source["path"])
            if destination.exists():
                verify_file(destination, source)
            else:
                destination.parent.mkdir(parents=True, exist_ok=True)
                temporary.replace(destination)
    plan["scope"] = "Exact release sdists and separately identified upstream tag/commit source supplements; HTTPS trust, not signed author provenance; wheel/native build correspondence unverified"
    write_atomic(plan_path, json_bytes(plan))
    return plan


def package_source(package: dict) -> dict | None:
    return package["source"] or (package.get("upstream") or {}).get("source")


def records(plan: dict):
    for package in plan["packages"]:
        yield package["index"]
        if package.get("upstream"):
            yield from package["upstream"]["proofs"]
        if package_source(package):
            yield package_source(package)


def runtime_binding(image_dir: Path, lock: Path) -> tuple[str, dict]:
    manifest = json.loads((image_dir / "manifest.json").read_text("utf-8"))
    image = image_dir / "ubuntu.tar.bin"
    if digest(image) != manifest["sha256"] or manifest.get("kind") != "complete":
        raise ValueError("Complete runtime image digest differs")
    files, _, _ = scan_runtime(image)
    installed = python_packages(files)
    wanted = required_packages(lock)
    pairs = {canonical(p["name"]): p["version"] for p in installed}
    if len(pairs) != len(installed) or pairs != wanted:
        raise ValueError("Actual runtime Python packages differ from dependency lock")
    metadata = {canonical(p["name"]): {
        "runtimeMember": p["metadata"].removeprefix("runtime/"),
        "sha256": hashlib.sha256(files[p["metadata"].removeprefix("runtime/")]).hexdigest(),
    } for p in installed}
    return manifest["sha256"], metadata


def plan_sources(image_dir: Path, lock: Path, cache: Path, plan_path: Path, jobs: int = 4) -> dict:
    runtime_sha, metadata = runtime_binding(image_dir, lock)
    wanted = required_packages(lock)
    cache.mkdir(parents=True, exist_ok=True)

    def plan_one(pair):
        name, version = pair
        url = safe_url(f"https://pypi.org/pypi/{name}/{urllib.parse.quote(version, safe='')}/json", "pypi.org")
        data = public_bytes(url, MAX_INDEX)
        source = select_sdist(data, name, version)
        relative = f"metadata/{name}/{version}.json"
        write_atomic(local_path(cache, relative), data)
        return {"name": name, "version": version, "installedMetadata": metadata[name],
                "index": {"url": url, "path": relative, "bytes": len(data), "sha256": hashlib.sha256(data).hexdigest()},
                "source": source}

    with ThreadPoolExecutor(max_workers=jobs) as pool:
        packages = list(pool.map(plan_one, sorted(wanted.items())))
    plan = {"schemaVersion": 1, "format": FORMAT, "runtimeSha256": runtime_sha,
            "pythonLockSha256": digest(lock), "packages": packages,
            "scope": "Exact PyPI release metadata and available sdists; HTTPS trust, not signed author provenance; wheel/native build correspondence unverified"}
    write_atomic(plan_path, json_bytes(plan))
    return plan


def load_plan(plan_path: Path, lock: Path) -> dict:
    plan = json.loads(plan_path.read_text("utf-8"))
    if (plan["schemaVersion"] != 1 or plan["format"] != FORMAT or
            plan["pythonLockSha256"] != digest(lock) or not re.fullmatch(r"[a-f0-9]{64}", plan["runtimeSha256"])):
        raise ValueError("Source plan format or dependency lock differs")
    packages = plan["packages"]
    pairs = {p["name"]: p["version"] for p in packages}
    if len(pairs) != len(packages) or pairs != required_packages(lock):
        raise ValueError("Source plan does not cover the exact dependency set")
    for p in packages:
        name, version = p["name"], p["version"]
        if name != canonical(name) or version != version_string(version):
            raise ValueError("Noncanonical package identity")
        index = p["index"]
        if (index["path"] != f"metadata/{name}/{version}.json" or
                index["url"] != f"https://pypi.org/pypi/{name}/{urllib.parse.quote(version, safe='')}/json" or
                not re.fullmatch(r"[a-f0-9]{64}", index["sha256"]) or
                type(index["bytes"]) is not int or not 0 < index["bytes"] <= MAX_INDEX):
            raise ValueError("Invalid pinned PyPI metadata")
        safe_url(index["url"], "pypi.org")
        source = p["source"]
        if source is not None and source_record({**source, "size": source["bytes"], "digests": {"sha256": source["sha256"]}}) != source:
            raise ValueError("Invalid pinned source record")
        member = clean_path(p["installedMetadata"]["runtimeMember"])
        if (not member.startswith("opt/astrbot-venv/lib/python3.12/site-packages/") or
                not member.endswith(".dist-info/METADATA") or
                not re.fullmatch(r"[a-f0-9]{64}", p["installedMetadata"]["sha256"])):
            raise ValueError("Invalid runtime metadata binding")
        upstream_record(p)
    return plan


def checked_metadata(plan: dict, read) -> None:
    for p in plan["packages"]:
        index = p["index"]
        data = read(index["path"])
        if len(data) != index["bytes"] or hashlib.sha256(data).hexdigest() != index["sha256"]:
            raise ValueError("Pinned PyPI metadata digest differs")
        if select_sdist(data, p["name"], p["version"]) != p["source"]:
            raise ValueError("Planned source differs from original PyPI release metadata")


def verify_file(path: Path, record: dict) -> None:
    if not path.is_file() or path.stat().st_size != record["bytes"] or digest(path) != record["sha256"]:
        raise ValueError("Collected source length or digest differs")


def fetch_sources(plan: dict, cache: Path, jobs: int = 4, maximum_bytes: int = MAX_SOURCE) -> dict:
    checked_metadata(plan, lambda name: local_path(cache, name).read_bytes())
    checked_upstream(plan, lambda name: local_path(cache, name).read_bytes())
    source_records = [package_source(p) for p in plan["packages"] if package_source(p)]
    if sum(r["bytes"] for r in source_records) > maximum_bytes:
        raise ValueError("Source plan exceeds download byte budget")
    remaining = sum(r["bytes"] for r in source_records if not local_path(cache, r["path"]).exists())
    if shutil.disk_usage(cache).free < remaining + 64 * 1024 * 1024:
        raise ValueError("Insufficient source cache space")

    def fetch(record):
        path = local_path(cache, record["path"])
        if path.exists():
            verify_file(path, record)
            return
        path.parent.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(prefix="source-fetch-", dir=path.parent) as directory:
            temporary = Path(directory) / "source"
            request = urllib.request.Request(record["url"], headers={"User-Agent": "AstrBot-Android-source-collector/0.1"})
            with urllib.request.build_opener(NoRedirect()).open(request, timeout=30) as incoming, temporary.open("wb") as outgoing:
                if incoming.status != 200:
                    raise ValueError("Unexpected source download response")
                length = 0
                while block := incoming.read(1024 * 1024):
                    length += len(block)
                    if length > record["bytes"]:
                        raise ValueError("Source download exceeds pinned size")
                    outgoing.write(block)
            verify_file(temporary, record)
            temporary.replace(path)

    with ThreadPoolExecutor(max_workers=jobs) as pool:
        list(pool.map(fetch, source_records))
    for package in plan["packages"]:
        if package.get("upstream"):
            with local_path(cache, package_source(package)["path"]).open("rb") as stream:
                check_upstream_version(package, stream)
    return {**summary(plan), "sourcePackagesVerified": len(source_records), "allRequiredVersionsCollected": all(package_source(p) for p in plan["packages"])}


def summary(plan: dict) -> dict:
    missing = [{"name": p["name"], "version": p["version"], "reason": "No sdist in exact PyPI release"}
               for p in plan["packages"] if package_source(p) is None]
    pypi_missing = [{"name": p["name"], "version": p["version"]} for p in plan["packages"] if p["source"] is None]
    return {"requiredPackages": len(plan["packages"]), "sourcePackagesAvailable": len(plan["packages"]) - len(missing),
            "pypiSourcePackagesAvailable": sum(p["source"] is not None for p in plan["packages"]),
            "upstreamSourcePackagesAvailable": sum(bool(p.get("upstream")) for p in plan["packages"]), "pypiMissing": pypi_missing,
            "allRequiredVersionsAvailable": not missing, "missing": missing,
            "scope": "Source archive collection only; wheel and vendored/native build provenance unverified"}


def verify_archive(archive_path: Path, plan_path: Path, lock: Path) -> dict:
    plan = load_plan(plan_path, lock)
    expected = {"plan.json", "README.txt", "COLLECTION-MANIFEST.json"}
    expected.update(r["path"] for r in records(plan))
    with zipfile.ZipFile(archive_path) as archive:
        names = archive.namelist()
        if len(names) != len(set(names)) or set(names) != expected:
            raise ValueError("Source archive members or pinned plan differ")
        pinned = {r["path"]: r for r in records(plan)}
        for info in archive.infolist():
            if not stat.S_ISREG(info.external_attr >> 16):
                raise ValueError("Source companion contains nonregular member")
            if info.filename in pinned and info.file_size != pinned[info.filename]["bytes"]:
                raise ValueError("Source companion differs from pinned length")
            if info.filename == "plan.json" and info.file_size != plan_path.stat().st_size:
                raise ValueError("Source companion plan size differs")
            if info.filename == "README.txt" and info.file_size > 8192:
                raise ValueError("Source companion README exceeds limit")
            if info.filename == "COLLECTION-MANIFEST.json" and info.file_size > MAX_INDEX:
                raise ValueError("Source companion manifest exceeds limit")
        if archive.read("plan.json") != plan_path.read_bytes():
            raise ValueError("Source archive pinned plan differs")
        manifest = json.loads(archive.read("COLLECTION-MANIFEST.json"))
        manifest_files = manifest["files"]
        if len(manifest_files) != len(expected) - 1 or {r["path"] for r in manifest_files} != expected - {"COLLECTION-MANIFEST.json"}:
            raise ValueError("Source archive manifest differs")
        # Check the source against the external lock as well as the archive's own manifest.
        for record in manifest_files:
            clean_path(record["path"])
            if record["path"] in pinned:
                original = pinned[record["path"]]
                if (record["bytes"], record["sha256"]) != (original["bytes"], original["sha256"]):
                    raise ValueError("Source archive differs from externally pinned digest")
            if archive.getinfo(record["path"]).file_size != record["bytes"]:
                raise ValueError("Source archive length differs")
            with archive.open(record["path"]) as stream:
                if hashlib.file_digest(stream, "sha256").hexdigest() != record["sha256"]:
                    raise ValueError("Source archive content digest differs")
        checked_metadata(plan, archive.read)
        checked_upstream(plan, archive.read)
        for package in plan["packages"]:
            if package.get("upstream"):
                with archive.open(package_source(package)["path"]) as stream:
                    check_upstream_version(package, stream)
    return {"verified": True, "file": archive_path.name, "bytes": archive_path.stat().st_size,
            "sha256": digest(archive_path), "runtimeSha256": plan["runtimeSha256"], "planSha256": digest(plan_path),
            "entries": len(expected), **summary(plan), "sourcePackagesVerified": sum(package_source(p) is not None for p in plan["packages"]),
            "allRequiredVersionsCollected": all(package_source(p) for p in plan["packages"])}


def pack_sources(plan_path: Path, lock: Path, cache: Path, output: Path) -> dict:
    plan = load_plan(plan_path, lock)
    checked_metadata(plan, lambda name: local_path(cache, name).read_bytes())
    checked_upstream(plan, lambda name: local_path(cache, name).read_bytes())
    sources = {}
    for record in records(plan):
        path = local_path(cache, record["path"])
        verify_file(path, record)
        sources[record["path"]] = path
    output.mkdir(parents=True, exist_ok=True)
    if shutil.disk_usage(output).free < sum(p.stat().st_size for p in sources.values()) + 64 * 1024 * 1024:
        raise ValueError("Insufficient companion archive space")
    with tempfile.TemporaryDirectory(prefix="python-source-pack-", dir=output) as directory:
        archive = Path(directory) / "python-source-packages.zip"
        write_zip(archive, {"plan.json": plan_path.read_bytes(), "README.txt": (
            "Exact release sources and original public metadata, bound to a fixed runtime.\n"
            "Missing PyPI sdists remain explicit; known upstream supplements are separately identified.\n"
            "No wheels or different versions are substituted. GitHub tag/commit proofs and version declarations are retained.\n"
            "Do not execute package code to inspect this collection. HTTPS and SHA-256 are used;\n"
            "this is not signed author provenance, a wheel rebuild or a redistribution clearance.\n"
            "Vendored/native sources and binary build correspondence require separate review.\n").encode()}, sources)
        result = verify_archive(archive, plan_path, lock)
        archive.replace(local_path(output, archive.name))
        write_atomic(local_path(output, "python-source-collection.json"), json_bytes(result))
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("operation", choices=["plan", "supplement", "fetch", "pack", "verify"])
    parser.add_argument("--cache", type=Path, required=True, help="Public metadata/source cache; use a drive with sufficient space")
    parser.add_argument("--plan", type=Path, default=ROOT / "runtime/python-sources.lock.json")
    parser.add_argument("--python-lock", type=Path, default=ROOT / "runtime/astrbot-python.lock.txt")
    parser.add_argument("--runtime-dir", type=Path, default=ROOT / "runtime/apk-assets/bootstrap/arm64-v8a")
    parser.add_argument("--output", type=Path, required=True, help="Companion archive directory; kept separate from APK assets")
    parser.add_argument("--jobs", type=int, choices=range(1, 5), default=4)
    parser.add_argument("--maximum-bytes", type=int, default=MAX_SOURCE)
    args = parser.parse_args()
    if args.operation == "plan":
        result = summary(plan_sources(args.runtime_dir, args.python_lock, args.cache, args.plan, args.jobs))
    else:
        plan = load_plan(args.plan, args.python_lock)
        runtime_sha, metadata = runtime_binding(args.runtime_dir, args.python_lock)
        if runtime_sha != plan["runtimeSha256"] or any(metadata[p["name"]] != p["installedMetadata"] for p in plan["packages"]):
            raise ValueError("Source plan differs from actual runtime metadata")
        if args.operation == "supplement":
            result = summary(supplement_sources(args.plan, args.python_lock, args.cache))
        elif args.operation == "fetch":
            result = fetch_sources(plan, args.cache, args.jobs, args.maximum_bytes)
        elif args.operation == "pack":
            result = pack_sources(args.plan, args.python_lock, args.cache, args.output)
        else:
            result = verify_archive(args.output / "python-source-packages.zip", args.plan, args.python_lock)
    print(json.dumps(result, indent=2))


if __name__ == "__main__":
    main()
