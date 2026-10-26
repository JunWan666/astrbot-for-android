# APK 构建工具

Windows 双击根目录 `build.bat`，或执行 `python3 -B tools/build_release.py`。所需资源与签名配置见[构建指南](../docs/构建指南.md)。

此目录只保留正式打包直接使用的文件：

| 文件 | 用途 |
| --- | --- |
| `build_release.py` | 构建 ARM64 Release、使用长期签名、执行 lint 并校验 APK |
| `build_support/collect_distribution.py` | 按实际发行依赖生成随包许可和源码清单 |
| `build_support/collect_python_sources.py` | 校验发行材料中的 Python 源码伴随包 |
| `build_support/verify_distribution.py` | 校验发行归档、文件摘要及来源关系 |
| `build_support/repository_layout.py` | 限定源码包允许包含的公开文档与图片 |

后四项是构建入口直接调用或导入的依赖。资源准备、更新发布、图标处理和测试工具由维护者单独保存在本地，不属于默认 APK 构建流程。
