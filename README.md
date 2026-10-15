# travel-memory_android

[Travel Memory](https://github.com/shihezichen/travel-memory) 的 **Android 壳应用**。

和鸿蒙那版（[travel-memory_harmony](https://github.com/shihezichen/travel-memory_harmony)）同一个思路：
**不打包网页代码**，运行时用系统 WebView 加载自托管的 `https://top001.xin`，
把浏览器做不到的原生能力补上。

产物是**自签名的 APK**，Android 允许直接安装（对方打开「允许安装未知应用」即可），
不需要应用商店、也不需要开发者账号。

> **直接下载**：见本仓库的 [Releases](../../releases) 页（`travel-memory-<版本>.apk`）。
> 自己打：见下面「构建」一节。

## 为什么是 APK，而不是把鸿蒙那个包换个后缀

APK 是 Android 的包格式，**纯血鸿蒙（HarmonyOS NEXT / 5 / 6 / 7）装不了 APK**，
只能装 `.hap` / `.app`。反过来 Android 也装不了 hap。所以两种机型必须是两个工程、
两套壳，共用同一个网页后端。

| 机型 | 能装什么 |
| --- | --- |
| Android 手机 / 平板 | 本仓库打出来的 **APK** |
| HarmonyOS 4 及更早（AOSP 内核） | 也能装 APK |
| HarmonyOS NEXT / 5 / 6 / 7 | 只能装 `.hap`/`.app`（见 travel-memory_harmony） |
| iPhone / iPad | 不能装 APK，用 Safari「添加到主屏幕」（PWA） |

## 功能

- **WebView 全屏加载** `https://top001.xin`
- **系统返回键**：先回退网页历史，退到底才退出 App
- **上传**：网页 `<input type=file>` 唤起「相册 / 文件 / **拍照**」三选一，支持多选
- **下载**：交给系统 `DownloadManager`，**直接落到公共「下载」目录**，文件管理器 /
  相册里就能看到；并手动带上 WebView 的 Cookie，避免「浏览器里能下、App 里 401」
- **外链**：站外 `http(s)` 与 `mailto:` / `tel:` / `sms:` 交给系统处理
- **定位 / 相机 / 麦克风**：网页真正用到时才弹权限（`onGeolocationPermissionsShowPrompt`
  / `onPermissionRequest`）
- **加载失败 / 证书错误**：可重试的错误页；证书有问题一律不放过（`handler.cancel()`）
- 自动播放已开启（路线回放的自动播放需要）
- 顶部细进度条

**刻意不做沉浸式（edge-to-edge）**：Android 的 `env(safe-area-inset-top)` 只反映屏幕挖孔、
不反映状态栏高度，真做成边到边反而会把网页自己的顶栏压住。这里是白底状态栏 + 深色图标，
和网页的浅色顶栏自然接上。

## 与 Web 版的关系

App 只加载线上站点，**服务器更新后重开即生效，不需要重新发包**。Web 端针对
「装到桌面」做了适配（`viewport-fit=cover` + `env(safe-area-inset-*)`），
两个壳和浏览器都受益。

## 构建

需要 JDK 17+ 和 Android SDK（`platforms;android-35`、`build-tools;35.0.0`）。

```bash
# 1. 改 local.properties 里的 SDK 路径
#    sdk.dir=D:\\tools\\android-sdk

# 2. 生成签名密钥（首次，仓库里不放密钥库）
keytool -genkeypair -v -keystore keystore/travel-memory.jks -storetype JKS \
        -alias travelmemory -keyalg RSA -keysize 2048 -validity 10950 \
        -storepass travelmemory -keypass travelmemory \
        -dname "CN=Travel Memory, O=shihezichen, C=CN"

# 3. 打包
gradle assembleRelease        # 或 ./gradlew assembleRelease（如果有 wrapper）
```

产物：`app/build/outputs/apk/release/app-release.apk`

> 换成自己的密钥时，记得同步改 `app/build.gradle.kts` 里 `signingConfigs` 的四个值。
> **密钥库不要提交**（`keystore/` 已在 `.gitignore` 里）。

## 安装到 Android 手机

1. 把 APK 传到手机（微信/网盘/数据线都行）；
2. 点开安装，系统提示「未知来源」时选择允许（一般只在第一次问）；
3. 装好后桌面出现「旅行记忆」图标。

也可以命令行：

```bash
adb install -r app-release.apk
```

## 结构

```
settings.gradle.kts / build.gradle.kts     # Gradle 配置（AGP 8.6.1）
app/build.gradle.kts                      # compileSdk 35 / minSdk 24 / 签名配置
app/src/main/AndroidManifest.xml          # 权限、FileProvider
app/src/main/java/xin/top001/travelmemory/MainActivity.java   # 壳的全部逻辑（服务器地址在此修改）
app/src/main/res/values/{strings,colors,themes}.xml
app/src/main/res/xml/file_paths.xml       # FileProvider 只暴露缓存里的 capture/
keystore/                                 # 自签名密钥（不提交）
```

修改服务器地址：`MainActivity.java` 顶部的 `SERVER_URL` 与 `SERVER_HOST`。

## 已知差异（相对鸿蒙版）

- 拍照走系统相机，拍完的照片是「临时文件 → 上传」，不会单独存进相册（系统相机会另存一份）
- 没有做「下载进度」UI，进度交给系统通知栏
- 没做沉浸式（原因见上）

## License

[MIT](./LICENSE)（与上游项目一致）
