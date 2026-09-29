#!/usr/bin/env python3
"""Interactively create the root-only server configuration file."""

import getpass
import json
import os
from pathlib import Path


CONFIG_PATH = Path("/etc/buaa-sign-tool.json")


def load_existing():
    try:
        with CONFIG_PATH.open("r", encoding="utf-8") as handle:
            return json.load(handle)
    except (FileNotFoundError, json.JSONDecodeError):
        return {}


def prompt(label, default=""):
    suffix = f" [{default}]" if default else ""
    value = input(f"{label}{suffix}: ").strip()
    return value or default


def main():
    if os.geteuid() != 0:
        raise SystemExit("Run this configurator as root")
    existing = load_existing()
    username = prompt("BUAA SSO username", str(existing.get("username", "")))
    password = getpass.getpass(
        "BUAA SSO password (leave blank to keep existing): "
    )
    if not password:
        password = str(existing.get("password", ""))
    raw_courses = prompt(
        "Course IDs/names, comma separated; blank means all courses",
        ",".join(str(item) for item in existing.get("course_allowlist", [])),
    )
    if not username or not password:
        raise SystemExit("Username and password must not be empty")
    config = {
        "username": username,
        "password": password,
        "course_allowlist": [
            item.strip() for item in raw_courses.split(",") if item.strip()
        ],
        "lead_minutes": int(existing.get("lead_minutes", 10)),
        "poll_seconds": int(existing.get("poll_seconds", 60)),
        "poll_start": str(existing.get("poll_start", "07:30")),
        "poll_end": str(existing.get("poll_end", "22:30")),
        "max_attempts": int(existing.get("max_attempts", 3)),
        "timeout_seconds": int(existing.get("timeout_seconds", 20)),
    }
    temporary = CONFIG_PATH.with_suffix(".json.tmp")
    flags = os.O_WRONLY | os.O_CREAT | os.O_TRUNC
    descriptor = os.open(temporary, flags, 0o600)
    with os.fdopen(descriptor, "w", encoding="utf-8") as handle:
        json.dump(config, handle, ensure_ascii=False, indent=2)
        handle.write("\n")
    os.replace(temporary, CONFIG_PATH)
    os.chmod(CONFIG_PATH, 0o600)
    print(f"Configuration written to {CONFIG_PATH} with mode 0600")


if __name__ == "__main__":
    main()
