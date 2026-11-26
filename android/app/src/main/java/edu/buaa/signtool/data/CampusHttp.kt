package edu.buaa.signtool.data

import java.io.IOException
import java.net.CookieManager
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response

internal enum class CampusFeature(val title: String, val hosts: Set<String>) {
    SPOC("SPOC 作业", setOf("spoc.buaa.edu.cn")),
    JUDGE("希冀作业", setOf("judge.buaa.edu.cn")),
    FINANCE("财务收入", setOf("icw.buaa.edu.cn", "chaxun.icw.buaa.edu.cn")),
}

internal class CampusQueryException(message: String) : IllegalStateException(message)

internal fun campusAuthRequired(): Nothing =
    throw CampusQueryException("学校登录已失效，请返回课表重新登录后再查询。")

/** Each query stays within its selected university service and the existing SSO route. */
internal class CampusRoute(private val mode: NetworkMode, private val feature: CampusFeature) {
    private val hosts = feature.hosts + "sso.buaa.edu.cn"
    private val vpnHosts = hosts.associateWith(::encodeVpnHost)

    fun direct(raw: URI): URI {
        val host = raw.host?.lowercase(Locale.ROOT)
        if (host == gateway && mode == NetworkMode.WEB_VPN) {
            if (raw.scheme != "https" || raw.userInfo != null || raw.port !in setOf(-1, 443)) invalid()
            if (raw.path == "/login" || raw.path == "/") campusAuthRequired()
            val parts = raw.rawPath.split('/', limit = 4)
            val originalHost = vpnHosts.entries.firstOrNull { it.value == parts.getOrNull(2) }?.key ?: invalid()
            val scheme = parts.getOrNull(1)
            val path = "/" + parts.getOrElse(3) { "" }
            val query = raw.rawQuery?.let { "?$it" }.orEmpty()
            return direct(URI("$scheme://$originalHost$path$query"))
        }
        // The finance portal uses a legacy HTTP CAS service identifier. Follow the same path over HTTPS.
        val legacyFinance = host == "icw.buaa.edu.cn" &&
            raw.path in setOf("/checkbuaa.aspx", "/Newindex.aspx", "/jump.aspx") &&
            raw.scheme == "http" && raw.port in setOf(-1, 80)
        if (host !in hosts || raw.userInfo != null || raw.fragment != null ||
            (!legacyFinance && (raw.scheme != "https" || raw.port !in setOf(-1, 443)))
        ) invalid()
        return if (legacyFinance) URI("https://icw.buaa.edu.cn" + raw.rawPath + raw.rawQuery?.let { "?$it" }.orEmpty()) else raw
    }

    fun wire(target: URI): URI {
        val uri = direct(target)
        if (mode == NetworkMode.CAMPUS) return uri
        val name = vpnHosts[uri.host.lowercase(Locale.ROOT)] ?: invalid()
        val path = uri.rawPath.ifEmpty { "/" }
        val query = uri.rawQuery?.let { "?$it" }.orEmpty()
        return URI("https://$gateway/https/$name$path$query")
    }

    fun redirect(current: URI, location: String): URI {
        val parsed = try { URI(location) } catch (_: Exception) { invalid() }
        val next = when {
            parsed.isAbsolute -> parsed
            location.startsWith("//") -> current.resolve(parsed)
            mode == NetworkMode.WEB_VPN && (location.startsWith("/https/") || location.startsWith("/http/")) ->
                wire(current).resolve(parsed)
            mode == NetworkMode.WEB_VPN && location == "/login" -> campusAuthRequired()
            else -> current.resolve(parsed)
        }
        val target = direct(next)
        if (feature == CampusFeature.FINANCE) {
            val allowed = when (target.host) {
                "sso.buaa.edu.cn" -> target.path == "/login"
                "icw.buaa.edu.cn" -> target.path in setOf("/checkbuaa.aspx", "/Newindex.aspx", "/jump.aspx")
                "chaxun.icw.buaa.edu.cn" -> target.path == "/gxzhcxzhb/login"
                else -> false
            }
            if (!allowed) invalid()
        }
        return target
    }

    private fun invalid(): Nothing = throw CampusQueryException("学校服务返回了无法识别的跳转地址。")

    private companion object {
        const val gateway = "d.buaa.edu.cn"
    }
}

internal fun encodeVpnHost(host: String): String {
    val key = "wrdvpnisthebest!".toByteArray(Charsets.US_ASCII)
    val cipher = Cipher.getInstance("AES/CFB/NoPadding")
    cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(key))
    return (key + cipher.doFinal(host.toByteArray(Charsets.US_ASCII))).joinToString("") {
        "%02x".format(Locale.ROOT, it.toInt() and 0xff)
    }
}

internal data class CampusHttpReply(val code: Int, val body: String, val location: String?)

internal class CampusHttp(mode: NetworkMode, private val cookies: CookieManager) {
    private val routes = CampusFeature.entries.associateWith { CampusRoute(mode, it) }
    private val closed = AtomicBoolean(false)
    private val client = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .connectTimeout(10, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .build()

    fun close() {
        synchronized(cookies) { closed.set(true) }
        client.dispatcher.cancelAll()
    }

    fun routedUrl(feature: CampusFeature, url: URI): String = routes.getValue(feature).wire(url).toString()

    fun nextUrl(feature: CampusFeature, current: URI, location: String): URI =
        routes.getValue(feature).redirect(current, location)

    suspend fun request(
        feature: CampusFeature,
        url: URI,
        body: RequestBody? = null,
        headers: Map<String, String> = emptyMap(),
    ): CampusHttpReply = suspendCancellableCoroutine { continuation ->
        val target = routes.getValue(feature).wire(url)
        val request = Request.Builder().url(target.toString())
            .header("Accept-Language", "zh-CN,zh;q=0.9")
            .header("Cache-Control", "no-cache")
        headers.forEach { (key, value) -> request.header(key, value) }
        synchronized(cookies) {
            if (closed.get()) campusAuthRequired()
            cookies.get(target, emptyMap()).forEach { (name, values) ->
                if (name.equals("Cookie", ignoreCase = true)) request.header("Cookie", values.joinToString("; "))
            }
        }
        if (body != null) request.post(body)
        val call = client.newCall(request.build())
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) {
                    continuation.resumeWithException(CampusQueryException("连接学校服务失败，请检查网络后重试。"))
                }
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    val reply = response.use { result ->
                        synchronized(cookies) {
                            if (closed.get()) campusAuthRequired()
                            cookies.put(target, mapOf("Set-Cookie" to result.headers("Set-Cookie")))
                        }
                        val payload = result.body
                        val source = payload?.source()
                        if (source?.request(2 * 1024 * 1024L + 1) == true) {
                            throw CampusQueryException("学校服务返回了过大的响应。")
                        }
                        val text = source?.readString(payload?.contentType()?.charset(Charsets.UTF_8) ?: Charsets.UTF_8)
                        CampusHttpReply(result.code, text.orEmpty().trimStart('\uFEFF'), result.header("Location"))
                    }
                    if (continuation.isActive) continuation.resume(reply)
                } catch (error: Exception) {
                    if (continuation.isActive) {
                        continuation.resumeWithException(error as? CampusQueryException
                            ?: CampusQueryException("学校服务响应读取失败，请重试。"))
                    }
                }
            }
        })
    }
}

internal fun campusUrl(base: String, vararg query: Pair<String, String>): URI {
    val params = query.joinToString("&") { (name, value) ->
        URLEncoder.encode(name, "UTF-8") + "=" + URLEncoder.encode(value, "UTF-8")
    }
    return URI(if (params.isEmpty()) base else "$base?$params")
}

internal fun URI.queryValue(key: String, ignoreCase: Boolean = false): String? =
    rawQuery.orEmpty().split('&')
        .mapNotNull { item ->
            val pair = item.split('=', limit = 2)
            if (pair.size == 2 && URLDecoder.decode(pair[0], "UTF-8").equals(key, ignoreCase = ignoreCase)) {
                URLDecoder.decode(pair[1], "UTF-8").takeIf(String::isNotBlank)
            } else null
        }
        .singleOrNull()

internal data class CampusPage(val url: URI, val body: String)

internal suspend fun CampusHttp.getSchoolPage(
    feature: CampusFeature,
    initial: URI,
    stopBefore: (URI) -> Boolean = { false },
): CampusPage {
    var current = initial
    repeat(12) {
        if (stopBefore(current)) return CampusPage(current, "")
        val reply = request(feature, current)
        if (reply.code == 401) campusAuthRequired()
        if (reply.code in 300..399 && reply.location != null) {
            current = nextUrl(feature, current, reply.location)
        } else {
            if (reply.code != 200) throw CampusQueryException("学校服务暂不可用（HTTP ${reply.code}）。")
            if (current.host == "sso.buaa.edu.cn") campusAuthRequired()
            return CampusPage(current, reply.body)
        }
    }
    throw CampusQueryException("学校服务登录跳转过多，请重新登录。")
}
