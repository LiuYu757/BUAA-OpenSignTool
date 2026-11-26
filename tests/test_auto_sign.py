import unittest
from pathlib import Path
from tempfile import TemporaryDirectory
from datetime import datetime, timedelta
from unittest.mock import Mock, patch

import requests

from auto_sign import (
    AutoSigner,
    BEIJING,
    CHECKIN_URL,
    LOGIN_URL,
    JUMP_URL,
    API_BASE,
    CheckinUnconfirmed,
    CaptchaRecognitionError,
    CheckinWindowClosed,
    IClassClient,
    extract_captcha_id,
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
        first = stable_run_at("schedule-1", start, 5)
        second = stable_run_at("schedule-1", start, 5)
        self.assertEqual(first, second)
        self.assertGreaterEqual(first, start - timedelta(minutes=5))
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

    def test_course_is_ineligible_before_five_minute_window_or_when_signed(self):
        signer = AutoSigner({"username": "test", "password": "test"})
        start = datetime(2026, 9, 17, 8, 0, tzinfo=BEIJING)
        course = {
            "id": "schedule-1",
            "classBeginTime": "2026-09-17 08:00:00",
            "signStatus": "0",
        }
        self.assertFalse(signer._eligible(course, start - timedelta(minutes=5, seconds=1)))
        course["signStatus"] = "1"
        self.assertFalse(signer._eligible(course, start - timedelta(seconds=30)))

    def test_config_cannot_expand_window_past_five_minutes(self):
        for lead in (0, -1, 6, 10):
            with self.subTest(lead=lead), self.assertRaises(ValueError):
                AutoSigner({"username": "test", "password": "test", "lead_minutes": lead})

    def test_schedule_query_crossing_start_does_not_submit(self):
        signer = AutoSigner({"username": "test", "password": "test"})
        start = datetime(2026, 9, 17, 8, 0, tzinfo=BEIJING)
        signer.client = Mock()
        signer.client.query_schedule.return_value = [{
            "id": "schedule-1",
            "classBeginTime": "2026-09-17 08:00:00",
            "signStatus": "0",
        }]
        with patch("auto_sign.datetime", wraps=datetime) as clock:
            clock.now.side_effect = [start - timedelta(seconds=30), start]
            signer.run_once()
        signer.client.checkin.assert_not_called()

    def test_success_is_completed_without_duplicate_checkin(self):
        signer = AutoSigner({"username": "test", "password": "test"})
        signer.client = Mock()
        course = {"id": "schedule-1", "classBeginTime": "2026-09-17 08:00:00", "signStatus": "0"}
        signed = {**course, "signStatus": "1"}
        signer.client.query_schedule.side_effect = [[course], [signed], [signed]]
        signer.client.checkin.return_value = (True, "accepted")
        start = datetime(2026, 9, 17, 8, 0, tzinfo=BEIJING)
        with patch("auto_sign.datetime", wraps=datetime) as clock:
            clock.now.return_value = start - timedelta(seconds=30)
            signer.run_once()
            signer.run_once()
        signer.client.checkin.assert_called_once_with("schedule-1", start)
        self.assertEqual(signer.completed, {"schedule-1"})
        self.assertFalse(signer.pending)

    def test_uncertain_submission_is_not_replayed_after_restart(self):
        course = {"id": "schedule-1", "classBeginTime": "2026-09-17 08:00:00", "signStatus": "0"}
        with TemporaryDirectory() as directory:
            config = {"username": "test", "password": "test", "state_file": str(Path(directory) / "state.json")}
            signer = AutoSigner(config)
            signer.client = Mock()
            signer.client.query_schedule.return_value = [course]
            signer.client.checkin.side_effect = requests.ReadTimeout("response lost")
            with patch("auto_sign.datetime", wraps=datetime) as clock:
                clock.now.return_value = datetime(2026, 9, 17, 7, 59, 30, tzinfo=BEIJING)
                signer.run_once()
                restored = AutoSigner(config)
                restored.client = Mock()
                restored.client.query_schedule.return_value = [course]
                restored.run_once()
            signer.client.checkin.assert_called_once()
            restored.client.checkin.assert_not_called()
            self.assertEqual(restored.pending, {"schedule-1"})

    def test_success_without_readback_stays_pending(self):
        signer = AutoSigner({"username": "test", "password": "test"})
        self.assertEqual(signer.lead_minutes, 5)
        signer.client = Mock()
        signer.client.query_schedule.return_value = [{
            "id": "schedule-1", "classBeginTime": "2026-09-17 08:00:00", "signStatus": "0",
        }]
        signer.client.checkin.return_value = (True, "accepted")
        with patch("auto_sign.datetime", wraps=datetime) as clock:
            clock.now.return_value = datetime(2026, 9, 17, 7, 59, 30, tzinfo=BEIJING)
            signer.run_once()
            signer.run_once()
        signer.client.checkin.assert_called_once()
        self.assertFalse(signer.completed)
        self.assertEqual(signer.pending, {"schedule-1"})


class UbaaCheckinTests(unittest.TestCase):
    def setUp(self):
        self.start = datetime(2026, 9, 17, 8, 0, tzinfo=BEIJING)
        self.client = IClassClient("test", "test")
        self.client.class_id = "user-id"
        self.client.login_name = "temporary-login"
        self.client.session_id = "business-session"
        self.client.session = Mock()
        self.client.session.request.return_value.status_code = 200

    def test_sends_exact_ubaa_form_and_business_session(self):
        self.client.session.request.return_value.json.return_value = {"STATUS": "0", "result": {"stuSignStatus": 1}}
        with patch("auto_sign.datetime", wraps=datetime) as clock:
            clock.now.return_value = self.start - timedelta(seconds=30)
            self.assertTrue(self.client.checkin("schedule-1", self.start)[0])
        request = self.client.session.request.call_args
        self.assertEqual(request.args, ("POST", CHECKIN_URL))
        self.assertIn("/https-8181/", CHECKIN_URL)
        self.assertTrue(CHECKIN_URL.endswith("/app/course/stu_auto_sign.action"))
        self.assertNotIn("params", request.kwargs)
        self.assertFalse(request.kwargs["allow_redirects"])
        self.assertEqual(request.kwargs["data"], {
            "id": "user-id", "courseSchedId": "schedule-1",
            "routerInfo": "02:00:00:00:00:00", "longitude": "0", "latitude": "0", "machineInfo": "Android",
        })
        self.assertEqual(request.kwargs["headers"], {
            "sessionId": "business-session", "Accept": "application/json",
            "Content-Type": "application/x-www-form-urlencoded",
        })

    def test_outside_five_minute_window_does_not_submit(self):
        with patch("auto_sign.datetime", wraps=datetime) as clock:
            for now in (self.start - timedelta(minutes=5, seconds=1), self.start):
                clock.now.return_value = now
                with self.assertRaises(CheckinWindowClosed):
                    self.client.checkin("schedule-1", self.start)
        self.client.session.request.assert_not_called()

    def test_login_crossing_start_does_not_submit(self):
        self.client.class_id = ""
        with patch("auto_sign.datetime", wraps=datetime) as clock:
            clock.now.return_value = self.start - timedelta(seconds=30)
            self.client.login = Mock(side_effect=lambda: setattr(clock.now, "return_value", self.start))
            with self.assertRaises(CheckinWindowClosed):
                self.client.checkin("schedule-1", self.start)
        self.client.session.request.assert_not_called()

    def test_expired_write_is_not_replayed(self):
        self.client.session.request.return_value.json.return_value = {"STATUS": "4001"}
        self.client.login = Mock()
        with patch("auto_sign.datetime", wraps=datetime) as clock:
            clock.now.return_value = self.start - timedelta(seconds=30)
            self.assertFalse(self.client.checkin("schedule-1", self.start)[0])
        self.client.session.request.assert_called_once()
        self.client.login.assert_not_called()

    def test_explicit_rejection_inside_successful_envelope_is_reported(self):
        self.client.session.request.return_value.json.return_value = {
            "STATUS": "success", "ERRMSG": "签到失败", "ERRCODE": "100", "result": {"stuSignStatus": 0},
        }
        with patch("auto_sign.datetime", wraps=datetime) as clock:
            clock.now.return_value = self.start - timedelta(seconds=30)
            ok, reason = self.client.checkin("schedule-1", self.start)
        self.assertFalse(ok)
        self.assertIn("100", reason)

    def test_missing_attendance_status_requires_readback(self):
        self.client.session.request.return_value.json.return_value = {"STATUS": "0"}
        with patch("auto_sign.datetime", wraps=datetime) as clock:
            clock.now.return_value = self.start - timedelta(seconds=30)
            with self.assertRaises(CheckinUnconfirmed):
                self.client.checkin("schedule-1", self.start)


class WebVPNLoginTests(unittest.TestCase):
    LOGIN_URL = "https://d.buaa.edu.cn/https/sso/login?service=webvpn"

    @staticmethod
    def response(body, status=200, content_type="text/html"):
        response = requests.Response()
        response.status_code = status
        response._content = body.encode() if isinstance(body, str) else body
        response.encoding = "utf-8"
        response.url = WebVPNLoginTests.LOGIN_URL
        response.headers["Content-Type"] = content_type
        return response

    def setUp(self):
        self.client = IClassClient("test-user", "secret-password")
        self.client.session = Mock()
        self.client.session.post.return_value = self.response("WebVPN portal")

    def test_business_login_uses_the_returned_session(self):
        self.client.reset_session = Mock()
        self.client._submit_credentials = Mock()
        self.client._resolve_login_name = Mock(return_value="temporary-identity")
        self.client.session.get.return_value = self.response(
            '{"STATUS":"0","result":{"id":"user","sessionId":"business-session"}}',
            content_type="application/json",
        )
        self.client.login()
        self.assertEqual(LOGIN_URL, API_BASE + "/app/user/login.action")
        self.assertEqual(self.client.session_id, "business-session")
        self.assertEqual(self.client.session.get.call_args.args, (LOGIN_URL,))
        self.assertEqual(self.client.session.get.call_args.kwargs["params"]["phone"], "temporary-identity")

    def test_identity_redirect_preserves_webvpn_for_relative_and_absolute_paths(self):
        responses = [self.response("", status=302) for _ in range(3)]
        for response, location in zip(responses, (
            "/intermediate", "https://iclass.buaa.edu.cn:8347/next", "/done?loginName=identity%2Bvalue",
        )):
            response.headers["Location"] = location
        self.client.session.get.side_effect = responses
        self.assertEqual(self.client._resolve_login_name(), "identity+value")
        self.assertEqual(
            [call.args[0] for call in self.client.session.get.call_args_list],
            [JUMP_URL, JUMP_URL.split("/?")[0] + "/intermediate", API_BASE + "/next"],
        )

    def test_business_sso_uses_the_existing_webvpn_cookie_origin(self):
        replies = [self.response("", status=302) for _ in range(2)]
        replies[0].headers["Location"] = "https://sso.buaa.edu.cn/login?service=fixture"
        replies[1].headers["Location"] = "https://iclass.buaa.edu.cn:8346/done?loginName=identity"
        self.client.session.get.side_effect = replies
        self.assertEqual(self.client._resolve_login_name(), "identity")
        urls = [call.args[0] for call in self.client.session.get.call_args_list]
        self.assertEqual(urls[1], (
            "https://d.buaa.edu.cn/https/"
            "77726476706e69737468656265737421e3e44ed225256951300d8db9d6562d/login?service=fixture"
        ))

    def test_browser_fragment_keeps_the_identity_query(self):
        for target in (
            "https://iclass.buaa.edu.cn:8346/?loginName=identity#/myCenter",
            JUMP_URL.split("/?")[0] + "/?loginName=identity#/myCenter",
        ):
            with self.subTest(target=target):
                reply = self.response("", status=302)
                reply.headers["Location"] = target
                self.client.session.get.return_value = reply
                self.assertEqual(self.client._resolve_login_name(), "identity")
                self.client.session.get.assert_called_once()
                self.client.session.get.reset_mock()

    def test_untrusted_identity_host_is_never_requested(self):
        response = self.response("", status=302)
        response.headers["Location"] = "https://outside.example/?loginName=identity"
        self.client.session.get.return_value = response
        with self.assertRaisesRegex(RuntimeError, "identity hosts"):
            self.client._resolve_login_name()
        self.client.session.get.assert_called_once()

    def test_current_cas_script_exposes_captcha_id(self):
        html = """<script>config.captcha = {
            type: 'image', id: '6846851997'
        };</script>"""
        self.assertEqual(extract_captcha_id(html), "6846851997")
        self.assertIsNone(extract_captcha_id("<form>no CAPTCHA</form>"))
        with self.assertRaisesRegex(RuntimeError, "image id"):
            extract_captcha_id("config.captcha = {type: 'image'};")

    def test_login_without_captcha_remains_supported(self):
        self.client._fetch_execution = Mock(return_value=(self.LOGIN_URL, "token-1", None))
        self.client._solve_captcha = Mock()
        self.client._submit_credentials()
        self.client._solve_captcha.assert_not_called()
        self.assertNotIn("captcha", self.client.session.post.call_args.kwargs["data"])

    def test_captcha_is_fetched_and_submitted_in_same_session(self):
        self.client.session.get.return_value = self.response(b"image-bytes", content_type="image/png")
        self.client._fetch_execution = Mock(return_value=(self.LOGIN_URL, "token-1", "image-1"))
        self.client._captcha_ocr = Mock()
        self.client._captcha_ocr.classification.return_value = "Ab12cc"
        self.client._captcha_ocr_beta = Mock()
        self.client._captcha_ocr_beta.classification.return_value = "ab12cc"
        self.client._submit_credentials()
        self.client.session.get.assert_called_once_with(
            "https://d.buaa.edu.cn/https/sso/captcha",
            params={"captchaId": "image-1"},
            headers={"Referer": self.LOGIN_URL}, timeout=self.client.timeout,
        )
        self.client._captcha_ocr.classification.assert_called_once_with(b"image-bytes")
        submitted = self.client.session.post.call_args.kwargs["data"]
        self.assertEqual(submitted["captcha"], "ab12cc")
        self.assertEqual(submitted["execution"], "token-1")
        self.assertEqual(submitted["password"], "secret-password")

    def test_rejected_captcha_retries_with_fresh_token_and_image(self):
        self.client._fetch_execution = Mock(side_effect=[
            (self.LOGIN_URL, "token-1", "image-1"),
            (self.LOGIN_URL, "token-2", "image-2"),
        ])
        self.client._solve_captcha = Mock(side_effect=["wrong1", "right2"])
        self.client.session.post.side_effect = [
            self.response('<div id="errorDiv"><p>验证码不正确</p></div>', 401),
            self.response("WebVPN portal"),
        ]
        self.client._submit_credentials()
        calls = self.client.session.post.call_args_list
        self.assertEqual([c.kwargs["data"]["execution"] for c in calls], ["token-1", "token-2"])
        self.assertEqual([c.kwargs["data"]["captcha"] for c in calls], ["wrong1", "right2"])

    def test_captcha_retries_are_bounded(self):
        self.client._fetch_execution = Mock(return_value=(self.LOGIN_URL, "token", "image"))
        self.client._solve_captcha = Mock(return_value="wrong1")
        self.client.session.post.return_value = self.response(
            '<div id="errorDiv">Invalid captcha</div>', 401,
        )
        with self.assertRaisesRegex(RuntimeError, "after 3 attempts"):
            self.client._submit_credentials()
        self.assertEqual(self.client.session.post.call_count, 3)

    def test_password_rejection_does_not_repeat_credentials(self):
        self.client._fetch_execution = Mock(return_value=(self.LOGIN_URL, "token", None))
        self.client.session.post.return_value = self.response(
            '<div id="errorDiv">用户名或密码错误</div>', 401,
        )
        with self.assertRaisesRegex(RuntimeError, "credentials or locked"):
            self.client._submit_credentials()
        self.client.session.post.assert_called_once()

    def test_read_timeout_does_not_resubmit_login(self):
        self.client._fetch_execution = Mock(return_value=(self.LOGIN_URL, "token", None))
        self.client.session.post.side_effect = requests.ReadTimeout("response lost")
        with self.assertRaises(requests.ReadTimeout):
            self.client._submit_credentials()
        self.client.session.post.assert_called_once()

    def test_non_image_captcha_is_not_sent_to_recognizer(self):
        self.client.session.get.return_value = self.response("login page")
        self.client._captcha_ocr = Mock()
        with self.assertRaisesRegex(RuntimeError, "did not return an image"):
            self.client._solve_captcha(self.LOGIN_URL, "image")
        self.client._captcha_ocr.classification.assert_not_called()

    def test_attendance_transport_never_retries_across_class_start(self):
        client = IClassClient("test", "test")
        self.assertEqual(client.session.get_adapter(CHECKIN_URL).max_retries.total, 0)
        self.assertEqual(
            client.session.get_adapter("https://d.buaa.edu.cn/").max_retries.connect, 2,
        )

    def test_uncertain_ocr_refreshes_without_submitting_credentials(self):
        self.client._fetch_execution = Mock(return_value=(self.LOGIN_URL, "token", "image"))
        self.client._solve_captcha = Mock(side_effect=CaptchaRecognitionError("uncertain"))
        with self.assertRaises(CaptchaRecognitionError):
            self.client._submit_credentials()
        self.assertEqual(self.client._fetch_execution.call_count, 3)
        self.client.session.post.assert_not_called()

    def test_missing_character_or_model_disagreement_is_rejected_before_login(self):
        self.client.session.get.return_value = self.response(b"image", content_type="image/png")
        self.client._captcha_ocr = Mock()
        self.client._captcha_ocr_beta = Mock()
        for first, second in [("bgfgm", "bgfgm"), ("ab12cc", "ab12cd")]:
            with self.subTest(first=first, second=second):
                self.client._captcha_ocr.classification.return_value = first
                self.client._captcha_ocr_beta.classification.return_value = second
                with self.assertRaises(CaptchaRecognitionError):
                    self.client._solve_captcha(self.LOGIN_URL, "image")


if __name__ == "__main__":
    unittest.main()
