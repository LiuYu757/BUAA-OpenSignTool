import json
import unittest
from datetime import datetime
from unittest.mock import Mock, patch
from zoneinfo import ZoneInfo

from app import Api
from iclass_client import get_network_urls, late_sign_error


def millis(value):
    return datetime.fromisoformat(value).replace(
        tzinfo=ZoneInfo("Asia/Shanghai")
    ).timestamp() * 1000


class LateCheckinTests(unittest.TestCase):
    def setUp(self):
        self.api = Api()
        self.api.userId = "user-id"
        self.api.sessionId = "session-id"
        self.course = {
            "id": "schedule-1", "courseSchedIds": ["schedule-1", "schedule-2"],
            "courseName": "Test course", "signStatus": "0",
            "classBeginTime": "2026-10-30 08:00:00",
            "classEndTime": "2026-10-30 09:35:00",
        }
        self.api._week_cache = {0: [self.course]}
        self.api._get_server_timestamp = Mock(return_value="real-server-timestamp")
        self.api.session.post = Mock(return_value=Mock(status_code=200))
        self.api.session.post.return_value.json.return_value = {"STATUS": "0"}
        self.clock = patch("app.server_now_millis", return_value=millis("2026-10-30 08:05:00")).start()
        patch("app.time.sleep").start()
        self.addCleanup(patch.stopall)

    def sign(self, ids=None):
        return self.api.sign_course(ids or ["schedule-1"], ["Test course"], "late")

    def test_late_request_uses_android_app_channel_in_both_network_modes(self):
        for vpn in (False, True):
            with self.subTest(vpn=vpn):
                self.course["signStatus"] = "0"
                self.api.use_vpn = vpn
                result = self.sign()
                self.assertEqual(result["success"], 1)
                call = self.api.session.post.call_args
                self.assertEqual(call.args[0], get_network_urls(vpn)["app_scan_sign"])
                self.assertTrue(call.args[0].endswith("/app/course/stu_scan_sign.action"))
                self.assertNotIn("/eschool/", call.args[0])
                self.assertEqual(call.kwargs["params"], {
                    "id": "user-id", "courseSchedId": "schedule-1", "timestamp": "real-server-timestamp",
                })
                self.assertEqual(call.kwargs["headers"], {"Sessionid": "session-id"})

    def test_late_window_uses_beijing_class_start_and_end(self):
        for value, allowed in [
            ("2026-10-30 07:59:59", False), ("2026-10-30 08:00:00", True),
            ("2026-10-30 09:34:59", True), ("2026-10-30 09:35:00", False),
            ("2026-10-29 08:05:00", False), ("2026-10-31 08:05:00", False),
        ]:
            with self.subTest(value=value):
                self.assertEqual(late_sign_error(self.course, millis(value)) is None, allowed)

    def test_missing_or_invalid_times_are_not_submitted(self):
        for key, value in [("classEndTime", ""), ("classBeginTime", "invalid"),
                           ("classEndTime", "2026-10-30 07:00:00")]:
            with self.subTest(key=key, value=value):
                original = self.course[key]
                self.course[key] = value
                self.assertEqual(self.sign()["failed"], 1)
                self.course[key] = original
        self.api.session.post.assert_not_called()

    def test_unknown_course_requires_schedule_refresh(self):
        result = self.sign(["unknown"])
        self.assertIn("刷新课表", result["results"][0]["message"])
        self.api._get_server_timestamp.assert_not_called()
        self.api.session.post.assert_not_called()

    def test_upcoming_and_ended_classes_do_not_make_requests(self):
        for now in ("2026-10-30 07:59:59", "2026-10-30 09:35:00"):
            self.clock.return_value = millis(now)
            self.assertEqual(self.sign()["failed"], 1)
        self.api._get_server_timestamp.assert_not_called()
        self.api.session.post.assert_not_called()

    def test_slow_timestamp_lookup_crossing_end_does_not_submit(self):
        self.clock.side_effect = [millis("2026-10-30 09:34:59"), millis("2026-10-30 09:35:00")]
        result = self.sign()
        self.assertEqual(result["failed"], 1)
        self.assertIn("课程已结束", result["results"][0]["message"])
        self.api._get_server_timestamp.assert_called_once()
        self.api.session.post.assert_not_called()

    def test_server_rejection_is_displayed_and_not_cached_as_signed(self):
        self.api.session.post.return_value.json.return_value = {"STATUS": "1", "ERRMSG": "不允许迟到签到"}
        result = self.sign()
        self.assertEqual(result["success"], 0)
        self.assertEqual(result["failed"], 1)
        self.assertEqual(result["results"][0]["message"], "不允许迟到签到")
        self.assertEqual(self.course["signStatus"], "0")

    def test_late_success_preserves_server_message_and_updates_cache(self):
        self.api.session.post.return_value.json.return_value = {"STATUS": 0, "MSG": "迟到签到成功"}
        result = self.sign()
        self.assertEqual(result["results"][0]["message"], "迟到签到成功")
        self.assertEqual(self.course["signStatus"], "1")
        self.assertEqual(self.sign()["skipped"], 1)
        self.api.session.post.assert_called_once()

    def test_batch_submits_all_merged_ids_once_and_filters_other_courses(self):
        future = {**self.course, "id": "future", "courseSchedIds": ["future"],
                  "classBeginTime": "2026-10-30 10:00:00", "classEndTime": "2026-10-30 11:00:00"}
        past = {**self.course, "id": "past", "courseSchedIds": ["past"],
                "classBeginTime": "2026-10-29 08:00:00", "classEndTime": "2026-10-29 09:35:00"}
        signed = {**self.course, "id": "signed", "courseSchedIds": ["signed"], "signStatus": "1"}
        self.api._week_cache[0] += [future, past, signed, self.course.copy()]
        result = self.api.batch_sign_week(9, 2026, 9, 1, "late")
        self.assertEqual(result["total"], 2)
        self.assertEqual(result["success"], 2)
        self.assertEqual([c.kwargs["params"]["courseSchedId"] for c in self.api.session.post.call_args_list],
                         ["schedule-1", "schedule-2"])
        self.assertEqual(self.course["signStatus"], "1")

    def test_empty_late_batch_explains_no_eligible_classes(self):
        self.clock.return_value = millis("2026-10-30 10:00:00")
        result = self.api.batch_sign_week(9, 2026, 9, 1, "late")
        self.assertEqual(result["total"], 0)
        self.assertEqual(result["results"], [])
        self.assertIn("没有可迟到签到", result["message"])
        self.api.session.post.assert_not_called()

    def test_normal_checkin_keeps_existing_endpoint_and_two_argument_api(self):
        result = self.api.sign_course(json.dumps(["schedule-1"]), json.dumps(["Test course"]))
        self.assertEqual(result["success"], 1)
        self.assertEqual(self.api.session.post.call_args.args[0], get_network_urls(False)["scan_sign"])

    def test_invalid_response_containing_success_word_is_not_success(self):
        self.api.session.post.return_value.json.side_effect = json.JSONDecodeError("bad", "", 0)
        self.api.session.post.return_value.text = "签到不成功"
        result = self.sign()
        self.assertEqual(result["success"], 0)
        self.assertEqual(result["failed"], 1)
        self.assertEqual(self.course["signStatus"], "0")

    def test_invalid_mode_is_rejected_before_request(self):
        with self.assertRaises(ValueError):
            self.api.sign_course(["schedule-1"], sign_mode="invalid")
        with self.assertRaises(ValueError):
            self.api.batch_sign_week(9, 2026, 9, 1, "invalid")
        self.api.session.post.assert_not_called()


if __name__ == "__main__":
    unittest.main()
