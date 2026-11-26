package edu.buaa.signtool.data

import java.math.BigDecimal
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup

internal data class CampusCourse(val id: String, val name: String)

internal enum class HomeworkState(val label: String) {
    TODO("未提交"), PARTIAL("部分提交"), DONE("已提交"), UNKNOWN("状态未知"),
}

internal data class CampusHomework(
    val id: String,
    val course: CampusCourse,
    val title: String,
    val starts: LocalDateTime? = null,
    val due: LocalDateTime? = null,
    val state: HomeworkState = HomeworkState.UNKNOWN,
    val score: String? = null,
)

internal data class HomeworkProblem(val name: String, val result: String, val score: String?)

internal data class CampusHomeworkDetail(
    val homework: CampusHomework,
    val description: String? = null,
    val submittedAt: LocalDateTime? = null,
    val problems: List<HomeworkProblem> = emptyList(),
)

internal data class CampusIncome(
    val id: String,
    val project: String?,
    val description: String?,
    val year: String?,
    val month: String?,
    val recordedOn: String?,
    val gross: BigDecimal?,
    val tax: BigDecimal?,
    val net: BigDecimal?,
    val taxRate: String?,
)

internal fun JSONObject.value(name: String): String? {
    val field = opt(name)
    return when (field) {
        is String, is Number -> field.toString().trim().takeIf(String::isNotBlank)
        else -> null
    }
}

internal fun JSONArray.objects(): List<JSONObject> =
    List(length()) { index -> getJSONObject(index) }

internal fun campusTime(value: String?): LocalDateTime? {
    if (value.isNullOrBlank()) return null
    return runCatching {
        OffsetDateTime.parse(value).atZoneSameInstant(ZoneId.of("Asia/Shanghai")).toLocalDateTime()
    }.getOrNull() ?: listOf("yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd HH:mm").firstNotNullOfOrNull {
        runCatching { LocalDateTime.parse(value.replace('T', ' '), DateTimeFormatter.ofPattern(it)) }.getOrNull()
    }
}

internal fun htmlPlainText(html: String?): String? {
    if (html.isNullOrBlank()) return null
    val doc = Jsoup.parseBodyFragment(html)
    doc.select("script, style, template").remove()
    doc.select("br, p, li, div, tr, h1, h2, h3").forEach { it.appendText("\n") }
    return doc.body().wholeText().lines().map(String::trim).filter(String::isNotBlank)
        .joinToString("\n").takeIf(String::isNotBlank)
}
