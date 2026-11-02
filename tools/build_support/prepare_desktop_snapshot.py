"""Preconfigure locked desktop packages in a disposable Linux build root.

The result is an overlay for a freshly extracted, matching bootstrap image only.
Original .deb assets remain available for repair of an existing guest. Linux root,
GNU tar, dpkg-deb and ARM64 execution (native or binfmt/QEMU) are required.
Windows builds invoke this tool in WSL; no phone data is used.
"""
import argparse
import compileall
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import shutil
import stat
import subprocess
import sys
import tarfile
import tempfile

ROOT = Path(__file__).resolve().parents[2]
ROOTS = ('etc', 'usr', 'opt', 'var/lib', 'var/cache/fontconfig')


def digest(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def inventory(root):
    result = {}
    for prefix in ROOTS:
        base = root / prefix
        if not base.exists():
            continue
        for current, directories, files in os.walk(base, followlinks=False):
            for name in [*directories, *files]:
                path = Path(current) / name
                relative = path.relative_to(root).as_posix()
                info = path.lstat()
                if stat.S_ISLNK(info.st_mode):
                    value = ('link', os.readlink(path))
                elif stat.S_ISDIR(info.st_mode):
                    value = ('dir', stat.S_IMODE(info.st_mode))
                elif stat.S_ISREG(info.st_mode):
                    value = ('file', stat.S_IMODE(info.st_mode), info.st_size, digest(path))
                else:
                    raise ValueError('Unexpected special file: ' + relative)
                result[relative] = value
    return result


def inputs(runtime, bundle):
    manifest = json.loads((runtime / 'manifest.json').read_text())
    lock = json.loads((bundle / 'manifest.json').read_text())
    pinned = json.loads((ROOT / 'runtime/desktop-packages.lock.json').read_text())
    if lock != pinned or manifest['sha256'] != lock['baseRuntimeSha256']:
        raise ValueError('Desktop snapshot must use the pinned clean base and package lock')
    if digest(runtime / 'ubuntu.tar.bin') != manifest['sha256']:
        raise ValueError('Base runtime integrity mismatch')
    for item in lock['packages']:
        if Path(item['file']).name != item['file']:
            raise ValueError('Unsafe package path')
        package = bundle / item['file']
        if package.stat().st_size != item['bytes'] or digest(package) != item['sha256']:
            raise ValueError('Package integrity mismatch: ' + item['name'])
        text = subprocess.check_output(['dpkg-deb', '-f', str(package), 'Package', 'Version', 'Architecture'], text=True)
        fields = dict(s.split(': ', 1) for s in text.splitlines() if ': ' in s)
        if any(fields.get(k) != item[v] for k, v in [('Package','name'), ('Version','version'), ('Architecture','architecture')]):
            raise ValueError('Package identity mismatch: ' + item['name'])
    return manifest, lock


def prepare(runtime, bundle, output):
    manifest, lock = inputs(runtime, bundle)
    recipe = hashlib.sha256(b''.join((ROOT / name).read_bytes() for name in (
        'tools/build_support/prepare_desktop_snapshot.py', 'app/src/main/assets/runtime/desktop/install.py',
        'app/src/main/assets/runtime/desktop/sources.json'))).hexdigest()
    expected = dict(schemaVersion=1, baseRuntimeSHA256=manifest['sha256'],
                    bundleSHA256=digest(bundle / 'manifest.json'), recipeSHA256=recipe)
    output.mkdir(parents=True, exist_ok=True)
    record_path = output / 'manifest.json'
    archive_path = output / 'desktop.tar.bin'
    if record_path.is_file() and archive_path.is_file():
        record = json.loads(record_path.read_text())
        if all(record.get(k) == v for k, v in expected.items()) and digest(archive_path) == record['sha256']:
            print('DESKTOP_SNAPSHOT_CACHED ' + json.dumps(record), flush=True)
            return
    if os.geteuid() != 0:
        raise ValueError('Run desktop snapshot preparation as Linux root in a disposable build environment')
    with tempfile.TemporaryDirectory(prefix='astrbot-desktop-build-') as temporary:
        root = Path(temporary)
        print('Extracting the verified clean build image', flush=True)
        subprocess.run(['tar', '-xzf', str(runtime / 'ubuntu.tar.bin'), '-C', str(root)], check=True)
        before = inventory(root)
        for name in ('tmp', 'proc', 'dev', 'run', 'root', 'var/tmp', 'var/log', 'var/cache/debconf'):
            (root / name).mkdir(exist_ok=True, parents=True)
        (root / 'tmp').chmod(0o1777)
        (root / 'var/tmp').chmod(0o1777)
        for name, major, minor in [('null',1,3), ('zero',1,5), ('random',1,8), ('urandom',1,9)]:
            os.mknod(root / 'dev' / name, stat.S_IFCHR | 0o666, os.makedev(major,minor))
        manager = root / 'manager/extensions/desktop'
        manager.mkdir(parents=True)
        for name in ('install.py', 'sources.json'):
            shutil.copyfile(ROOT / 'app/src/main/assets/runtime/desktop' / name, manager / name)
        shutil.copytree(bundle, manager / 'bundled-packages')
        policy = root / 'usr/sbin/policy-rc.d'
        original_policy = policy.read_bytes() if policy.exists() else None
        original_policy_mode = stat.S_IMODE(policy.stat().st_mode) if policy.exists() else None
        policy.write_text('#!/bin/sh\nexit 101\n')
        policy.chmod(0o755)
        # Bytecode is architecture independent. When the build host has the
        # exact same bytecode format, generate it natively after postinst instead
        # of repeatedly emulating the Python compiler under QEMU.
        target_magic = subprocess.check_output(['chroot', str(root), '/usr/bin/python3', '-c',
            'import importlib.util; print(importlib.util.MAGIC_NUMBER.hex())'], text=True).strip()
        native_bytecode = target_magic == importlib.util.MAGIC_NUMBER.hex()
        pycompile = root / 'usr/bin/py3compile'
        original_compile = pycompile.read_bytes()
        original_compile_mode = stat.S_IMODE(pycompile.stat().st_mode)
        if native_bytecode:
            if any(p['name'] in ('python3-minimal', 'python3.12-minimal') for p in lock['packages']):
                raise ValueError('Native bytecode preparation requires an unchanged target Python interpreter')
            pycompile.write_text('#!/bin/sh\nexit 0\n')
        subprocess.run(['mount', '-t', 'proc', 'proc', str(root / 'proc')], check=True)
        try:
            print('Installing/configuring desktop packages at build time', flush=True)
            subprocess.run(['chroot', str(root), '/usr/bin/env', 'HOME=/tmp', 'LC_ALL=C',
                'DEBIAN_FRONTEND=noninteractive', '/usr/bin/python3', '/manager/extensions/desktop/install.py', 'preinstall'], check=True)
            audit = subprocess.check_output(['chroot', str(root), '/usr/bin/dpkg', '--audit'], text=True)
            if audit.strip():
                raise ValueError('Unconfigured build packages: ' + audit)
        finally:
            subprocess.run(['umount', str(root / 'proc')], check=True)
            pycompile.write_bytes(original_compile)
            pycompile.chmod(original_compile_mode)
        if native_bytecode:
            print('Generating portable Python bytecode with the matching build-host format', flush=True)
            for name in ('usr/lib/python3', 'usr/lib/python3.12'):
                if not compileall.compile_dir(str(root / name), ddir='/' + name, quiet=2):
                    raise ValueError('Python bytecode generation failed: ' + name)
        if original_policy is None:
            policy.unlink()
        else:
            policy.write_bytes(original_policy)
            policy.chmod(original_policy_mode)
        # Do not ship machine-specific font caches; fontconfig rebuilds them on demand.
        # Python bytecode and package configuration are portable and retained.
        shutil.rmtree(root / 'var/cache/fontconfig', ignore_errors=True)
        after = inventory(root)
        changed = sorted(name for name, value in after.items() if before.get(name) != value)
        removed = sorted(before.keys() - after.keys(), key=lambda n: (-n.count('/'), n))
        if any(not n.startswith(('etc/', 'usr/', 'opt/', 'var/lib/', 'var/cache/fontconfig/')) for n in [*changed, *removed]):
            raise ValueError('Snapshot leaves managed system roots')
        partial = output / 'desktop.tar.bin.part'
        total = 0
        try:
            with tarfile.open(str(partial), 'w|gz', compresslevel=6) as archive:
                for name in changed:
                    path = root / name
                    item = archive.gettarinfo(str(path), arcname=name)
                    item.uid = item.gid = 0
                    item.uname = item.gname = ''
                    # Preserve Python source timestamps so precompiled bytecode
                    # remains usable after extraction on the phone.
                    if not name.endswith('.py'):
                        item.mtime = 1792886400
                    if item.isfile():
                        total += item.size
                        with path.open('rb') as stream:
                            archive.addfile(item, stream)
                    else:
                        archive.addfile(item)
            partial.replace(archive_path)
        finally:
            partial.unlink(missing_ok=True)
        record = dict(expected, sha256=digest(archive_path), bytes=archive_path.stat().st_size,
                      unpackedBytes=total, entryCount=len(changed), removed=removed,
                      packages=len(lock['packages']))
        record_path.write_text(json.dumps(record, indent=2)+'\n')
        print('DESKTOP_SNAPSHOT_READY ' + json.dumps(record), flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--runtime-dir', type=Path, required=True)
    parser.add_argument('--bundle-dir', type=Path, required=True)
    parser.add_argument('--output-dir', type=Path, required=True)
    args = parser.parse_args()
    if os.name == 'nt':
        def linux(path):
            path = path.resolve().as_posix()
            return '/mnt/' + path[0].lower() + path[2:]
        subprocess.run(['wsl.exe', '-u', 'root', '--', 'python3', '-B', linux(Path(__file__)),
            '--runtime-dir', linux(args.runtime_dir), '--bundle-dir', linux(args.bundle_dir),
            '--output-dir', linux(args.output_dir)], check=True)
    else:
        prepare(args.runtime_dir.resolve(), args.bundle_dir.resolve(), args.output_dir.resolve())


if __name__ == '__main__':
    main()
