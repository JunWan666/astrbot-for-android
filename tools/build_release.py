"""Build and verify a signed ARM64 release; keep private signing files outside Git."""
from __future__ import annotations

import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import secrets
import shutil
import subprocess
import sys
import zipfile

ROOT = Path(__file__).resolve().parents[1]
PACKAGE = "io.astrbot.android"
PASSWORD_ENV = "ASTRBOT_KEYSTORE_PASSWORD"


def say(message: str):
    print(message, flush=True)


def private_path(path: Path):
    """Refuse to create credentials or build outputs at a publishable repository path."""
    if path.resolve().is_relative_to(ROOT.resolve()):
        relative = path.resolve().relative_to(ROOT.resolve()).as_posix()
        result = subprocess.run(["git", "check-ignore", "--no-index", "--quiet", "--", relative], cwd=ROOT)
        if result.returncode:
            raise ValueError("私有文件和构建输出必须放在 Git 已忽略的目录或工程外")


def secure_directory(directory: Path):
    private_path(directory / "release.properties")
    directory.mkdir(parents=True, exist_ok=True)
    if os.name == "nt":
        import csv
        user = subprocess.run(["whoami", "/user", "/fo", "csv", "/nh"], check=True,
                              capture_output=True, text=True).stdout
        sid = next(csv.reader([user.strip()]))[1]
        subprocess.run(["icacls", str(directory), "/inheritance:r", "/grant:r",
                        f"*{sid}:(OI)(CI)F", "*S-1-5-18:(OI)(CI)F"], check=True, capture_output=True)
    else:
        directory.chmod(0o700)


def read_properties(path: Path) -> dict[str, str]:
    if not path.is_file():
        raise ValueError("未找到签名配置。已有应用请恢复原签名目录；仅首次建立签名时使用 --init-signing")
    result = {}
    for line in path.read_text("utf8").splitlines():
        if line.strip() and not line.lstrip().startswith(("#", "!")):
            key, separator, value = line.partition("=")
            if not separator:
                raise ValueError("签名配置格式错误")
            result[key.strip()] = value.strip()
    if any(not result.get(key) for key in ("storeFile", "storePassword", "keyAlias", "keyPassword")):
        raise ValueError("签名配置不完整")
    return result


def certificate(java_home: Path, properties: Path, env: dict[str, str]) -> str:
    values = read_properties(properties)
    keystore = properties.parent / values["storeFile"]
    if not keystore.is_file():
        raise ValueError("签名密钥不存在，请恢复原密钥；不会自动生成替代密钥")
    tool = java_home / "bin" / ("keytool.exe" if os.name == "nt" else "keytool")
    command_env = dict(env, **{PASSWORD_ENV: values["storePassword"]})
    result = subprocess.run([str(tool), "-exportcert", "-keystore", str(keystore), "-alias", values["keyAlias"],
                             "-storepass:env", PASSWORD_ENV], capture_output=True, env=command_env)
    if result.returncode:
        raise ValueError("无法读取签名证书，请检查密钥文件、别名和密码")
    return hashlib.sha256(result.stdout).hexdigest()


def initialize_signing(properties: Path, java_home: Path, env: dict[str, str]):
    keystore = properties.parent / "release.p12"
    identity = properties.parent / "identity.json"
    if any(path.exists() for path in (properties, keystore, identity)):
        raise ValueError("签名目录已有密钥、配置或指纹记录，禁止覆盖；请继续使用或恢复原签名")
    secure_directory(properties.parent)
    password = secrets.token_urlsafe(32)
    tool = java_home / "bin" / ("keytool.exe" if os.name == "nt" else "keytool")
    result = subprocess.run([str(tool), "-genkeypair", "-storetype", "PKCS12", "-keystore", str(keystore),
        "-alias", "astrbot-android-release", "-keyalg", "RSA", "-keysize", "3072", "-sigalg", "SHA256withRSA",
        "-validity", "10000", "-dname", "CN=AstrBot for Android, O=JunWan666, C=CN",
        "-storepass:env", PASSWORD_ENV, "-keypass:env", PASSWORD_ENV],
        capture_output=True, env=dict(env, **{PASSWORD_ENV: password}))
    if result.returncode:
        raise ValueError("签名密钥生成失败；如目录已有文件，请先检查，禁止直接覆盖")
    with properties.open("x", encoding="utf8", newline="\n") as stream:
        stream.write("storeFile=release.p12\nkeyAlias=astrbot-android-release\n"
                     f"storePassword={password}\nkeyPassword={password}\n")
    fingerprint = certificate(java_home, properties, env)
    identity.write_text(json.dumps({"packageName": PACKAGE, "certificateSha256": fingerprint,
        "createdAt": datetime.now(timezone.utc).isoformat()}, indent=2) + "\n", encoding="utf8")
    for path in (keystore, properties, identity):
        if os.name != "nt":
            path.chmod(0o600)
    say("已创建长期 APK 签名密钥。请备份整个签名目录，后续构建会复用此密钥。")
    say(f"证书 SHA-256：{fingerprint}")


def run_logged(command: list[str], env: dict[str, str], log):
    process = subprocess.Popen(command, cwd=ROOT, env=env, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                               text=True, encoding="utf8", errors="replace")
    for line in process.stdout:
        log.write(line)
        log.flush()
        print(line, end="", flush=True)
    if process.wait():
        raise ValueError("构建或检查失败，请查看上方输出和构建日志；未生成新的发行包")


def verify_bundled_runtime(apk: Path) -> dict:
    """Verify the final APK payload after Gradle has merged all runtime asset sources."""
    base = "assets/bootstrap/arm64-v8a/"
    manifest_name, image_name = base + "manifest.json", base + "ubuntu.tar.bin"
    try:
        with zipfile.ZipFile(apk) as archive:
            names = archive.namelist()
            if any(names.count(name) != 1 for name in (manifest_name, image_name)):
                raise ValueError("APK 内置 Ubuntu 资源或清单缺失、重复，已拒绝导出")
            manifest = json.loads(archive.read(manifest_name))
            if (not isinstance(manifest, dict) or manifest.get("abi") != "arm64-v8a" or
                    manifest.get("kind") != "complete"):
                raise ValueError("APK 必须包含 ARM64 完整运行环境，已拒绝导出")
            expected = manifest.get("sha256")
            if not isinstance(expected, str) or not re.fullmatch(r"[a-f0-9]{64}", expected):
                raise ValueError("APK 内置 Ubuntu 清单的 SHA-256 无效，已拒绝导出")
            image = archive.getinfo(image_name)
            with archive.open(image) as stream:
                actual = hashlib.file_digest(stream, "sha256").hexdigest()
            if image.file_size == 0 or actual != expected:
                raise ValueError(f"APK 内置 Ubuntu 资源完整性校验失败：{image_name}，"
                                 f"读取 {image.file_size} 字节，期望 SHA-256 {expected}，实际 {actual}；已拒绝导出")
            return {"abi": manifest["abi"], "bytes": image.file_size, "sha256": actual}
    except (zipfile.BadZipFile, json.JSONDecodeError, UnicodeDecodeError) as failure:
        raise ValueError("无法读取 APK 内置 Ubuntu 资源或清单，已拒绝导出") from failure


def build(properties: Path, java_home: Path, sdk: Path, env: dict[str, str], work: Path, output: Path, config=None):
    config = config or {}
    say("[1/6] 检查签名身份与构建目录…")
    fingerprint = certificate(java_home, properties, env)
    identity_file = properties.parent / "identity.json"
    if identity_file.is_file():
        identity = json.loads(identity_file.read_text("utf8"))
        if identity.get("packageName") != PACKAGE or identity.get("certificateSha256") != fingerprint:
            raise ValueError("当前密钥与已保存的签名身份不同，已停止构建；请恢复原签名")
    private_path(work / "build.log")
    private_path(output / "release.json")
    work.mkdir(parents=True, exist_ok=True)
    output.mkdir(parents=True, exist_ok=True)
    init = work / "build-layout.init.gradle"
    build_root = str(work / "build").replace("\\", "/").replace("'", "\\'")
    init.write_text("allprojects { layout.buildDirectory.set(file('" + build_root +
        "/' + (path == ':' ? 'root' : path.substring(1).replace(':', '/')))) }\n", encoding="utf8")
    log_path = work / "build-release.log"
    with log_path.open("w", encoding="utf8") as log:
        say("[2/6] 编译进程托管程序并收集正式包的实际发行依赖…")
        inputs = {}
        local = ROOT / "local.properties"
        if local.is_file():
            for line in local.read_text("utf8").splitlines():
                if line.strip() and not line.lstrip().startswith(("#", "!")) and '=' in line:
                    key, value = line.split('=', 1)
                    inputs[key.strip()] = value.strip()
        def input_path(name, default):
            return (ROOT / inputs.get(name, default)).resolve()
        native_output = work / 'native-libs'
        run_logged([sys.executable, '-B', '-X', 'utf8', 'tools/build_support/build_native.py',
                    '--sdk', str(sdk), '--input-dir', str(input_path('nativeLibsDir', 'app/src/main/jniLibs')),
                    '--output-dir', str(native_output)], env, log)
        native_record = json.loads((native_output / 'native-build.json').read_text('utf-8'))
        optional_output = work / 'optional-assets'
        run_logged([sys.executable, '-B', '-X', 'utf8', 'tools/build_support/prepare_optional_assets.py',
                    '--output-dir', str(optional_output)], env, log)
        wrapper = ROOT / ("gradlew.bat" if os.name == "nt" else "gradlew")
        command = ([os.environ.get("COMSPEC", "cmd.exe"), "/d", "/c", str(wrapper)] if os.name == "nt"
                   else ["bash", str(wrapper)])
        common = ["--project-cache-dir", str(work / "gradle"), "-I", str(init), "-PbundledRuntime=true",
                  f"-PnativeLibsDir={native_output}",
                  f"-PoptionalAssetsDir={optional_output}",
                  "-Pkotlin.incremental=false", f"-PreleaseSigningProperties={properties}", "--console", "plain"]
        run_logged(command + common + [":app:collectReleaseDependencyMetadata"], env, log)
        say("[3/6] 生成并校验随包许可材料…")
        distribution = Path(config.get("distributionDirectory", ROOT / ".cache/distribution/release")).resolve()
        apk_assets = input_path("distributionAssetsDir", "runtime/distribution-assets")
        private_path(distribution / "collection.json")
        private_path(apk_assets / "third-party/manifest.json")
        collect = [sys.executable, "-B", "-X", "utf8", "tools/build_support/collect_distribution.py",
            "--runtime-dir", str(input_path("runtimeAssetsDir", "runtime/apk-assets") / "bootstrap/arm64-v8a"),
            "--cache-dir", str(Path(config.get("sourceCacheDirectory", ROOT / ".cache")).resolve()),
            "--native-dir", str(native_output),
            "--android-metadata", str(work / "build/app/distribution/android-dependencies"),
            "--output", str(distribution), "--apk-assets", str(apk_assets)]
        if config.get("pythonSourceDirectory"):
            collect += ["--python-source-directory", str(Path(config["pythonSourceDirectory"]).resolve())]
        run_logged(collect, env, log)
        verify_sources = [sys.executable, "-B", "-X", "utf8", "tools/build_support/verify_distribution.py", "--directory", str(distribution)]
        if config.get("pythonSourceDirectory"):
            verify_sources += ["--python-source-directory", str(Path(config["pythonSourceDirectory"]).resolve())]
        run_logged(verify_sources, env, log)
        say("[4/6] 构建 ARM64 正式包并执行 lint 检查…")
        run_logged(command + common + [":app:assembleBundledRelease", ":app:lintBundledRelease"], env, log)
    say("[5/6] 核对 APK 签名、包名、版本、内置环境及文件摘要…")
    directory = work / "build/app/outputs/apk/bundled/release"
    metadata = json.loads((directory / "output-metadata.json").read_text("utf8"))
    if metadata.get("applicationId") != PACKAGE or len(metadata.get("elements", [])) != 1:
        raise ValueError("发行包必须使用正式包名且只有一个 ARM64 安装包")
    item = metadata["elements"][0]
    apk = directory / "app-bundled-release.apk"
    if item["outputFile"] != apk.name or not apk.is_file():
        raise ValueError("未找到正式签名 APK，禁止将未签名文件作为发行包")
    versions = [p for p in (sdk / "build-tools").iterdir() if re.fullmatch(r"\d+\.\d+\.\d+", p.name)]
    if not versions:
        raise ValueError("未找到稳定版 Android Build Tools，请安装 35.0.0 或更新版本")
    tools = max(versions, key=lambda p: tuple(map(int, p.name.split('.'))))
    # Force verification of v1 as well: the default minSdk >= 24 skips it once v2 verifies.
    # This affects signature verification only; the app still requires Android 8 (API 26).
    verify = subprocess.run([str(java_home / "bin" / ("java.exe" if os.name == "nt" else "java")), "-jar",
        str(tools / "lib/apksigner.jar"), "verify", "--verbose", "--print-certs", "--min-sdk-version", "23", str(apk)],
        check=True, capture_output=True, text=True, encoding="utf8")
    signers = re.findall(r"Signer #\d+ certificate SHA-256 digest: ([a-fA-F0-9]+)", verify.stdout)
    if signers != [fingerprint]:
        raise ValueError("APK 签名与长期密钥不一致，已拒绝导出")
    schemes = {scheme: bool(re.search(rf"Verified using {scheme} scheme [^\n]*: true", verify.stdout))
               for scheme in ("v1", "v2", "v3")}
    if not all(schemes.values()):
        raise ValueError("APK 必须同时通过 v1、v2、v3 签名验证，兼容签名缺失，已拒绝导出")
    badging = subprocess.run([str(tools / ("aapt2.exe" if os.name == "nt" else "aapt2")), "dump", "badging", str(apk)],
                             check=True, capture_output=True, text=True, encoding="utf8").stdout
    expected = f"package: name='{PACKAGE}' versionCode='{item['versionCode']}' versionName='{item['versionName']}'"
    if expected not in badging or "native-code: 'arm64-v8a'" not in badging or "x86_64" in badging:
        raise ValueError("APK 包名、版本或架构与发行元数据不一致")
    bundled_runtime = verify_bundled_runtime(apk)
    with zipfile.ZipFile(apk) as archive:
        optional = json.loads((ROOT / 'runtime/shizuku-manager.lock.json').read_text('utf-8'))
        if hashlib.sha256(archive.read('assets/optional/shizuku.apk')).hexdigest() != optional['sha256']:
            raise ValueError('Bundled Shizuku installer differs from pinned original')
        for program in native_record['programs']:
            if (hashlib.sha256((ROOT / program['source']).read_bytes()).hexdigest() != program['sourceSha256'] or
                    hashlib.sha256(archive.read(program['file'])).hexdigest() != program['sha256']):
                raise ValueError("APK 进程托管程序与本次源码编译结果不一致，已拒绝导出")
    version = item["versionName"]
    if not re.fullmatch(r"[a-zA-Z0-9._+-]{1,100}", version):
        raise ValueError("版本号不能用于发行文件名")
    with apk.open('rb') as stream:
        sha = hashlib.file_digest(stream, 'sha256').hexdigest()
    name = f"AstrBot-for-Android-v{version}-arm64-v8a.apk"
    target = output / name
    temporary = output / (name + ".part")
    shutil.copyfile(apk, temporary)
    temporary.replace(target)
    item["outputFile"] = name
    (output / "output-metadata.json").write_text(json.dumps(metadata, indent=2) + "\n", encoding="utf8")
    record = {"file": name, "packageName": PACKAGE, "versionName": version, "versionCode": item["versionCode"],
              "bytes": target.stat().st_size, "sha256": sha, "certificateSha256": fingerprint,
              "signingVerified": True, "signatureSchemes": schemes, "bundledRuntime": bundled_runtime,
              "nativeBuild": native_record}
    (output / "release.json").write_text(json.dumps(record, indent=2) + "\n", encoding="utf8")
    (output / "SHA256SUMS").write_text(f"{sha}  {name}\n", encoding="utf8")
    older = sorted(output.glob("AstrBot-for-Android-v*-arm64-v8a.apk"), key=lambda p: p.stat().st_mtime, reverse=True)
    for candidate in older[2:]:
        if candidate.is_file() and not candidate.is_symlink():
            candidate.unlink()
    say("[6/6] 构建完成，签名验证通过。输出目录最多保留最近两份候选安装包。")
    say(f"安装包：{target}\n版本：{version}（{item['versionCode']}）\nSHA-256：{sha}\n构建日志：{log_path}")


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description="AstrBot 中文一键构建：检查、正式签名、校验和导出",
                                     add_help=False, usage="%(prog)s [选项]")
    parser._optionals.title = "选项"
    parser.add_argument("-h", "--help", action="help", help="显示帮助并退出")
    parser.add_argument("--init-signing", action="store_true", help="首次创建长期签名；不会覆盖已有密钥")
    parser.add_argument("--signing-properties", type=Path, default=ROOT / ".local/signing/release.properties", help="私有签名配置")
    parser.add_argument("--config", type=Path, default=ROOT / ".local/build.json", help="本机构建路径配置")
    parser.add_argument("--output-dir", type=Path, help="安装包输出目录，默认 dist")
    args = parser.parse_args(argv)
    try:
        config = json.loads(args.config.read_text("utf8")) if args.config.is_file() else {}
        env = dict(os.environ, PYTHONDONTWRITEBYTECODE="1")
        java_raw = config.get("javaHome") or env.get("JAVA_HOME")
        if not java_raw:
            raise ValueError("请设置 JDK 17 的 JAVA_HOME，或在 .local/build.json 配置 javaHome")
        java_home = Path(java_raw).resolve()
        if not (java_home / "bin" / ("keytool.exe" if os.name == "nt" else "keytool")).is_file():
            raise ValueError("JDK 路径无效，请检查 JAVA_HOME")
        env["JAVA_HOME"] = str(java_home)
        properties = args.signing_properties.resolve()
        if args.init_signing:
            initialize_signing(properties, java_home, env)
            return 0
        sdk_raw = config.get("androidSdk") or env.get("ANDROID_HOME") or env.get("ANDROID_SDK_ROOT")
        if not sdk_raw or not (Path(sdk_raw) / "build-tools").is_dir():
            raise ValueError("请准备 Android SDK 35 和 Build Tools，并设置 ANDROID_HOME 或 androidSdk")
        sdk = Path(sdk_raw).resolve()
        env["ANDROID_HOME"] = str(sdk)
        env["ANDROID_SDK_ROOT"] = str(sdk)
        if config.get("gradleUserHome"):
            env["GRADLE_USER_HOME"] = config["gradleUserHome"]
        work = Path(config.get("workDirectory", ROOT / ".local/build")).resolve()
        output = (args.output_dir or Path(config.get("outputDirectory", ROOT / "dist"))).resolve()
        build(properties, java_home, sdk, env, work, output, config)
        return 0
    except (OSError, ValueError, KeyError, subprocess.SubprocessError) as error:
        # Never print signing configuration, process environment or a keytool command containing credentials.
        say(f"操作失败：{error}")
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
