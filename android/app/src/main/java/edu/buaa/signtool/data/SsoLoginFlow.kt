package edu.buaa.signtool.data

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.CancellationException

data class LoginCaptcha(
    val imageBytes: ByteArray?,
    val revision: Long,
    val message: String? = null,
)

sealed interface LoginResult {
    data class Authenticated(val displayName: String) : LoginResult
    data class CaptchaRequired(val captcha: LoginCaptcha) : LoginResult
}

internal data class SsoPage(val code: Int, val url: String, val html: String)

/** One SSO attempt. The caller supplies transport backed by one cookie store. */
internal class SsoLoginFlow(
    private val getPage: (String) -> SsoPage,
    private val postForm: (String, Map<String, String>, String) -> SsoPage,
    private val getImage: (String, String) -> ByteArray,
    private val clockMillis: () -> Long = System::currentTimeMillis,
) {
    private var pending: LoginForm? = null
    private var imageReady = false
    private var revision = 0L
    private var lastImageTime = 0L

    /** Returns a challenge, or null when SSO can continue to iClass. */
    fun begin(entryUrl: String, username: String, password: String): LoginCaptcha? {
        clear()
        val page = getPage(entryUrl)
        val form = parseLoginForm(page, Jsoup.parse(page.html))
            ?: throw IllegalStateException("无法读取统一认证登录表单，请稍后重试")
        if (form.captcha != null) return showCaptcha(form)
        checkLoginStatus(page)
        return submit(form, username, password, null)
    }

    fun answer(username: String, password: String, code: String): LoginCaptcha? {
        val form = pending ?: throw IllegalStateException("验证码会话已结束，请重新登录")
        require(imageReady && code.isNotBlank()) { "请先获取并输入图形验证码" }
        // A POST can consume the form's execution token even if the connection fails.
        clear()
        return submit(form, username, password, code.trim().lowercase(Locale.ROOT))
    }

    fun refresh(): LoginCaptcha {
        val form = pending ?: throw IllegalStateException("验证码会话已结束，请重新登录")
        return showCaptcha(form)
    }

    fun clear() {
        pending = null
        imageReady = false
    }

    private fun submit(form: LoginForm, username: String, password: String, code: String?): LoginCaptcha? {
        val fields = form.fields.toMutableMap().apply {
            put("username", username.trim())
            put("password", password)
            put("type", "username_password")
            putIfAbsent("submit", "登录")
            putIfAbsent("_eventId", "submit")
            if (code != null) put(checkNotNull(form.captcha).fieldName, code)
        }
        var response = postForm(form.action, fields, form.pageUrl)
        var doc = Jsoup.parse(response.html)

        // Keep the existing SSO continuation step before resolving iClass identity.
        val continuation = doc.selectFirst("form#continueForm, form[name=continueForm]")
        if (continuation != null) {
            checkLoginStatus(response)
            val execution = continuation.inputValue("execution")
                ?: throw IllegalStateException("统一认证风险页缺少确认令牌")
            val action = resolveFormAction(response.url, continuation.attr("action"))
            response = postForm(action, mapOf("execution" to execution, "_eventId" to "ignoreAndContinue"), response.url)
            doc = Jsoup.parse(response.html)
        }

        // A rejected CAPTCHA normally returns a fresh execution token and image ID.
        // Inspect that form before treating HTTP 400/401 as an ordinary login error.
        val nextForm = parseLoginForm(response, doc)
        if (nextForm?.captcha != null) {
            val notice = if (code != null) "登录未通过，请输入新验证码重试。" else null
            return showCaptcha(nextForm, notice)
        }
        checkLoginStatus(response)
        if (nextForm != null || listOf("用户名或密码错误", "账号或密码错误", "认证信息无效", "invalid credentials")
                .any { doc.text().contains(it, ignoreCase = true) }
        ) {
            throw IllegalStateException("统一认证未通过，请核对账号和密码")
        }
        return null
    }

    private fun showCaptcha(form: LoginForm, notice: String? = null): LoginCaptcha {
        pending = form
        imageReady = false
        val captcha = checkNotNull(form.captcha)
        // The official mobile page refreshes this resource with a fresh `t` value.
        lastImageTime = maxOf(clockMillis(), lastImageTime + 1)
        val image = try {
            getImage(withImageTime(captcha.imageUrl, lastImageTime), form.pageUrl).takeIf { it.isNotEmpty() }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            null
        }
        imageReady = image != null
        return LoginCaptcha(image, ++revision, if (imageReady) notice else "验证码图片加载失败，请刷新重试。")
    }

    private fun checkLoginStatus(page: SsoPage) {
        if (page.code == 401) throw IllegalStateException("统一认证未通过，请核对账号和密码")
        if (page.code !in 200..299) throw IllegalStateException("统一认证连接失败 (HTTP ${page.code})")
    }
}

private data class LoginForm(
    val pageUrl: String,
    val action: String,
    val fields: Map<String, String>,
    val captcha: ImageChallenge?,
)

private data class ImageChallenge(val imageUrl: String, val fieldName: String)

private fun parseLoginForm(page: SsoPage, doc: Document): LoginForm? {
    val form = doc.selectFirst("form#loginForm") ?: doc.select("form").firstOrNull {
        it.selectFirst("input[name=username]") != null && it.selectFirst("input[name=password]") != null
    } ?: return null
    val execution = form.inputValue("execution")
        ?: throw IllegalStateException("统一认证页面缺少登录令牌，请重新登录")
    val fields = form.select("input[name][type=hidden]").associate { it.attr("name") to it.attr("value") }.toMutableMap()
    fields["execution"] = execution
    form.inputValue("submit")?.let { fields["submit"] = it }
    form.inputValue("_eventId")?.let { fields["_eventId"] = it }

    val imageSource = doc.selectFirst("#captchaDiv img[src], #captchaParent img[src], img#captchaImg[src], img[src*='captchaId=']")
        ?.attr("src")?.trim()?.takeIf(String::isNotEmpty)
    val captchaId = sequenceOf(
        form.inputValue("captchaId"),
        doc.selectFirst("#captchaId")?.let { it.attr("value").ifBlank { it.text() } },
        doc.selectFirst("#captchaDiv[data-captcha-id], #captchaParent[data-captcha-id]")?.attr("data-captcha-id"),
        doc.select("script").asSequence().mapNotNull { captchaConfigId.find(it.data())?.groupValues?.get(2) }.firstOrNull(),
    ).filterNotNull().map(String::trim).firstOrNull { it.isNotEmpty() && it.length <= 256 }
    // The normal page has an empty, hidden #captchaDiv. It is not a challenge.
    val imagePath = imageSource ?: captchaId?.let { "/captcha?captchaId=${URLEncoder.encode(it, "UTF-8")}" }
    val captcha = imagePath?.let {
        val fieldName = form.selectFirst("input[name=captcha], input[name=validateCode], input[name=verificationCode]")
            ?.attr("name") ?: "captcha"
        ImageChallenge(resolveSameOrigin(page.url, it).toString(), fieldName)
    }
    return LoginForm(page.url, resolveFormAction(page.url, form.attr("action")), fields, captcha)
}

private val captchaConfigId = Regex("""\bconfig\s*\.\s*captcha\s*=\s*\{[^}]*?["']?\bid["']?\s*:\s*(["'])([^"'\r\n]+)\1""")
private val tunnelPath = Regex("^/https(?:-\\d+)?/[0-9a-fA-F]{32,128}(?=/)")

private fun Element.inputValue(name: String): String? = selectFirst("input[name=$name]")
    ?.attr("value")?.takeIf(String::isNotBlank)

internal fun secureBuaaAddress(value: String): URI {
    val uri = runCatching { URI(value) }.getOrNull()
    val host = uri?.host?.lowercase(Locale.ROOT)
    if (uri == null || uri.scheme != "https" || uri.userInfo != null || uri.port !in listOf(-1, 443) ||
        host == null || (host != "buaa.edu.cn" && !host.endsWith(".buaa.edu.cn"))
    ) {
        throw IllegalStateException("统一认证返回了无法使用的链接，请重新登录")
    }
    return uri
}

internal fun sameSsoOrigin(first: URI, second: URI): Boolean = first.host.equals(second.host, ignoreCase = true)

private fun resolveSameOrigin(pageUrl: String, reference: String): URI {
    val page = secureBuaaAddress(pageUrl)
    val prefix = tunnelPath.find(page.rawPath)?.value
    val ref = reference.trim()
    // WebVPN rewrites the CAS script's root-relative /captcha into the login tunnel.
    val path = if (prefix != null && ref.startsWith('/') && !ref.startsWith("//") && !tunnelPath.containsMatchIn(ref)) {
        prefix + ref
    } else ref
    val resolved = runCatching { page.resolve(path).normalize().toString() }.getOrNull()
        ?: throw IllegalStateException("统一认证返回了无法使用的链接，请重新登录")
    val target = secureBuaaAddress(resolved)
    if (!sameSsoOrigin(page, target)) throw IllegalStateException("统一认证返回了无法使用的链接，请重新登录")
    return target
}

private fun resolveFormAction(pageUrl: String, action: String): String {
    if (action.isBlank()) return secureBuaaAddress(pageUrl).toString()
    val page = secureBuaaAddress(pageUrl)
    val target = resolveSameOrigin(pageUrl, action)
    // Preserve the service query when CAS posts to the same login path.
    return if (page.rawPath == target.rawPath && target.rawQuery == null) page.toString() else target.toString()
}

private fun withImageTime(imageUrl: String, millis: Long): String {
    val uri = secureBuaaAddress(imageUrl)
    val query = uri.rawQuery.orEmpty().split('&').filter { it.isNotEmpty() && it.substringBefore('=') != "t" }
    return uri.toASCIIString().substringBefore('#').substringBefore('?') + "?" + (query + "t=$millis").joinToString("&")
}
