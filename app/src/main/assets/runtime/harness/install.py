#!/usr/bin/env python3
"""Install the optional official Harness distribution without changing shared Node.js."""
import hashlib
import json
import os
import queue
from pathlib import Path
import shutil
import subprocess
import tarfile
import threading
import time
import urllib.request

BASE = Path('/manager/extensions/harness')
SOURCE = BASE / 'scripts'


STAGES = ('download', 'verify', 'extract', 'system', 'packages', 'verify_program')


class Progress:
    def __init__(self):
        self.started = self.changed = time.monotonic()
        self.stage = None

    def emit(self, stage, **values):
        now = time.monotonic()
        if self.stage != stage:
            self.stage, self.changed = stage, now
        print('ASTRBOT_HARNESS ' + json.dumps(dict(stage=stage,
            step=STAGES.index(stage) + 1 if stage in STAGES else len(STAGES),
            elapsed=int(now - self.started), stageElapsed=int(now - self.changed), **values)), flush=True)

    def run(self, stage, command, **kwargs):
        self.emit(stage, activity='starting')
        process = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                   text=True, errors='replace', **kwargs)
        lines = queue.Queue(maxsize=256)

        def read():
            try:
                for line in process.stdout:
                    lines.put(line)
            finally:
                lines.put(None)

        threading.Thread(target=read, daemon=True).start()
        activity, fetched, last_output, last_report = 'waiting', 0, time.monotonic(), 0.0
        try:
            while True:
                try:
                    line = lines.get(timeout=2)
                except queue.Empty:
                    line = ''
                if line is None:
                    break
                if line:
                    print(line.rstrip(), flush=True)
                    last_output = time.monotonic()
                    if 'npm http fetch' in line:
                        fetched += 1
                        activity = 'fetch'
                    elif any(word in line.lower() for word in ('gyp ', 'cmake', 'cxx', 'cc ', 'make:')):
                        activity = 'compile'
                    else:
                        activity = 'working'
                if time.monotonic() - last_report >= 2:
                    self.emit(stage, activity=activity, fetched=fetched,
                              quietSeconds=int(time.monotonic() - last_output))
                    last_report = time.monotonic()
            code = process.wait()
            self.emit(stage, activity='complete' if code == 0 else 'failed', fetched=fetched,
                      quietSeconds=int(time.monotonic() - last_output))
            if code:
                raise subprocess.CalledProcessError(code, command)
        finally:
            if process.poll() is None:
                process.terminate()
                try:
                    process.wait(timeout=8)
                except subprocess.TimeoutExpired:
                    process.kill()
            process.stdout.close()


def install():
    progress = Progress()
    lock = json.loads((SOURCE / 'sources.json').read_text())
    stage, program, previous = BASE / 'program.new', BASE / 'program', BASE / 'program.old'
    # Only these manager-owned staging paths are replaced. User home/workspace stay separate.
    if previous.exists():
        if not program.exists():
            previous.rename(program)
        else:
            shutil.rmtree(previous)
    shutil.rmtree(stage, ignore_errors=True)
    stage.mkdir(parents=True)
    for name in ('home', 'workspace', 'npm-cache'):
        (BASE / name).mkdir(exist_ok=True)
    archive = BASE / 'node.tar.xz'
    try:
        node = lock['node']
        progress.emit('download', downloaded=0, total=node['bytes'])
        digest = hashlib.sha256()
        with urllib.request.urlopen(node['url'], timeout=30) as response, archive.open('wb') as output:
            count, last = 0, 0.0
            while data := response.read(65536):
                count += len(data)
                if count > node['bytes']:
                    raise ValueError('Node archive is larger than pinned source')
                output.write(data)
                digest.update(data)
                if time.monotonic() - last > .3:
                    progress.emit('download', downloaded=count, total=node['bytes'])
                    last = time.monotonic()
        # Always finish the counter, even when the final chunk is inside the throttle interval.
        progress.emit('download', downloaded=count, total=node['bytes'])
        progress.emit('verify')
        if count != node['bytes'] or digest.hexdigest() != node['sha256']:
            raise ValueError('Node archive integrity check failed')
        progress.emit('extract')
        with tarfile.open(archive) as tar:
            members = tar.getmembers()
            last = 0.0
            for index, member in enumerate(members):
                tar.extract(member, stage, filter='data')
                if time.monotonic() - last > .5 or index == len(members) - 1:
                    progress.emit('extract', completed=index + 1, entries=len(members))
                    last = time.monotonic()
        (stage / node['directory']).rename(stage / 'node')
        shutil.copyfile(SOURCE / 'package.json', stage / 'package.json')
        shutil.copyfile(SOURCE / 'package-lock.json', stage / 'package-lock.json')
        environment = dict(os.environ, HOME=str(BASE / 'home'),
            PATH=str(stage / 'node/bin') + ':/manager/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin',
            npm_config_cache=str(BASE / 'npm-cache'), npm_config_registry='https://registry.npmjs.org',
            npm_config_audit='false', npm_config_fund='false', DEBIAN_FRONTEND='noninteractive')
        # npm native add-ons may need a compiler on a fresh Ubuntu installation.
        progress.emit('system')
        if any(shutil.which(tool, path=environment['PATH']) is None for tool in ('make', 'g++', 'python3', 'xdg-user-dir')):
            progress.run('system', ['apt-get', 'update', '-o', 'Acquire::Retries=2'], env=environment)
            progress.run('system', ['apt-get', 'install', '-y', '--no-install-recommends', 'make', 'g++', 'python3', 'ca-certificates', 'xdg-user-dirs'], env=environment)
        progress.run('packages', [str(stage / 'node/bin/node'), str(stage / 'node/lib/node_modules/npm/bin/npm-cli.js'),
            'ci', '--no-audit', '--no-fund', '--foreground-scripts', '--loglevel=http'], cwd=stage, env=environment)
        progress.run('verify_program', [str(stage / 'node/bin/node'), str(stage / 'node_modules/@deepseek-ai/dsh/lib/bin.js'), '--help'],
            cwd=BASE / 'workspace', env=environment)
        if program.exists():
            program.rename(previous)
        stage.rename(program)
        marker = BASE / 'installed.json'
        temporary = marker.with_suffix('.new')
        temporary.write_text(json.dumps({'version': lock['version'], 'nodeVersion': node['version']}))
        temporary.replace(marker)
        shutil.rmtree(previous, ignore_errors=True)
        progress.emit('finished')
    finally:
        archive.unlink(missing_ok=True)
        shutil.rmtree(stage, ignore_errors=True)


if __name__ == '__main__':
    install()
