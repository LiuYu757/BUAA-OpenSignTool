#!/usr/bin/env python3
"""Headless BUAA iClass auto check-in service for Linux servers."""

import argparse
import fcntl
import hashlib
import json
import logging
import os
import re
import signal
import time
from datetime import datetime, timedelta
from pathlib import Path
from urllib.parse import parse_qs, unquote_plus, urljoin, urlparse
from zoneinfo import ZoneInfo

import requests
from bs4 import BeautifulSoup
from requests.adapters import HTTPAdapter
from urllib3.util.retry import Retry


BEIJING = ZoneInfo("Asia/Shanghai")
WEBVPN_ENTRY = "https://d.buaa.edu.cn/"
VPN_SERVICE_ID = (
    "77726476706e69737468656265737421"
    "f9f44d9d342326526b0988e29d51367ba018"
)
LOGIN_BASE = f"https://d.buaa.edu.cn/https-8346/{VPN_SERVICE_ID}"
API_BASE = f"https://d.buaa.edu.cn/https-8347/{VPN_SERVICE_ID}"
SIGN_BASE = f"https://d.buaa.edu.cn/https-8181/{VPN_SERVICE_ID}"
JUMP_URL = f"{LOGIN_BASE}/?type=jumpMyCenter"
LOGIN_URL = f"{API_BASE}/app/user/login.action"
SCHEDULE_URL = f"{API_BASE}/app/course/get_stu_course_sched.action"
TIMESTAMP_URL = f"{API_BASE}/app/common/get_timestamp.action"
CHECKIN_URL = f"{SIGN_BASE}/app/course/stu_auto_sign.action"
SSO_VPN_ID = "77726476706e69737468656265737421e3e44ed225256951300d8db9d6562d"
BUSINESS_ORIGINS = {
    f"/https-8346/{VPN_SERVICE_ID}": "https://iclass.buaa.edu.cn:8346",
    f"/https-8347/{VPN_SERVICE_ID}": "https://iclass.buaa.edu.cn:8347",
    f"/https/{VPN_SERVICE_ID}": "https://iclass.buaa.edu.cn",
    f"/https/{SSO_VPN_ID}": "https://sso.buaa.edu.cn",
}
USER_AGENT = (
    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36"
)


def extract_login_name(url):
    if not url:
        return ""
    parsed = urlparse(url)
    values = next((value for key, value in parse_qs(parsed.query).items() if key.lower() == "loginname"), None)
    if values and values[0]:
        return values[0]
    decoded = unquote_plus(url)
    for separator in ("?", "#", "&"):
        decoded = decoded.replace(separator, "\n")
    for part in decoded.splitlines():
        if part.partition("=")[0].lower() == "loginname":
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
    """Pick a stable time inside the configured pre-class window."""
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


class CheckinWindowClosed(RuntimeError):
    """The class started before a check-in request could be sent."""


class CheckinUnconfirmed(RuntimeError):
    """A submission requires a course-record readback before another write."""


def attendance_form(user_id, schedule_id):
    """Use UBAA v2.1.0's fixed placeholder fields for online courses."""
    return {
        "id": str(user_id), "courseSchedId": str(schedule_id),
        "routerInfo": "02:00:00:00:00:00",
        "longitude": "0", "latitude": "0", "machineInfo": "Android",
    }


def direct_business_url(value):
    parsed = urlparse(value)
    value = parsed._replace(fragment="").geturl()
    parsed = urlparse(value)
    if parsed.scheme != "https" or parsed.username or parsed.password:
        raise RuntimeError(
            "iClass returned an invalid identity redirect "
            f"(scheme={parsed.scheme}, host={parsed.hostname}, port={parsed.port}, "
            f"has_userinfo={bool(parsed.username or parsed.password)}, has_fragment={bool(parsed.fragment)})"
        )
    if parsed.hostname == "d.buaa.edu.cn" and parsed.port in (None, 443):
        for prefix, origin in BUSINESS_ORIGINS.items():
            if parsed.path == prefix or parsed.path.startswith(prefix + "/"):
                path = parsed.path[len(prefix):] or "/"
                query = "?" + parsed.query if parsed.query else ""
                return origin + path + query
        raise RuntimeError("WebVPN session expired during iClass identity lookup")
    if parsed.hostname == "iclass.buaa.edu.cn" and parsed.port in (None, 443, 8346, 8347):
        return value
    if parsed.hostname == "sso.buaa.edu.cn" and parsed.port in (None, 443):
        return value
    raise RuntimeError("iClass redirected outside its identity hosts")


def business_request_url(value):
    direct = direct_business_url(value)
    parsed = urlparse(direct)
    resource = SSO_VPN_ID if parsed.hostname == "sso.buaa.edu.cn" else VPN_SERVICE_ID
    protocol = "https" if parsed.port in (None, 443) else f"https-{parsed.port}"
    path = parsed.path or "/"
    query = "?" + parsed.query if parsed.query else ""
    return f"https://d.buaa.edu.cn/{protocol}/{resource}{path}{query}"


class CaptchaRecognitionError(RuntimeError):
    """Refresh an uncertain image before sending account credentials."""


def extract_captcha_id(html):
    """Read the image CAPTCHA config injected into the current CAS page."""
    config = re.search(r"config\.captcha\s*=\s*\{([^}]+)\}", html, re.DOTALL)
    if not config:
        return None
    match = re.search(r"\bid\s*:\s*['\"]([A-Za-z0-9_-]+)['\"]", config[1])
    if not match:
        raise RuntimeError("WebVPN CAPTCHA config did not contain an image id")
    return match[1]


def login_error_text(response):
    soup = BeautifulSoup(response.text, "html.parser")
    return " ".join(
        node.get_text(" ", strip=True)
        for node in soup.select("#errorDiv, .errors, .alert-danger")
    ).strip()


class IClassClient:
    def __init__(self, username, password, timeout=20):
        self.username = username
        self.password = password
        # Retry only failed connections, before any request can be delivered.
        # Never replay a submitted check-in after a read timeout or HTTP error.
        self.timeout = (min(float(timeout), 5), float(timeout))
        self._captcha_ocr = None
        self._captcha_ocr_beta = None
        self.session = None
        self.class_id = ""
        self.login_name = ""
        self.session_id = ""
        self.reset_session()

    def reset_session(self):
        if self.session is not None:
            self.session.close()
        self.session = requests.Session()
        self.session.trust_env = False
        self.session.mount(
            "https://",
            HTTPAdapter(max_retries=Retry(
                total=2, connect=2, read=0, status=0, redirect=0,
                backoff_factor=0.5,
            )),
        )
        # A connection retry could cross the class-start deadline. Keep the
        # attendance endpoint at one transport attempt, including connects.
        self.session.mount(CHECKIN_URL, HTTPAdapter(max_retries=0))
        self.session.headers.update(
            {
                "User-Agent": USER_AGENT,
                "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                "Accept-Language": "zh-CN,zh;q=0.9",
            }
        )
        self.class_id = ""
        self.login_name = ""
        self.session_id = ""

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
            return response.url, node["value"], extract_captcha_id(response.text)
        match = re.search(
            r'name=["\']execution["\'][^>]*value=["\']([^"\']+)',
            response.text,
            re.IGNORECASE,
        )
        if match:
            return response.url, match.group(1), extract_captcha_id(response.text)
        raise RuntimeError("WebVPN login page did not contain execution token")

    def _solve_captcha(self, login_url, captcha_id):
        response = self.session.get(
            urljoin(login_url, "captcha"),
            params={"captchaId": captcha_id},
            headers={"Referer": login_url},
            timeout=self.timeout,
        )
        response.raise_for_status()
        if not response.headers.get("Content-Type", "").lower().startswith("image/"):
            raise RuntimeError("WebVPN CAPTCHA endpoint did not return an image")
        if len(response.content) > 1024 * 1024:
            raise RuntimeError("WebVPN CAPTCHA image exceeded size limit")
        if self._captcha_ocr is None:
            import ddddocr

            self._captcha_ocr = ddddocr.DdddOcr(show_ad=False)
            self._captcha_ocr_beta = ddddocr.DdddOcr(show_ad=False, beta=True)
        answers = [
            model.classification(response.content).strip().lower()
            for model in [self._captcha_ocr, self._captcha_ocr_beta]
        ]
        # This CAS deployment uses six alphanumeric characters. It reports a
        # wrong CAPTCHA as a credential error, so do not submit uncertain OCR.
        value = answers[0]
        if answers[1] != value or not re.fullmatch(r"[a-z0-9]{6}", value):
            raise CaptchaRecognitionError("WebVPN CAPTCHA models did not agree on six characters")
        return value

    def _submit_credentials(self):
        for attempt in range(3):
            login_url, execution, captcha_id = self._fetch_execution()
            data = {
                "username": self.username,
                "password": self.password,
                "submit": "登录",
                "type": "username_password",
                "execution": execution,
                "_eventId": "submit",
            }
            if captcha_id:
                try:
                    data["captcha"] = self._solve_captcha(login_url, captcha_id)
                except CaptchaRecognitionError:
                    if attempt < 2:
                        logging.warning("WebVPN CAPTCHA recognition uncertain; fetching a fresh challenge")
                        continue
                    raise
            response = self.session.post(
                login_url,
                data=data,
                headers={"Referer": login_url},
                allow_redirects=True,
                timeout=self.timeout,
            )
            error = login_error_text(response)
            if error and re.search(r"验证码|captcha|validation code", error, re.I):
                if attempt < 2:
                    logging.warning("WebVPN rejected CAPTCHA; fetching a fresh challenge")
                    continue
                raise RuntimeError("WebVPN CAPTCHA was rejected after 3 attempts")
            if error and re.search(r"密码|口令|password|credentials|locked|锁定", error, re.I):
                raise RuntimeError("WebVPN authentication rejected credentials or locked account")
            if response.status_code == 401:
                raise RuntimeError("WebVPN authentication failed (HTTP 401)")
            response.raise_for_status()
            break

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
        self.login_name = self._resolve_login_name()
        response = self.session.get(
            LOGIN_URL,
            params={
                "phone": self.login_name, "password": "", "userLevel": "1",
                "verificationType": "2", "verificationUrl": "",
            },
            timeout=self.timeout,
            allow_redirects=False,
        )
        if response.status_code != 200:
            raise RuntimeError(f"iClass business login failed (HTTP {response.status_code})")
        data = response.json()
        if str(data.get("STATUS", data.get("status", ""))).lower() not in {"0", "200", "success"}:
            raise RuntimeError(api_message(data, "iClass login failed"))
        result = data.get("result", data)
        self.class_id = str(result.get("id") or "").strip()
        self.session_id = str(result.get("sessionId") or "").strip()
        if not self.class_id or not self.session_id:
            raise RuntimeError("iClass business login returned incomplete credentials")
        logging.info("iClass session established")

    def _resolve_login_name(self):
        current = direct_business_url(JUMP_URL)
        for _ in range(12):
            parsed = urlparse(current)
            login_name = extract_login_name(current)
            if parsed.hostname == "iclass.buaa.edu.cn" and login_name:
                return login_name
            wire_url = business_request_url(current)
            response = self.session.get(wire_url, timeout=self.timeout, allow_redirects=False)
            if response.status_code not in (301, 302, 303, 307, 308):
                raise RuntimeError("Unable to resolve iClass identity through WebVPN")
            location = response.headers.get("Location", "")
            if not location:
                raise RuntimeError("iClass identity redirect did not include a location")
            segment = urlparse(location).path.split("/")[1:2]
            wrapped = segment and segment[0].split("-", 1)[0] in {"http", "https"}
            base = wire_url if wrapped else current
            current = direct_business_url(urljoin(base, location))
        raise RuntimeError("iClass identity redirect limit exceeded")

    def _ensure_login(self):
        if not self.class_id or not self.session_id:
            self.login()

    def _request_json(self, method, url, params, retry=True, not_after=None, not_before=None):
        self._ensure_login()
        now = datetime.now(BEIJING)
        if not_after is not None and now >= not_after:
            raise CheckinWindowClosed("class has started; check-in skipped")
        if not_before is not None and now < not_before:
            raise CheckinWindowClosed("pre-class check-in window has not opened")
        single_write = method == "POST" and url == CHECKIN_URL
        fields = {"id": self.class_id, **params}
        arguments = {"data": fields} if single_write else {"params": fields}
        headers = {"sessionId": self.session_id, "Accept": "application/json"}
        if single_write:
            headers["Content-Type"] = "application/x-www-form-urlencoded"
        response = self.session.request(
            method, url, **arguments, headers=headers,
            timeout=self.timeout, allow_redirects=False,
        )
        if response.status_code != 200:
            if single_write:
                raise CheckinUnconfirmed(f"check-in response HTTP {response.status_code}")
            if response.status_code in {301, 302, 303, 307, 308, 401} and retry:
                self.login()
                return self._request_json(
                    method, url, params, retry=False, not_after=not_after, not_before=not_before,
                )
            raise RuntimeError(f"iClass query failed (HTTP {response.status_code})")
        try:
            data = response.json()
            if not isinstance(data, dict):
                raise ValueError("unexpected response type")
        except ValueError as exc:
            if single_write:
                raise CheckinUnconfirmed("check-in response was not a JSON object") from exc
            raise RuntimeError("iClass query returned an invalid JSON response") from exc
        status = str(data.get("STATUS", data.get("status", "")))
        if status in {"4001", "401"} and retry and not single_write:
            logging.info("iClass session expired; logging in again")
            self.login()
            return self._request_json(
                method, url, params, retry=False, not_after=not_after, not_before=not_before,
            )
        return data

    def query_schedule(self, day):
        data = self._request_json("GET", SCHEDULE_URL, {"dateStr": day.strftime("%Y%m%d")})
        status = str(data.get("STATUS", data.get("status", "")))
        if status == "2":
            return []
        if status.lower() not in {"0", "200", "success"}:
            raise RuntimeError(api_message(data, f"schedule query failed: {status}"))
        return data.get("result", []) or []

    def server_timestamp(self):
        data = self._request_json("GET", TIMESTAMP_URL, {})
        if str(data.get("STATUS", data.get("status", ""))).lower() not in {"0", "200", "success"}:
            raise RuntimeError(api_message(data, "timestamp query failed"))
        timestamp = data.get("timestamp")
        if timestamp is None:
            raise RuntimeError("timestamp response was empty")
        return str(timestamp)

    def checkin(self, schedule_id, class_start):
        self._ensure_login()
        data = self._request_json(
            "POST", CHECKIN_URL, attendance_form(self.class_id, schedule_id),
            retry=False, not_after=class_start, not_before=class_start - timedelta(minutes=5),
        )
        status = str(data.get("STATUS", data.get("status", "")))
        message = api_message(data, "")
        if "已签到" in message:
            return True, message
        if status.lower() not in {"0", "200", "success"}:
            return False, message or f"iClass status={status}"
        result = data.get("result")
        attendance_status = str(result.get("stuSignStatus", "")) if isinstance(result, dict) else ""
        if attendance_status == "1":
            return True, message or "accepted; verifying course record"
        if attendance_status == "0":
            code = data.get("ERRCODE", data.get("errCode", ""))
            return False, f"{message or 'iClass rejected check-in'} (code={code})"
        raise CheckinUnconfirmed("check-in response did not contain an attendance status")


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
        self.lead_minutes = int(config.get("lead_minutes", 5))
        if not 1 <= self.lead_minutes <= 5:
            raise ValueError("lead_minutes must be between 1 and 5")
        self.poll_seconds = max(30, int(config.get("poll_seconds", 60)))
        self.max_attempts = max(1, int(config.get("max_attempts", 3)))
        self.poll_start = parse_clock(config.get("poll_start", "07:30"))
        self.poll_end = parse_clock(config.get("poll_end", "22:30"))
        self.attempts = {}
        self.completed = set()
        self.pending = set()
        self.state_day = None
        self.state_file = Path(config["state_file"]) if config.get("state_file") else None
        if self.state_file is not None and self.state_file.exists():
            state = json.loads(self.state_file.read_text())
            self.state_day = state["day"]
            self.attempts = {key: int(value) for key, value in state["attempts"].items()}
            self.completed = set(state["completed"])
            self.pending = set(state["pending"])
        self.stop_requested = False

    def _save_state(self):
        if self.state_file is None:
            return
        self.state_file.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
        temporary = self.state_file.with_suffix(".tmp")
        descriptor = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
        with os.fdopen(descriptor, "w", encoding="utf-8") as handle:
            json.dump({
                "day": self.state_day, "attempts": self.attempts,
                "completed": sorted(self.completed), "pending": sorted(self.pending),
            }, handle)
            handle.write("\n")
            handle.flush()
            os.fsync(handle.fileno())
        os.replace(temporary, self.state_file)

    def request_stop(self, *_args):
        self.stop_requested = True

    def _eligible(self, course, now):
        schedule_id = str(course.get("id", ""))
        if not schedule_id:
            return False
        if str(course.get("signStatus", "")) == "1":
            self.completed.add(schedule_id)
            self.pending.discard(schedule_id)
            return False
        if schedule_id in self.completed or schedule_id in self.pending:
            return False
        if str(course.get("signStatus", "")) != "0":
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
        if self.state_day != now.date().isoformat():
            self.state_day = now.date().isoformat()
            self.attempts.clear()
            self.completed.clear()
            self.pending.clear()
            self._save_state()
        schedules = self.client.query_schedule(now.date())
        logging.info("schedule poll complete: %d course(s)", len(schedules))
        for course in schedules:
            # Querying the schedule or checking in an earlier course can take
            # time. Never use the poll's old timestamp to submit another course.
            if not self._eligible(course, datetime.now(BEIJING)):
                continue
            schedule_id = str(course.get("id", ""))
            course_name = str(course.get("courseName", "unknown course"))
            attempts = self.attempts.get(schedule_id, 0)
            if attempts >= self.max_attempts:
                continue
            self.attempts[schedule_id] = attempts + 1
            self._save_state()
            try:
                self.client._ensure_login()
            except Exception as exc:
                logging.warning("pre-check-in login failed: %s", type(exc).__name__)
                self.client.reset_session()
                continue
            class_start = parse_class_time(str(course["classBeginTime"]))
            self.pending.add(schedule_id)
            self._save_state()
            accepted = None
            message = "response pending"
            try:
                accepted, message = self.client.checkin(schedule_id, class_start)
            except CheckinWindowClosed:
                self.pending.discard(schedule_id)
                self._save_state()
                logging.info("check-in skipped course=%s: outside pre-class window", course_name)
                continue
            except Exception as exc:
                logging.warning("check-in response pending course=%s error_type=%s", course_name, type(exc).__name__)
                self.client.reset_session()
            try:
                fresh = self.client.query_schedule(class_start.date())
                confirmed = any(
                    str(item.get("id", "")) == schedule_id and str(item.get("signStatus", "")) == "1"
                    for item in fresh
                )
            except Exception as exc:
                logging.warning("check-in readback unavailable: %s", type(exc).__name__)
                confirmed = False
            if confirmed:
                self.completed.add(schedule_id)
                self.pending.discard(schedule_id)
                logging.info("check-in confirmed course=%s", course_name)
            elif accepted is False:
                self.pending.discard(schedule_id)
                logging.warning(
                    "check-in rejected course=%s attempt=%d/%d result=%s",
                    course_name, attempts + 1, self.max_attempts, message,
                )
            else:
                logging.warning("check-in awaiting course record course=%s", course_name)
            self._save_state()
        self._save_state()

    def run(self, once=False):
        signal.signal(signal.SIGTERM, self.request_stop)
        signal.signal(signal.SIGINT, self.request_stop)
        logging.info(
            "automatic check-in started: channel=ubaa-student lead_minutes=%d "
            "poll_seconds=%d timezone=Asia/Shanghai",
            self.lead_minutes,
            self.poll_seconds,
        )
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
    config = load_config(args.config)
    state_directory = os.environ.get("STATE_DIRECTORY")
    if state_directory:
        config.setdefault("state_file", str(Path(state_directory.split(":")[0]) / "state.json"))
    lock = None
    if config.get("state_file"):
        state_file = Path(config["state_file"])
        state_file.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
        lock = state_file.with_suffix(".lock").open("a")
        try:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            lock.close()
            raise SystemExit("The automatic check-in service is already running")
    try:
        signer = AutoSigner(config)
        signer.run(once=args.once)
    finally:
        if lock is not None:
            lock.close()


if __name__ == "__main__":
    main()
