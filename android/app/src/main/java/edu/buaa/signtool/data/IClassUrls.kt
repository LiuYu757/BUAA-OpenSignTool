package edu.buaa.signtool.data

import java.net.URI
import java.util.Locale

private const val ICLASS_HOST = "iclass.buaa.edu.cn"
private const val WEBVPN_HOST = "d.buaa.edu.cn"
private const val SSO_HOST = "sso.buaa.edu.cn"

internal fun iClassHttpsBase(mode: NetworkMode, port: Int, vpnResource: String): String =
    when (mode) {
        NetworkMode.CAMPUS -> "https://$ICLASS_HOST" + if (port == 443) "" else ":$port"
        NetworkMode.WEB_VPN -> {
            val protocol = if (port == 443) "https" else "https-$port"
            "https://$WEBVPN_HOST/$protocol/$vpnResource"
        }
    }

internal const val ICLASS_LOGIN_PATH = "/app/user/login.action"
internal const val ICLASS_SIGN_PATH = "/app/course/stu_auto_sign.action"
internal const val ICLASS_SESSION_HEADER = "sessionId"
internal const val ICLASS_FORM_CONTENT_TYPE = "application/x-www-form-urlencoded"

/** Resolve the business login in its original URL space, then route each request through WebVPN. */
internal class IClassLoginRoute(private val mode: NetworkMode, private val vpnResource: String) {
    private val loginPorts = listOf(8346, 8347, 443)
    private val gatewayOrigins = loginPorts.associate { port ->
        URI(iClassHttpsBase(NetworkMode.WEB_VPN, port, vpnResource)).rawPath to
            iClassHttpsBase(NetworkMode.CAMPUS, port, vpnResource)
    } + ("/https/${encodeVpnHost(SSO_HOST)}" to "https://$SSO_HOST")

    fun resolveLoginName(redirect: (String) -> String?): String {
        var hop = route(URI("https://$ICLASS_HOST:8346/?type=jumpMyCenter"))
        repeat(12) {
            if (hop.upstream.host.equals(ICLASS_HOST, ignoreCase = true)) {
                hop.upstream.queryValue("loginName", ignoreCase = true)?.let { return it }
            }
            val location = redirect(hop.request.toString()) ?: throw IClassSessionExpired()
            val relative = try {
                URI(location)
            } catch (_: java.net.URISyntaxException) {
                invalidRedirect()
            }
            val segment = relative.rawPath.orEmpty().split('/').getOrNull(1).orEmpty()
            val usesGatewayPath = segment == "https" || segment.startsWith("https-") ||
                segment == "http" || segment.startsWith("http-")
            val base = if (hop.request.host == WEBVPN_HOST && usesGatewayPath) hop.request else hop.upstream
            val target = if (!relative.isAbsolute && relative.rawAuthority == null &&
                relative.rawPath.isNullOrEmpty() && relative.rawQuery != null
            ) {
                URI(base.toString().substringBefore('?') + "?" + relative.rawQuery)
            } else {
                base.resolve(relative)
            }
            hop = route(target.normalize())
        }
        throw IllegalStateException("iClass 身份跳转次数过多")
    }

    private fun route(raw: URI): LoginHop {
        val target = if (raw.rawFragment == null) raw else URI(raw.toString().substringBefore('#'))
        val host = target.host?.lowercase(Locale.ROOT)
        if (target.scheme != "https" || target.rawUserInfo != null || target.rawFragment != null) invalidRedirect()
        val port = if (target.port == -1) 443 else target.port
        if (host == SSO_HOST && port == 443) {
            val request = if (mode == NetworkMode.WEB_VPN) {
                URI("https://$WEBVPN_HOST/https/${encodeVpnHost(SSO_HOST)}" +
                    target.rawPath.ifEmpty { "/" } + target.rawQuerySuffix())
            } else target
            return LoginHop(target, request)
        }
        if (host == ICLASS_HOST && port in loginPorts) {
            val request = if (mode == NetworkMode.CAMPUS) target else URI(
                iClassHttpsBase(mode, port, vpnResource) + target.rawPath.ifEmpty { "/" } + target.rawQuerySuffix(),
            )
            return LoginHop(target, request)
        }
        if (host == WEBVPN_HOST && mode == NetworkMode.WEB_VPN && port == 443) {
            val path = target.rawPath
            val origin = gatewayOrigins.entries.firstOrNull { path == it.key || path.startsWith("${it.key}/") }
            if (origin == null) {
                if (path == "/" || path == "/login" || path.startsWith("/wengine-vpn/")) throw IClassSessionExpired()
                invalidRedirect()
            }
            val upstream = URI(origin.value + path.removePrefix(origin.key).ifEmpty { "/" } + target.rawQuerySuffix())
            return LoginHop(upstream, target)
        }
        invalidRedirect()
    }

    private fun URI.rawQuerySuffix(): String = rawQuery?.let { "?$it" }.orEmpty()

    private fun invalidRedirect(): Nothing = throw IllegalStateException("iClass 返回了无法识别的登录跳转地址")

    private data class LoginHop(val upstream: URI, val request: URI)
}

internal fun attendanceForm(userId: String, scheduleId: String): Map<String, String> =
    mapOf(
        "id" to userId,
        "courseSchedId" to scheduleId,
        "routerInfo" to "02:00:00:00:00:00",
        "longitude" to "0",
        "latitude" to "0",
        "machineInfo" to "Android",
    )

internal fun isIClassSuccess(status: String): Boolean =
    status == "0" || status == "200" || status.equals("success", ignoreCase = true)
