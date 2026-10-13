# zoe-iptv

一个极简的 Android 电视 / 手机二合一播放器：

- 打开 App **直接进入横屏全屏播放**，自动续播上次频道
- **播放列表是 m3u 格式**，但里面的地址是**网页地址**（不是 .m3u8 直链）
- 频道用**火狐 GeckoView 内核**打开网页，由网页自己的播放器解码播放
  （网页内的 JS/WASM 解密逻辑原样执行，不需要 App 去破解）
- **自适应全屏**：内置扩展自动找 `<video>` 播放并全屏（三重策略：requestFullscreen
  → 点页面全屏按钮 → 兜底把 video 铺满视口）
- **频道面板**：按分组显示，带台标（tvg-logo）、编号，当前频道高亮

## 播放内核：GeckoView（火狐）

播放内核**打包在 APK 里**，不依赖系统 WebView，各设备体验一致：

- 桌面 UA 模式打开页面（央视频 tv/home 是桌面页面）
- `PermissionDelegate` 放行自动播放（有声/无声）
- `assets/zoeext/` 内置扩展做自动播放 + 全屏

代价：APK 体积增加约 100MB+（Gecko 的 .so）；已只保留 `arm64-v8a` /
`armeabi-v7a` 双 ABI 砍体积。x86 设备（极少数电视盒子/模拟器）装不了，
如需支持，去掉 `app/build.gradle.kts` 里 `ndk.abiFilters` 即可。

## m3u 格式

```m3u
#EXTM3U
#EXTINF:-1 tvg-logo="https://example.com/cctv2.png" group-title="央视",CCTV-2 财经
https://www.yangshipin.cn/tv/home?pid=600001800
#EXTINF:-1 tvg-logo="https://example.com/cctv13.png" group-title="央视",CCTV-13 新闻
https://www.yangshipin.cn/tv/home?pid=600001811
```

- `#EXTINF` 后面的名字显示在列表里，支持 `tvg-logo`（面板显示台标）/ `group-title`（面板分组）
- 地址行只要是 `http(s)` 开头就收录，网页地址和直链可混排
- 播放列表来源：长按屏幕（或遥控器菜单键）进「设置」填 m3u 的 http 地址；
  留空则用内置 `app/src/main/assets/sample.m3u`（央视频道示例）

## 遥控器 / 按键 / 手势

| 操作 | 电视遥控器 | 手机 |
|---|---|---|
| 呼出频道面板 | OK / 确认键 | 点一下屏幕 |
| 面板内导航 | 上 / 下 | 滑动 / 点选 |
| 直接切台 | 上 / 下、频道+/- | 上下滑动 |
| 打开设置（配直播源） | 菜单键 | 长按屏幕 |
| 返回 | 退出 App | 退出 App |

面板打开时：返回键 / 菜单键关闭面板；上下键在列表里导航，OK 确认播放。

## 编译

### 方法一：VPS 标准 Gradle 编译（当前在用）

VPS（Ubuntu 22.04，2G 内存）上已有环境：JDK 17、Gradle 8.9、Android SDK
（platform-34、build-tools 34.0.0）。`gradle.properties` 里有针对 2G 内存的
调优（Xmx1024m 等），同步工程时不要覆盖。

```bash
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
export ANDROID_HOME=$HOME/android-sdk ANDROID_SDK_ROOT=$HOME/android-sdk
export PATH=$HOME/tools/gradle-8.9/bin:$JAVA_HOME/bin:$PATH
cd ~/zoe-iptv && gradle assembleDebug
```

APK 在 `app/build/outputs/apk/debug/app-debug.apk`（debug 签名，个人自用直接装）。

### 方法二：GitHub Actions 云端编译

`.github/workflows/build-apk.yml` 已备好，push 到 main 自动构建并发布 Release。
注意：GitHub App 没有 Workflows 权限，`.github` 目录需在网页端手动创建一次。

### 方法三：本地 Android Studio 编译

`File → Open` 打开本目录，等 Gradle 同步完成，`Build → Build APK(s)`。
要求：JDK 17，`compileSdk 34`，`minSdk 21`（Android 5.0+，老盒子可装）。

## 文件结构

```
app/src/main/
├── AndroidManifest.xml            # PlayerActivity 即启动页（LEANBACK_LAUNCHER 电视入口）
├── assets/
│   ├── sample.m3u                 # 内置示例播放列表
│   └── zoeext/                    # 内置扩展：自动播放 + 全屏（manifest.json + autoplay.js）
├── java/com/zoe/iptv/
│   ├── PlayerActivity.kt          # 横屏全屏播放器（GeckoView，核心）
│   ├── SettingsActivity.kt        # 播放列表地址设置
│   ├── model/Channel.kt
│   ├── data/PlaylistParser.kt     # m3u 解析（含 tvg-logo / group-title）
│   ├── data/PlaylistGrouper.kt    # 按分组整理面板数据
│   ├── data/PlaylistHolder.kt     # 进程内列表单例
│   ├── data/Prefs.kt              # 设置存储（含上次播放频道）
│   └── ui/PlayerChannelAdapter.kt # 分组头 + 频道行（台标/编号/高亮）
└── res/                           # 布局 / 主题
```

## 已知限制

- 网页播不了，App 也播不了：App 不破解、不嗅探，只是网页的壳
- 境外 IP 打开央视频可能被拦（403 / 一直加载中），这是网络环境问题
- APK 体积大（GeckoView .so，约 100MB+），低端设备首次安装稍慢
