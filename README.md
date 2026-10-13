# zoe-iptv

一个极简的 Android 电视 / 手机二合一播放器：

- **播放列表是 m3u 格式**，但里面的地址是**网页地址**（不是 .m3u8 直链）
- 点播频道后用**全屏 WebView** 打开网页，由网页自己的播放器解码播放
  （网页内的 JS/WASM 解密逻辑原样执行，不需要 App 去破解）
- **自适应全屏**：页面加载后自动注入 JS 找 `<video>` 播放并全屏；
  网页视频全屏时由 `WebChromeClient.onShowCustomView` 接管
- **TV / 手机自适应**：电视上显示 Leanback 入口、焦点导航、遥控器切台；
  手机上是普通触控列表

## 播放内核

默认用**系统 WebView（Chromium/Chrome 内核）**：

- 零 APK 体积增量，随系统更新
- 电视盒子上的视频兼容性最好（硬解、MSE 都走系统能力）

如需换成**火狐内核（GeckoView）**，见下文「换内核」。

## m3u 格式

```m3u
#EXTM3U
#EXTINF:-1 group-title="央视",CCTV-2 财经
https://www.yangshipin.cn/tv/home?pid=600001800
#EXTINF:-1 group-title="央视",CCTV-13 新闻
https://www.yangshipin.cn/tv/home?pid=600001811
```

- `#EXTINF` 后面的名字显示在列表里，支持 `tvg-logo` / `group-title`
- 地址行只要是 `http(s)` 开头就收录，网页地址和直链可混排
- 播放列表来源：App 内「设置」里填 m3u 的 http 地址；留空则用内置
  `app/src/main/assets/sample.m3u`（央视频道示例）

## 遥控器 / 按键

| 按键 | 列表页 | 播放页 |
|---|---|---|
| 确认 | 打开频道 | — |
| 上 / 下 | 移动焦点 | 上一个 / 下一个频道 |
| 频道+/- | — | 上一个 / 下一个频道 |
| 菜单键 | 打开设置 | — |
| 返回 | 退出 | 先退视频全屏，再退回列表 |

## 编译

### 方法一：GitHub Actions 云端编译（推荐，不用装任何东西）

1. 在 GitHub 新建一个仓库（比如 `zoe-iptv`，Public/Private 都行）
2. 把本目录所有文件传上去：网页上点 `Add file → Upload files`，
   把 `zoe-iptv` 里的全部文件拖进去（**包含 `.github` 隐藏文件夹**）→ Commit
   - 或者用 git：`git init && git add -A && git commit -m init && git remote add origin <仓库地址> && git push -u origin main`
3. 推完后自动触发构建：去仓库的 **Actions** 标签页看进度（约 3~5 分钟）
4. 构建成功后去 **Releases** 页面下载 `zoe-iptv.apk`
5. 装到电视/盒子：`adb install zoe-iptv.apk`
   （电视需开「未知来源安装」或用 ADB）

注意：云端打出来的是 debug 签名包，个人自用直接装没问题；
以后每次 push 代码都会自动打新包、发新 Release。

### 方法二：本地 Android Studio 编译

1. 安装 [Android Studio](https://developer.android.com/studio)（会自动装 SDK）
2. `File → Open` 打开本目录（`zoe-iptv`）
3. 等 Gradle 同步完成，`Build → Build APK(s)`
4. APK 在 `app/build/outputs/apk/debug/app-debug.apk`
5.  adb 安装到电视/盒子：`adb install app-debug.apk`
   （电视需开「未知来源安装」或用 ADB）

要求：JDK 17，`compileSdk 34`，`minSdk 21`（Android 5.0+，老盒子可装）。

## 换内核：GeckoView（火狐）

改动点只有 `PlayerActivity`，把 `WebView` 换成 `GeckoView`：

1. `app/build.gradle.kts` 加依赖：
   ```kotlin
   implementation("org.mozilla.geckoview:geckoview:128.0.20240710175624")
   ```
   （版本号去 [GeckoView 文档](https://firefox-source-docs.mozilla.org/mobile/android/geckoview/) 查最新的）
2. 布局里的 `<WebView>` 换成 `<org.mozilla.geckoview.GeckoView>`
3. `PlayerActivity` 里：
   - `GeckoRuntime.getDefault(context)` 创建 runtime 并 `geckoView.setSession(session)`
   - `GeckoSession` 负责 `loadUri()`；JS 注入改用 `session.evaluateJS()`（需自己包 Promise）
   - 全屏视频：`GeckoSession.ContentDelegate.onFullScreen()` / `onFullScreenExit()`，
     对应现在的 `onShowCustomView` / `onHideCustomView`

代价：APK 增大约 100~180MB（各 ABI 的 .so），且 GeckoView 的视频硬解在部分
电视盒子上不如系统 WebView 稳定。建议先用默认 Chrome 内核验证流程。

## 文件结构

```
app/src/main/
├── AndroidManifest.xml            # LEANBACK_LAUNCHER 电视入口
├── assets/sample.m3u              # 内置示例播放列表
├── java/com/zoe/iptv/
│   ├── MainActivity.kt            # 频道列表（TV/手机自适应）
│   ├── PlayerActivity.kt          # 全屏 WebView 播放器（核心）
│   ├── SettingsActivity.kt        # 播放列表地址设置
│   ├── model/Channel.kt
│   ├── data/PlaylistParser.kt     # m3u 解析
│   ├── data/PlaylistHolder.kt     # 进程内列表单例
│   ├── data/Prefs.kt              # 设置存储
│   └── ui/ChannelAdapter.kt
└── res/                           # 布局 / 主题 / 频道卡片焦点态
```

## 已知限制

- 网页播不了，App 也播不了：App 不破解、不嗅探，只是网页的壳
- 某些站点的视频全屏按钮在 WebView 里行为各异，已用「JS 主动 requestFullscreen +
  onShowCustomView 接管」双保险覆盖大多数情况
- 境外 IP 打开央视频可能被拦（403 / 一直加载中），这是网络环境问题
