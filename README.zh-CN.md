# LootTrade

[English](README.md)

创作者：Eric_SXR

面向搜打撤服务器的战利品出售与命令方块木桶购买功能。

## 功能

- 出售配置的战利品并购买配置的商品。
- 与 Vault 及 STPR 对局限制配合使用。

## 兼容环境

- 插件版本：`1.3.0`（以 `plugin.yml` 为准）。
- 目标服务端：Paper/Purpur 1.20.1，API `1.20`。
- 编译 JDK：17。
- 必需插件：Vault。
- 可选插件：未声明。

## 编译与安装

1. 使用 JDK 17，在仓库根目录运行 `mvn package`。
2. 将 `target/` 中生成的 JAR 复制到服务器的 `plugins/` 目录。
3. 重启服务器，再按需修改插件生成的配置文件。

## 命令与配置

- 注册命令：`/lt`。权限和用法见 `src/main/resources/plugin.yml`。
- 主要配置段：`messages`, `include-offhand`, `sell`, `buy`。详见 `src/main/resources/config.yml`。
