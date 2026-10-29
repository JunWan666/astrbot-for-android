"""Documentation included in the public repository and source distribution."""

PUBLIC_DOCUMENTS = frozenset({
    ".github/CONTRIBUTING.md",
    ".github/GIT_COMMIT_RULES.md",
    "docs/README.en.md",
    "docs/CHANGELOG.md",
    "docs/examples/local.properties.example",
    "docs/legal/AUTHORS.md",
    "docs/legal/NOTICE",
    "docs/legal/THIRD_PARTY_NOTICES.md",
    "docs/使用指南.md",
    "docs/构建指南.md",
    "docs/更新与分发.md",
    "docs/发行材料.md",
    "docs/在线更新协议.md",
    "docs/更新包格式与恢复协议.md",
    "docs/images/logo.png",
    "docs/images/qq-community.svg",
    "docs/images/qq-community.en.svg",
    "docs/images/astrbot-logo-with-text.svg",
    "docs/images/napcat-logo.png",
    "docs/images/home.webp",
    "docs/images/personas.webp",
    "docs/images/settings.webp",
    "docs/images/websites.webp",
    "docs/images/extensions.webp",
    "docs/images/permissions.webp",
    "docs/images/astrbot-webui.webp",
    "docs/images/napcat-webui.webp",
})


def is_public_document(name: str) -> bool:
    return name in PUBLIC_DOCUMENTS
