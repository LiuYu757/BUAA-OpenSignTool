> [!important]
> 请严格遵循学校相关规章制度，合理使用本工具。严禁用于恶意并发请求、破坏系统稳定性等违规行为。若引起服务器异常，作者将立即终止项目维护。

# BUAA Open Sign Tool（北航课程签到）

北航 iClass 课程签到的开源 Android 客户端，支持课表浏览、WebVPN 登录、课堂签到，以及 SPOC、希冀作业和财务收入查询。另附桌面端与 Linux 自动签到服务源码。

## 功能

- 统一身份认证登录，支持校园网和 WebVPN，图形验证码可显示、填写和刷新。
- 默认展示当天课程，支持切换日期、翻周和周总览。
- 课程卡片显示签到时段和状态，提交后查询学校考勤记录确认结果。
- 课表「更多操作」提供 SPOC 作业、希冀作业和财务收入查询。
- 登录密码经 Android Keystore 加密保存，可随时清除。
- Linux 服务在课前 5 分钟内自动提交线上课程签到，持久化待确认记录并防止重复进程。

## 安装

从 [Releases](https://github.com/LiuYu757/BUAA-OpenSignTool/releases) 下载 APK，适用于 Android 8.0 及以上设备。

## 使用

1. 选择校园网或 WebVPN 网络模式，使用统一身份认证登录。
2. 首次登录后在「更多操作」中设置学期基准日。
3. 在日期栏选择课程日期，进入课程签到时段后点「签到」并确认课程。
4. 提交结果不明确时点「刷新状态」，查看学校考勤记录。

## 自行构建

用 Android Studio 打开 `android/`，或在该目录执行：

```bash
./gradlew :app:assembleDebug
```

调试 APK 输出在 `android/app/build/outputs/apk/debug/`。签名和发布构建见 [README-android.md](README-android.md)。

## 其他组件

- `app.py` 与 `web/` 提供 Python + pywebview 桌面端，支持手动迟到签到。
- `auto_sign.py` 与 `deploy/` 提供 Linux 自动签到服务，部署见 [README-server.md](README-server.md)。
- `ClassSignToolCLI.py` 提供命令行工具。

## 免责声明

1. **非官方性质**：本项目为个人开发的开源学习交流项目，与北京航空航天大学官方无任何关联。
2. **安全与隐私**：本项目为**纯本地运行**架构，程序绝不包含任何恶意收集、上传隐私数据的代码。
3. **使用风险**：用户须自行承担使用本软件所带来的一切风险。
4. **合理使用**：请遵循学校相关规章制度，合理使用本工具。
