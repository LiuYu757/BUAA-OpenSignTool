> [!important]
> 请严格遵循学校相关规章制度，合理使用本工具。严禁用于恶意并发请求、破坏系统稳定性等违规行为。若引起服务器异常，作者将立即终止项目维护。

# BUAA Open Sign Tool（北航课程签到）

北航 iClass（智慧教室）课程签到的开源 Android 客户端，支持查看每周课表，并在开课前 10 分钟内完成签到。另附桌面端与 Linux 自动签到服务源码。

## 功能

- **统一身份认证登录**：经 iClass `jumpMyCenter` 换取身份，账号密码不经过第三方
- **双网络模式**：校园网直连 / 校外 WebVPN
- **周课表浏览**：按学期基准日计算周次，支持周视图与日列表切换
- **限时签到**：仅在开课前 10 分钟窗口内开放，提供「教师签到」「正常签到」两种通道
- **本机记住登录**：密码经 Android Keystore 加密保存，可随时清除
- 跟随系统明暗主题，Material 3 界面

## 安装

从 [Releases](../../releases) 下载最新 APK 安装到 Android 设备（Android 8.0+）。

## 使用

1. 选择网络模式（校内选「校园网」，校外选「WebVPN」）
2. 输入统一身份认证账号密码登录
3. 首次使用请在登录页设置**学期基准日**（本学期第一周的周一），用于计算周次
4. 课程进入开课前 10 分钟窗口后，在课程卡片或底部批量按钮完成签到

## 自行构建

用 Android Studio 打开 `android/` 目录，或在 `android/` 下运行：

```bash
./gradlew :app:assembleDebug
```

APK 输出在 `android/app/build/outputs/apk/debug/`。正式版签名等细节见 [README-android.md](README-android.md)。

## 其他组件

- `app.py` + `web/`：Python + pywebview 桌面端（`pip install -r requirements.txt && python app.py`）
- `auto_sign.py` + `deploy/`：Linux systemd 自动签到服务，部署见 [README-server.md](README-server.md)
- `ClassSignToolCLI.py`：命令行签到工具

## 免责声明

1. **非官方性质**：本项目为个人开发的开源学习交流项目，与北京航空航天大学官方无任何关联。
2. **安全与隐私**：本项目为**纯本地运行**架构，程序绝不包含任何恶意收集、上传隐私数据的代码。
3. **使用风险**：用户须自行承担使用本软件所带来的一切风险。
4. **合理使用**：请遵循学校相关规章制度，合理使用本工具。
