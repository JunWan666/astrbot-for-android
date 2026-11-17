"""Recognize reviewed migration boundaries by source, independently of release version."""
import hashlib
import inspect
from pathlib import Path

# Official migra_helper.py in v4.29.1 and v4.29.3/v4.29.4 respectively.
# Unknown migration implementations must be reviewed before claiming rollback-safe success.
MIGRATION_SOURCES = frozenset((
    "00b541015b55780dd9e2bd3760b0b85d4ad929fdac65f6166b71f4fa0eb4bb70",
    "a117961dee7288ea57d4715979a4b8bfaa76be9addda9175ef619bf4550093fb",
))


def migration_source_supported(path):
    path = Path(path)
    return path.is_file() and path.stat().st_size <= 1024 * 1024 and hashlib.sha256(path.read_bytes()).hexdigest() in MIGRATION_SOURCES


def migration_supported(function):
    if not inspect.iscoroutinefunction(function) or function.__module__ != "astrbot.core.utils.migra_helper":
        return False
    source = inspect.getsourcefile(function)
    return source is not None and "logger" in function.__globals__ and migration_source_supported(source)
