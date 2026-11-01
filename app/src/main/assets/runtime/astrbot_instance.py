"""Serialize this installation's launches without reading Android's restricted /proc/net.

Only an identified Python entry point in this installation may be retired. Never
signal a port number, a process group, PRoot, a terminal, or another application.
"""
import errno
import fcntl
import http.client
import json
import os
from pathlib import Path
import signal
import socket
import ssl
import sys
import time


class InstanceBlocked(Exception):
    def __init__(self, status):
        self.status = status
        super().__init__(status)


class AstrBotInstance:
    def __init__(self, directory="/manager", application="/opt/astrbot", startup_grace=300):
        self.directory = Path(directory)
        self.application = Path(application)
        self.startup_grace = startup_grace
        self.lock_path = self.directory / "astrbot-instance.lock"
        self.owner_path = self.directory / "astrbot-instance.json"

    @staticmethod
    def identity(pid):
        try:
            root = Path(f"/proc/{pid}")
            stat = (root / "stat").read_text().rsplit(")", 1)[1].split()
            if stat[0] in ("Z", "X"):
                return None
            uid = next(line.split()[1:] for line in (root / "status").read_text().splitlines() if line.startswith("Uid:"))
            return {"pid": int(pid), "start": stat[19], "uid": uid}
        except (OSError, ValueError, IndexError, StopIteration):
            return None

    def recognized(self, pid):
        identity = self.identity(pid)
        own = self.identity(os.getpid())
        if not identity or not own or identity["uid"] != own["uid"]:
            return None
        try:
            root = Path(f"/proc/{pid}")
            if not os.path.samefile(root / "exe", sys.executable) or not os.path.samefile(root / "cwd", self.application):
                return None
            args = (root / "cmdline").read_bytes().split(b"\0")
            # No substring matches: arbitrary Python commands mentioning main.py do not qualify.
            if len(args) < 2 or os.fsdecode(args[1]) not in (
                "main.py", str(self.application / "main.py"), "/manager/launch_astrbot.py"
            ):
                return None
            if self.identity(pid) != identity:
                return None
            return identity
        except OSError:
            return None

    def candidates(self):
        result = []
        for entry in Path("/proc").iterdir():
            if entry.name.isdecimal() and int(entry.name) != os.getpid():
                identity = self.recognized(int(entry.name))
                if identity:
                    result.append(identity)
        return result

    @staticmethod
    def port_free(endpoint):
        if endpoint is None:
            return True
        host, port, _ = endpoint
        for family, kind, protocol, _, address in socket.getaddrinfo(host, port, type=socket.SOCK_STREAM):
            try:
                with socket.socket(family, kind, protocol) as listener:
                    listener.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
                    listener.bind(address)
                    listener.listen(1)
            except OSError as failure:
                if failure.errno == errno.EADDRINUSE:
                    return False
                raise
        return True

    @staticmethod
    def healthy(endpoint):
        if endpoint is None:
            return False
        host, port, scheme = endpoint
        connection = (http.client.HTTPSConnection if scheme == "https" else http.client.HTTPConnection)(host, port, timeout=2)
        try:
            connection.request("GET", "/")
            response = connection.getresponse()
            return 100 <= response.status <= 599
        except ssl.SSLError:
            # A certificate/trust error still proves a TLS listener answered. Do
            # not retire it merely because Android's local cert is untrusted.
            return True
        except (OSError, http.client.HTTPException):
            return False
        finally:
            connection.close()

    def young(self, identity):
        try:
            # /proc/uptime can also be denied to an Android App UID. This clock
            # includes suspend, matching the kernel's process-start tick base.
            uptime = time.clock_gettime(time.CLOCK_BOOTTIME)
            return uptime - int(identity["start"]) / os.sysconf("SC_CLK_TCK") < self.startup_grace
        except (OSError, ValueError):
            return True  # An unknown age must not authorize automatic termination.

    def retire(self, identity):
        pid = identity["pid"]
        for number, seconds in ((signal.SIGTERM, 5), (signal.SIGKILL, 3)):
            if self.recognized(pid) != identity:
                return
            # pidfd binds the signal to this process identity on supported kernels.
            descriptor = None
            try:
                if hasattr(os, "pidfd_open") and hasattr(signal, "pidfd_send_signal"):
                    try:
                        descriptor = os.pidfd_open(pid)
                    except OSError as failure:
                        if failure.errno not in (errno.ENOSYS, errno.EINVAL, errno.EPERM):
                            raise
                if self.recognized(pid) != identity:
                    return
                if descriptor is not None:
                    signal.pidfd_send_signal(descriptor, number)
                else:
                    os.kill(pid, number)
            except ProcessLookupError:
                return
            finally:
                if descriptor is not None:
                    os.close(descriptor)
            deadline = time.monotonic() + seconds
            while self.identity(pid) == identity and time.monotonic() < deadline:
                time.sleep(.1)
        if self.identity(pid) == identity:
            raise InstanceBlocked("cleanup-failed")

    def read_owner(self):
        try:
            if self.owner_path.stat().st_size > 4096:
                return None
            return json.loads(self.owner_path.read_text())
        except (OSError, ValueError):
            return None

    def prepare(self, endpoint, hold=False):
        self.directory.mkdir(parents=True, exist_ok=True)
        descriptor = os.open(self.lock_path, os.O_CREAT | os.O_RDWR | os.O_NOFOLLOW, 0o600)
        recovered = False
        try:
            try:
                fcntl.flock(descriptor, fcntl.LOCK_EX | fcntl.LOCK_NB)
            except BlockingIOError:
                owner = self.read_owner()
                identity = owner.get("identity") if isinstance(owner, dict) else None
                if not identity or self.recognized(identity.get("pid", -1)) != identity:
                    raise InstanceBlocked("busy")
                previous = owner.get("endpoint")
                if (not isinstance(previous, list) or len(previous) != 3
                        or previous[0] not in ("127.0.0.1", "localhost", "::1")
                        or not isinstance(previous[1], int) or not 1 <= previous[1] <= 65535
                        or previous[2] not in ("http", "https")):
                    raise InstanceBlocked("busy")
                if self.healthy(previous):
                    raise InstanceBlocked("healthy")
                if self.young(identity) or endpoint is None:
                    raise InstanceBlocked("starting")
                # Retry the actual interface before retiring a long-running unresponsive owner.
                time.sleep(.5)
                if self.healthy(previous):
                    raise InstanceBlocked("healthy")
                self.retire(identity)
                recovered = True
                try:
                    fcntl.flock(descriptor, fcntl.LOCK_EX | fcntl.LOCK_NB)
                except BlockingIOError:
                    raise InstanceBlocked("busy")

            legacy = self.candidates()
            if legacy:
                if self.healthy(endpoint):
                    raise InstanceBlocked("healthy")
                if endpoint is None or any(self.young(item) for item in legacy):
                    raise InstanceBlocked("starting")
                if not self.port_free(endpoint):
                    time.sleep(.5)
                    if self.healthy(endpoint):
                        raise InstanceBlocked("healthy")
                    for identity in legacy:
                        self.retire(identity)
                    recovered = True
                else:
                    # A process without this listener may be booting or use a different config.
                    raise InstanceBlocked("busy")

            deadline = time.monotonic() + 5
            while not self.port_free(endpoint):
                if time.monotonic() >= deadline:
                    raise InstanceBlocked("unknown-port")
                time.sleep(.2)
            if hold:
                record = {"identity": self.identity(os.getpid()), "endpoint": endpoint}
                temporary = self.owner_path.with_suffix(".tmp")
                with temporary.open("w") as stream:
                    json.dump(record, stream)
                    stream.flush()
                    os.fsync(stream.fileno())
                temporary.replace(self.owner_path)
                # Keep the singleton across an upstream exec restart. subprocess.Popen's
                # default close_fds prevents child programs from inheriting this lock.
                os.set_inheritable(descriptor, True)
                os.environ["ASTRBOT_INSTANCE_LOCK_FD"] = str(descriptor)
                return descriptor
            return "recovered" if recovered else "free"
        finally:
            if not hold or sys.exc_info()[0] is not None:
                os.close(descriptor)

    def acquire(self, endpoint):
        inherited = os.environ.pop("ASTRBOT_INSTANCE_LOCK_FD", "")
        if inherited.isdecimal():
            descriptor = int(inherited)
            owner = self.read_owner()
            try:
                if (isinstance(owner, dict) and owner.get("identity") == self.identity(os.getpid())
                        and os.path.samestat(os.fstat(descriptor), self.lock_path.stat())):
                    fcntl.flock(descriptor, fcntl.LOCK_EX | fcntl.LOCK_NB)
                    os.environ["ASTRBOT_INSTANCE_LOCK_FD"] = str(descriptor)
                    return descriptor
            except OSError:
                pass
        return self.prepare(endpoint, hold=True)


def configured_endpoint():
    try:
        config = json.loads(Path("/persistent/astrbot/data/cmd_config.json").read_text(encoding="utf-8-sig")).get("dashboard", {})
        if config.get("enable") is False:
            return None
        host = config.get("host", "127.0.0.1")
        host = {"0.0.0.0": "127.0.0.1", "": "127.0.0.1", "::": "::1", "[::]": "::1", "[::1]": "::1"}.get(host, host)
        if host not in ("localhost", "127.0.0.1", "::1"):
            raise ValueError("non-local endpoint")
        port = int(config.get("port", 6185))
        if not 1 <= port <= 65535:
            raise ValueError("invalid port")
        ssl_config = config.get("ssl")
        return host, port, "https" if isinstance(ssl_config, dict) and ssl_config.get("enable") else "http"
    except (OSError, ValueError, TypeError, AttributeError):
        raise InstanceBlocked("configuration")


if __name__ == "__main__":
    try:
        print(json.dumps({"status": AstrBotInstance().prepare(configured_endpoint())}))
    except InstanceBlocked as failure:
        print(json.dumps({"status": failure.status}))
        sys.exit(73)
    except (OSError, ValueError, TypeError):
        print(json.dumps({"status": "inspection-failed"}))
        sys.exit(73)
