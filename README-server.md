# Linux 自动签到服务

服务通过北航 WebVPN 登录 iClass，每天北京时间 07:30–22:30 每隔 60 秒查询当日课表，在开课前 5 分钟内为每节课选择稳定的计划时刻，自动提交线上课程签到。请求使用 UBAA v2.1.0 的 HTTPS 8181 学生接口 /app/course/stu_auto_sign.action、业务登录返回的 sessionId 和六字段 POST 表单。经纬度使用上游的固定占位值 0，routerInfo 使用 02:00:00:00:00:00。

lead_minutes 默认为 5，允许设为 1–5。提交前再次核对时间，课程开始时截止。每节课收到明确拒绝后最多尝试 3 次。提交后回查学校考勤记录；响应不确定时保存待确认状态，后续轮询继续查询记录。

运行状态保存在 /var/lib/buaa-sign-tool/state.json，通过 deploy/30-state.conf 配置 systemd 状态目录。待确认、已完成及尝试次数在重启后恢复，状态文件锁用于避免两个进程同时提交。

凭据仅保存在服务器 `/etc/buaa-sign-tool.json`，权限为 `0600`，不进入 Git 仓库。

WebVPN 图形验证码由服务器本地处理。示例部署目录为 `/opt/buaa-sign-tool`，Python 环境为 `.venv-webvpn`，依赖版本见 `requirements-webvpn.lock.txt`。

## 初次配置

```bash
cd /opt/buaa-sign-tool
python3 -m venv .venv-webvpn
.venv-webvpn/bin/pip install -r requirements-webvpn.lock.txt
.venv-webvpn/bin/python configure_server.py
install -Dm644 deploy/buaa-sign-tool.service /etc/systemd/system/buaa-sign-tool.service
install -Dm644 deploy/20-webvpn.conf /etc/systemd/system/buaa-sign-tool.service.d/20-webvpn.conf
install -Dm644 deploy/30-state.conf /etc/systemd/system/buaa-sign-tool.service.d/30-state.conf
systemctl daemon-reload
systemctl enable --now buaa-sign-tool.service
```

`course_allowlist` 留空表示所有课程；也可输入逗号分隔的课程 ID、排课 ID 或完整课程名称。

## 运行管理

```bash
systemctl status buaa-sign-tool.service --no-pager
journalctl -u buaa-sign-tool.service -n 100 --no-pager
systemctl restart buaa-sign-tool.service
```

只读验证 WebVPN 登录、今明课表和时间戳（不会签到）：

```bash
/opt/buaa-sign-tool/.venv-webvpn/bin/python /opt/buaa-sign-tool/verify_webvpn.py
```

需要单次执行时，先停止正在运行的服务。以下命令会提交符合窗口条件的签到。

```bash
/opt/buaa-sign-tool/.venv-webvpn/bin/python /opt/buaa-sign-tool/auto_sign.py --config /etc/buaa-sign-tool.json --once
```
