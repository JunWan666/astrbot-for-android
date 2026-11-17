"""Read-only, expiring local adapter observations; never sends messages or actions."""
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
                with contextlib.suppress(Exception):
                    self.observe_platforms()
                try:
                    supported = importlib.metadata.version("aiocqhttp") == "1.4.4"
                except importlib.metadata.PackageNotFoundError:
                    supported = False
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

    def observe_platforms(self):
        # Only documented adapter lifecycle states, never accounts, credentials,
        # QR codes, error text or message contents. RUNNING is not proof that a
        # remote Webhook callback or message round trip has succeeded.
        config = Path("/persistent/astrbot/data/cmd_config.json").read_bytes()
        configured = json.loads(config).get("platform", [])
        entries = []
        for platform in self.context.platform_manager.get_insts():
            kind = platform.config.get("type")
            if kind not in ("qq_official", "qq_official_webhook", "weixin_oc"):
                continue
            # An edited disk configuration can precede adapter replacement. Never
            # label that new configuration with an old instance's lifecycle.
            matches = [item for item in configured if item.get("type") == kind
                       and item.get("id") == platform.config.get("id")]
            if len(matches) != 1 or matches[0] != platform.config:
                continue
            state = getattr(getattr(platform, "status", None), "value", "unknown")
            if state not in ("pending", "running", "error", "stopped"):
                state = "unknown"
            entries.append({"key": digest(self.nonce, kind + "\0" + str(platform.config.get("id", ""))),
                            "status": state})
        target = Path("/persistent/manager/platform-observation.json")
        value = {"schema": 1, "nonce": self.nonce,
                 "elapsedMs": int(time.clock_gettime(time.CLOCK_BOOTTIME) * 1000),
                 "configDigest": hashlib.sha256((self.nonce + ":").encode() + config).hexdigest(),
                 "adapters": entries}
        temporary = target.with_suffix(".tmp")
        temporary.write_text(json.dumps(value, separators=(",", ":")), encoding="utf-8")
        temporary.replace(target)
