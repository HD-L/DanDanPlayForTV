# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

《弹弹play 概念版》— a local/network video player for Android focused on video + danmaku (bullet comments) playback. Kotlin + MVVM + 组件化 (componentized multi-module) architecture. APIs are provided by the [DanDanPlay open platform](https://api.dandanplay.net/swagger/ui/index).

Note: the User module's account-related API calls are disabled for security; those features won't work in a self-built APK.

## Build & Run

Gradle wrapper is `gradle-7.4`, AGP `7.3.1`, Kotlin `1.7.10`, JDK 8 source/target. `compileSdk 33`, `minSdk 21`, `targetSdk 29`.

```bash
./gradlew assembleDebug          # build debug APK (app module)
./gradlew :app:installDebug      # build + install on connected device
./gradlew clean
./gradlew :common_component:assembleDebug   # build a single module
```

Build variants: `debug`, `release`, `beta` (beta `initWith` release). Output APKs are renamed to `dandanplay_v<versionName>_<variant>.apk`, split per ABI (`armeabi-v7a`, `arm64-v8a`, plus a universal APK).

**Signing.** Debug signing reads `gradle/assemble/keystore.properties` (falls back to `debug.properties`) for `KEY_LOCATION`/`KEYSTORE_PASS`/`ALIAS_NAME`/`ALIAS_PASS`; if absent, debug signing is skipped. Release/beta signing uses `gradle/assemble/dandanplay.jks` plus env vars `KEYSTORE_PASS`/`ALIAS_NAME`/`ALIAS_PASS` (CI provides these — see `.github/workflows/package-*.yml`).

```bash
./gradlew dependencyUpdates      # check for dependency updates (report in build/dependencyUpdates)
```

### Tests

Standard Android test wiring (JUnit + AndroidX test/Espresso, added to every module via `setupDefaultDependencies`):

```bash
./gradlew test                              # all unit tests
./gradlew :app:testDebugUnitTest            # unit tests for one module/variant
./gradlew :app:connectedDebugAndroidTest    # instrumented tests (device required)
```

There are no project-authored tests of note; the test infra is mostly inherited scaffolding.

## Build configuration system (`buildSrc`)

All module build logic is centralized in `buildSrc` — do **not** hardcode versions or dependency strings in module `build.gradle.kts` files:

- `buildSrc/src/main/java/Versions.kt` — SDK levels, `versionCode`/`versionName`, `applicationId`.
- `buildSrc/src/main/java/Dependencies.kt` — all dependency coordinates, grouped by vendor (`AndroidX`, `Google`, `Square`, `Github`, `Alibaba`, `Tencent`, ...). Reference as `Dependencies.Square.retrofit`.
- `setup.applicationSetup()` (app module) / `setup.moduleSetup()` (library modules) — apply shared `android {}` config: DataBinding on, JDK 8, Kotlin options, signing, APK output naming. Library modules call `moduleSetup()`; the app calls `applicationSetup()`.
- Every module injects `BuildConfig.BUILD_COMMIT` (current git short hash).

## Module architecture

Componentized: feature modules are Android libraries that the `app` module assembles. Cross-module navigation and communication go through **ARouter**, never direct module-to-module type dependencies.

```
app                → entry point: SplashActivity, MainActivity (IApplication extends BaseApplication)
anime_component    → anime info: home, search, season, detail
local_component    → local media, danmu/subtitle download
storage_component  → SMB / FTP / WebDav / Alist / remote / screencast / torrent media-source UI
player_component   → video player (the playback engine + PlayerActivity)
user_component     → user info, login, app/player settings
common_component   → shared infrastructure (base classes, networking, DB, storage abstraction, configs) — api-exposed to all features
data_component     → pure data: Room entities, beans, enums, API data classes, TypeConverters (no Android UI)
repository/*       → prebuilt local AARs wrapped as gradle subprojects (see below)
```

`common_component` `api`-exposes `data_component` and the danmaku/immersion_bar/thunder/7zip repositories, so feature modules get them transitively.

### `repository/` AAR wrappers

Each is a single `build.gradle.kts` exposing a prebuilt `.aar` (no source):
- `danmaku` — DanmakuFlameMaster (`master.flame.danmaku`) bullet-comment rendering engine
- `immersion_bar` — ImmersionBar status/nav bar tinting
- `panel_switch` — keyboard ↔ bottom-panel switch helper
- `seven_zip` — sevenzipjbinding archive extraction
- `thunder` — Xunlei (迅雷) download SDK (magnet/torrent backend)
- `video_cache` — HTTP video proxy-cache library

## Core patterns (in `common_component`)

When adding a screen or feature, follow these — they are the backbone everything else mirrors.

### MVVM base classes (`common_component/.../base/`)

- Screens extend `BaseActivity<VM : BaseViewModel, V : ViewDataBinding>` or `BaseFragment<VM, V>`.
- Each must implement `initViewModel()` returning `ViewModelInit(BR.viewModel, MyViewModel::class.java)`, `getLayoutId()`, and `initView()`.
- DataBinding is enabled project-wide; the ViewModel is auto-bound to the `viewModel` binding variable. `BaseViewModel` exposes a `loadingObserver` LiveData with `showLoading()/hideLoading()/hideLoadingSuccess()/hideLoadingFailed()`.

### ARouter navigation

All route paths are centralized in `common_component/.../config/RouteTable.kt` (nested objects: `RouteTable.Anime`, `.Local`, `.User`, `.Player`, `.Stream`). Annotate target with `@Route(path = RouteTable.X.Y)`; navigate via `ARouter.getInstance().build(RouteTable.X.Y).navigation()`. Each module's `build.gradle.kts` passes `AROUTER_MODULE_NAME` to kapt.

### Networking (`common_component/.../network/`)

- `Retrofit.kt` — singleton holding service instances; two OkHttp clients: `danDanClient` (DanDanPlay API, with signature/auth/agent/backup-domain interceptors) and `commonClient` (everything else, with a `DynamicBaseUrlInterceptor`). Moshi for JSON.
- Service interfaces in `network/service/`, domain repositories in `network/repository/` (extend `BaseRepository`), interceptors in `network/helper/`, base URLs in `network/config/Api.kt`.
- `network/request/Request.kt` wraps calls in Kotlin stdlib `Result<T>` on `Dispatchers.IO`, mapping errors to a typed `NetworkException`. Call sites use `.onSuccess`/`.onFailure`/`getOrNull()`.

### Database (Room)

- `common_component/.../database/DatabaseInfo.kt` — `@Database` (version 13, db file `rood_db`); access via `DatabaseManager.instance`, which holds all migrations.
- DAOs in `common_component/.../database/dao/`; **entities live in `data_component`** (`com.xyoye.data_component.entity`). TypeConverters are in `data_component/.../helper/`.

### Key-value config (MMKV)

Typed config wrappers are **code-generated** from annotated objects via local jars (`common_component/libs/mmkv-annotation.jar`, `mmkv-compiler.jar`). Define a Kotlin `object` annotated `@MMKVKotlinClass(className = "XxxConfig")` with `@MMKVFiled const val` fields in `common_component/.../config/` (e.g. `AppConfigTable.kt` → generated `AppConfig`). Call generated accessors like `AppConfig.getDarkMode()`. See [MMKVStorage](https://github.com/xyoye/MMKVStorage).

### Storage abstraction (`common_component/.../storage/`)

Unified contract over all media-source types (local SAF, internal video, SMB, FTP, WebDav, Alist, remote, torrent, stream link, screencast):
- `Storage` interface + `AbstractStorage` base; `StorageFile` interface + `AbstractStorageFile` base (per-file).
- `StorageFactory.createStorage(library: MediaLibraryEntity)` dispatches on `MediaType` to a concrete impl in `storage/impl/`; matching file impls in `storage/file/impl/`.
- `MediaLibraryEntity` (a Room entity) is the persisted record of each configured source; its `mediaType` discriminates. Non-HTTP sources (SMB/FTP) are served through local proxy HTTP servers in `storage/file/helper/`.

### Player engine (`player_component`)

Three-kernel architecture switched at runtime by `PlayerType` enum (IJK / ExoPlayer / VLC):
- `player/kernel/inter/AbstractVideoPlayer.kt` — kernel base; `PlayerFactory` returns the kernel for a `PlayerType`; impls in `player/kernel/impl/{ijk,exo,vlc}/`.
- `player/wrapper/ControlWrapper.kt` — facade combining player + video/danmu/subtitle/setting controllers; `DanDanVideoPlayer.kt` is the embeddable view; `PlayerActivity` is the playback screen.
- Render surface abstraction: `player/surface/InterSurfaceView.kt` (Surface/Texture/VLC impls). Subtitles via `subtitle/ExternalSubtitleManager.kt` + `FormatFactory`.

## Custom tooling

- **MVVM template plugin** — `plugin/MVVMTemplate-xx.jar` (Android Studio plugin) scaffolds Activity/Fragment + ViewModel + layout matching project conventions. See [MVVMTemplate](https://github.com/xyoye/MVVMTemplate).

## Git / commit policy

Default branch is `master`; pushing to `master` triggers a beta APK build (GitHub Actions). Do not `git commit`/`git push` unless the user explicitly asks.
