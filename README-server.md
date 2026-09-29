# Linux 自动签到服务

服务通过北航 WebVPN 登录 iClass，按北京时间在每日 07:30–22:30 查询当日课表，并仅在开课前 10 分钟内为每节课选择一个稳定的伪随机时刻签到。开课后不会发起签到，已签到课程会自动跳过。

凭据仅保存在服务器 `/etc/buaa-sign-tool.json`，权限为 `0600`，不进入 Git 仓库。

## 初次配置

```bash
python3 /opt/dev/buaa-sign-tool/configure_server.py
systemctl enable --now buaa-sign-tool.service
```

`course_allowlist` 留空表示所有课程；也可输入逗号分隔的课程 ID、排课 ID 或完整课程名称。

## 运行管理

```bash
systemctl status buaa-sign-tool.service --no-pager
journalctl -u buaa-sign-tool.service -n 100 --no-pager
systemctl restart buaa-sign-tool.service
```

单次查询与签到窗口检查：

```bash
/opt/dev/buaa-sign-tool/.venv/bin/python /opt/dev/buaa-sign-tool/auto_sign.py --config /etc/buaa-sign-tool.json --once
```
