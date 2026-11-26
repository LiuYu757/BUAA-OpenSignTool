package edu.buaa.signtool.data

import java.math.BigDecimal
import java.math.RoundingMode
import okhttp3.FormBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

private const val finance = "https://chaxun.icw.buaa.edu.cn/api-zhcx"

internal class FinanceQuery(private val http: CampusHttp) {
    private val feature = CampusFeature.FINANCE

    suspend fun incomes(): List<CampusIncome> {
        val portal = http.getSchoolPage(
            feature,
            campusUrl("https://sso.buaa.edu.cn/login", "service" to "http://icw.buaa.edu.cn/checkbuaa.aspx"),
        )
        if (portal.url.host != "icw.buaa.edu.cn" || portal.url.path != "/Newindex.aspx") {
            throw CampusQueryException("学校财务门户尚未完成认证。")
        }
        val handoff = http.getSchoolPage(feature, campusUrl("https://icw.buaa.edu.cn/jump.aspx", "sysid" to "wscx")) {
            it.host == "chaxun.icw.buaa.edu.cn" && it.path == "/gxzhcxzhb/login"
        }.url
        if (handoff.host != "chaxun.icw.buaa.edu.cn" || handoff.path != "/gxzhcxzhb/login") {
            throw CampusQueryException("学校财务系统未返回有效的个人查询入口。")
        }
        val user = handoff.queryValue("Userid") ?: campusAuthRequired()
        val ticket = handoff.queryValue("Cypt") ?: campusAuthRequired()
        val stamp = handoff.queryValue("Stamp") ?: campusAuthRequired()
        // This branch exchanges the signed CAS portal handoff for a personal query token.
        val exchange = FormBody.Builder()
            .add("username", user).add("password", ticket)
            .add("yzm", "yzm").add("stamp", stamp).build()
        val token = financeEnvelope(http.request(
            feature, campusUrl("$finance/login"), exchange,
            mapOf("Referer" to http.routedUrl(feature, handoff)),
        )).value("token") ?: campusAuthRequired()
        val records = linkedMapOf<String, CampusIncome>()
        var expected: Int? = null
        for (page in 1..200) {
            val params = JSONObject().put("userId", user).put("currentPage", page).put("limit", 100)
            val response = financeEnvelope(http.request(
                feature, campusUrl("$finance/getStudentSRCX"),
                params.toString().toRequestBody(campusJsonType),
                mapOf("Authorization" to "Bearer $token"),
            ))
            val (rows, total) = parseIncomePage(response)
            if (expected != null && expected != total) invalidIncome("收入记录在查询时发生变化，请刷新。")
            expected = total
            for (record in rows) {
                if (records.putIfAbsent(record.id, record) != null) invalidIncome("学校返回了重复的收入记录。")
            }
            if (records.size == total) {
                return records.values.sortedWith(
                    compareByDescending<CampusIncome> { it.year.orEmpty() }
                        .thenByDescending { it.month.orEmpty() }.thenByDescending { it.recordedOn.orEmpty() },
                )
            }
            if (rows.isEmpty() || records.size > total) invalidIncome("收入记录未完整加载，请刷新。")
        }
        invalidIncome("收入记录页数过多，请稍后重试。")
    }

    private fun financeEnvelope(reply: CampusHttpReply): JSONObject {
        val body = parseJson(reply)
        if (reply.code == 401 || reply.code in 300..399 || body?.optInt("code") == 401) campusAuthRequired()
        if (reply.code == 403 || body?.optInt("code") == 403) {
            throw CampusQueryException("当前账号没有个人收入查询权限。")
        }
        if (reply.code != 200 || body?.optInt("code") != 200) {
            throw CampusQueryException("学校财务系统未能完成查询，请稍后重试。")
        }
        return body.optJSONObject("data") ?: invalidIncome()
    }
}

internal fun parseIncomePage(data: JSONObject): Pair<List<CampusIncome>, Int> {
    val currentShape = data.has("mx") || data.has("total")
    val rows = data.optJSONArray(if (currentShape) "mx" else "data") ?: invalidIncome()
    val total = data.optInt(if (currentShape) "total" else "count", -1)
    if (total < rows.length() || rows.length() > 100) invalidIncome()
    return rows.objects().map(::parseIncome) to total
}

private fun parseIncome(row: JSONObject): CampusIncome = CampusIncome(
    id = row.value("lsh") ?: invalidIncome(),
    project = row.value("ffxmmc"),
    description = row.value("zy"),
    year = row.value("nian")?.takeIf { it.matches(Regex("[0-9]{4}")) },
    month = row.value("yue")?.toIntOrNull()?.takeIf { it in 1..12 }?.toString()?.padStart(2, '0'),
    recordedOn = row.value("lrrq"),
    gross = money(row, "je"),
    tax = money(row, "se"),
    net = money(row, "sfje"),
    taxRate = row.value("slbfb"),
)

private fun money(row: JSONObject, field: String): BigDecimal? {
    val value = row.value(field) ?: return null
    return runCatching { value.toBigDecimal().setScale(2, RoundingMode.UNNECESSARY) }.getOrElse { invalidIncome() }
}

private fun invalidIncome(message: String = "收入数据格式发生变化，请刷新后重试。"): Nothing =
    throw CampusQueryException(message)
