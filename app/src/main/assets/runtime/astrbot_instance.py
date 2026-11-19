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
        self.stage = "configuration"

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

    def recognized(self, pid, own=None):
        identity = self.identity(pid)
        own = own or self.identity(os.getpid())
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
        own = self.identity(os.getpid())
        if own is None:
            raise InstanceBlocked("identity-unavailable")
        deadline = time.monotonic() + 10
        for entry in Path("/proc").iterdir():
            if time.monotonic() > deadline:
                raise InstanceBlocked("inspection-timeout")
            if entry.name.isdecimal() and int(entry.name) != os.getpid():
                identity = self.recognized(int(entry.name), own)
                if identity:
                    result.append(identity)
        return result

    def recognized_owner(self, identity):
        """Prove the recorded launcher still owns this exact flock inode.

        PRoot exposes other tracees' real loader/cwd through procfs. After a
        tracer dies those paths cannot identify the guest Python interpreter.
        The recorded PID, kernel start time, UID, exact entrypoint and kernel
        flock ownership remain verifiable without trusting those virtual paths.
        This fallback is never used for arbitrary processes found in a scan.
        """
        if (not isinstance(identity, dict) or type(identity.get("pid")) is not int
                or identity["pid"] <= 1):
            return None
        pid = identity["pid"]
        own = self.identity(os.getpid())
        if not own or identity.get("uid") != own["uid"] or self.identity(pid) != identity:
            return None
        if self.recognized(pid, own) == identity:
            return identity
        owner = self.read_owner()
        if not isinstance(owner, dict) or owner.get("identity") != identity:
            return None
        try:
            root = Path(f"/proc/{pid}")
            args = (root / "cmdline").read_bytes().split(b"\0")
            if len(args) < 2 or os.fsdecode(args[1]) != str(self.directory / "launch_astrbot.py"):
                return None
            lock = self.lock_path.stat()
            for descriptor in (root / "fd").iterdir():
                try:
                    if not os.path.samestat(descriptor.stat(), lock):
                        continue
                    for line in (root / "fdinfo" / descriptor.name).read_text().splitlines():
                        fields = line.split()
                        if (len(fields) == 9 and fields[0] == "lock:"
                                and fields[2:6] == ["FLOCK", "ADVISORY", "WRITE", str(pid)]
                                and fields[7:] == ["0", "EOF"] and self.identity(pid) == identity):
                            return identity
                except OSError:
                    continue
        except OSError:
            pass
        return None

    def orphaned(self, identity):
        try:
            status = Path(f"/proc/{identity['pid']}/status").read_text().splitlines()
            fields = dict(line.split(":", 1) for line in status if ":" in line)
            # A managed tracee must not outlive both its parent and tracer.
            # Only a separately verified lock owner may skip the startup grace.
            return (fields.get("PPid", "").strip() == "1"
                    and fields.get("TracerPid", "").strip() == "0"
                    and self.recognized_owner(identity) == identity)
        except OSError:
            return False

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

    def retire(self, identity, recorded_owner=False):
        pid = identity["pid"]
        def verified():
            return self.recognized_owner(identity) if recorded_owner else self.recognized(pid)
        for number, seconds in ((signal.SIGTERM, 5), (signal.SIGKILL, 3)):
            if verified() != identity:
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
                if verified() != identity:
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
        self.stage = "lock"
        self.directory.mkdir(parents=True, exist_ok=True)
        descriptor = os.open(self.lock_path, os.O_CREAT | os.O_RDWR | os.O_NOFOLLOW, 0o600)
        recovered = False
        try:
            try:
                fcntl.flock(descriptor, fcntl.LOCK_EX | fcntl.LOCK_NB)
            except BlockingIOError:
                self.stage = "owner"
                owner = self.read_owner()
                identity = owner.get("identity") if isinstance(owner, dict) else None
                if not identity or self.recognized_owner(identity) != identity:
                    raise InstanceBlocked("busy")
                previous = owner.get("endpoint")
                if (not isinstance(previous, list) or len(previous) != 3
                        or previous[0] not in ("127.0.0.1", "localhost", "::1")
                        or not isinstance(previous[1], int) or not 1 <= previous[1] <= 65535
                        or previous[2] not in ("http", "https")):
                    raise InstanceBlocked("busy")
                if self.healthy(previous):
                    raise InstanceBlocked("healthy")
                if (self.young(identity) and not self.orphaned(identity)) or endpoint is None:
                    raise InstanceBlocked("starting")
                # Retry the actual interface before retiring a long-running unresponsive owner.
                time.sleep(.5)
                if self.healthy(previous):
                    raise InstanceBlocked("healthy")
                self.retire(identity, recorded_owner=True)
                recovered = True
                try:
                    fcntl.flock(descriptor, fcntl.LOCK_EX | fcntl.LOCK_NB)
                except BlockingIOError:
                    raise InstanceBlocked("busy")

            self.stage = "process-scan"
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

            self.stage = "port"
            deadline = time.monotonic() + 5
            while not self.port_free(endpoint):
                if time.monotonic() >= deadline:
                    raise InstanceBlocked("unknown-port")
                time.sleep(.2)
            if hold:
                self.stage = "record-owner"
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
    # The manager and its startup probe must use the same local address.
    try:
        endpoint = json.loads(os.environ["ASTRBOT_MANAGER_DASHBOARD_ENDPOINT"])
        if endpoint is None:
            return None
        host, port, scheme = endpoint
        if (not isinstance(endpoint, list) or host not in ("localhost", "127.0.0.1", "::1")
                or type(port) is not int or not 1024 <= port <= 65535 or scheme not in ("http", "https")):
            raise ValueError("invalid endpoint")
        return host, port, scheme
    except (KeyError, ValueError, TypeError):
        raise InstanceBlocked("configuration")


if __name__ == "__main__":
    instance = AstrBotInstance()
    try:
        print(json.dumps({"status": instance.prepare(configured_endpoint()), "stage": instance.stage}))
    except InstanceBlocked as failure:
        print(json.dumps({"status": failure.status, "stage": instance.stage}))
        sys.exit(73)
    except (OSError, ValueError, TypeError) as failure:
        # No paths, configuration text or argv: only bounded diagnostic codes.
        print(json.dumps({"status": "inspection-failed", "stage": instance.stage,
                          "error": type(failure).__name__, "errno": getattr(failure, "errno", None)}))
        sys.exit(73)
