"""Apply file-log limits when the upstream logging API supports them, without editing config."""
import inspect
import math


def install():
    from astrbot.core.log import LogManager

    original = getattr(LogManager, "_add_file_sink", None)
    if not callable(original):
        return "unsupported"
    if getattr(original, "_android_bounded", False):
        return "bounded"
    if not {"max_mb", "backup_count"}.issubset(inspect.signature(original).parameters):
        return "unsupported"

    def bounded(cls, **kwargs):
        maximum = kwargs.get("max_mb")
        if (isinstance(maximum, bool) or not isinstance(maximum, (int, float))
                or not math.isfinite(maximum) or maximum <= 0 or maximum > 8):
            maximum = 8
        backups = kwargs.get("backup_count")
        if isinstance(backups, bool) or not isinstance(backups, int) or not 1 <= backups <= 3:
            backups = 3
        return original(**{**kwargs, "max_mb": maximum, "backup_count": backups})

    bounded._android_bounded = True
    LogManager._add_file_sink = classmethod(bounded)
    return "bounded"
