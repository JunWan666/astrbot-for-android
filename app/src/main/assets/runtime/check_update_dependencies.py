"""Check an upstream requirements file without mutating the running Python environment."""
import importlib.metadata
import sys
from pathlib import Path

from packaging.requirements import Requirement


def check(path):
    failures = []
    for raw in Path(path).read_text(encoding="utf-8-sig").splitlines():
        line = raw.split(" #", 1)[0].strip()
        if not line or line.startswith("#"):
            continue
        requirement = Requirement(line)
        if requirement.url:
            failures.append(f"{requirement.name}: direct URL requirement needs runtime maintenance")
            continue
        if requirement.marker and not requirement.marker.evaluate():
            continue
        try:
            version = importlib.metadata.version(requirement.name)
        except importlib.metadata.PackageNotFoundError:
            failures.append(f"{requirement.name}: missing")
        else:
            if not requirement.specifier.contains(version, prereleases=True):
                failures.append(f"{requirement.name}: installed {version}, requires {requirement.specifier}")
    if failures:
        print("\n".join(failures))
        return 1
    print("dependencies-ok")
    return 0


if __name__ == "__main__":
    if len(sys.argv) > 2:
        sys.path.insert(0, str(Path(sys.argv[1]).parent))
        from astrbot_update_compat import migration_source_supported
        if not migration_source_supported(sys.argv[2]):
            print("migration-helper-incompatible")
            sys.exit(2)
    sys.exit(check(sys.argv[1]))
