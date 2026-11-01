# SnowLuma 手机手动部署与 AstrBot 对接

在 ARM64 Android 手机上，使用 AstrBot for Android 的 Ubuntu 环境，手动连接 **原版 Linux QQ → SnowLuma → AstrBot → 大模型 → QQ 回复**。

## 阅读导航

- [方案背景与使用范围](#方案背景与使用范围)
- [开始前准备](#开始前准备)
- [1. 在 App 中安装桌面和原版 QQ](#1-在-app-中安装桌面和原版-qq)
- [2. 手动下载与配置 SnowLuma](#2-手动下载与配置-snowluma)
- [3. 登录 QQ 并启动 SnowLuma](#3-登录-qq-并启动-snowluma)
- [4. 连接 AstrBot 并测试回复](#4-连接-astrbot-并测试回复)
- [日常启动与停止](#日常启动与停止)
- [常见问题](#常见问题)
- [来源与许可](#来源与许可)

## 方案背景与使用范围

App 默认使用 NapCat 接入 QQ。希望了解其他 OneBot 接入方案的用户，也可以参考 SnowLuma。[NapCat README 的 “New Framework?” 一节](https://github.com/NapNeko/NapCatQQ#new-framework-)写道：

> 可以试试更新更好用的 SnowLuma 作为NapCat Gui替代品。

这里保留原文的 **NapCat Gui** 范围。SnowLuma 是独立的 QQ 互操作项目，提供 OneBot v11 动作与事件；本文介绍怎样让它连接 AstrBot，不表示两者的所有接口、插件和使用场景都能直接互换。

本文参考官方资料，结合本项目已有的手机手动部署记录整理。App 提供通用 Ubuntu、桌面、原版 QQ 和终端；SnowLuma 由用户另外从官方获取。本教程不附带 SnowLuma 程序、原生库或修改版，命令示例需用户自行复制执行，也不代表上游对本 App 或本文的背书。

**先阅读许可再继续。** SnowLuma 采用[源码可见非商业许可](https://github.com/SnowLuma/SnowLuma/blob/v2.2.1/LICENSE)，不是 OSI 定义的开源许可，原生组件另受 [EULA](https://github.com/SnowLuma/SnowLuma/blob/v2.2.1/EULA.md) 约束。其 EULA 第 5.4 条要求第三方打包、自动化脚本部署及商业使用等事先取得书面授权。本文按个人非商业、手动使用官方原包的情形编写；手动操作和保留原包不构成额外许可，具体兼容启动方式有疑义时请向著作权人确认。更多来源见[文末](#来源与许可)。

当前 App 内的专用部署引导、启停及凭据辅助入口为预览功能，其适用许可范围仍需确认。`LICENSE` 第 4(b) 条还限制未经书面授权“基于本软件开发、发布或分发另一个项目”。命令由用户粘贴、准备与启动分开，以及不内置程序，均不能单独证明这些入口已获许可；条文也未明确列出启停按钮或凭据代填的豁免。这些专用集成行为的许可范围应向著作权人确认。本教程及来源注释不构成书面授权。

## 开始前准备

| 项目 | 本文使用的版本或要求 |
| --- | --- |
| 手机 | Android 8.0 及以上，ARM64 系统，无需手机 Root |
| AstrBot for Android | **1.4.0 / 10041**，扩展中有 **Linux QQ** 入口 |
| 独立原版 Linux QQ | **3.2.34-53644**，从 App 扩展页安装 |
| SnowLuma | **v2.2.1 Linux ARM64 完整包**，使用包内 Node.js |
| 模型服务 | 自己的 API 地址、Key 和可用模型 |

上述 QQ / SnowLuma 组合此前已完成手机端实际消息往返；本篇菜单已按 1.4.0 调整，公开 1.3.0 的操作见[对应版本教程](https://github.com/JunWan666/astrbot-for-android/blob/v1.3.0/docs/SnowLuma手动部署.md)。更换 QQ 或 SnowLuma 版本时，应重新确认兼容性，不直接套用旧库路径和启动参数。

下载 QQ 和 SnowLuma 时保持联网，并按 App 提示预留空间；桌面依赖由完整 APK 离线提供。桌面、独立 QQ 与手动部署文件不在机器人备份的完整覆盖范围内，卸载 App、清除数据或重建环境前先备份重要文件。

## 1. 在 App 中安装桌面和原版 QQ

1. 安装本项目 1.4.0 完整 APK，打开后完成环境初始化。此处的新菜单尚未包含在公开的 1.3.0 安装包中。
2. 初始化会离线准备桌面、编译工具和 `guest-compat`；已有环境首次使用时补齐依赖，必要时到 **Linux QQ → QQ 设置 → 修复内置桌面** 重试。
3. 进入 **设置 → 扩展组件 → Linux QQ**，点击 **安装 QQ**，确认版本为 **3.2.34-53644**。已有旧版时使用更新入口。
4. **本步只安装，暂不启动 QQ**，完成下方兼容配置后再打开并扫码登录。若已经启动，先在 Linux QQ 中停止。回首页，**只启动 AstrBot，让 NapCat 保持停止**。
5. 在 **设置 → 后台运行** 中按本机系统提示配置通知、电池和后台权限。

**本方案运行时不要点首页“启动全部”。** 启动 NapCat 会停止 App 管理的独立原版 QQ。首页显示 AstrBot 运行、NapCat 停止是预期状态，不需要为了凑齐 `2/2` 而启动 NapCat。

## 2. 手动下载与配置 SnowLuma

进入 **设置 → 扩展组件 → Ubuntu 终端**。下面的命令均在这个 Ubuntu 终端执行，每步成功后再继续。

App 内引导归为四步：**准备桌面与 QQ → 准备官方安装包 → 配置并登录 QQ → 连接 AstrBot**。第 1 步的按钮只进入 Linux QQ 安装页，不自动启动；第 3 步配置成功后才通过“QQ 桌面”启动并登录。第 4 步按 AstrBot、SnowLuma 分开列出连接信息，底部可直接返回运行管理，不再单设检查步骤；程序文件和兼容配置也可随时在“安装目录”查看。

引导中的关键词加粗，路径与版本采用等宽样式。安装包准备页将创建目录、下载、校验、解压合为一个带注释的命令块；兼容配置与启动仍为独立操作。注释为绿色，命令为蓝色，长行自动换行完整展示，小屏幕或大字号可滚动页面查看。蓝色的“复制命令”和“Ubuntu 终端”按钮并排显示，按提示粘贴后执行；复制内容包含整段命令与注释。

以下命令从[官方 v2.2.1 Release](https://github.com/SnowLuma/SnowLuma/releases/tag/v2.2.1) 下载 **Linux ARM64 完整包**，约 50 MiB，并核对固定 SHA-256 后解压。本教程不使用 Lite、x64 或 Windows 包。可整段复制到 Ubuntu 终端执行：

```bash
# 创建并进入目录
mkdir -p '/persistent/SnowLuma' && cd '/persistent/SnowLuma' &&
# 下载官方完整包
curl -fL --retry 2 -o SnowLuma.tar.gz https://github.com/SnowLuma/SnowLuma/releases/download/v2.2.1/SnowLuma-v2.2.1-linux-arm64.tar.gz &&
# 校验 SHA-256，失败不解压
printf '%s  %s\n' '26727d30109a5a7cc19e95f7c7a7ca9cbf9eb17adce7c122129e96bda88a2945' 'SnowLuma.tar.gz' | sha256sum -c - &&
# 解压官方完整包
tar -xzf SnowLuma.tar.gz
```

正常会显示 `SnowLuma.tar.gz: OK`（中文环境可能显示“成功”），随后完成解压。前面的命令失败时，后面的步骤不会执行。下载报错、文件不完整或摘要不同，先停止，重新核对官方来源；不要忽略校验结果。

当前目录应有 `node`、`index.mjs`、`native` 等文件。保留发行包的许可和声明，然后保存外部库的兼容启动配置：

```bash
guest-compat configure --library /persistent/SnowLuma/native/snowluma-linux-arm64.so --symbol snowluma_linux_hook_start_dynamic --wait-module wrapper.node
```

显示 `Optional desktop startup profile saved.` 表示配置保存成功。这一步记录用户下载文件的路径及初始化参数，后续 App 的普通原版 QQ 启动入口会读取该配置，不修改 SnowLuma 文件。它是本教程采用的手机环境操作，不是照搬官方 Linux 启动脚本。

如果 QQ 已在配置前启动，先从 App 停止原版 QQ，再按下一节重新启动。该配置不需要每天重复设置；移动或删除 SnowLuma 目录后，应更新路径或[清除配置](#恢复普通原版-qq)。

## 3. 登录 QQ 并启动 SnowLuma

回到 **Linux QQ** 扩展：

1. 点击 **打开 QQ**，App 会自动准备桌面、启动 QQ 并等待窗口就绪。
2. 在打开的 QQ 窗口中自行扫码登录；返回 App 不会停止会话。
3. 如需从其他设备操作，在 **局域网访问 → 局域网设置** 中允许访问并保存，浏览器连接的是同一个桌面与 QQ 会话。

App 内部署步骤的 **QQ 桌面** 按钮也可以打开：未安装 QQ 时先确认安装，成功后自动启动并进入桌面；已安装时自动启动，已运行时直接复用会话。从桌面返回后仍在原来的部署步骤。配置命令需要自行执行；QQ 已在配置前运行时，请先到 Linux QQ 停止后重新打开。

回到刚才的 Ubuntu 终端，执行：

```bash
cd /persistent/SnowLuma
SNOWLUMA_HOOK_AUTOLOAD=0 SNOWLUMA_WEBUI_HOST=127.0.0.1 SNOWLUMA_WEBUI_PORT=5099 guest-compat ./node ./index.mjs
```

这里关闭该次 SnowLuma 启动的自动载入，由前一节保存的 QQ 启动配置配合原包运行；WebUI 仅监听本机。

用**同一台手机**的浏览器打开 **http://127.0.0.1:5099**，根据终端提示的初始凭据登录，阅读并自行确认官方协议，按页面要求修改密码。若没有自动显示在线账号，按页面提示选择已经登录的 QQ 进程并连接，确认账号在线。

使用当前预览版 **SnowLuma → 运行管理** 启动时，App 会从本次启动输出提取初始凭据，可在 **初始凭据** 中查看或复制，默认隐藏密码。通过本页的 **打开管理后台** 进入时，会自动填入空的登录密码框；确认协议、点击登录和修改密码仍由你完成。快捷凭据仅保存在本次运行的内存中，停止、退出或重启后清除，改密后也可以主动清除。终端中另行启动的程序不能在此提取凭据；已经修改过的密码不会读取或找回，普通网页收藏及外部浏览器也不会自动填入。

也可以在 App 底部 **网页 → 添加网址** 中保存这个地址，命名为“SnowLuma”，以后通过内置网页打开。这个收藏入口只负责访问页面，程序仍需从终端手动启动。

**从终端启动时，保持该终端会话运行。** 返回首页、切换终端或浏览器可以继续操作；关闭该终端标签或点击“结束全部终端”会结束其中的程序。停止 QQ 或桌面会中断消息链路，但不会接管或停止你在其他终端手动启动的 SnowLuma；请先在原终端按 Ctrl+C 停止它。同一时间只启动一份 SnowLuma。

**从 App 运行管理页启动时，程序与手动终端独立。** 用本页“停止”结束该次运行；App 停止 QQ 或桌面时，也会先停止这份由 App 启动的 SnowLuma。其他终端里的程序仍由原终端管理。

## 4. 连接 AstrBot 并测试回复

### 4.1 AstrBot：准备 OneBot 连接

从 App 打开 **AstrBot 后台**，使用对应服务“凭据”页中的登录信息，首次登录按提示设置新密码。

进入 **机器人 / 平台管理**，找到 **OneBot v11 / aiocqhttp** 连接。新装环境通常已有名为 `napcat` 的默认连接，**可以编辑复用，名字不要求实际运行 NapCat**。确实没有时再新增，避免两个连接监听相同端口。

| 项目 | 填写内容 |
| --- | --- |
| 平台类型 | OneBot v11 / aiocqhttp |
| 启用 | 开启 |
| 反向 WebSocket 监听地址 / `ws_reverse_host` | `127.0.0.1` |
| 端口 / `ws_reverse_port` | `6199` |
| Token / `ws_reverse_token` | 自己设置一串随机字符，下一步使用同一个值 |

保存；若页面提示重启，回 App **只重启 AstrBot**。

### 4.2 SnowLuma：添加反向 WebSocket

在 SnowLuma 后台已登录账号的 OneBot 配置中，添加 **WS 客户端 / WebSocket Client（反向连接）**：

| 项目 | 填写内容 |
| --- | --- |
| URL | `ws://127.0.0.1:6199/ws` |
| Token / `accessToken` | 与 AstrBot 的 Token 完全相同 |
| 角色 / `role` | `Universal` |
| 消息格式 | 数组（`array`） |
| 启用 | 开启 |

保存，按页面提示应用配置或重启连接，确认连接成功。若修改了 AstrBot 的端口，这里的 URL 也要同步修改。

| 地址 | 用途 |
| --- | --- |
| `http://127.0.0.1:5099` | SnowLuma 管理后台 |
| `http://127.0.0.1:6185` | AstrBot 管理后台，默认端口 |
| `ws://127.0.0.1:6199/ws` | SnowLuma 向 AstrBot 发送 OneBot 事件的连接 |

这些地址中的 `127.0.0.1` 都指当前手机。不要把管理后台地址填成 OneBot 连接地址。

### 4.3 配置模型并发送测试消息

在 AstrBot 的 **模型提供商** 中添加自己的服务。使用兼容 OpenAI 的 API 时，选择对应类型并填写服务商给出的 Base URL、API Key 和实际可用的模型 ID，再按页面提示选择默认聊天模型。

Base URL 按服务商说明填写，常见形式为 `https://你的服务地址/v1`，不要重复拼接 `/v1` 或自行加上 `/chat/completions`。先使用 AstrBot 的测试功能确认模型可用。

然后用**另一个 QQ 账号**私聊机器人账号：

> 请计算 19+23，简短回答。

实际收到模型回复，才能确认消息接收、模型调用和 QQ 发送已连通。只看到进程运行或连接成功，还需要这一步收发验证。群聊是否回复还受 AstrBot 的 @、白名单及回复规则影响。

## 日常启动与停止

开发预览中另提供“扩展组件 → SnowLuma”运行管理页：用户完成本教程的手动安装、QQ 兼容配置后，可填写安装目录并重新检测，再通过页面启动、停止及打开管理后台。该入口尚未包含在正式 v1.3.0 中，不自动执行安装或配置命令。若原终端已经运行了一份 SnowLuma，请先在原终端停止，再交由管理页启动；页面不会接管其他会话。以下终端操作仍适用于正式 v1.3.0。

首次下载、解压和兼容启动配置完成后，日常按这个顺序操作：

1. 在 App **只启动 AstrBot**。
2. 在 Linux QQ 扩展中点击“打开 QQ”，确认 QQ 已登录。
3. 打开 Ubuntu 终端，执行[第 3 节](#3-登录-qq-并启动-snowluma)的两行启动命令。
4. 根据需要打开 SnowLuma 或 AstrBot 后台。

停止时，先在运行 SnowLuma 的终端按 **Ctrl+C**：点终端快捷键 `Ctrl`，再按键盘 `C`。然后在 App 中停止原版 QQ、桌面和 AstrBot。

手机重启、App 被系统结束或终端会话退出后，需要重新启动这些程序；App 不会自动重放终端命令。Ubuntu 维护、桌面安装等操作也会关闭终端，应先结束手动运行的程序。

## 常见问题

### 提示 `guest-compat: command not found`

确认使用的是 App 的 **Ubuntu 终端**，并已完成环境初始化。已有环境可到 **Linux QQ → QQ 设置 → 修复内置桌面** 补齐依赖，再重新打开终端。不需要安装其他终端 App。

### 原版 QQ 提示外部库不存在

检查 `/persistent/SnowLuma/native/snowluma-linux-arm64.so` 是否存在，是否下载了正确架构的完整包，解压后是否多了一层目录。将文件放回原路径，或用实际绝对路径重新执行第 2 节的配置命令，再从 App 重启原版 QQ。不要从其他人处单独索取原生库。

### 5099 打不开，或端口被占用

确认访问的是同一台手机，查看 SnowLuma 所在终端有无报错。若已有一份进程运行，使用现有后台，或回原终端按 Ctrl+C 停止后重试；不要反复新建终端启动多份程序。

### 首页只显示 1/2，QQ 也不在 NapCat 中登录

本方案只使用 AstrBot 和独立原版 QQ，NapCat 保持停止，因此 `1/2` 是预期状态。QQ 登录在 **Ubuntu 桌面里的原版 QQ** 完成，账号及接入状态在 SnowLuma 后台查看。App 的 NapCat 详情不会代替 SnowLuma 的状态页。

### QQ 在线、反向连接成功，但不回复

按顺序确认 SnowLuma 账号在线、反向连接成功、两边 Token 一致、角色为 `Universal`，以及 AstrBot 模型测试可用。查看 AstrBot 日志是否收到测试消息，再检查私聊回复开关、白名单、默认模型等配置。先用另一个账号私聊，容易判断问题在哪一段。

### 终端显示“Shell 已退出（137 / 143）”

先查看 **设置 → 日志与诊断 → 日志 → 管理器** 中的终端生命周期记录，并确认后台权限。退出码不能单独确定原因，不必立即卸载重装。重新打开终端，按日常顺序启动即可；已安装文件通常仍保留。

Android 12 及以上若日志确认是后台派生子进程数量限制，可到 **设置 → 权限**，通过已启动并授权的 Shizuku 查看实际数量与有效上限，按需调整或恢复默认。`256` 是可选参考值，不是安装要求；以重新检测结果为准。息屏省电白名单及厂商后台设置另见[使用指南](使用指南.md#权限与后台子进程)。

### 恢复普通原版 QQ

先在 SnowLuma 终端按 Ctrl+C，然后执行：

```bash
guest-compat clear-profile
```

回 App 停止并重新启动原版 QQ。此命令仅清除外部库启动配置，不删除 QQ 登录数据或 SnowLuma 文件。

如需恢复默认 NapCat 方案，先停止 SnowLuma 和独立原版 QQ，再从首页启动 NapCat。检查原 OneBot 连接的 Token 与 NapCat 配置是否匹配；两份 QQ 的登录数据相互独立。

## 来源与许可

本文由本项目维护者独立整理，更新于 **2026-11-01**。下列官方资料用于确认项目定位、安装入口、接口和许可；手机端路径、App 菜单、`guest-compat` 配置及 AstrBot 对接步骤结合已有实测记录编写，不是 SnowLuma 官方 Android 部署承诺。

- [SnowLuma 官方仓库与 README](https://github.com/SnowLuma/SnowLuma)：项目介绍、运行入口及 OneBot v11 能力。
- [SnowLuma 官方部署指南](https://snowluma.github.io/zh/docs/guide/deploy)与[动作参考](https://snowluma.github.io/zh/docs/api)。
- [SnowLuma v2.2.1 官方发行页](https://github.com/SnowLuma/SnowLuma/releases/tag/v2.2.1)：本文 ARM64 完整包的下载来源与摘要。
- [v2.2.1 LICENSE](https://github.com/SnowLuma/SnowLuma/blob/v2.2.1/LICENSE)：非商业使用范围、再分发条件、修改版及衍生项目限制。
- [v2.2.1 EULA](https://github.com/SnowLuma/SnowLuma/blob/v2.2.1/EULA.md)：官方二进制及专有原生组件的补充条款，尤其第 5 条。
- [NapCat README](https://github.com/NapNeko/NapCatQQ#new-framework-)：本文背景部分所引推荐的来源，保留其 NapCat Gui 表述。
- [AstrBot 官方文档](https://docs.astrbot.app/)：模型提供商、OneBot 平台与回复规则的详细配置。

本项目的 MIT 许可适用于本项目原创内容，不为 SnowLuma 或 QQ 授予额外权利。请保留官方发行包的署名、许可及声明，遵守对应软件和接入平台的条款；商业使用、再分发、自动化部署或其他超出许可的使用方式，应先取得相应授权。分享排错资料时请隐藏账号、二维码、密码、Token 与 API Key。
