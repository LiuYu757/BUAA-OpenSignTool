# BUAA Sign Tool Android

原有桌面/Web 工具之外的原生 Android 客户端，源码位于 `android/`，使用 Kotlin、Jetpack Compose 和 Material 3。

## 当前功能

- 使用统一身份认证登录，并通过 iClass 的 `jumpMyCenter` 跳转获取 iClass 身份。
- 支持校园网直连和 WebVPN 两种网络模式。
- 按学期基准日计算周次、浏览一周七天的课表。
- 支持周视图和日列表切换，点击周视图中的日期可查看当天课程详情。
- 学期基准日通过 Android Preferences DataStore 保存在本机；会话 Cookie 不写入持久存储。
- 登录成功后可选择使用本机记忆的账号一键登录；密码由 Android Keystore 加密保存，也可随时清除记忆。
- 课程只会在开课前 10 分钟（含窗口起点）至开课前开放签到；应用端和发请求前都会检查，开课后不补签。
- 应用使用日历签到图标，并提供自适应圆形图标资源。
- 跟随系统明暗主题，使用 edge-to-edge 布局；宽屏时课表内容保持易读宽度。

## 在本机打开

用 Android Studio 打开 `android/` 目录并等待 Gradle 同步，然后选择 `app` 配置运行到 Android 设备或模拟器。项目使用 Android SDK 36、Android Gradle Plugin 8.13.2 和 Gradle 8.13；仓库已包含 Gradle Wrapper。Android Studio 2026 会为 Gradle 自动安装并选择兼容的 JetBrains Runtime 21。

若要构建调试 APK，可在 Android Studio 选择 **Build > Build APK(s)**，或在 `android/` 下运行：

```bash
./gradlew :app:assembleDebug
```

APK 生成在 `android/app/build/outputs/apk/debug/app-debug.apk`。当前源码已在本机完成一次调试 APK 构建验证；首次构建需要下载项目依赖。

## 正式版签名

正式版使用 `android/signing/release.jks` 和别名 `buaa-sign-tool`，构建时通过环境变量 `BUAA_RELEASE_STORE_PASSWORD` 提供密钥口令。本机首次发布的口令保存在 macOS 钥匙串中，服务名为 `BUAA-SignTool-Android-Release`。在这台 Mac 上可使用：

```bash
export BUAA_RELEASE_STORE_PASSWORD="$(security find-generic-password -s BUAA-SignTool-Android-Release -a buaa-sign-tool -w)"
cd android
./gradlew :app:assembleRelease
unset BUAA_RELEASE_STORE_PASSWORD
```

换电脑构建时需一并迁移签名密钥和口令。

产物在 `android/app/build/outputs/apk/release/app-release.apk`。签名密钥和口令不进入 Git；请分别做好安全备份。丢失签名密钥后，后续版本无法作为同一应用覆盖安装。

如果手机上已经安装本项目的调试 APK，因签名不同，需先卸载调试版再安装正式版；卸载会清除应用在本机保存的登录信息和学期基准日。

## 网络说明

校园网直连使用 iClass 8346/8347/8081 端口；校外模式通过 BUAA WebVPN。先前遇到的 8346 连接超时应切换到 WebVPN。登录失败时不要把账号密码贴进日志或提交到仓库。

Android 客户端的签到操作由用户在课程窗口内主动触发；Linux 服务器上的 systemd 自动签到服务仍是独立组件，不会随 APK 一起运行。
