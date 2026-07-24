<img src="https://geysermc.org/img/geyser-1760-860.png" alt="Geyser" width="600"/>

[![License: MIT](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)
[![Discord](https://img.shields.io/discord/613163671870242838.svg?color=%237289da&label=discord)](https://discord.gg/geysermc)
[![Crowdin](https://badges.crowdin.net/e/51361b7f8a01644a238d0fe8f3bddc62/localized.svg)](https://translate.geysermc.org/)

Geyser is a bridge between Minecraft: Bedrock Edition and Minecraft: Java Edition, closing the gap from those wanting to play true cross-platform.

Geyser is an [Open Collaboration](https://opencollaboration.dev/) project.

## What is Geyser?
Geyser is a proxy, bridging the gap between Minecraft: Bedrock Edition and Minecraft: Java Edition servers.
The ultimate goal of this project is to allow Minecraft: Bedrock Edition users to join Minecraft: Java Edition servers as seamlessly as possible. However, due to the nature of Geyser translating packets over the network of two different games, *do not expect everything to work perfectly!*

Special thanks to the DragonProxy project for being a trailblazer in protocol translation and for all the team members who have joined us here!

## 本分支改动

本分支基于 GeyserMC/Geyser，主要增加并修正 Java 与 Bedrock 世界高度坐标转换喵~

- 在初始连接阶段声明完整 Bedrock 主世界高度窗口，并为超高 Java 维度建立双向 Y 坐标映射喵~
- 按映射后的高度处理区块 Section、生物群系、区块实体及动态方块更新喵~
- 在方块预测、服务端确认、缓存回滚和交互路径中区分 Java 服务端坐标与 Bedrock 客户端坐标喵~
- 为实体、载具、掉落物、钓鱼钩、投射物、声音、粒子和传送应用对应的坐标转换；实体位置只应用 offset，不按世界边界截断喵~
- 在 `debugMode` 下使用 INFO 日志输出高度声明、活动映射、区块编码及 Bedrock 上游包诊断喵~

上游 GeyserMC/Geyser 代码继续遵循原有 MIT License，并保留原版权与许可声明；根目录 `LICENSE` 保持上游 MIT License 不变。本分支作者新增及修改的部分按 AGPL-3.0-or-later 说明发布，完整文本位于 `LICENSE-AGPL-3.0`，不改变上游代码的 MIT 授权范围喵~

公开开发分支为 `new-happy`，它以最新上游代码为基底并只保留一条汇总的 fork 改动 commit；旧 `happy` 分支的测试 commit 仅保留为私有历史，不会发布喵~

本项目使用 AI 辅助进行二次开发，所有改动仍经过人工审查与测试喵~

## Supported Versions

| Edition | Supported Versions                                                                                |
|---------|---------------------------------------------------------------------------------------------------|
| Bedrock | 26.0, 26.1, 26.2, 26.3, 26.10, 26.20, 26.21, 26.22, 26.23, 26.30, 26.31, 26.32, 26.33                      |
| Java    | 26.2 (For older versions, [see this guide](https://geysermc.org/wiki/geyser/supported-versions/)) |

## Setting Up
Take a look [here](https://geysermc.org/wiki/geyser/setup/) for how to set up Geyser.

## Links:
- Website: https://geysermc.org
- Docs: https://geysermc.org/wiki/geyser/
- Download: https://geysermc.org/download
- Discord: https://discord.gg/geysermc
- Donate: https://opencollective.com/geysermc
- Test Server: `test.geysermc.org` port `25565` for Java and `19132` for Bedrock

## What's Left to be Added/Fixed
- Near-perfect movement (to the point where anticheat on large servers is unlikely to ban you)
- Some Entity Flags

## What can't be fixed
There are a few things Geyser is unable to support due to various differences between Minecraft Bedrock and Java. For a list of these limitations, see the [Current Limitations](https://geysermc.org/wiki/geyser/current-limitations/) page.

## Compiling
1. Clone the repo to your computer
2. Navigate to the Geyser root directory and run `git submodule update --init --recursive`. This command downloads all the needed submodules for Geyser and is a crucial step in this process.
3. Run `gradlew build` and locate to `bootstrap/build` folder.

## Contributing
Any contributions are appreciated. Please feel free to reach out to us on [Discord](https://discord.gg/geysermc) if
you're interested in helping out with Geyser.

## Libraries Used:
- [Adventure Text Library](https://github.com/KyoriPowered/adventure)
- [CloudburstMC Bedrock Protocol Library](https://github.com/CloudburstMC/Protocol)
- [GeyserMC's Java Protocol Library](https://github.com/GeyserMC/MCProtocolLib)
- [TerminalConsoleAppender](https://github.com/Minecrell/TerminalConsoleAppender)
- [Simple Logging Facade for Java (slf4j)](https://github.com/qos-ch/slf4j)
