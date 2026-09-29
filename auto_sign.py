#!/usr/bin/env python3
"""Headless BUAA iClass auto check-in service for Linux servers."""

import argparse
import hashlib
import json
import logging
import os
import re
import signal
import time
from datetime import datetime, timedelta
from pathlib import Path
from urllib.parse import parse_qs, unquote_plus, urlparse
from zoneinfo import ZoneInfo

import requests
from bs4 import BeautifulSoup


BEIJING = ZoneInfo("Asia/Shanghai")
WEBVPN_ENTRY = "https://d.buaa.edu.cn/"
VPN_SERVICE_ID = (
    "77726476706e69737468656265737421"
    "f9f44d9d342326526b0988e29d51367ba018"
)
LOGIN_BASE = f"https://d.buaa.edu.cn/https-8346/{VPN_SERVICE_ID}"
API_BASE = f"https://d.buaa.edu.cn/https-8347/{VPN_SERVICE_ID}"
SIGN_BASE = f"https://d.buaa.edu.cn/http-8081/{VPN_SERVICE_ID}"
JUMP_URL = f"{LOGIN_BASE}/?type=jumpMyCenter"
LOGIN_URL = f"{LOGIN_BASE}/eschool/app/user/login_buaa.do"
SCHEDULE_URL = f"{API_BASE}/app/course/get_stu_course_sched.action"
TIMESTAMP_URL = f"{SIGN_BASE}/app/common/get_timestamp.action"
CHECKIN_URL = f"{SIGN_BASE}/eschool/app/course/stu_scan_sign.action"
USER_AGENT = (
    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36"
)


def extract_login_name(url):
    if not url:
        return ""
    parsed = urlparse(url)
    values = parse_qs(parsed.query).get("loginName")
    if values and values[0]:
        return values[0]
    decoded = unquote_plus(url)
    for separator in ("?", "#", "&"):
        decoded = decoded.replace(separator, "\n")
    for part in decoded.splitlines():
        if part.startswith("loginName="):
            return unquote_plus(part[len("loginName="):])
    return ""


def parse_class_time(value):
    if not value:
        return None
    for fmt in ("%Y-%m-%d %H:%M:%S", "%Y-%m-%d %H:%M"):
        try:
            return datetime.strptime(value, fmt).replace(tzinfo=BEIJING)
        except ValueError:
            continue
    return None


def parse_clock(value):
    try:
        return datetime.strptime(str(value), "%H:%M").time()
    except ValueError as exc:
        raise ValueError(f"invalid clock value: {value!r}; expected HH:MM") from exc


def stable_run_at(schedule_id, class_start, lead_minutes):
    """Pick a stable time after the 10-minute window opens, before class starts."""
    window_seconds = max(0, int(lead_minutes) * 60)
    if window_seconds == 0:
        return class_start
    # Keep one polling interval before class as a safety margin. This prevents a
    # run scheduled in the last few seconds from slipping past the hard cutoff.
    latest_offset = min(60, window_seconds)
    available_seconds = window_seconds - latest_offset
    digest = hashlib.sha256(str(schedule_id).encode("utf-8")).digest()
    offset = latest_offset + (
        int.from_bytes(digest[:4], "big") % (available_seconds + 1)
    )
    return class_start - timedelta(seconds=offset)


def matches_allowlist(course, allowlist):
    if not allowlist:
        return True
    candidates = {
        str(course.get("id", "")).strip(),
        str(course.get("courseId", "")).strip(),
        str(course.get("courseName", "")).strip(),
    }
    return bool(candidates & allowlist)


def api_message(data, default=""):
    if not isinstance(data, dict):
        return default
    for key in ("ERRMSG", "ERRORMSG", "MSG", "message", "msg"):
        value = data.get(key)
        if value is not None and str(value):
            return str(value)
    return default


class IClassClient:
    def __init__(self, username, password, timeout=20):
        self.username = username
        self.password = password
        self.timeout = timeout
        self.session = None
        self.class_id = ""
        self.login_name = ""
        self.reset_session()

    def reset_session(self):
        self.session = requests.Session()
        self.session.trust_env = False
        self.session.headers.update(
            {
                "User-Agent": USER_AGENT,
                "Accept": "application/json, text/html;q=0.9, */*;q=0.8",
                "Accept-Language": "zh-CN,zh;q=0.9",
            }
        )
        self.class_id = ""
        self.login_name = ""

    def _fetch_execution(self):
        response = self.session.get(
            WEBVPN_ENTRY,
            timeout=self.timeout,
            allow_redirects=True,
        )
        response.raise_for_status()
        soup = BeautifulSoup(response.text, "html.parser")
        node = soup.find("input", {"name": "execution"})
        if node and node.get("value"):
            return response.url, node["value"]
        match = re.search(
            r'name=["\']execution["\'][^>]*value=["\']([^"\']+)',
            response.text,
            re.IGNORECASE,
        )
        if match:
            return response.url, match.group(1)
        raise RuntimeError("WebVPN login page did not contain execution token")

    def _submit_credentials(self):
        login_url, execution = self._fetch_execution()
        response = self.session.post(
            login_url,
            data={
                "username": self.username,
                "password": self.password,
                "submit": "登录",
                "type": "username_password",
                "execution": execution,
                "_eventId": "submit",
            },
            headers={"Referer": login_url},
            allow_redirects=True,
            timeout=self.timeout,
        )
        response.raise_for_status()

        if "continueForm" in response.text:
            soup = BeautifulSoup(response.text, "html.parser")
            node = soup.find("input", {"name": "execution"})
            if not node or not node.get("value"):
                raise RuntimeError("SSO risk page did not contain execution token")
            response = self.session.post(
                response.url,
                data={
                    "execution": node["value"],
                    "_eventId": "ignoreAndContinue",
                },
                headers={"Referer": response.url},
                allow_redirects=True,
                timeout=self.timeout,
            )
            response.raise_for_status()

        errors = (
            "用户名或密码错误",
            "账号或密码错误",
            "您提供的用户名或者密码有误",
            "invalid credentials",
        )
        body = response.text.lower()
        if response.status_code == 401 or any(message in body for message in errors):
            raise RuntimeError("SSO username or password is invalid")

    def login(self):
        self.reset_session()
        self._submit_credentials()
        response = self.session.get(
            JUMP_URL,
            timeout=self.timeout,
            allow_redirects=True,
        )
        response.raise_for_status()
        candidates = [response.url]
        candidates.extend(
            item.headers.get("Location", "") for item in response.history
        )
        self.login_name = next(
            (name for name in map(extract_login_name, candidates) if name),
            "",
        )
        if not self.login_name:
            raise RuntimeError("Unable to resolve iClass loginName through WebVPN")

        response = self.session.get(
            LOGIN_URL,
            params={
                "phone": self.login_name,
                "password": "",
                "userLevel": "1",
                "verificationType": "2",
                "verificationUrl": "",
            },
            timeout=self.timeout,
        )
        response.raise_for_status()
        data = response.json()
        if str(data.get("STATUS", data.get("status", ""))) != "0":
            raise RuntimeError(api_message(data, "iClass login failed"))
        result = data.get("result", data)
        self.class_id = str(result.get("id", ""))
        if not self.class_id:
            raise RuntimeError("iClass login returned no user id")
        self.session.headers.update({"Sessionid": self.login_name})
        logging.info("iClass session established")

    def _ensure_login(self):
        if not self.class_id or not self.login_name:
            self.login()

    def _request_json(self, method, url, params, retry=True):
        self._ensure_login()
        response = self.session.request(
            method,
            url,
            params={"id": self.class_id, **params},
            headers={"Sessionid": self.login_name},
            timeout=self.timeout,
        )
        response.raise_for_status()
        data = response.json()
        status = str(data.get("STATUS", data.get("status", "")))
        if status in {"4001", "401"} and retry:
            logging.info("iClass session expired; logging in again")
            self.login()
            return self._request_json(method, url, params, retry=False)
        return data

    def query_schedule(self, day):
        data = self._request_json(
            "GET",
            SCHEDULE_URL,
            {"dateStr": day.strftime("%Y%m%d")},
        )
        status = str(data.get("STATUS", data.get("status", "")))
        if status == "2":
            return []
        if status != "0":
            raise RuntimeError(api_message(data, f"schedule query failed: {status}"))
        return data.get("result", []) or []

    def server_timestamp(self):
        data = self._request_json("POST", TIMESTAMP_URL, {})
        if str(data.get("STATUS", data.get("status", ""))) != "0":
            raise RuntimeError(api_message(data, "timestamp query failed"))
        timestamp = data.get("timestamp")
        if timestamp is None:
            raise RuntimeError("timestamp response was empty")
        return str(timestamp)

    def checkin(self, schedule_id):
        data = self._request_json(
            "POST",
            CHECKIN_URL,
            {
                "courseSchedId": str(schedule_id),
                "timestamp": self.server_timestamp(),
            },
        )
        status = str(data.get("STATUS", data.get("status", "")))
        message = api_message(data, "")
        if status == "0":
            return True, message or "success"
        if "已签到" in message:
            return True, message
        return False, message or f"iClass status={status}"


class AutoSigner:
    def __init__(self, config):
        self.config = config
        self.client = IClassClient(
            config["username"],
            config["password"],
            timeout=int(config.get("timeout_seconds", 20)),
        )
        self.allowlist = {
            str(item).strip()
            for item in config.get("course_allowlist", [])
            if str(item).strip()
        }
        self.lead_minutes = int(config.get("lead_minutes", 10))
        self.poll_seconds = max(30, int(config.get("poll_seconds", 60)))
        self.max_attempts = max(1, int(config.get("max_attempts", 3)))
        self.poll_start = parse_clock(config.get("poll_start", "07:30"))
        self.poll_end = parse_clock(config.get("poll_end", "22:30"))
        self.attempts = {}
        self.completed = set()
        self.stop_requested = False

    def request_stop(self, *_args):
        self.stop_requested = True

    def _eligible(self, course, now):
        schedule_id = str(course.get("id", ""))
        if not schedule_id or schedule_id in self.completed:
            return False
        if str(course.get("signStatus", "")) == "1":
            self.completed.add(schedule_id)
            return False
        if not matches_allowlist(course, self.allowlist):
            return False
        class_start = parse_class_time(str(course.get("classBeginTime", "")))
        if not class_start:
            return False
        run_at = stable_run_at(schedule_id, class_start, self.lead_minutes)
        return run_at <= now < class_start

    def run_once(self, force=False):
        now = datetime.now(BEIJING)
        if not force and not self.poll_start <= now.time() <= self.poll_end:
            return
        schedules = self.client.query_schedule(now.date())
        logging.info("schedule poll complete: %d course(s)", len(schedules))
        for course in schedules:
            if not self._eligible(course, now):
                continue
            schedule_id = str(course.get("id", ""))
            course_name = str(course.get("courseName", "unknown course"))
            attempts = self.attempts.get(schedule_id, 0)
            if attempts >= self.max_attempts:
                continue
            self.attempts[schedule_id] = attempts + 1
            try:
                ok, message = self.client.checkin(schedule_id)
            except Exception as exc:
                logging.warning(
                    "check-in error course=%s attempt=%d/%d: %s",
                    course_name,
                    attempts + 1,
                    self.max_attempts,
                    exc,
                )
                self.client.reset_session()
                continue
            if ok:
                self.completed.add(schedule_id)
                logging.info("check-in complete course=%s result=%s", course_name, message)
            else:
                logging.warning(
                    "check-in rejected course=%s attempt=%d/%d result=%s",
                    course_name,
                    attempts + 1,
                    self.max_attempts,
                    message,
                )

    def run(self, once=False):
        signal.signal(signal.SIGTERM, self.request_stop)
        signal.signal(signal.SIGINT, self.request_stop)
        while not self.stop_requested:
            try:
                self.run_once(force=once)
            except Exception as exc:
                logging.exception("poll failed: %s", exc)
                self.client.reset_session()
            if once:
                return
            deadline = time.monotonic() + self.poll_seconds
            while not self.stop_requested and time.monotonic() < deadline:
                time.sleep(min(1, deadline - time.monotonic()))


def load_config(path):
    with Path(path).open("r", encoding="utf-8") as handle:
        config = json.load(handle)
    username = str(config.get("username", "")).strip()
    password = str(config.get("password", ""))
    if not username or not password:
        raise ValueError("config must contain non-empty username and password")
    config["username"] = username
    config["password"] = password
    return config


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--config",
        default=os.environ.get("BUAA_SIGN_CONFIG", "/etc/buaa-sign-tool.json"),
    )
    parser.add_argument("--once", action="store_true")
    args = parser.parse_args()
    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s %(levelname)s %(message)s",
    )
    signer = AutoSigner(load_config(args.config))
    signer.run(once=args.once)


if __name__ == "__main__":
    main()
