import unittest
from datetime import datetime, timedelta

from auto_sign import (
    AutoSigner,
    BEIJING,
    matches_allowlist,
    parse_class_time,
    parse_clock,
    stable_run_at,
)


class AutoSignTests(unittest.TestCase):
    def test_parse_class_time_uses_beijing_timezone(self):
        parsed = parse_class_time("2026-09-17 08:00:00")
        self.assertEqual(parsed.tzinfo, BEIJING)
        self.assertEqual(parsed.hour, 8)

    def test_stable_run_at_stays_inside_lead_window(self):
        start = datetime(2026, 9, 17, 8, 0, tzinfo=BEIJING)
        first = stable_run_at("schedule-1", start, 10)
        second = stable_run_at("schedule-1", start, 10)
        self.assertEqual(first, second)
        self.assertGreaterEqual(first, start - timedelta(minutes=10))
        self.assertLessEqual(first, start - timedelta(minutes=1))

    def test_allowlist_matches_id_schedule_or_exact_name(self):
        course = {
            "id": "schedule-1",
            "courseId": "course-1",
            "courseName": "Machine Learning",
        }
        self.assertTrue(matches_allowlist(course, set()))
        self.assertTrue(matches_allowlist(course, {"schedule-1"}))
        self.assertTrue(matches_allowlist(course, {"course-1"}))
        self.assertTrue(matches_allowlist(course, {"Machine Learning"}))
        self.assertFalse(matches_allowlist(course, {"Other"}))

    def test_parse_clock(self):
        self.assertEqual(parse_clock("07:30").hour, 7)
        self.assertEqual(parse_clock("07:30").minute, 30)
        with self.assertRaises(ValueError):
            parse_clock("7:xx")

    def test_course_is_never_eligible_at_or_after_start(self):
        signer = AutoSigner({"username": "test", "password": "test"})
        course = {
            "id": "schedule-1",
            "courseName": "Test course",
            "classBeginTime": "2026-09-17 08:00:00",
            "signStatus": "0",
        }
        start = datetime(2026, 9, 17, 8, 0, tzinfo=BEIJING)
        self.assertFalse(signer._eligible(course, start))
        self.assertFalse(signer._eligible(course, start + timedelta(seconds=1)))


if __name__ == "__main__":
    unittest.main()
