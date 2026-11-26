package edu.buaa.signtool.data

import org.jsoup.Jsoup

private const val judge = "https://judge.buaa.edu.cn"
private val courseLink = Regex("(?i)courselist\\.jsp\\?courseID=([0-9]+)(?:[&#]|$)")
private val assignmentLink = Regex("(?i)(?:^|/)index\\.jsp\\?assignID=([0-9]+)(?:[&#]|$)")
private val timePattern = Regex(
    """作业时间[：:]\s*(\d{4}-\d{2}-\d{2}\s+\d{2}:\d{2}(?::\d{2})?)\s*至\s*(\d{4}-\d{2}-\d{2}\s+\d{2}:\d{2}(?::\d{2})?)""",
)

internal class JudgeQuery(private val http: CampusHttp) {
    private val feature = CampusFeature.JUDGE
    private var activated = false

    suspend fun courses(): List<CampusCourse> {
        activate()
        return parseJudgeCourses(page("/courselist.jsp", "courseID" to "0"))
    }

    suspend fun assignments(course: CampusCourse): List<CampusHomework> {
        activate()
        page("/courselist.jsp", "courseID" to course.id)
        return parseJudgeAssignments(page("/assignment/index.jsp"), course)
    }

    suspend fun detail(homework: CampusHomework): CampusHomeworkDetail {
        activate()
        page("/courselist.jsp", "courseID" to homework.course.id)
        return parseJudgeDetail(page("/assignment/index.jsp", "assignID" to homework.id), homework)
    }

    private suspend fun activate() {
        if (activated) return
        val response = http.getSchoolPage(
            feature, campusUrl("https://sso.buaa.edu.cn/login", "service" to "$judge/"),
        )
        checkPage(response)
        activated = true
    }

    private suspend fun page(path: String, vararg query: Pair<String, String>): String {
        val response = http.getSchoolPage(feature, campusUrl("$judge$path", *query))
        checkPage(response)
        return response.body
    }

    private fun checkPage(page: CampusPage) {
        if (page.url.host != "judge.buaa.edu.cn") {
            activated = false
            campusAuthRequired()
        }
        if (page.url.path == "/indexcs/simple.jsp" || page.body.contains("cgstuloginbtn")) {
            activated = false
            campusAuthRequired()
        }
    }
}

internal fun parseJudgeCourses(html: String): List<CampusCourse> =
    Jsoup.parse(html).select("a[href]").mapNotNull { link ->
        val id = courseLink.find(link.attr("href"))?.groupValues?.get(1) ?: return@mapNotNull null
        link.text().takeIf { id != "0" && it.isNotBlank() }?.let { CampusCourse(id, it) }
    }.distinctBy { it.id }

internal fun parseJudgeAssignments(html: String, course: CampusCourse): List<CampusHomework> =
    Jsoup.parse(html).select("a[href]").mapNotNull { link ->
        val id = assignmentLink.find(link.attr("href"))?.groupValues?.get(1) ?: return@mapNotNull null
        link.text().takeIf(String::isNotBlank)?.let { CampusHomework(id, course, it) }
    }.distinctBy { it.id }

internal fun parseJudgeDetail(html: String, homework: CampusHomework): CampusHomeworkDetail {
    val doc = Jsoup.parse(html)
    doc.select("script, style, template").remove()
    val text = doc.body().text()
    val time = timePattern.find(text)
    val problems = doc.select("table tr").filter { row ->
        val table = row.closest("table")
        table != null && table.parent()?.closest("table") == null
    }.mapNotNull { row ->
        val cells = row.children().filter { it.tagName() in setOf("th", "td") }.map { cell ->
            cell.clone().apply { select("table").remove() }.text()
        }
        when {
            cells.size >= 4 && cells[2].toBigDecimalOrNull() != null ->
                problem(cells[1], cells.drop(3).joinToString(" "), cells[2])
            cells.size == 2 && cells[0].trimEnd('.').toIntOrNull() != null ->
                problem("第${cells[0].trimEnd('.')}题", cells[1], null)
            else -> null
        }
    }
    val total = Regex("""共\s*(\d+)\s*道""").find(text)?.groupValues?.get(1)?.toIntOrNull() ?: problems.size
    val submitted = problems.count { it.result == "已提交" }
    val state = when {
        total == 0 || problems.isEmpty() -> HomeworkState.UNKNOWN
        submitted == 0 -> HomeworkState.TODO
        submitted < total -> HomeworkState.PARTIAL
        else -> HomeworkState.DONE
    }
    val score = scorePart(text, "总分")
    val maximum = scorePart(text, "作业满分")
    val formatted = if (score != null && maximum != null) "$score / $maximum" else score ?: homework.score
    return CampusHomeworkDetail(
        homework = homework.copy(
            starts = campusTime(time?.groupValues?.get(1)),
            due = campusTime(time?.groupValues?.get(2)),
            state = state,
            score = formatted,
        ),
        problems = problems,
    )
}

private fun problem(name: String, text: String, maximum: String?): HomeworkProblem? {
    val state = when {
        listOf("未提交", "未作答").any(text::contains) -> "未提交"
        listOf("已提交", "提交时间", "得分", "Accept").any { text.contains(it, ignoreCase = true) } -> "已提交"
        else -> return null
    }
    val score = scorePart(text, "得分")
    val formatted = if (score != null && maximum != null) "$score / $maximum" else score ?: maximum?.let { "满分 $it" }
    return HomeworkProblem(name, state, formatted)
}

private fun scorePart(text: String, label: String): String? =
    Regex("${Regex.escape(label)}[：:]\\s*(-?[0-9]+(?:\\.[0-9]+)?)").find(text)?.groupValues?.get(1)
