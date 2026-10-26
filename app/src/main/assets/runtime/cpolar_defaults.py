"""Seed managed cpolar tunnels once, keeping official account settings and user tunnels."""
import json
import os
import stat
import sys
import tempfile

import yaml


def seed(path, tunnels):
    if not isinstance(tunnels, dict) or set(tunnels) != {"ssh", "astrbot", "napcat"}:
        raise ValueError("Unexpected default tunnel names")
    if os.path.getsize(path) > 1024 * 1024 or not stat.S_ISREG(os.lstat(path).st_mode):
        raise ValueError("Unsupported cpolar configuration file")
    with open(path, encoding="utf-8") as source:
        configuration = yaml.safe_load(source)
    if configuration is None:
        configuration = {}
    if not isinstance(configuration, dict):
        raise ValueError("Expected a cpolar configuration mapping")
    existing = configuration.get("tunnels")
    if existing is not None and not isinstance(existing, dict):
        raise ValueError("Expected a tunnel mapping")
    if existing:
        return "preserved"
    configuration["tunnels"] = tunnels
    directory = os.path.dirname(os.path.abspath(path))
    descriptor, temporary = tempfile.mkstemp(prefix="cpolar-defaults-", dir=directory)
    try:
        with os.fdopen(descriptor, "w", encoding="utf-8") as destination:
            yaml.safe_dump(configuration, destination, allow_unicode=True, sort_keys=False)
            destination.flush()
            os.fsync(destination.fileno())
        os.replace(temporary, path)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)
    return "created"


if __name__ == "__main__":
    # Never print parser errors: they can quote a user's Authtoken from the YAML.
    try:
        print(seed(sys.argv[1], json.loads(sys.argv[2])))
    except Exception:
        print("configuration-error")
        sys.exit(1)
