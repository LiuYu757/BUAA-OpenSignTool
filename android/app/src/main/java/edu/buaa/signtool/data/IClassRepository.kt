package edu.buaa.signtool.data

import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
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
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.CoroutineContext

/** iClass client which keeps credentials and cookies in memory only. */
class IClassRepository(private val mode: NetworkMode) {
    private val cookies = JavaCookieManager(null, CookiePolicy.ACCEPT_ALL)
    private var campus: CampusQueries? = null
    private val session = AtomicReference<BusinessSession?>()
    @Volatile private var serverClockOffsetMs: Long = 0L

    private val vpnId = "77726476706e69737468656265737421f9f44d9d342326526b0988e29d51367ba018"
    private val loginRoute = IClassLoginRoute(mode, vpnId)
    private val apiBase = iClassHttpsBase(mode, 8347, vpnId)
    private val signBase = iClassHttpsBase(mode, 8181, vpnId)
    private val userLoginUrl = "$apiBase$ICLASS_LOGIN_PATH"
    private val sso = SsoLoginFlow(
        getPage = { requestSso(it) },
        postForm = { url, fields, referer -> requestSso(url, fields, referer) },
        getImage = ::loadCaptchaImage,
    )

    fun logout() {
        session.set(null)
        campus?.close()
        campus = null
        sso.clear()
        cookies.cookieStore.removeAll()
        serverClockOffsetMs = 0L
    }

    fun currentServerTime(): LocalDateTime = serverNow()

    internal fun campusQueries(): CampusQueries {
        currentSession()
        return campus ?: CampusQueries(mode, cookies).also { campus = it }
    }

    suspend fun login(username: String, password: String): LoginResult = withContext(Dispatchers.IO) {
        require(username.isNotBlank() && password.isNotBlank()) { "请输入统一认证账号和密码" }
        logout()
        CookieHandler.setDefault(cookies)
        val ssoEntry = if (mode == NetworkMode.WEB_VPN) {
            "https://d.buaa.edu.cn/"
        } else {
            "https://sso.buaa.edu.cn/login"
        }
        finishOrChallenge(sso.begin(ssoEntry, username, password))
    }

    suspend fun submitCaptcha(username: String, password: String, code: String): LoginResult = withContext(Dispatchers.IO) {
        finishOrChallenge(sso.answer(username, password, code))
    }

    suspend fun refreshCaptcha(): LoginResult.CaptchaRequired = withContext(Dispatchers.IO) {
        LoginResult.CaptchaRequired(sso.refresh())
    }

    private suspend fun finishOrChallenge(captcha: LoginCaptcha?): LoginResult {
        if (captcha != null) return LoginResult.CaptchaRequired(captcha)
        val credential = loginBusinessSession()
        currentCoroutineContext().ensureActive()
        session.set(credential)
        return LoginResult.Authenticated(credential.displayName)
    }

    private fun loginBusinessSession(): BusinessSession {
        val loginName = loginRoute.resolveLoginName { url ->
            val response = request("GET", url, followRedirects = false, acceptHttpError = true)
            if (response.code == 401) throw IClassSessionExpired()
            if (response.code !in 200..399) throw IllegalStateException("iClass 身份查询失败（HTTP ${response.code}）")
            response.location.takeIf { response.code in 300..399 }
        }

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
            followRedirects = false,
            acceptHttpError = true,
        )
        requireBusinessReadSuccess(login.code)
        updateServerClock(login.dateHeader)
        val data = JSONObject(login.body)
        if (!isIClassSuccess(data.optString("STATUS", data.optString("status")))) {
            throw IllegalStateException(apiMessage(data, "iClass 登录失败"))
        }
        val result = data.optJSONObject("result") ?: data
        val userId = result.optString("id").trim()
        val businessSession = result.optString("sessionId").trim()
        if (userId.isBlank() || userId.equals("null", ignoreCase = true)) {
            throw IllegalStateException("iClass 登录未返回用户编号")
        }
        if (businessSession.isBlank() || businessSession.equals("null", ignoreCase = true)) {
            throw IllegalStateException("iClass 登录未返回签到会话，请重新登录")
        }
        return BusinessSession(userId, businessSession, result.optString("realName", result.optString("name")))
    }

    suspend fun loadWeek(weekStart: LocalDate): List<DaySchedule> = withContext(Dispatchers.IO) {
        withReadSession(currentCoroutineContext()) { credential ->
            coroutineScope {
                (0..6).map { offset ->
                    async {
                        val date = weekStart.plusDays(offset.toLong())
                        readDay(date, credential).withMergedCourses()
                    }
                }.awaitAll()
            }
        }
    }

    internal suspend fun sign(courses: List<Course>): SignResult = withContext(Dispatchers.IO) {
        val context = currentCoroutineContext()
        val result = CourseSigner(
            now = ::serverNow,
            readDay = { date -> withReadSession(context) { readDay(date, it) } },
            submit = { scheduleId ->
                context.ensureActive()
                val credential = currentSession()
                val reply = jsonRequest(
                    "$signBase$ICLASS_SIGN_PATH",
                    credential,
                    form = attendanceForm(credential.userId, scheduleId),
                )
                CheckinReply(
                    status = reply.optString("STATUS", reply.optString("status")),
                    message = apiMessage(reply, "").trim().take(160),
                    errorCode = reply.optString("ERRCODE", reply.optString("errCode")).trim(),
                    attendanceStatus = reply.optJSONObject("result")?.optString("stuSignStatus").orEmpty(),
                )
            },
        ).sign(courses)
        result.copy(refreshedDays = result.refreshedDays.map { it.withMergedCourses() })
    }

    private fun readDay(date: LocalDate, credential: BusinessSession): DaySchedule {
        val data = jsonRequest(
            "$apiBase/app/course/get_stu_course_sched.action",
            credential,
            mapOf("dateStr" to date.format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE)),
        )
        val status = data.optString("STATUS", data.optString("status"))
        if (status == "2") return DaySchedule(date, emptyList())
        if (!isIClassSuccess(status)) throw IllegalStateException(apiMessage(data, "课表查询失败 ($status)"))
        val result = data.optJSONArray("result") ?: JSONArray()
        val courses = (0 until result.length()).mapNotNull { index -> result.optJSONObject(index)?.toCourse() }
        return DaySchedule(date, courses)
    }

    private fun JSONObject.toCourse(): Course? {
        val id = optString("id")
        if (id.isBlank()) return null
        val signStatus = optString("signStatus")
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
            signed = signStatus == "1",
            signStatusKnown = signStatus == "0" || signStatus == "1",
        )
    }

    private fun DaySchedule.withMergedCourses(): DaySchedule {
        val merged = courses.groupBy { listOf(it.courseNum, it.beginText, it.classroom).joinToString("|") }
            .values.map { group ->
                val first = group.first()
                first.copy(
                    scheduleIds = group.flatMap { it.scheduleIds }.distinct(),
                    signed = group.all { it.signed },
                    signStatusKnown = group.all { it.signStatusKnown },
                    teacher = group.map { it.teacher }.filter(String::isNotBlank).distinct().joinToString("、"),
                )
            }.sortedBy { it.begin }
        return copy(courses = merged)
    }

    private fun jsonRequest(
        url: String,
        credential: BusinessSession,
        query: Map<String, String> = emptyMap(),
        form: Map<String, String>? = null,
    ): JSONObject {
        val singleWrite = form != null
        val response = request(
            if (singleWrite) "POST" else "GET",
            url,
            query = if (singleWrite) emptyMap() else mapOf("id" to credential.userId) + query,
            form = form,
            headers = mapOf(ICLASS_SESSION_HEADER to credential.token, "Cache-Control" to "no-cache"),
            followRedirects = false,
            acceptHttpError = true,
            singleWrite = singleWrite,
        )
        val data = if (singleWrite) {
            decodeAttendanceResponse(response.code, response.body)
        } else {
            requireBusinessReadSuccess(response.code)
            JSONObject(response.body)
        }
        if (url.startsWith(apiBase) || url.startsWith(signBase)) updateServerClock(response.dateHeader)
        val status = data.optString("STATUS", data.optString("status"))
        if (status == "4001" || status == "401") {
            throw IClassSessionExpired()
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
        acceptHttpError: Boolean = false,
        singleWrite: Boolean = false,
    ): HttpReply {
        val fullUrl = appendQuery(url, query)
        val formBody = form?.let { encode(it).toByteArray(StandardCharsets.UTF_8) }
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
            if (formBody != null) {
                doOutput = true
                setRequestProperty(
                    "Content-Type",
                    if (singleWrite) ICLASS_FORM_CONTENT_TYPE else "application/x-www-form-urlencoded; charset=UTF-8",
                )
                // The attendance request writes the form once and has redirects disabled.
                if (singleWrite) setFixedLengthStreamingMode(formBody.size)
            }
        }
        try {
            if (formBody != null) {
                connection.outputStream.use { it.write(formBody) }
            }
            val code = connection.responseCode
            val stream = if (code >= 400) connection.errorStream else connection.inputStream
            val body = stream?.readUtf8().orEmpty()
            if (!acceptHttpError && code !in 200..399) throw IllegalStateException("服务器连接失败 (HTTP $code)")
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

    /** Follow SSO redirects with the same cookie store, keeping form data on its origin. */
    private fun requestSso(url: String, form: Map<String, String>? = null, referer: String? = null): SsoPage {
        var current = secureBuaaAddress(url)
        var fields = form
        var source = referer
        repeat(MAX_REDIRECTS) {
            val reply = request(
                if (fields == null) "GET" else "POST", current.toString(), form = fields,
                referer = source, followRedirects = false, acceptHttpError = true,
            )
            val location = reply.location
            if (reply.code !in listOf(301, 302, 303, 307, 308) || location.isNullOrBlank()) {
                return SsoPage(reply.code, reply.url, reply.body)
            }
            val next = secureBuaaAddress(current.resolve(location).toString())
            val sameOrigin = sameSsoOrigin(current, next)
            if (reply.code == 307 || reply.code == 308) {
                if (fields != null && !sameOrigin) throw IllegalStateException("统一认证登录地址已变更，请重新登录")
            } else {
                fields = null
            }
            source = current.toString().takeIf { sameOrigin }
            current = next
        }
        throw IllegalStateException("统一认证跳转次数过多，请重新登录")
    }

    private fun loadCaptchaImage(url: String, referer: String): ByteArray {
        val image = secureBuaaAddress(url)
        check(sameSsoOrigin(image, secureBuaaAddress(referer))) { "验证码图片地址无效" }
        val connection = (image.toURL().openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 15_000
            instanceFollowRedirects = false
            useCaches = false
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", "image/*")
            setRequestProperty("Cache-Control", "no-cache")
            setRequestProperty("Referer", referer)
        }
        try {
            check(connection.responseCode in 200..299 && connection.contentLength <= MAX_CAPTCHA_BYTES)
            val bytes = connection.inputStream.use { stream ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = stream.read(buffer)
                    if (count < 0) break
                    check(output.size() + count <= MAX_CAPTCHA_BYTES)
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            check(bounds.outWidth in 1..2048 && bounds.outHeight in 1..1024)
            return bytes
        } finally {
            connection.disconnect()
        }
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

    private fun currentSession(): BusinessSession = session.get() ?: throw IClassSessionExpired()

    private inline fun <T> withReadSession(context: CoroutineContext, read: (BusinessSession) -> T): T {
        context.ensureActive()
        val previous = currentSession()
        return retryIClassRead(
            read = {
                context.ensureActive()
                val credential = currentSession()
                if (credential.userId != previous.userId) throw IClassSessionExpired()
                read(credential)
            },
            renew = {
                context.ensureActive()
                if (session.get() === previous) {
                    val replacement = loginBusinessSession()
                    context.ensureActive()
                    if (replacement.userId != previous.userId) throw IClassSessionExpired()
                    session.compareAndSet(previous, replacement)
                }
            },
        )
    }

    private fun requireBusinessReadSuccess(code: Int) {
        if (code == 401 || code in 300..399) throw IClassSessionExpired()
        if (code != HttpURLConnection.HTTP_OK) throw IllegalStateException("iClass 查询失败（HTTP $code）")
    }

    private data class BusinessSession(val userId: String, val token: String, val displayName: String)

    private data class HttpReply(
        val code: Int,
        val url: String,
        val body: String,
        val location: String?,
        val dateHeader: String?,
    )

    private companion object {
        const val USER_AGENT = "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Mobile Safari/537.36"
        const val MAX_REDIRECTS = 12
        const val MAX_CAPTCHA_BYTES = 512 * 1024
    }
}

internal inline fun <T> retryIClassRead(read: () -> T, renew: () -> Unit): T = try {
    read()
} catch (_: IClassSessionExpired) {
    renew()
    read()
}

internal fun decodeAttendanceResponse(httpCode: Int, body: String): JSONObject {
    val data = try {
        JSONObject(body)
    } catch (_: JSONException) {
        null
    }
    if (httpCode != HttpURLConnection.HTTP_OK) {
        val status = "签到接口返回 HTTP $httpCode"
        val reason = data?.let { apiMessage(it, "").trim().take(160) }.orEmpty()
        throw IClassSignUnconfirmed(if (reason.isBlank()) status else "$status（$reason）")
    }
    return data ?: throw IClassSignUnconfirmed("签到接口返回了无法识别的内容")
}

private fun apiMessage(data: JSONObject, fallback: String): String = sequenceOf("ERRMSG", "ERRORMSG", "MSG", "message", "msg")
    .map { data.optString(it) }.firstOrNull(String::isNotBlank) ?: fallback
