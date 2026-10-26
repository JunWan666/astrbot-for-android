"""Verify collected release archives, all file digests and inventory relationships."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import zipfile

from collect_distribution import ROOT, clean_path, digest, manager_source_paths, selected_runtime


def verify_collection(directory: Path, root: Path = ROOT, python_source_directory: Path | None = None) -> dict:
    collection = json.loads((directory / "collection.json").read_text("utf-8"))
    verified_files = 0
    for record in collection["outputs"]:
        path = directory / clean_path(record["file"])
        assert path.stat().st_size == record["bytes"], path.name
        assert digest(path) == record["sha256"], path.name
        if path.suffix != ".zip":
            continue
        with zipfile.ZipFile(path) as archive:
            names = archive.namelist()
            assert len(names) == len(set(names)) == record["entries"], path.name
            manifest = json.loads(archive.read("COLLECTION-MANIFEST.json"))
            assert set(names) == {f["path"] for f in manifest["files"]} | {"COLLECTION-MANIFEST.json"}
            for entry in manifest["files"]:
                clean_path(entry["path"])
                hasher = hashlib.sha256()
                length = 0
                with archive.open(entry["path"]) as stream:
                    while block := stream.read(1024 * 1024):
                        hasher.update(block)
                        length += len(block)
                assert length == entry["bytes"], entry["path"]
                assert hasher.hexdigest() == entry["sha256"], entry["path"]
                verified_files += 1
    inventory = json.loads((directory / "release-inventory.json").read_text("utf-8"))
    python_record = inventory.get("pythonSourceCollection")
    expected_external = [{key: python_record[key] for key in ("file", "sha256", "bytes", "entries")}] if python_record else []
    if collection.get("externalOutputs", []) != expected_external:
        raise ValueError("External source outputs differ from release inventory")
    if python_record:
        if python_source_directory is None:
            raise ValueError("Supply --python-source-directory to verify the required external source archive")
        from collect_python_sources import verify_archive
        python_verified = verify_archive(python_source_directory / "python-source-packages.zip",
                                         root / "runtime/python-sources.lock.json", root / "runtime/astrbot-python.lock.txt")
        if python_verified != python_record or python_record["runtimeSha256"] != inventory["runtime"]["sha256"]:
            raise ValueError("External Python source evidence differs")
        with zipfile.ZipFile(python_source_directory / "python-source-packages.zip") as source:
            plan = json.loads(source.read("plan.json"))
        canonical = lambda name: re.sub(r"[-_.]+", "-", name).lower()
        if {(p["name"], p["version"]) for p in plan["packages"]} != {(canonical(p["name"]), p["version"]) for p in inventory["pythonPackages"]}:
            raise ValueError("External source versions differ from installed inventory")
        verified_files += python_record["entries"] - 1
    elif python_source_directory is not None:
        raise ValueError("The release inventory does not bind an external Python source archive")
    assert collection["runtimeSha256"] == inventory["runtime"]["sha256"]
    assert inventory["releaseReady"] is False and inventory["openItems"]
    text = json.dumps(inventory)
    assert str(root) not in text
    assert "files-2.1" not in text and "gradle-cache" not in text
    with zipfile.ZipFile(directory / "third-party-notices.zip") as notices:
        assert json.loads(notices.read("release-inventory.json")) == inventory
        for name in ("LICENSE", "NOTICE"):
            assert notices.read("manager/" + name) == (root / name).read_bytes()
        assert b"MIT License" in notices.read("manager/LICENSE")
        assert b"JunWan666" in notices.read("manager/LICENSE")
        assert not any(item["id"] == "manager-license" for item in inventory["openItems"])
        names = set(notices.namelist())
        for path in names:
            if path.startswith("runtime/"):
                assert selected_runtime(path.removeprefix("runtime/")), path
        for package in inventory["ubuntuPackages"]:
            assert package["copyright"] in names, package["name"]
        for package in inventory["pythonPackages"] + inventory["npmPackages"]:
            assert package["metadata"] in names
            assert set(package["notices"]).issubset(names)
        napcat_license = next(p for p in names if p.startswith("sources/napcat-source/") and p.count("/") == 3 and p.endswith("/LICENSE"))
        assert b"Limited Redistribution License for NapCat" in notices.read(napcat_license)
        assert b"GNU AFFERO GENERAL PUBLIC LICENSE" in notices.read("runtime/opt/astrbot/LICENSE")
        for dependency in inventory["androidArtifacts"]:
            if "pom" in dependency:
                assert dependency["pom"] in names
    with zipfile.ZipFile(directory / "available-sources.zip") as sources:
        for source in inventory["originalSources"]:
            assert hashlib.sha256(sources.read("upstream/" + source["filename"])).hexdigest() == source["sha256"]
        for artifact in inventory["androidArtifacts"]:
            for source in artifact.get("sourceArtifacts", []):
                data = sources.read(source["path"])
                assert len(data) == source["bytes"]
                assert hashlib.sha256(data).hexdigest() == source["sha256"]
    if "ubuntuSourceCollection" in inventory:
        record = inventory["ubuntuSourceCollection"]
        with zipfile.ZipFile(directory / record["file"]) as sources:
            plan = json.loads(sources.read("plan.json"))
            assert hashlib.sha256(sources.read("plan.json")).hexdigest() == record["planSha256"]
            assert plan["runtimeSha256"] == inventory["runtime"]["sha256"]
            assert not plan["missing"]
            assert {(p["name"], p["version"]) for p in plan["packages"]} == {(p["sourceName"], p["sourceVersion"]) for p in inventory["ubuntuPackages"]}
            assert record["sourceFilesVerified"] == len({f["path"] for p in plan["packages"] for f in p["files"]})
    with zipfile.ZipFile(directory / "manager-source.zip") as sources:
        expected = {p.relative_to(root).as_posix(): p for p in manager_source_paths(root)}
        assert set(sources.namelist()) == set(expected) | {"SOURCE-COLLECTION.txt", "COLLECTION-MANIFEST.json"}
        for name, path in expected.items():
            assert sources.read(name) == path.read_bytes(), f"Source changed since collection: {name}"
        assert "core/src/main/kotlin/io/astrbot/android/core/SecretRedactor.kt" in expected
        assert all(not any(part in {".cache", "build", "jniLibs", "apk-assets", "distribution-assets"}
                           for part in Path(name).parts) for name in expected)
    return {"verified": True, "releaseReady": False, "filesWithVerifiedDigests": verified_files,
            "runtimeSha256": collection["runtimeSha256"], "summary": inventory["summary"]}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--directory", type=Path, default=ROOT / ".cache/distribution/release")
    parser.add_argument("--evidence", type=Path)
    parser.add_argument("--python-source-directory", type=Path)
    args = parser.parse_args()
    result = verify_collection(args.directory, python_source_directory=args.python_source_directory)
    text = json.dumps(result, indent=2) + "\n"
    if args.evidence:
        args.evidence.write_text(text, encoding="utf-8")
    print(text)
