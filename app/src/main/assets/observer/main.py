"""Read-only observer for the APK's pinned aiocqhttp adapter; never sends events/actions."""
import asyncio
import contextlib
import hashlib
import importlib.metadata
import json
import os
import time
from pathlib import Path

from astrbot.api.star import Star


def digest(nonce, value):
    return hashlib.sha256((nonce + ":" + value).encode()).hexdigest()


def fingerprint(nonce, config):
    return digest(nonce, "\0".join(str(value) for value in (
        config["id"], config["ws_reverse_host"], int(config["ws_reverse_port"]),
        config.get("ws_reverse_token") or "",
    )))


class AndroidConnectionObserver(Star):
    async def initialize(self):
        self.nonce = os.environ.get("ASTRBOT_MANAGER_OBSERVATION_NONCE", "")
        self.task = asyncio.create_task(self.observe()) if self.nonce else None

    async def terminate(self):
        if self.task:
            self.task.cancel()
            with contextlib.suppress(asyncio.CancelledError):
                await self.task

    async def observe(self):
        target = Path("/persistent/manager/onebot-observation.json")
        while True:
            try:
                supported = importlib.metadata.version("aiocqhttp") == "1.4.4"
                adapters = []
                # Read on AstrBot's event loop: connection collection mutations cannot interleave.
                for platform in self.context.platform_manager.get_insts():
                    config = platform.config
                    if config.get("type") != "aiocqhttp" or config.get("enable") is not True:
                        continue
                    entry = {"fingerprint": fingerprint(self.nonce, config),
                             "supported": False, "universal": [], "partial": False}
                    bot = getattr(platform, "bot", None)
                    api = getattr(bot, "_wsr_api_clients", None)
                    events = getattr(bot, "_wsr_event_clients", None)
                    if supported and isinstance(api, dict) and isinstance(events, set):
                        universal = {ws for ws in api.values() if ws in events}
                        entry.update(supported=True,
                                     universal=[digest(self.nonce, str(account))
                                                for account, ws in api.items() if ws in universal],
                                     partial=any(ws not in universal for ws in api.values())
                                             or any(ws not in universal for ws in events))
                    adapters.append(entry)
                value = {"schema": 1, "nonce": self.nonce,
                         "elapsedMs": int(time.clock_gettime(time.CLOCK_BOOTTIME) * 1000),
                         "adapters": adapters}
                temporary = target.with_suffix(".tmp")
                temporary.write_text(json.dumps(value, separators=(",", ":")), encoding="utf-8")
                temporary.replace(target)
            except Exception:
                # Failed/disabled/unsupported observers expire to UNKNOWN in the manager.
                # Avoid logging any upstream configuration or account data.
                pass
            await asyncio.sleep(2)
