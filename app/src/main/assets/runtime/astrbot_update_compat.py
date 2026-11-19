"""Keep manager adaptations scoped to reviewed upstream implementations."""
import functools
import hashlib
import inspect
from pathlib import Path

# Reviewed official migra_helper.py in 4.29.1, 4.29.3/4.29.4 and 4.30.0 alpha.1/beta.1.
# The new agent migration propagates failures; remaining caught errors still pass
# through this module's logger and are observed by launch_astrbot.py.
# Unknown migration implementations must be reviewed before claiming rollback-safe success.
MIGRATION_SOURCES = frozenset((
    "00b541015b55780dd9e2bd3760b0b85d4ad929fdac65f6166b71f4fa0eb4bb70",
    "a117961dee7288ea57d4715979a4b8bfaa76be9addda9175ef619bf4550093fb",
    "aa49d497277e5b6d796f60c275b1f655e229e22270c2ce7344051ae5348d8be1",
))


def migration_source_supported(path):
    path = Path(path)
    return path.is_file() and path.stat().st_size <= 1024 * 1024 and hashlib.sha256(path.read_bytes()).hexdigest() in MIGRATION_SOURCES


def migration_supported(function):
    if not inspect.iscoroutinefunction(function) or function.__module__ != "astrbot.core.utils.migra_helper":
        return False
    source = inspect.getsourcefile(function)
    return source is not None and "logger" in function.__globals__ and migration_source_supported(source)


# AuthService.setup_status in the reviewed 4.29.3/4.29.4 and 4.30.0 alpha.1/beta.1
# sources reports setup_required=False for all clients when desktop sessions are enabled.
_DESKTOP_SETUP_STATUS_SHA256 = "e6de336f170963b996e5e5fb0cee10942dc42f185492c7be9f1b01adce2d1f9a"


def install_dashboard_setup_status():
    try:
        from astrbot.core.desktop_runtime import is_desktop_session_auth_enabled
        from astrbot.dashboard.services.auth_service import AuthService

        original = AuthService.setup_status
        if not inspect.iscoroutinefunction(original):
            return
        source = inspect.getsource(original)
    except (ImportError, AttributeError, OSError, TypeError):
        return
    if hashlib.sha256(source.encode("utf-8")).hexdigest() != _DESKTOP_SETUP_STATUS_SHA256:
        return

    @functools.wraps(original)
    async def setup_status(service):
        result = await original(service)
        if is_desktop_session_auth_enabled():
            # Report the real setup state to password-authenticated browsers.
            # The setup endpoint and its authentication requirements stay upstream.
            result.data["setup_required"] = await service.is_setup_required()
        return result

    AuthService.setup_status = setup_status
