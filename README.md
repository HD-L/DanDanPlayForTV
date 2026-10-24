# DanDanPlayForTV · 弹弹 TV

面向 Android TV 的弹幕视频播放器，是 [DanDanPlayForAndroid](https://github.com/xyoye/DanDanPlayForAndroid)（弹弹play 概念版）的 TV 版。设计从遥控器的观看流程出发，用 Compose for TV 独立重写。它能播放本地和局域网视频，自动匹配弹弹play 弹幕，浏览每周番剧；需要打字的地方都改用手机扫码填写。

> 这是非官方项目，与弹弹play 官方无关。应用只是播放器，不提供、不托管任何视频资源。当前版本 0.5.4 Beta，还在测试阶段。

<p>
<img src="docs/screenshots/0.3.0-%E9%A6%96%E9%A1%B5%E6%B5%B7%E6%8A%A5%E5%A2%99.png" width="49%" alt="首页海报墙">
<img src="docs/screenshots/0.5.0-%E7%89%87%E5%BA%93%E6%B5%B7%E6%8A%A5%E5%A2%99.png" width="49%" alt="片库海报墙">
<img src="docs/screenshots/0.3.0-%E7%95%AA%E5%89%A7%E8%AF%A6%E6%83%85.png" width="49%" alt="番剧详情">
<img src="docs/screenshots/0.4.0-%E6%92%AD%E6%94%BE%E5%99%A8%E6%8E%A7%E5%88%B6%E6%A0%8F.png" width="49%" alt="播放器控制栏">
</p>

## 功能

- **首页**：继续观看、我的片库、我的追番、每周番剧、本季新番，都以海报墙展示。左侧导航栏可以展开和收起，焦点会放大并带转场动画。
- **媒体源**：支持本地目录、HTTP/HTTPS、WebDAV、SMB 和 FTP。电视上显示二维码，用手机填写地址和账号，电视不用弹出软键盘。
- **片库海报墙**：扫描媒体源后，按作品归并季、集号、特别篇、特典和电影，并在后台向弹弹play 识别海报和官方剧集名。作品页可以从任意一集开始连播、标记已看，也可以手动指定识别结果。
- **播放器**：Media3 与 VLC 双内核，播放中也能切换。遥控操作参照 YouTube TV：快进快退、按住加速、拖动进度时预览时间、选集栏、下一集倒计时。另有音轨、字幕、画面比例和倍速设置。
- **弹幕**：按文件特征自动匹配弹弹play 弹幕，也可以手动搜索或导入 XML。支持调整样式、按关键字和正则屏蔽、简繁转换，以及[公开云屏蔽规则](https://github.com/Cathgao/dandanplay-filter)。
- **字幕**：外挂字幕可以选文件或填地址，支持选择编码、导入字体、调整时间偏移，也可以在 OpenSubtitles 上搜索和匹配。
- **番剧**：每周和季度番剧、搜索、详情、按标签浏览、剧集已看标记、本地追番。
- **网络播放**：局域网播放大码率视频时，缓冲策略会根据网速调整，缓冲画面会显示网速和视频码率。
- **磁链边下边播**：填写第三方资源搜索节点后，番剧的每一集都可以找资源并边下边播。这项功能只支持 arm 设备。
- **手机遥控**：用手机网页控制播放、调整弹幕设置，或把视频链接投送到电视。
- **备份与恢复**：可以导出来源、设置、收藏、索引、进度和剧集绑定；设置密码后，备份还可以加密带上账号凭证。

## 安装

要求 Android 8.0（API 26）或以上，界面按横屏电视设计，不需要触摸屏。包名是 `tv.dandan.clean`，可以和原版应用同时安装。

在 [Releases](https://github.com/HD-L/DanDanPlayForTV/releases) 页面下载安装包，按电视的处理器选一个：

| 安装包 | 适合的设备 |
| --- | --- |
| `…-arm64-v8a.apk` | 大多数近几年的电视和盒子（64 位） |
| `…-armeabi-v7a.apk` | 较老的 32 位电视和盒子；装不上 v8 包时用这个 |

把 APK 复制到电视上，用系统文件管理器安装；也可以在电脑上用 `adb install -r 文件名.apk` 安装。Release 里的安装包用本项目的发布密钥签名。如果电视上装过自己用源码构建的调试版，签名不同，需要先卸载调试版才能安装（卸载会清空数据，可以先在“设置 → 数据管理与备份”导出备份）。

也可以自己构建调试版：

```bash
# 只打 arm64 包（约 81 MiB）
./gradlew :app:assembleDebug -Pabi=arm64-v8a
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n tv.dandan.clean/tv.dandan.app.MainActivity
```

去掉 `-Pabi` 参数会同时打包 arm64、armv7、x86 和 x86-64 四种架构，体积约 220 MiB。

## 首次使用

1. **添加媒体源**：进入“媒体库 → 添加媒体源”，用手机扫电视上的二维码，选择类型，再填写地址和账号。如果是本地目录，提交后还要在电视上通过系统的文件选择器授权。
2. **填写弹弹play 凭证**：番剧、弹幕匹配和片库识别都需要你在[弹弹play 开放平台](https://doc.dandanplay.com/open/)自己申请的 AppId 和 AppSecret。在“设置 → 开放平台凭证”里扫码填写。应用不内置任何凭证。
3. **播放**：用方向键浏览，按确定键开始播放。播放时：
   - 确定键：暂停或继续
   - 左右键：快退或快进 10 秒
   - 按住右键：临时 2 倍速
   - 上下键：呼出控制栏

   退出时会保存进度，之后从首页“继续观看”接着播。

| 在线服务 | 在哪里配置 | 你需要提供 |
| --- | --- | --- |
| 弹弹play 番剧与弹幕 | 设置 → 开放平台凭证 | 自己申请的 AppId 和 AppSecret；设备时间要准确；接口有调用额度 |
| OpenSubtitles 字幕 | 设置 → 在线字幕服务 | 自己的 API Key、账号密码和应用名称；与弹弹play 凭证无关 |
| 磁链资源搜索 | 设置 → 磁链与资源搜索 | 第三方资源搜索节点的地址（支持 /list、/type、/subgroup 接口） |

文字都在手机网页上填写。每次操作都会生成一个专属二维码，十分钟内有效，提交一次就失效。手机和电视要连在同一个可信的局域网里。这项服务走 HTTP，传输不加密。

## 构建与测试

需要 JDK 17 和 Android SDK 36。SDK 路径可以写进 `local.properties`（这个文件不进版本库），也可以设置环境变量 `ANDROID_HOME`。

```bash
./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
node --test tools/tests/remote-page.test.mjs   # 可选：测试手机网页的脚本，需要 Node.js
```

构建发布版：`./gradlew :app:assembleRelease -Pabi=arm64-v8a`（32 位换成 `armeabi-v7a`）。要签名的话，在仓库根目录放一个 `keystore.properties`，写明 `storeFile`、`storePassword`、`keyAlias`、`keyPassword` 四项，构建时会自动签名。这个文件和密钥文件（`*.jks`、`signing/`）都已加入 `.gitignore`，不会提交。没有这个文件时，生成的是未签名的发布包，可以之后用 `apksigner` 签名。

主要依赖的版本：Gradle 8.13、AGP 8.13.2、Kotlin 2.3.10、Compose BOM 2026.06.01、tv-material 1.1.0、Media3 1.11.1、LibVLC 3.7.7、Coil 3.5.0、Opencc4j 1.14.0。

代码结构：

```text
core/              纯 Kotlin 规则：媒体模型、文件名解析与作品归并、续播与按键、弹幕筛选、字幕时间线、缓冲策略
app/.../data/      来源协议、索引与备份、凭证存储、在线服务、片库识别
app/.../ui/        Compose for TV 页面、焦点、手机表单请求
app/.../player/    播放会话、Media3 与 VLC 内核、弹幕与字幕
app/.../remote/    局域网 Web 服务：手机表单、配对、遥控
docs/              产品规格、功能状态和验收记录
tools/             手机网页的脚本测试；bridge/ 下是在电脑上通过 adb 操作模拟器或电视的调试脚本
```

协作约定见 [AGENTS.md](AGENTS.md)。

## 文档

- [功能状态](docs/功能状态.md)：每项功能的实现情况、测试和实机验收记录，以及已知限制。功能是否可用以这份文档为准。
- [产品规格](docs/产品规格.md)、[需求核对](docs/需求核对.md)、[原版功能清单与归属讨论](docs/原版功能清单与归属讨论.md)
- [界面层重写](docs/界面层重写.md)、[架构与审查](docs/架构与审查.md)、[媒体库与备份](docs/媒体库与备份.md)、[实施计划](docs/实施计划.md)

## 已知限制

- 目前是 Beta 版。实机只在华为 KANT-370（Android 10）上验收过，其余结果来自 Android TV 模拟器；armeabi-v7a 安装包只校验了签名和架构，还没在 32 位设备上实测。
- 网络来源的实际网速低于视频码率时，播放仍会周期性缓冲。应用只能把停顿合并成次数少、时间长的几次。
- 磁链下载用的是迅雷的闭源库，只支持 arm64-v8a 和 armeabi-v7a，x86 设备上会自动隐藏入口。
- 弹弹play 接口按应用分配调用额度，额度用完后片库识别会暂停，等额度恢复后继续。
- 部分在线服务和设备还没有实际验收，比如还没有用正式的 OpenSubtitles 账户下载过字幕。详情见[功能状态](docs/功能状态.md)。

## 与原项目的关系

本项目参照 [xyoye/DanDanPlayForAndroid](https://github.com/xyoye/DanDanPlayForAndroid) 的功能整理需求，代码全部重写，没有沿用原工程的实现。唯一的例外是磁链下载库 `app/libs/thunder.aar`，直接使用原项目附带的文件。

仓库的提交历史里保留了原项目的代码，以及 2026 年 6 月直接在原版代码上改造的第一版 TV 版，可以通过标签 `legacy` 查看。

## 许可证与声明

本项目沿用原项目的 [Apache License 2.0](LICENSE)，所用第三方库各自遵循其许可证。

本应用只是播放器，不提供、不托管、不推荐任何视频资源。番剧资料和弹幕来自弹弹play 开放平台，使用时需遵守其服务条款。通过磁链或第三方节点获取内容时，请自行确认来源合法。
