<p align="center"><a href="README.md">English</a> · <strong>简体中文</strong></p>

<div align="center">
  <img src="docs/images/app-icon.png" alt="HyperImmersive 应用图标" width="128" height="128" />
  <h1>HyperImmersive</h1>
  <p><strong>为 HyperOS 4 锁屏带来 iPhone 风格的待机显示（StandBy）。</strong></p>
  <p>
    <a href="https://github.com/Lewewe/HyperImmersive/releases"><img alt="下载" src="https://img.shields.io/github/v/release/Lewewe/HyperImmersive?include_prereleases&amp;style=flat-square&amp;label=Download&amp;color=f38caf" /></a>
    <a href="https://t.me/HyperMusicCoverEnhanced"><img alt="官方 Telegram 群组" src="https://img.shields.io/badge/Telegram-Official_group-26A5E4?style=flat-square&amp;logo=telegram" /></a>
    <a href="LICENSE"><img alt="AGPL-3.0 许可证" src="https://img.shields.io/badge/License-AGPL--3.0-ffaaa3?style=flat-square" /></a>
  </p>
</div>

> [!IMPORTANT]
> HyperImmersive 仅是 **[HyperMusicCover-Enhanced](https://github.com/Lewewe/HyperMusicCover-Enhanced)** 的扩展，必须安装该应用。HyperImmersive 没有自己的界面：所有设置都在 HyperMusicCover-Enhanced 中更改，歌词、可视化和锁屏背景也由它提供。没有使用相同密钥签名的 HyperMusicCover-Enhanced，StandBy 不会启动。

## 功能

| | |
| --- | --- |
| 🌙 **StandBy** | 手机充电并横放时显示在锁屏之上 |
| 🧩 **小组件** | 时钟、日历等，可就地编辑页面和堆叠 |
| 🎵 **音乐页** | 封面和播放控制，歌词与可视化来自 HyperMusicCover-Enhanced |
| 🖼️ **封面背景** | 播放中歌曲的封面模糊后作为 StandBy 背景 |
| 🔋 **状态一览** | 电量、Wi-Fi 和信号汇聚成一个小图标 |
| 🛌 **休息与息屏** | 一段时间未触摸后变暗，之后可关闭屏幕 |
| 🚫 **屏蔽系统手势** | 通过 LSPosed 让系统栏和边缘手势不打扰 StandBy |

## 安装

**需要：** 已 root 的 HyperOS 4（Android 15+）、LSPosed，以及高于 v1.0.1 的 HyperMusicCover-Enhanced。

1. 在 HyperMusicCover-Enhanced 中打开 **Extensions → HyperImmersive → Download**。
2. 点击 **Request hook permissions**，在 LSPosed 中启用 HyperImmersive，作用域包含系统界面。
3. 点击 **Restart hooks to finish setup**。
4. 打开 **HyperImmersive**，点击 **Grant permissions (root)**。
5. 在锁屏状态下充电并横放手机，或点击 **Preview StandBy**。

所有设置都在 HyperMusicCover-Enhanced 中，包括 **Uninstall HyperImmersive**。应用没有桌面图标。

## 构建

```sh
./gradlew assembleRelease
```

需要 Android SDK 37。请使用与 HyperMusicCover-Enhanced 相同的密钥签名（`keystore.properties`），否则两个应用无法通信，StandBy 也不会启动。没有该文件时会回退到调试密钥。

## 状态

Alpha 阶段，在小米 17 Pro 上测试。其他设备的表现可能不同。

## 致谢

- [Lewewe](https://github.com/Lewewe)：开发
- [miuix](https://github.com/compose-miuix-ui/miuix)：返回和设置图标（Apache-2.0）

许可证：[AGPL-3.0](LICENSE)
