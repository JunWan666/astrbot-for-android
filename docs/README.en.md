<div align="center">
  <img src="./images/logo.png" alt="AstrBot for Android" width="120" />
  <h1>AstrBot for Android</h1>
  <p><a href="../README.md">简体中文</a> | <strong>English</strong></p>
  <p><strong>Run AstrBot and NapCat on your Android device.</strong></p>
  <p>One APK · Local execution · Native management · Built-in WebUIs</p>
  <p>
    <img src="https://img.shields.io/badge/Kotlin-2.1.20-7F52FF?style=for-the-badge&amp;logo=kotlin&amp;logoColor=white" alt="Kotlin" />
    <img src="https://img.shields.io/badge/Jetpack_Compose-Native_UI-4285F4?style=for-the-badge&amp;logo=jetpackcompose&amp;logoColor=white" alt="Jetpack Compose" />
    <img src="https://img.shields.io/badge/Ubuntu-PRoot-E95420?style=for-the-badge&amp;logo=ubuntu&amp;logoColor=white" alt="PRoot Ubuntu" />
  </p>
  <p>
    <a href="https://github.com/JunWan666/astrbot-for-android/releases/latest"><img src="https://img.shields.io/badge/Version-1.3.0-3867FF?style=flat-square" alt="Version 1.3.0" /></a>
    <img src="https://img.shields.io/badge/Android-8.0%2B-3DDC84?style=flat-square&amp;logo=android&amp;logoColor=white" alt="Android 8.0 and above" />
    <img src="https://img.shields.io/badge/Architecture-ARM64-64748B?style=flat-square" alt="ARM64" />
    <a href="../LICENSE"><img src="https://img.shields.io/badge/License-MIT-18A978?style=flat-square" alt="MIT License" /></a>
    <a href="https://github.com/JunWan666"><img src="https://img.shields.io/badge/Author-JunWan666-111827?style=flat-square&amp;logo=github" alt="JunWan666" /></a>
  </p>
  <p>
    <a href="https://github.com/JunWan666/astrbot-for-android/releases/latest">Download APK</a> ·
    <a href="#screenshots">Screenshots</a> ·
    <a href="#architecture">Architecture</a> ·
    <a href="#getting-started">Getting started</a> ·
    <a href="#optional-setup-snowluma">SnowLuma manual setup</a> ·
    <a href="./使用指南.md">User guide (Chinese)</a> ·
    <a href="#building-from-source">Build from source</a> ·
    <a href="./CHANGELOG.md">Changelog (Chinese)</a>
  </p>
</div>

## Overview

**AstrBot for Android** (AstrBot 运行管理器) is an Android application for deploying and managing AstrBot and NapCat locally. A single APK bundles the PRoot Ubuntu environment and service components, providing environment setup, service controls, status monitoring, logs, backup and restore, and independent online updates. Root access is not required.

Built with Kotlin and Jetpack Compose, the app provides a native management interface in Simplified Chinese and English and embeds the AstrBot and NapCat WebUIs in a WebView. Users can sign in to QQ, configure model providers and plugins, manage personas, enable LAN access, and use SSH and cpolar tunneling extensions directly from their phone.

Current version: **1.3.0**. This update adds website bookmarks, Shizuku permission management, Android device access for terminal tools and an optional DeepSeek Harness extension. It also groups settings and improves service readiness, download progress, terminal selection and process management. See the [changelog (Chinese)](CHANGELOG.md) for the full list of changes.

## Screenshots

Captured from **v1.3.0** on an Android phone. The eight screens show service management, personas, website bookmarks, grouped settings, extensions, permissions and both embedded WebUIs.

<table width="100%" align="center">
  <tr>
    <td align="center" width="25%">
      <img src="./images/home.webp" alt="Home: service status, controls and resource usage" width="150" />
      <p align="center"><strong>Home · Services &amp; resources</strong></p>
    </td>
    <td align="center" width="25%">
      <img src="./images/personas.webp" alt="Personas: marketplace, search and local presets" width="150" />
      <p align="center"><strong>Personas · Search &amp; switch</strong></p>
    </td>
    <td align="center" width="25%">
      <img src="./images/websites.webp" alt="Websites: saved addresses, site icons and built-in browsing" width="150" />
      <p align="center"><strong>Websites · Quick access</strong></p>
    </td>
    <td align="center" width="25%">
      <img src="./images/settings.webp" alt="Settings: runtime, permissions, maintenance and general options" width="150" />
      <p align="center"><strong>Settings · Grouped menus</strong></p>
    </td>
  </tr>
  <tr>
    <td align="center" width="25%">
      <img src="./images/extensions.webp" alt="Extensions: terminal, desktop, remote access and AI workspace" width="150" />
      <p align="center"><strong>Extensions · On demand</strong></p>
    </td>
    <td align="center" width="25%">
      <img src="./images/permissions.webp" alt="Permissions: Shizuku, child process statistics and idle exemption" width="150" />
      <p align="center"><strong>Permissions · Live status</strong></p>
    </td>
    <td align="center" width="25%">
      <img src="./images/astrbot-webui.webp" alt="AstrBot dashboard inside the app" width="150" />
      <p align="center"><strong>AstrBot · Built-in WebUI</strong></p>
    </td>
    <td align="center" width="25%">
      <img src="./images/napcat-webui.webp" alt="NapCat dashboard inside the app" width="150" />
      <p align="center"><strong>NapCat · Built-in WebUI</strong></p>
    </td>
  </tr>
</table>

The screenshots show the app in Simplified Chinese. English is also available in Settings → Language.

## Features

| Feature | Description |
| --- | --- |
| Service management | Start or stop AstrBot and NapCat individually or together; view service health, connection status and errors. |
| Built-in WebUIs | Open the original WebUIs with valid initial credentials prefilled, or use an external browser. |
| Fixed zoom | Defaults to 75%, with 100% / 75% / 50% / 25% options; pinch zoom is disabled. |
| Local connectivity | New installations prepare a OneBot connection to `ws://127.0.0.1:6199/ws` with an empty token, preserving custom connections. |
| LAN access | Enable LAN access for each WebUI independently, copy its address, and retain WebUI authentication. |
| Persona library | Includes 4 presets adapted from community featured prompts, with attribution and adaptation notes. Add, delete (including presets), search, filter by category and switch the default persona. Includes a link to an external persona marketplace. |
| Live logs | Follow the latest service output automatically, pause updates, copy logs or export them with sensitive data redacted. |
| Resource overview | View memory usage for the app and managed processes, total device memory and the usage percentage. Storage usage is shown separately. |
| Backup and restore | Create local snapshots and portable backups, roll back updates, and automatically remove old unprotected automatic snapshots. |
| SSH extension | Installed by default and started on demand, with configurable password, port and LAN access. |
| Ubuntu terminal | Built-in local shell with multiple sessions, smooth touch scrolling, arrow keys, paste and Enter. Text selection magnifier on Android 9 and later. |
| Ubuntu desktop | Optional lightweight desktop and general build tools, with an embedded noVNC viewer. Install or update independent original ARM64 QQ while preserving login data. |
| cpolar extension | Install on demand and sign in through the official embedded WebUI. Includes SSH, AstrBot and NapCat tunnel presets. |
| DeepSeek Harness | Optional independent Node.js runtime and official AI workspace, with staged installation progress and optional LAN access. |
| Website bookmarks | Save HTTP / HTTPS addresses, load website icons automatically and open pages inside the app with per-site zoom settings. |
| Permission management | Install the official Shizuku APK offline, inspect tracked child processes and their effective limit, and optionally exempt this app from Android idle restrictions. |
| Android device access | Disabled by default; separately allow screen reading and input for local terminal tools, with device state queries and shared file exchange. |
| App language | Choose system default, Simplified Chinese or English for the management interface, notifications and app messages. |
| Background setup | Access notification, battery optimization, unrestricted background usage and auto-start settings, with guidance for locking the app in recent tasks and an option to hide its task cards. |
| Updates | Review release notes and choose in-app download with verification and installation, or download in an external browser. AstrBot and NapCat update independently. |

Settings, extensions and permissions use section headings and grouped cards in a continuous menu, with scrolling on small screens or at larger font sizes. Opening a service switches the bottom navigation to that service's overview, connections, credentials and logs. Its credentials page can reset a forgotten WebUI password or access token.

The Websites tab stores custom HTTP / HTTPS bookmarks, with the DeepSeek usage page provided by default. Website icons load automatically, with an initial as fallback. Pages open inside the app and remember zoom for each bookmark. Desktop and original QQ downloads display bytes, total size, percentage and speed, with pause, resume and stop during download. Partial files are kept for verified reuse; package installation runs to completion.

## Getting started

1. Download the full APK from [Releases](https://github.com/JunWan666/astrbot-for-android/releases/latest), install it on an ARM64 Android device, then open the app and initialize the environment.
2. Tap **Start all** and wait for AstrBot and NapCat to become ready.
3. Open the NapCat WebUI to sign in to QQ, then configure model providers, messaging platforms and plugins in the AstrBot WebUI.
4. Follow **Settings → Background running** to adjust device permissions, then configure personas, backups, SSH and cpolar as needed. **Settings → Permissions** provides an official offline Shizuku installer. On Android 12 and later, it separately displays tracked child process counts for this app and the device, plus the effective limit, which you can adjust if needed. Unavailable counts are never reported as zero. Start and authorize Shizuku after installation.

The full APK includes the runtime environment, so initialization does not require a separate Ubuntu download or dependency installation. QQ sign-in, model API calls and online updates require network access. Enter account credentials and API keys directly in the relevant WebUI. See the [user guide (Chinese)](使用指南.md) for more details.

### Device and storage requirements

- Android 8.0 / API 26 or later, with an ARM64 processor. Root access is not required.
- The full APK is approximately **720 MiB**. Allow additional storage for the extracted environment, QQ data and backups.
- If free space is below the runtime preparation estimate, a warning offers **Continue anyway**. Running out of writable storage can still cause the operation to fail.
- Resource usage varies with plugins, model calls and QQ activity. Home displays actual app memory usage; the app does not preallocate memory for a virtual machine.
- Continuous operation requires appropriate background permissions on the device. The app currently does not start automatically at boot.

Install cpolar separately in **Settings → Runtime extensions**; its client is not bundled in the APK. Sign in through its official WebUI to save the token automatically and manage the three default tunnels. Start each target service in the app before using its tunnel. Features depend on your cpolar account plan.

## Optional setup: SnowLuma

[SnowLuma](https://github.com/SnowLuma/SnowLuma) is another QQ interoperability project providing OneBot v11 interfaces. The [NapCat README](https://github.com/NapNeko/NapCatQQ#new-framework-) recommends it as an alternative to **NapCat Gui**. See our [manual phone setup and AstrBot connection guide (Chinese)](SnowLuma手动部署.md) for this optional approach.

The guide uses the app's general Ubuntu, desktop, original QQ and terminal features. Users obtain the official full package and set it up themselves, keeping NapCat stopped while using this approach. The app's default setup remains NapCat. SnowLuma uses a **source-available non-commercial license**, with separate proprietary terms for native components; read its [LICENSE](https://github.com/SnowLuma/SnowLuma/blob/v2.2.1/LICENSE) and [EULA](https://github.com/SnowLuma/SnowLuma/blob/v2.2.1/EULA.md) first. This repository does not supply its programs or automated deployment scripts. The guide does not imply upstream endorsement or additional permission.

## Building from source

Use JDK 17, Android SDK 35, the Android NDK and Python 3.11+. Compile the application sources with:

```powershell
./gradlew.bat :core:compileKotlin :app:compileBootstrapReleaseKotlin
```

A full APK additionally requires a prepared runtime image, native resources and license inputs. Large assets and machine settings are excluded from Git. Once these inputs and the long-term signing configuration are ready, double-click `build.bat` on Windows, or run `python3 -B tools/build_release.py` on other systems. The script displays Chinese progress messages, builds and verifies a signed release, and exports it to `dist/` by default. See the [build guide (Chinese)](构建指南.md).

The keystore and credentials stay in the Git-ignored `.local/signing/` directory and are reused for later releases. See [signing initialization and backup (Chinese)](构建指南.md#一键构建正式包).

## Updates

**Settings → About → Check for updates** manages app APK updates. A dialog shows release notes and offers an in-app download or an external browser. The in-app download verifies the APK and opens the system installer; if installation permission is missing, the app guides you to settings and continues when you return. A message confirms when the latest applicable version is installed. AstrBot and NapCat have separate component update entries. The in-app flow retains signature verification, resumable downloads, snapshots before installation and component rollback. Android asks you to confirm APK installation.

The app uses this project's signed GitHub update channels by default: **Releases host app APKs and AstrBot packages, the `updates` branch hosts signed update manifests, and NapCat packages are downloaded directly from upstream official Shell releases**. Changes to Linux QQ, Python or underlying dependencies require a matching runtime environment upgrade. See [updates and distribution (Chinese)](更新与分发.md) for configuration details.

## Community

<p align="center">
  <a href="https://qm.qq.com/q/fS2CdoBQB2"><img src="./images/qq-community.en.svg" alt="Join the QQ community" width="220" /></a>
</p>

Join the QQ community to discuss app usage, AstrBot / NapCat configuration, personas and feature ideas. Group discussions are primarily in Chinese. You can also report issues through [GitHub Issues](https://github.com/JunWan666/astrbot-for-android/issues) for tracking and reference.

## Documentation

The detailed guides and project policies below are currently available in Chinese.

- [User guide](使用指南.md): WebUI configuration, personas, websites, permissions, device access, extensions, resources and backups.
- [SnowLuma manual setup](SnowLuma手动部署.md): Official package setup on a phone, AstrBot connection, troubleshooting, sources and license references.
- [Build guide](构建指南.md): Development tools, runtime assets and APK builds.
- [Updates and distribution](更新与分发.md): App signing, update channels and GitHub publishing.
- [Distribution materials](发行材料.md): Third-party licenses, dependency inventories and accompanying source archives.
- [Changelog](CHANGELOG.md) · [Contributing](../.github/CONTRIBUTING.md) · [Commit rules](../.github/GIT_COMMIT_RULES.md).

## License and acknowledgements

Original code in this project is licensed under the [MIT License](../LICENSE). **Copyright © 2026 [JunWan666](https://github.com/JunWan666)**. Retain the copyright notice and license text when using or distributing the code.

Thanks to [AstrBot](https://github.com/AstrBotDevs/AstrBot), [NapCatQQ](https://github.com/NapNeko/NapCatQQ), [PRoot](https://github.com/proot-me/proot), Ubuntu, Termux and the related open-source projects. Third-party components remain subject to their respective licenses and distribution terms. See [NOTICE](legal/NOTICE) and [third-party notices](legal/THIRD_PARTY_NOTICES.md).

## Star History

<div align="center">
  <a href="https://www.star-history.com/?repos=JunWan666%2Fastrbot-for-android&amp;type=date&amp;legend=top-left">
    <picture>
      <source media="(prefers-color-scheme: dark)" srcset="https://api.star-history.com/chart?repos=JunWan666/astrbot-for-android&amp;type=date&amp;theme=dark&amp;legend=top-left" />
      <source media="(prefers-color-scheme: light)" srcset="https://api.star-history.com/chart?repos=JunWan666/astrbot-for-android&amp;type=date&amp;legend=top-left" />
      <img src="https://api.star-history.com/chart?repos=JunWan666/astrbot-for-android&amp;type=date&amp;legend=top-left" alt="AstrBot for Android Star History" width="680" />
    </picture>
  </a>
</div>
