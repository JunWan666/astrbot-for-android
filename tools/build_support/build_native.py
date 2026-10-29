"""Compile manager-owned Android launchers, retaining prepared third-party libraries."""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess

ROOT = Path(__file__).resolve().parents[2]
NDK_VERSION = "27.0.12077973"


def prepare(sdk: Path, source: Path, output: Path, ndk: Path | None = None) -> dict:
    source, output = source.resolve(), output.resolve()
    if source == output or source.is_relative_to(output) or output.is_relative_to(source):
        raise ValueError("Native build output must be separate from prepared inputs")
    ndk = ndk or Path(os.environ.get("ANDROID_NDK_HOME", sdk / "ndk" / NDK_VERSION))
    host = "windows-x86_64" if os.name == "nt" else "darwin-x86_64" if os.sys.platform == "darwin" else "linux-x86_64"
    toolchain = ndk / "toolchains/llvm/prebuilt" / host
    compiler = toolchain / "bin" / ("clang.exe" if os.name == "nt" else "clang")
    strip = toolchain / "bin" / ("llvm-strip.exe" if os.name == "nt" else "llvm-strip")
    if not compiler.is_file() or not strip.is_file():
        raise ValueError(f"Android NDK {NDK_VERSION} is required; install it in the SDK or set ANDROID_NDK_HOME")
    revision = next(line.partition('=')[2].strip() for line in (ndk / 'source.properties').read_text().splitlines()
                    if line.startswith('Pkg.Revision'))
    target = output / 'arm64-v8a'
    target.mkdir(parents=True, exist_ok=True)
    inputs = list((source / 'arm64-v8a').glob('*.so'))
    if not inputs:
        raise ValueError("Prepared ARM64 PRoot libraries are missing")
    names = {path.name for path in inputs} | {'libprocess-runner.so', 'libterminal-runner.so'}
    for previous in target.glob('*.so'):
        if previous.name not in names:
            previous.unlink()
    for path in inputs:
        shutil.copyfile(path, target / path.name)
    flags = ['--target=aarch64-linux-android26', f'--sysroot={toolchain / "sysroot"}',
             '-fPIE', '-pie', '-O2', '-Wall', '-Wextra', '-Werror', '-Wl,-z,max-page-size=16384']
    programs = []
    for name in ('process', 'terminal'):
        code = ROOT / f'native/{name}_runner.c'
        destination = target / f'lib{name}-runner.so'
        staged = destination.with_suffix('.so.new')
        try:
            subprocess.run([str(compiler), *flags, str(code), '-o', str(staged)], check=True)
            # Match Android's packaging transform before recording the shipped digest.
            subprocess.run([str(strip), '--strip-unneeded', str(staged)], check=True)
            staged.replace(destination)
        finally:
            staged.unlink(missing_ok=True)
        programs.append({'source': code.relative_to(ROOT).as_posix(), 'sourceSha256': hashlib.sha256(code.read_bytes()).hexdigest(),
                         'file': f'lib/arm64-v8a/{destination.name}', 'sha256': hashlib.sha256(destination.read_bytes()).hexdigest()})
    record = {'ndkVersion': revision, 'api': 26, 'abi': 'arm64-v8a', 'programs': programs}
    (output / 'native-build.json').write_text(json.dumps(record, indent=2) + '\n', encoding='utf-8')
    return record


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--sdk', type=Path, required=True)
    parser.add_argument('--input-dir', type=Path, required=True)
    parser.add_argument('--output-dir', type=Path, required=True)
    parser.add_argument('--ndk', type=Path)
    arguments = parser.parse_args()
    print(json.dumps(prepare(arguments.sdk, arguments.input_dir, arguments.output_dir, arguments.ndk), indent=2))
