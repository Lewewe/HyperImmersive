<p align="center"><strong>English</strong> · <a href="README.zh-CN.md">简体中文</a></p>

<div align="center">
  <img src="docs/images/app-icon.png" alt="HyperImmersive app icon" width="128" height="128" />
  <h1>HyperImmersive</h1>
  <p><strong>iPhone-style StandBy on the HyperOS 4 lock screen.</strong></p>
  <p>
    <a href="https://github.com/Lewewe/HyperImmersive/releases"><img alt="Download" src="https://img.shields.io/github/v/release/Lewewe/HyperImmersive?include_prereleases&amp;style=flat-square&amp;label=Download&amp;color=f38caf" /></a>
    <a href="https://t.me/HyperMusicCoverEnhanced"><img alt="Official Telegram group" src="https://img.shields.io/badge/Telegram-Official_group-26A5E4?style=flat-square&amp;logo=telegram" /></a>
    <a href="https://t.me/HyperMusicCoverEnhancedChannel"><img alt="Official Telegram channel" src="https://img.shields.io/badge/Telegram-Official_channel-26A5E4?style=flat-square&amp;logo=telegram" /></a>
    <a href="LICENSE"><img alt="License AGPL-3.0" src="https://img.shields.io/badge/License-AGPL--3.0-ffaaa3?style=flat-square" /></a>
  </p>
</div>

> [!IMPORTANT]
> HyperImmersive is an extension for **[HyperMusicCover-Enhanced](https://github.com/Lewewe/HyperMusicCover-Enhanced)** only, and that app is required. HyperImmersive has no screen of its own: every setting is changed from HyperMusicCover-Enhanced, which also provides the lyrics, visualizer and lock screen backdrop. Without HyperMusicCover-Enhanced, signed with the same key, StandBy does not start.

## Features

| | |
| --- | --- |
| 🌙 **StandBy** | Opens over the lock screen when the phone charges on its side |
| 🧩 **Widgets** | Clocks, calendar and more, in pages and stacks you can edit in place |
| 🎵 **Music page** | Artwork and playback controls, with lyrics and visualizer from HyperMusicCover-Enhanced |
| 🖼️ **Artwork background** | The playing track's cover, blurred behind StandBy |
| 🔋 **Status at a glance** | Battery, Wi-Fi and signal gathered into one small glyph |
| 🛌 **Rest & sleep** | Dims after a while without a touch, then can turn the screen off |
| 🚫 **No system gestures** | The bars and edge gestures stay out of StandBy, through LSPosed |

## Install

**Requires:** rooted HyperOS 4 (Android 15+), LSPosed and HyperMusicCover-Enhanced newer than v1.0.1.

1. In HyperMusicCover-Enhanced, open **Extensions → HyperImmersive → Download**.
2. Tap **Request hook permissions** and enable HyperImmersive in LSPosed with System UI in its scope.
3. Tap **Restart hooks to finish setup**.
4. Open **HyperImmersive** and tap **Grant permissions (root)**.
5. Charge the phone and turn it on its side while it is locked, or tap **Preview StandBy**.

All settings live in HyperMusicCover-Enhanced, including **Uninstall HyperImmersive**. The app has no launcher icon.

## Build

```sh
./gradlew assembleRelease
```

Needs Android SDK 37. Sign it with the same key as HyperMusicCover-Enhanced (`keystore.properties`), or the two apps cannot talk to each other and StandBy does not start. Without that file it falls back to the debug key.

## Status

Alpha, tested on Xiaomi 17 Pro. Other devices may behave differently.

## Credits

- [Lewewe](https://github.com/Lewewe): development
- [miuix](https://github.com/compose-miuix-ui/miuix): the back and settings icons (Apache-2.0)

License: [AGPL-3.0](LICENSE)
