"""Keep supported upstream exec restarts inside the APK's launch adapter."""
import functools
import inspect
import os


def install(version, restart_requested=None):
    if version not in ("4.29.1", "4.29.3"):
        return "unsupported"
    from astrbot.core import process_restart

    original = getattr(process_restart, "_build_restart_argv", None)
    if not callable(original) or tuple(inspect.signature(original).parameters) != ("executable",):
        return "unsupported"
    if restart_requested is not None:
        cleanup = getattr(process_restart, "_terminate_child_processes", None)
        if not callable(cleanup) or tuple(inspect.signature(cleanup).parameters):
            return "unsupported"
        if not getattr(cleanup, "_android_owned_restart", False):
            @functools.wraps(cleanup)
            def owned_cleanup():
                try:
                    return cleanup()
                except process_restart.psutil.AccessDenied:
                    if os.environ.get("ASTRBOT_MANAGER_OWNED_SERVICE") == "1":
                        # Only the App's dedicated AstrBot session may exit for native
                        # cleanup. A manual terminal can also contain desktop/QQ programs.
                        restart_requested()
                        process_restart.os._exit(75)
                    else:
                        # Keep the terminal's PRoot root alive. Upstream exec replaces
                        # Python and closes its non-inheritable dashboard sockets.
                        process_restart.logger.warning("Android denied child inspection; restarting only the AstrBot process.")

            owned_cleanup._android_owned_restart = True
            process_restart._terminate_child_processes = owned_cleanup
    if getattr(original, "_android_entrypoint", False):
        return "managed"

    @functools.wraps(original)
    def managed(executable):
        arguments = original(executable)
        # Preserve upstream's cleanup, delay, exec, CLI/frozen cases and all options.
        if isinstance(arguments, list) and arguments[:2] == [executable, "/opt/astrbot/main.py"]:
            return [executable, "/manager/launch_astrbot.py", *arguments[2:]]
        return arguments

    managed._android_entrypoint = True
    process_restart._build_restart_argv = managed
    return "managed"
