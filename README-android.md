# BUAA Sign Tool Android

原有桌面/Web 工具之外的原生 Android 客户端，源码位于 `android/`，使用 Kotlin、Jetpack Compose 和 Material 3。

## 当前功能

- 使用统一身份认证登录，并通过 iClass 的 `jumpMyCenter` 跳转获取 iClass 身份。
- 支持校园网直连和 WebVPN 两种网络模式。
- 统一认证要求图形验证码时，在登录页直接显示图片和输入框，支持刷新图片后继续登录。普通登录与已记住账号的一键登录均适用。
- 按学期基准日计算周次、浏览一周七天的课表。
- 默认展示当天课程，点一排日期可换天，翻周时保留同一星期几；点「今天」回到今天的课表。右上角日历图标可打开周总览。
- 学期基准日通过 Android Preferences DataStore 保存在本机；会话 Cookie 不写入持久存储。
- 登录成功后可选择使用本机记忆的账号一键登录；密码由 Android Keystore 加密保存，也可随时清除记忆。
- 从开课前 10 分钟到下课前，符合条件的课程显示「签到」，支持单门和本周批量确认。是否接受以及如何记录由 iClass 决定。
- 提交前核对最新排课和签到状态。提交后查询学校的考勤记录，显示「已签到」或「签到结果待确认」。待确认时可用「刷新状态」查询。
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

发布构建使用 `android/signing/release.jks`，密钥别名为 `buaa-sign-tool`。构建环境通过私密环境变量 `BUAA_RELEASE_STORE_PASSWORD` 提供口令。

在配置好签名文件和口令后，于 `android/` 目录执行：

```bash
./gradlew :app:assembleRelease
```

产物为 `android/app/build/outputs/apk/release/app-release.apk`。签名文件应保存在私密位置并做好备份。后续覆盖安装需要使用同一签名密钥。

## 网络说明

校园网直连使用 iClass 8346/8347/8181 端口。校外模式通过 BUAA WebVPN。先前遇到的 8346 连接超时应切换到 WebVPN。登录失败时不要把账号密码贴进日志或提交到仓库。

Android 客户端的签到操作由用户在课程窗口内主动触发；Linux 服务器上的 systemd 自动签到服务仍是独立组件，不会随 APK 一起运行。

## v1.1.0

新增单门和批量迟到签到入口，并统一界面与提交端的时间判断。修复批量操作跨越签到窗口时没有反馈的问题。保留原签名，`versionCode` 增至 `2`，可覆盖安装 v1.0.0 正式版。

开发验证：`./gradlew :app:testDebugUnitTest :app:lintRelease :app:assembleRelease`。模拟器界面测试使用示例课表：`./gradlew :app:connectedDebugAndroidTest`，不会登录或向 iClass 提交签到。

## v1.2.0

登录遇到图形验证码时，在应用内输入图片中的字符，点“验证并登录”。看不清或图片加载失败时，点“刷新验证码”；刷新会清空旧输入，并沿用当前认证会话。可点“返回账号登录”放弃这次验证。验证码不会写入已记住的登录信息。

## v1.3.0

课表默认打开当日课程。日期栏、日期标题和课程使用同一个纵向滚动区，课程名称、地点、老师和签到状态在全宽卡片内显示。窄屏或大字体下可以横滑日期栏。

右上角日历图标打开周总览，点任意一天返回那天的课程。「更多操作」内可刷新课表、设置学期基准日、退出登录或打开本周批量签到。单门课程的签到入口仍在课程卡片中，签到规则保持不变。

## v1.4.0

课前和课中使用同一个学生签到入口，点「签到」后核对课程并确认。课程卡片显示开放时间、可签到、已签到、未签到或待确认。收到明确拒绝时显示 iClass 给出的原因和错误码。界面和结果反馈参考 [UBAA 的课堂签到说明](https://github.com/BUAASubnet/UBAA/blob/v2.0.3/docs/site/features/signin.md)。

## v1.4.1

安卓版通过 UBAA 使用的 iClass 学生业务接口获取会话并提交签到。首次确认签到时，Android 会请求前台位置权限。签到表单中用到当前位置和设备可读取的 Wi-Fi 接入点信息，提交给学校 iClass。提交结果仍通过课程记录确认。

## v1.5.0

课表右上角「更多操作」新增 SPOC 作业、希冀作业和财务收入。SPOC 可按课程和标题搜索作业，查看截止时间、提交状态和说明。希冀可选择课程并查看作业和逐题得分。财务收入可按年份、项目或摘要筛选，查看本人实发、应发和扣税明细。收入记录保留在当前界面的内存中。

查询复用当前安卓登录会话。接口参考 [UBAA 的功能说明](https://github.com/BUAASubnet/UBAA/tree/v2.0.3/docs/site/features)。学校系统拒绝访问或登录过期时，页面会显示错误和刷新入口。

## v1.5.1

安卓课堂签到无需位置权限。签到表单包含用户编号、排课编号和 Android 客户端标识，省略位置和 Wi-Fi 信息。提交后通过 iClass 考勤记录确认结果。

## v1.5.3

线上课程签到使用 UBAA v2.1.0 的六字段表单。经纬度采用上游的固定占位值 0，Wi-Fi 字段 routerInfo 采用 02:00:00:00:00:00。提交后查询学校考勤记录确认结果。

WebVPN 业务 SSO 复用网关会话，回跳支持页面片段。
