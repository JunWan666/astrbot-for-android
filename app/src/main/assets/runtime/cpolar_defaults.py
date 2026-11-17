"""Seed managed cpolar tunnels once, keeping official account settings and user tunnels."""
import json
import os
import stat
import sys
import tempfile

import yaml


def seed(path, tunnels, lan=None):
    if not isinstance(tunnels, dict) or set(tunnels) not in (set(), {"ssh", "astrbot"}):
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
    status = "preserved" if existing else "created"
    if existing and lan is None:
        return status
    if not existing and tunnels:
        configuration["tunnels"] = tunnels
    address = "0.0.0.0:9200" if lan else "127.0.0.1:9200"
    if (existing or not tunnels) and configuration.get("client_dashboard_addr") == address:
        return status
    if lan is not None:
        configuration["client_dashboard_addr"] = address
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
    return status


if __name__ == "__main__":
    # Never print parser errors: they can quote a user's Authtoken from the YAML.
    try:
        print(seed(sys.argv[1], json.loads(sys.argv[2]), (sys.argv[3] == "lan") if len(sys.argv) > 3 else None))
    except Exception:
        print("configuration-error")
        sys.exit(1)
