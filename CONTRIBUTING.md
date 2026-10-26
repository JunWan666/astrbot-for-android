# 参与开发

欢迎通过 Issue / Pull Request 反馈问题、提出建议和提交代码。

## 提交与版本

提交信息使用 `编号:类型:说明`，例如 `002:修复:修正服务停止后首页按钮状态`。编号、类型、版本递增和发布约定见[提交规则](GIT_COMMIT_RULES.md)。

## 开发与构建

先阅读 [README](README.md) 和[构建指南](docs/构建指南.md)，使用 JDK 17、Android SDK 35 和项目自带的 Gradle wrapper。源码编译可执行：

```powershell
./gradlew.bat :core:compileKotlin :app:compileBootstrapReleaseKotlin
```

完整 APK 需要准备运行镜像、原生资源与许可输入，随后运行根目录 `build.bat`。公开的 [tools 目录](tools/README.md)只保留正式打包直接使用的工具。

修改功能时说明具体问题、修改后的行为和验证方式；涉及界面或设备兼容时注明设备及 Android 版本。自动化测试和人工验收材料由维护者在本地管理，不作为公开源码或默认打包的前置依赖。

## 仓库内容

仓库保留应用源码、必要的构建工具、依赖锁、许可和使用说明。资源准备、更新发布、图标处理等独立维护工具保存在已忽略的 `.local/maintenance/`；测试源码、验收脚本、截图和报告保存在 `.local/testing/`。

APK、运行镜像、原生预编译程序和缓存不进入 Git。账号、API Key、QQ 会话、配置备份、本机 `local.properties` 和 `.local/signing/` 签名材料也不提交。第三方依赖变化时，同步维护来源、版本锁和适用的许可材料。

## 许可

原创代码采用 [MIT](LICENSE)。提交原创贡献表示允许按该协议分发；第三方材料保留原作者、来源和许可。
