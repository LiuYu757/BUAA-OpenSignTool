#!/usr/bin/env python3
"""Verify fresh WebVPN logins and iClass reads without submitting attendance."""
import argparse
import json
import logging
from datetime import datetime, timedelta

import requests

from auto_sign import BEIJING, IClassClient, load_config


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--config", default="/etc/buaa-sign-tool.json")
    parser.add_argument("--fresh-logins", type=int, default=2)
    args = parser.parse_args()
    if not 1 <= args.fresh_logins <= 3:
        parser.error("--fresh-logins must be between 1 and 3")
    config = load_config(args.config)
    client = IClassClient(config["username"], config["password"], timeout=15)
    original_request = requests.Session.request

    def read_only_request(session, method, url, *positional, **kwargs):
        if any(path in url for path in ("stu_scan_sign", "stu_auto_sign", "sign_in")):
            raise RuntimeError("Attendance submission is forbidden during verification")
        return original_request(session, method, url, *positional, **kwargs)

    requests.Session.request = read_only_request
    logging.basicConfig(level=logging.INFO, format="%(levelname)s %(message)s")
    try:
        for number in range(1, args.fresh_logins + 1):
            client.login()
            today = datetime.now(BEIJING).date()
            days = []
            for day in [today, today + timedelta(days=1)]:
                courses = client.query_schedule(day)
                days.append({"date": day.isoformat(), "count": len(courses)})
            timestamp = client.server_timestamp()
            assert timestamp, "Timestamp response was empty"
            print(json.dumps({
                "time": datetime.now(BEIJING).isoformat(),
                "fresh_login": number, "login_ok": True,
                "schedule_queries": days, "timestamp_ok": True,
                "attendance_submissions": 0,
            }, ensure_ascii=False), flush=True)
    finally:
        requests.Session.request = original_request


if __name__ == "__main__":
    main()
