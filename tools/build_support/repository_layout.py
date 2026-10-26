"""Documentation included in the public repository and source distribution."""

PUBLIC_DOCUMENTS = frozenset({
    "docs/使用指南.md",
    "docs/构建指南.md",
    "docs/更新与分发.md",
    "docs/发行材料.md",
    "docs/在线更新协议.md",
    "docs/更新包格式与恢复协议.md",
    "docs/images/logo.png",
    "docs/images/astrbot-logo-with-text.svg",
    "docs/images/napcat-logo.png",
    "docs/images/home.webp",
    "docs/images/personas.webp",
    "docs/images/settings.webp",
    "docs/images/service-logs.webp",
    "docs/images/astrbot-webui.webp",
    "docs/images/napcat-webui.webp",
})


def is_public_document(name: str) -> bool:
    return name in PUBLIC_DOCUMENTS
