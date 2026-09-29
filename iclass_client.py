from dataclasses import dataclass
from email.utils import parsedate_to_datetime
import time
from urllib.parse import parse_qs, unquote_plus, urlparse


SSO_LOGIN_URL = "https://sso.buaa.edu.cn/login"
SSO_VPN_ENTRY = "https://d.buaa.edu.cn/"
VPN_SERVICE_ID = "77726476706e69737468656265737421f9f44d9d342326526b0988e29d51367ba018"
VPN_OFFSET_CORRECTION_MS = -1000


def get_network_urls(use_vpn):
    """Return the current iClass endpoint set for direct or WebVPN mode."""
    if use_vpn:
        login_base = f"https://d.buaa.edu.cn/https-8346/{VPN_SERVICE_ID}"
        api_base = f"https://d.buaa.edu.cn/https-8347/{VPN_SERVICE_ID}"
        sign_base = f"https://d.buaa.edu.cn/http-8081/{VPN_SERVICE_ID}"
        return {
            "service_home": api_base,
            "jump_login": f"{login_base}/?type=jumpMyCenter",
            "user_login": f"{login_base}/eschool/app/user/login_buaa.do",
            "course_list": f"{api_base}/app/choosecourse/get_myall_course.action",
            "semester_list": f"{api_base}/app/course/get_base_school_year.action",
            "course_sign_detail": f"{api_base}/app/my/get_my_course_sign_detail.action",
            "timestamp": f"{sign_base}/app/common/get_timestamp.action",
            "scan_sign": f"{sign_base}/eschool/app/course/stu_scan_sign.action",
            "course_schedule_by_date": f"{api_base}/app/course/get_stu_course_sched.action",
        }

    login_base = "https://iclass.buaa.edu.cn:8346"
    api_base = "https://iclass.buaa.edu.cn:8347"
    sign_base = "http://iclass.buaa.edu.cn:8081"
    return {
        "service_home": api_base,
        "jump_login": f"{login_base}/?type=jumpMyCenter",
        "user_login": f"{login_base}/eschool/app/user/login_buaa.do",
        "course_list": f"{api_base}/app/choosecourse/get_myall_course.action",
        "semester_list": f"{api_base}/app/course/get_base_school_year.action",
        "course_sign_detail": f"{api_base}/app/my/get_my_course_sign_detail.action",
        "timestamp": f"{sign_base}/app/common/get_timestamp.action",
        "scan_sign": f"{sign_base}/eschool/app/course/stu_scan_sign.action",
        "course_schedule_by_date": f"{api_base}/app/course/get_stu_course_sched.action",
    }


def extract_login_name(url):
    """Extract and decode the iClass loginName returned by jumpMyCenter."""
    if not url:
        return ""

    parsed = urlparse(url)
    values = parse_qs(parsed.query).get("loginName")
    if values and values[0]:
        return values[0]

    # WebVPN can embed the target URL/query inside its own path or fragment.
    decoded = unquote_plus(url)
    for separator in ("?", "#", "&"):
        decoded = decoded.replace(separator, "\n")
    for part in decoded.splitlines():
        if part.startswith("loginName="):
            return unquote_plus(part[len("loginName="):])
    return ""


def server_time_offset_from_date(date_header, now_ms=None, use_vpn=False):
    """Derive server clock offset from an HTTP Date header."""
    if not date_header:
        return VPN_OFFSET_CORRECTION_MS if use_vpn else 0

    try:
        server_time = parsedate_to_datetime(date_header)
        offset = int(server_time.timestamp() * 1000) - (
            int(now_ms) if now_ms is not None else int(time.time() * 1000)
        )
    except (TypeError, ValueError, OverflowError):
        offset = 0

    if use_vpn:
        offset += VPN_OFFSET_CORRECTION_MS
    return offset


def server_now_millis(offset_ms):
    return int(time.time() * 1000) + int(offset_ms or 0)


def api_status(data):
    if not isinstance(data, dict):
        return ""
    raw = data.get("STATUS", data.get("status", ""))
    return str(raw)


def is_status_ok(data):
    return api_status(data) == "0"


def api_message(data, default=""):
    if not isinstance(data, dict):
        return default
    for key in ("ERRMSG", "ERRORMSG", "MSG", "message", "msg"):
        value = data.get(key)
        if value is not None and str(value):
            return str(value)
    return default


@dataclass(frozen=True)
class SignClassification:
    status: str
    message: str


def classify_sign_response(data):
    if is_status_ok(data):
        return SignClassification("success", api_message(data, "已提交"))

    message = api_message(data, "签到失败")
    if "已签到" in message:
        return SignClassification("skipped", message)
    return SignClassification("failed", message)


def value_to_string(data, key):
    if not isinstance(data, dict):
        return ""
    value = data.get(key)
    if value is None:
        return ""
    if isinstance(value, bool):
        return "true" if value else "false"
    return str(value)
