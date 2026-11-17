"""Observe reviewed migration implementations without changing their return/exception behavior."""
import functools
import json
import os
from pathlib import Path
import runpy
import sys
import uuid
from astrbot_update_compat import migration_supported

from astrbot_instance import AstrBotInstance, InstanceBlocked, configured_endpoint

try:
    instance_lock = AstrBotInstance().acquire(configured_endpoint())
except (InstanceBlocked, OSError):
    print("AstrBot instance guard: startup blocked; check the existing instance and dashboard port.", flush=True)
    sys.exit(73)

sys.path.insert(0, "/opt/astrbot")
nonce = os.environ["ASTRBOT_MANAGER_OBSERVATION_NONCE"]

# Use the same local-session contract as upstream AstrBot Desktop. Password
# authentication stays available for ordinary browser/LAN clients.
session = json.loads(Path("/manager/astrbot-local-session.json").read_text(encoding="utf-8"))
if (session.get("schema") != 1 or session.get("nonce") != nonce
        or not isinstance(session.get("secret"), str)
        or len(session["secret"]) != 64
        or any(char not in "0123456789abcdef" for char in session["secret"])):
    raise RuntimeError("Invalid local manager session")
os.environ["ASTRBOT_DESKTOP_MANAGED"] = "1"
os.environ["ASTRBOT_DESKTOP_SESSION_SECRET"] = session["secret"]
del session
target = Path("/manager/astrbot-startup.json")
log_policy = "pending"
restart_policy = "pending"
launch_id = uuid.uuid4().hex


def record(phase, version=""):
    temporary = target.with_suffix(".tmp")
    with temporary.open("w", encoding="utf-8") as stream:
        json.dump({"schema": 1, "nonce": nonce, "migration": phase, "version": version,
                   "logPolicy": log_policy, "restartPolicy": restart_policy,
                   "launchId": launch_id}, stream)
        stream.flush()
        os.fsync(stream.fileno())
    temporary.replace(target)


record("pending")
import runtime_bootstrap
runtime_bootstrap.initialize_runtime_bootstrap()
from astrbot import __version__
from astrbot.core import core_lifecycle
from log_limits import install as install_log_limits
from managed_restart import install as install_restart

log_policy = install_log_limits()
restart_policy = install_restart(__version__, lambda: record("restart-requested", __version__))
record("pending", __version__)

original = getattr(core_lifecycle, "migra", None)
if migration_supported(original):
    migration_active = False
    migration_failed = False
    upstream_logger = original.__globals__["logger"]

    class MigrationLogger:
        def __getattr__(self, name):
            function = getattr(upstream_logger, name)
            if name not in ("error", "exception", "critical"):
                return function

            def observe_error(*args, **kwargs):
                global migration_failed
                if migration_active:
                    migration_failed = True
                    record("failed", __version__)
                return function(*args, **kwargs)

            return observe_error

    # The pinned helper catches several migration errors itself. Observe its structured
    # error calls before log-level filtering; do not infer migration success from stdout.
    original.__globals__["logger"] = MigrationLogger()

    @functools.wraps(original)
    async def observed_migration(*args, **kwargs):
        global migration_active, migration_failed
        migration_active = True
        try:
            result = await original(*args, **kwargs)
        except BaseException:
            migration_failed = True
            record("failed", __version__)
            raise
        finally:
            migration_active = False
        record("failed" if migration_failed else "passed", __version__)
        return result

    core_lifecycle.migra = observed_migration
else:
    record("unsupported", __version__)

sys.argv[0] = "/opt/astrbot/main.py"
runpy.run_path(sys.argv[0], run_name="__main__")
