# Third-party components

The original Android manager code and documentation are copyright (c) 2026 JunWan666 and licensed under the [MIT License](../../LICENSE). Attribution is recorded in [NOTICE](NOTICE).

This file describes the pinned complete ARM64 runtime, not just the Ubuntu bootstrap. The manager's MIT license does not replace third-party terms or grant permission to redistribute third-party software.

The service cards use the [AstrBot brand symbol](https://astrbot.app/assets/astrbot-logo-with-text.svg) and the [NapCat artwork supplied for this project](https://cdn.apifox.com/app/project-icon/custom/20241226/3c5b999f-935a-4116-9d68-e4ddbaa7ef3f.png). The original files are retained in `docs/images/`; source URLs, SHA-256 and Android resource transformations are recorded in `runtime/brand-assets.json`. Artwork and trademarks retain their respective owners' rights and are separate from the manager's MIT license.

The README community buttons use the [QQ symbol from Simple Icons](https://simpleicons.org/?q=qq), distributed under [CC0-1.0](https://github.com/simple-icons/simple-icons/blob/develop/LICENSE.md). QQ is a trademark of Tencent.


| Component | Pinned version / source | License evidence |
| --- | --- | --- |
| AstrBot | [4.29.3](https://github.com/AstrBotDevs/AstrBot/tree/v4.29.3) | AGPL-3.0; exact upstream LICENSE retained |
| NapCat | [4.18.41](https://github.com/NapNeko/NapCatQQ/tree/v4.18.41) | **Limited Redistribution License for NapCat**; full original text retained, including noncommercial and modified-source restrictions. No broader permission inferred |
| Linux QQ | 3.2.34-53644; URL and digest in `runtime/projects.lock.json` | QQ redistribution permission **unconfirmed**. Electron and Chromium notices are retained and do not establish permission to redistribute QQ itself |
| PRoot | [Termux 5.1.107.101](https://github.com/termux/proot/tree/v5.1.107.101) | GPL-2.0-or-later, from COPYING and source headers |
| libandroid-shmem | [0.7](https://github.com/termux/libandroid-shmem/tree/v0.7) | BSD-3-Clause; exact source LICENSE retained |
| talloc library | [2.5.0](https://www.samba.org/ftp/talloc/talloc-2.5.0.tar.gz) | LGPL-3.0-or-later, from LICENSE and `talloc.c` / `talloc.h`. Bundled build tools and other source files retain their own terms; Termux recipe labels alone are not used to override the library headers |
| Ubuntu Base and installed packages | Ubuntu 24.04.3; 383 installed binary packages / 262 source-name-and-version pairs | Individual copyright/common-license texts retained. All 262 exact source packages are in the separate `ubuntu-source-packages.zip`, authenticated through Ubuntu archive signatures, including historical snapshots |
| AstrBot Python environment | 150 distributions in `runtime/astrbot-python.lock.txt` | Exact installed METADATA and available dist-info license/notice files retained; separate source companion contains 148 exact PyPI sdists and 2 explicitly identified upstream tag/commit archives. Wheel/native build correspondence remains unverified |
| NapCat external npm packages | 67 installed package.json records | Package metadata and individual license/notice texts retained. Bundled/minified code and embedded native components require additional mapping |
| Apache MINA SSHD | 2.20.0 (`sshd-core`, `sshd-common`) | Apache-2.0; exact source JARs, LICENSE and NOTICE retained in the release collection |
| Android release runtime dependencies | 71 unique external AAR/JAR artifacts | Actual Gradle release graph, binary SHA-256, resolved POM license metadata, embedded notices and available source JARs retained; platform/BOM source records are excluded from shipped-artifact counts |
| Gradle wrapper | [8.11.1](https://github.com/gradle/gradle/tree/v8.11.1) | Apache-2.0; wrapper/build tools are separate from the Android runtime graph |

`runtime/sources.lock.json`, `runtime/projects.lock.json` and `runtime/distribution-sources.lock.json` pin downloads. The prepared native resources use the following transformations: PRoot programs are packaged with `.so` filenames, and the `libtalloc.so.2` ELF dynamic string becomes `libtalloc.so` without changing string offsets. The Android NDK builds the manager's process owner and SSH PTY bridge from `native/process_runner.c` and `native/terminal_runner.c`; their per-ABI output hashes are recorded in the release inventory. The SSH server uses the Gradle-pinned Apache MINA SSHD libraries, not the Dropbear packages considered during investigation.

Run `:app:collectReleaseDependencyMetadata`, then `python tools/build_support/collect_distribution.py --apk-assets runtime/distribution-assets`. The collector reads only pinned build inputs and allowlisted source files; it never reads device data. It creates:

- `third-party-notices.zip`: original notice texts, public package metadata and a partial release inventory. Bundled APKs include this archive, with an application viewer and export action.
- `available-sources.zip`: original AstrBot/NapCat/native source archives, Termux packaging recipes and 71 Maven source JARs. This is **not complete corresponding source**.
- `manager-source.zip`: manager code, build/provisioning scripts, automated checks, locks and public documentation; excludes device data, caches, binary runtimes, signing material and build outputs.
- `ubuntu-source-packages.zip`: 262 exact Ubuntu source packages (833 original files), Debian build/patch archives and signed index evidence. Prepared as a separate build input; separate from the APK and `available-sources.zip`.
- `python-source-packages.zip`: a separate companion with 148 exact PyPI sdists and 2 explicitly identified upstream tag/commit archives, original metadata and fixed source lock. Bind its external directory using `--python-source-directory` when collecting and verifying release materials; it is not embedded in the APK or copied into the main output directory.
- `release-inventory.json` and `collection.json`: exact versions, SHA-256, file manifests, scope and unresolved delivery items. They contain no host cache paths.

Python source archives are collected for all 150 pinned versions; complete corresponding source, wheel provenance, vendored/native/JavaScript dependency mapping, binary build provenance and applicable third-party grants require separate release review. The original manager is MIT licensed. Ubuntu source archives are collected; matching binary rebuilds have not been performed. Merely retaining URLs or source JARs does not complete those obligations. Rebuild the inventory for every runtime/dependency change. Collection, source companions and required release materials are described in the [distribution guide](../发行材料.md).
