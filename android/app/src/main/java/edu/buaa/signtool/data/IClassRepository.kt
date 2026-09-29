package edu.buaa.signtool.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.net.CookieHandler
import java.net.CookieManager as JavaCookieManager
import java.net.CookiePolicy
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** iClass client which keeps credentials and cookies in memory only. */
class IClassRepository(private val mode: NetworkMode) {
    private val cookies = JavaCookieManager(null, CookiePolicy.ACCEPT_ALL)
    private var classId: String = ""
    private var loginName: String = ""
    private var displayName: String = ""
    private var serverClockOffsetMs: Long = 0L

    private val vpnId = "77726476706e69737468656265737421f9f44d9d342326526b0988e29d51367ba018"
    private val loginBase = if (mode == NetworkMode.WEB_VPN) {
        "https://d.buaa.edu.cn/https-8346/$vpnId"
    } else {
        "https://iclass.buaa.edu.cn:8346"
    }
    private val apiBase = if (mode == NetworkMode.WEB_VPN) {
        "https://d.buaa.edu.cn/https-8347/$vpnId"
    } else {
        "https://iclass.buaa.edu.cn:8347"
    }
    private val signBase = if (mode == NetworkMode.WEB_VPN) {
        "https://d.buaa.edu.cn/http-8081/$vpnId"
    } else {
        "http://iclass.buaa.edu.cn:8081"
    }
    private val jumpUrl = "$loginBase/?type=jumpMyCenter"
    private val userLoginUrl = "$loginBase/eschool/app/user/login_buaa.do"

    fun logout() {
        cookies.cookieStore.removeAll()
        classId = ""
        loginName = ""
        displayName = ""
        serverClockOffsetMs = 0L
    }

    fun currentServerTime(): LocalDateTime = serverNow()

    suspend fun login(username: String, password: String): String = withContext(Dispatchers.IO) {
        require(username.isNotBlank() && password.isNotBlank()) { "请输入统一认证账号和密码" }
        CookieHandler.setDefault(cookies)
        classId = ""
        loginName = ""

        val ssoEntry = if (mode == NetworkMode.WEB_VPN) {
            "https://d.buaa.edu.cn/"
        } else {
            "https://sso.buaa.edu.cn/login"
        }
        val ssoPage = request("GET", ssoEntry)
        var execution = htmlInput(ssoPage.body, "execution")
            ?: throw IllegalStateException("统一认证页面缺少登录令牌")
        var response = request(
            "POST",
            ssoPage.url,
            form = mapOf(
                "username" to username.trim(),
                "password" to password,
                "submit" to "登录",
                "type" to "username_password",
                "execution" to execution,
                "_eventId" to "submit",
            ),
            referer = ssoPage.url,
        )

        if (response.body.contains("continueForm")) {
            execution = htmlInput(response.body, "execution")
                ?: throw IllegalStateException("统一认证风险页缺少确认令牌")
            response = request(
                "POST",
                response.url,
                form = mapOf("execution" to execution, "_eventId" to "ignoreAndContinue"),
                referer = response.url,
            )
        }
        if (response.code == 401 || listOf("用户名或密码错误", "账号或密码错误", "invalid credentials")
                .any { response.body.contains(it, ignoreCase = true) }
        ) {
            throw IllegalStateException("统一认证账号或密码错误")
        }

        val jump = followGetRedirects(jumpUrl)
        loginName = jump.visitedUrls.asSequence().mapNotNull(::extractLoginName).firstOrNull()
            ?: throw IllegalStateException("已完成统一认证，但未能获取 iClass 身份")

        val login = request(
            "GET",
            userLoginUrl,
            query = mapOf(
                "phone" to loginName,
                "password" to "",
                "userLevel" to "1",
                "verificationType" to "2",
                "verificationUrl" to "",
            ),
        )
        updateServerClock(login.dateHeader)
        val data = JSONObject(login.body)
        if (data.optString("STATUS", data.optString("status")) != "0") {
            throw IllegalStateException(apiMessage(data, "iClass 登录失败"))
        }
        val result = data.optJSONObject("result") ?: data
        classId = result.optString("id")
        if (classId.isBlank()) throw IllegalStateException("iClass 登录未返回用户编号")
        displayName = result.optString("realName", result.optString("name"))
        displayName
    }

    suspend fun loadWeek(weekStart: LocalDate): List<DaySchedule> = withContext(Dispatchers.IO) {
        ensureLoggedIn()
        coroutineScope {
            (0..6).map { offset ->
                async(Dispatchers.IO) {
                    val date = weekStart.plusDays(offset.toLong())
                    DaySchedule(date, queryDay(date))
                }
            }.awaitAll()
        }
    }

    suspend fun sign(courses: List<Course>, channel: SignChannel): SignResult = withContext(Dispatchers.IO) {
        ensureLoggedIn()
        val now = serverNow()
        val eligible = courses.filter { course ->
            val start = course.begin
            !course.signed && start != null && now >= start.minusMinutes(10) && now < start
        }
        if (eligible.isEmpty()) {
            return@withContext SignResult(0, 0, 0, "当前没有处于开课前 10 分钟窗口内的课程")
        }

        val signUrl = when (channel) {
            SignChannel.ESCHOOL -> "$signBase/eschool/app/course/stu_scan_sign.action"
            SignChannel.APP -> "$signBase/app/course/stu_scan_sign.action"
        }
        var succeeded = 0
        var alreadySigned = 0
        var failed = 0
        var lastMessage = ""
        eligible.flatMap { it.scheduleIds }.distinct().forEach { scheduleId ->
            // Recheck immediately before each network request so a batch cannot cross class start.
            val course = eligible.firstOrNull { scheduleId in it.scheduleIds }
            val start = course?.begin
            if (start == null || serverNow() >= start) return@forEach
            val timestamp = jsonRequest("POST", "$signBase/app/common/get_timestamp.action")
                .optString("timestamp")
            if (timestamp.isBlank()) {
                failed++
                lastMessage = "获取 iClass 时间失败"
                return@forEach
            }
            // Timestamp lookup can be slow. Never submit after the class has started.
            if (serverNow() >= start) return@forEach
            val reply = jsonRequest(
                "POST",
                signUrl,
                mapOf("courseSchedId" to scheduleId, "timestamp" to timestamp),
            )
            val status = reply.optString("STATUS", reply.optString("status"))
            val message = apiMessage(reply, "")
            when {
                status == "0" -> succeeded++
                message.contains("已签到") -> alreadySigned++
                else -> {
                    failed++
                    lastMessage = message.ifBlank { "iClass 拒绝了签到请求" }
                }
            }
        }
        SignResult(
            succeeded = succeeded,
            alreadySigned = alreadySigned,
            failed = failed,
            message = lastMessage,
        )
    }

    private fun queryDay(date: LocalDate): List<Course> {
        val data = jsonRequest(
            "GET",
            "$apiBase/app/course/get_stu_course_sched.action",
            mapOf("dateStr" to date.format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE)),
        )
        val status = data.optString("STATUS", data.optString("status"))
        if (status == "2") return emptyList()
        if (status != "0") throw IllegalStateException(apiMessage(data, "课表查询失败 ($status)"))
        val result = data.optJSONArray("result") ?: JSONArray()
        val courses = (0 until result.length()).mapNotNull { index -> result.optJSONObject(index)?.toCourse() }
        return mergeDuplicateSessions(courses)
    }

    private fun JSONObject.toCourse(): Course? {
        val id = optString("id")
        if (id.isBlank()) return null
        return Course(
            scheduleIds = listOf(id),
            courseId = optString("courseId"),
            courseNum = optString("courseNum"),
            name = optString("courseName", "未命名课程"),
            beginText = optString("classBeginTime"),
            endText = optString("classEndTime"),
            classroom = optString("classroomName"),
            building = optString("teachBuildName"),
            floor = optString("storeyName"),
            teacher = optString("teacherName"),
            signed = optString("signStatus") == "1",
        )
    }

    private fun mergeDuplicateSessions(courses: List<Course>): List<Course> {
        return courses.groupBy { listOf(it.courseNum, it.beginText, it.classroom).joinToString("|") }
            .values.map { group ->
                val first = group.first()
                first.copy(
                    scheduleIds = group.flatMap { it.scheduleIds }.distinct(),
                    signed = group.all { it.signed },
                    teacher = group.map { it.teacher }.filter(String::isNotBlank).distinct().joinToString("、"),
                )
            }.sortedBy { it.begin }
    }

    private fun jsonRequest(method: String, url: String, query: Map<String, String> = emptyMap()): JSONObject {
        ensureLoggedIn()
        val response = request(
            method,
            url,
            query = mapOf("id" to classId) + query,
            headers = mapOf("Sessionid" to loginName),
        )
        if (url.startsWith(apiBase) || url.startsWith(signBase)) updateServerClock(response.dateHeader)
        val data = JSONObject(response.body)
        val status = data.optString("STATUS", data.optString("status"))
        if (status == "4001" || status == "401") {
            throw IllegalStateException("iClass 会话已失效，请重新登录")
        }
        return data
    }

    private fun request(
        method: String,
        url: String,
        query: Map<String, String> = emptyMap(),
        form: Map<String, String>? = null,
        referer: String? = null,
        followRedirects: Boolean = true,
        headers: Map<String, String> = emptyMap(),
    ): HttpReply {
        val fullUrl = appendQuery(url, query)
        val connection = (URL(fullUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 20_000
            readTimeout = 20_000
            instanceFollowRedirects = followRedirects
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", "application/json, text/html;q=0.9, */*;q=0.8")
            setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9")
            setRequestProperty("Connection", "keep-alive")
            referer?.let { setRequestProperty("Referer", it) }
            headers.forEach { (name, value) -> setRequestProperty(name, value) }
            if (form != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            }
        }
        try {
            if (form != null) {
                val body = encode(form).toByteArray(StandardCharsets.UTF_8)
                connection.outputStream.use { it.write(body) }
            }
            val code = connection.responseCode
            val stream = if (code >= 400) connection.errorStream else connection.inputStream
            val body = stream?.readUtf8().orEmpty()
            if (code !in 200..399) throw IllegalStateException("服务器连接失败 (HTTP $code)")
            return HttpReply(
                code,
                connection.url.toString(),
                body,
                connection.getHeaderField("Location"),
                connection.getHeaderField("Date"),
            )
        } finally {
            connection.disconnect()
        }
    }

    /** Follow jumpMyCenter redirects manually so loginName is captured even if a later hop drops it. */
    private fun followGetRedirects(url: String): RedirectTrail {
        val visited = mutableListOf<String>()
        var nextUrl = url
        var referer: String? = null
        repeat(MAX_REDIRECTS) {
            visited += nextUrl
            val response = request("GET", nextUrl, referer = referer, followRedirects = false)
            visited += response.url
            val location = response.location ?: return RedirectTrail(visited.distinct())
            val next = URL(URL(response.url), location).toString()
            if (next == response.url) return RedirectTrail(visited.distinct())
            referer = response.url
            nextUrl = next
        }
        throw IllegalStateException("iClass 身份跳转次数过多")
    }

    private fun InputStream.readUtf8(): String = BufferedReader(InputStreamReader(this, StandardCharsets.UTF_8)).use { it.readText() }

    private fun appendQuery(url: String, query: Map<String, String>): String {
        if (query.isEmpty()) return url
        return url + (if (url.contains('?')) "&" else "?") + encode(query)
    }

    private fun encode(values: Map<String, String>): String = values.entries.joinToString("&") { (key, value) ->
        "${urlEncode(key)}=${urlEncode(value)}"
    }

    private fun urlEncode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8.name())

    private fun htmlInput(html: String, name: String): String? {
        val pattern = Regex("<input\\b(?=[^>]*\\bname=[\\\"']${Regex.escape(name)}[\\\"'])(?=[^>]*\\bvalue=[\\\"']([^\\\"']+)[\\\"'])[^>]*>", RegexOption.IGNORE_CASE)
        return pattern.find(html)?.groupValues?.getOrNull(1)
    }

    private fun extractLoginName(url: String): String? {
        val queryValue = runCatching {
            URL(url).query.orEmpty().split('&').firstOrNull { it.substringBefore('=') == "loginName" }
                ?.substringAfter('=')?.let(::urlDecode)
        }.getOrNull()
        if (!queryValue.isNullOrBlank()) return queryValue
        val decoded = runCatching { urlDecode(url) }.getOrDefault(url)
        return Regex("(?:[?&#]|^)loginName=([^&#]+)").find(decoded)?.groupValues?.getOrNull(1)?.let(::urlDecode)
    }

    private fun urlDecode(value: String): String = java.net.URLDecoder.decode(value, StandardCharsets.UTF_8.name())

    private fun updateServerClock(dateHeader: String?) {
        if (dateHeader.isNullOrBlank()) return
        val serverMillis = runCatching {
            ZonedDateTime.parse(dateHeader, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
        }.getOrNull() ?: return
        val vpnCorrection = if (mode == NetworkMode.WEB_VPN) -1_000L else 0L
        serverClockOffsetMs = serverMillis - System.currentTimeMillis() + vpnCorrection
    }

    private fun serverNow(): LocalDateTime = Instant.ofEpochMilli(System.currentTimeMillis() + serverClockOffsetMs)
        .atZone(ZoneId.of("Asia/Shanghai"))
        .toLocalDateTime()

    private fun apiMessage(data: JSONObject, fallback: String): String = sequenceOf("ERRMSG", "ERRORMSG", "MSG", "message", "msg")
        .map { data.optString(it) }.firstOrNull(String::isNotBlank) ?: fallback

    private fun ensureLoggedIn() {
        if (classId.isBlank() || loginName.isBlank()) throw IllegalStateException("请先登录 iClass")
    }

    private data class HttpReply(
        val code: Int,
        val url: String,
        val body: String,
        val location: String?,
        val dateHeader: String?,
    )
    private data class RedirectTrail(val visitedUrls: List<String>)

    private companion object {
        const val USER_AGENT = "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Mobile Safari/537.36"
        const val MAX_REDIRECTS = 12
    }
}
