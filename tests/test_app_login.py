import unittest
from unittest.mock import MagicMock, patch

import requests

from app import Api


class FakeResponse:
    def __init__(self, payload, headers=None):
        self._payload = payload
        self.headers = headers or {}

    def raise_for_status(self):
        return None

    def json(self):
        return self._payload


class AppLoginTests(unittest.TestCase):
    def test_login_resolves_login_name_before_iclass_login(self):
        api = Api()
        with patch.object(api, "_reset_session") as reset_session, \
                patch.object(api, "_submit_sso_credentials") as submit_sso, \
                patch.object(api, "_resolve_login_name", return_value="resolved-login") as resolve, \
                patch.object(api, "_do_login", return_value={"success": True}) as do_login:
            result = api.login_vpn("sso-user", "secret")

        self.assertTrue(result["success"])
        reset_session.assert_called_once_with()
        submit_sso.assert_called_once_with("sso-user", "secret", True)
        resolve.assert_called_once_with()
        do_login.assert_called_once_with("resolved-login")

    def test_iclass_login_uses_resolved_login_name_as_session_header(self):
        api = Api()
        api._urls = {
            "user_login": "https://example.test/eschool/app/user/login_buaa.do"
        }
        api.session.get = MagicMock(
            return_value=FakeResponse(
                {
                    "STATUS": "0",
                    "result": {"id": "42", "realName": "Test User"},
                }
            )
        )

        result = api._do_login("resolved-login")

        self.assertTrue(result["success"])
        self.assertEqual(result["userId"], "42")
        self.assertEqual(result["userName"], "Test User")
        self.assertEqual(api.sessionId, "resolved-login")
        self.assertEqual(api.session.headers["Sessionid"], "resolved-login")
        self.assertEqual(
            api.session.get.call_args.kwargs["params"]["phone"],
            "resolved-login",
        )

    def test_direct_login_requires_password(self):
        api = Api()
        result = api.login_direct("student-id", "")
        self.assertFalse(result["success"])
        self.assertIn("密码", result["error"])

    def test_direct_8346_timeout_suggests_webvpn(self):
        api = Api()
        timeout = requests.exceptions.ConnectionError(
            "HTTPSConnectionPool(host='iclass.buaa.edu.cn', port=8346): timed out"
        )
        with patch.object(api, "_submit_sso_credentials"), \
                patch.object(api, "_resolve_login_name", side_effect=timeout):
            result = api.login_direct("student-id", "secret")

        self.assertFalse(result["success"])
        self.assertEqual(result["suggestMode"], "vpn")
        self.assertIn("8346", result["error"])


if __name__ == "__main__":
    unittest.main()
