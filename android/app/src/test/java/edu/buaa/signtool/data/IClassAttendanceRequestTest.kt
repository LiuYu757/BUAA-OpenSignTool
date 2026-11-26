package edu.buaa.signtool.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class IClassAttendanceRequestTest {
    @Test fun campusAndWebvpnUseTheSameBusinessLoginAndAttendanceRoutes() {
        val resource = "fixture-resource"
        assertEquals(
            "https://iclass.buaa.edu.cn:8347/app/user/login.action",
            iClassHttpsBase(NetworkMode.CAMPUS, 8347, resource) + ICLASS_LOGIN_PATH,
        )
        assertEquals(
            "https://d.buaa.edu.cn/https-8347/fixture-resource/app/user/login.action",
            iClassHttpsBase(NetworkMode.WEB_VPN, 8347, resource) + ICLASS_LOGIN_PATH,
        )
        assertEquals(
            "https://iclass.buaa.edu.cn:8181/app/course/stu_auto_sign.action",
            iClassHttpsBase(NetworkMode.CAMPUS, 8181, resource) + ICLASS_SIGN_PATH,
        )
        assertEquals(
            "https://d.buaa.edu.cn/https-8181/fixture-resource/app/course/stu_auto_sign.action",
            iClassHttpsBase(NetworkMode.WEB_VPN, 8181, resource) + ICLASS_SIGN_PATH,
        )
    }

    @Test fun webVpnPreservesTheRouteForRelativeAndAbsoluteBusinessRedirects() {
        val visited = mutableListOf<String>()
        val route = IClassLoginRoute(NetworkMode.WEB_VPN, "fixture-resource")
        val name = route.resolveLoginName { url ->
            visited += url
            when (visited.size) {
                1 -> "/intermediate?ticket=fixture"
                2 -> "https://iclass.buaa.edu.cn:8347/next"
                3 -> "/https-8347/fixture-resource/done?LoginName=fixture%2Bvalue#/myCenter"
                else -> error("Resolved identity must not be fetched")
            }
        }
        assertEquals("fixture+value", name)
        assertEquals(
            listOf(
                "https://d.buaa.edu.cn/https-8346/fixture-resource/?type=jumpMyCenter",
                "https://d.buaa.edu.cn/https-8346/fixture-resource/intermediate?ticket=fixture",
                "https://d.buaa.edu.cn/https-8347/fixture-resource/next",
            ),
            visited,
        )
    }

    @Test fun webVpnBusinessSsoKeepsTheGatewayCookieOrigin() {
        val urls = mutableListOf<String>()
        val name = IClassLoginRoute(NetworkMode.WEB_VPN, "fixture-resource").resolveLoginName { url ->
            urls += url
            when (urls.size) {
                1 -> "https://sso.buaa.edu.cn/login?service=fixture"
                2 -> "https://iclass.buaa.edu.cn:8346/done?loginName=student#/myCenter"
                else -> error("Identity must be resolved before another request")
            }
        }
        assertEquals("student", name)
        assertEquals(
            "https://d.buaa.edu.cn/https/" +
                "77726476706e69737468656265737421e3e44ed225256951300d8db9d6562d/login?service=fixture",
            urls[1],
        )
    }

    @Test fun directBusinessLoginResolvesAnIdentityWithoutFollowingOutsideTheSchool() {
        val route = IClassLoginRoute(NetworkMode.CAMPUS, "fixture-resource")
        assertEquals("student", route.resolveLoginName { "/resume?loginName=student" })
        val error = assertThrows(IllegalStateException::class.java) {
            route.resolveLoginName { "https://iclass.buaa.edu.cn.example/login?loginName=student" }
        }
        assertEquals("iClass 返回了无法识别的登录跳转地址", error.message)
    }

    @Test fun readRenewsAnExpiredBusinessSessionOnce() {
        var reads = 0
        var renewals = 0
        val result = retryIClassRead(
            read = { if (++reads == 1) throw IClassSessionExpired() else "course list" },
            renew = { renewals++ },
        )
        assertEquals("course list", result)
        assertEquals(2, reads)
        assertEquals(1, renewals)

        assertThrows(IClassSessionExpired::class.java) {
            retryIClassRead(read = { throw IClassSessionExpired() }, renew = { renewals++ })
        }
        assertEquals(2, renewals)
    }

    @Test fun attendanceErrorKeepsHttpStatusAndBusinessMessage() {
        val response = """{"ERRMSG":"该课程暂未开放签到"}"""
        val error = assertThrows(IClassSignUnconfirmed::class.java) { decodeAttendanceResponse(400, response) }
        assertEquals("签到接口返回 HTTP 400（该课程暂未开放签到）", error.detail)
    }

    @Test fun attendanceHtmlKeepsOnlyAResponseSummary() {
        val loginPage = "<html><title>Login</title></html>"
        val redirect = assertThrows(IClassSignUnconfirmed::class.java) { decodeAttendanceResponse(302, loginPage) }
        val invalid = assertThrows(IClassSignUnconfirmed::class.java) { decodeAttendanceResponse(200, loginPage) }
        assertEquals("签到接口返回 HTTP 302", redirect.detail)
        assertEquals("签到接口返回了无法识别的内容", invalid.detail)
    }

    @Test fun attendanceFormMatchesUbaa210() {
        assertEquals(
            mapOf(
                "id" to "user", "courseSchedId" to "schedule",
                "routerInfo" to "02:00:00:00:00:00",
                "longitude" to "0", "latitude" to "0", "machineInfo" to "Android",
            ),
            attendanceForm("user", "schedule"),
        )
    }
}
