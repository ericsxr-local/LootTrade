# LootTrade

[简体中文](README.zh-CN.md)

Creator: Eric_SXR

Loot selling and command-block barrel purchases for an extraction-style server.

## Features

- Sell configured loot and buy configured products.
- Integrates with Vault and STPR match restrictions.

## Compatibility

- Plugin version: `1.3.0` (from `plugin.yml`).
- Target: Paper/Purpur 1.20.1, API `1.20`.
- Build JDK: 17.
- Required plugins: Vault.
- Optional plugins: None declared.

## Build and install

1. Run `mvn package` from this repository using JDK 17.
2. Copy the built JAR from `target/` to your server's `plugins/` directory.
3. Restart the server and edit the generated plugin configuration as needed.

## Commands and configuration

- Registered commands: `/lt`. See `src/main/resources/plugin.yml` for permissions and usage.
- Main configuration sections: `messages`, `include-offhand`, `sell`, `buy`. See `src/main/resources/config.yml`.
