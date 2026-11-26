package edu.buaa.signtool.data

import edu.buaa.signtool.filterIncome
import edu.buaa.signtool.totalMoney
import java.math.BigDecimal
import java.net.URI
import java.time.LocalDateTime
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class CampusQueriesTest {
    @Test fun authenticatedRedirectsStayInsideTheSelectedCampusService() {
        val route = CampusRoute(NetworkMode.WEB_VPN, CampusFeature.FINANCE)
        val callback = URI("https://icw.buaa.edu.cn/checkbuaa.aspx?ticket=fixture")
        val wire = route.wire(callback)
        assertEquals("d.buaa.edu.cn", wire.host)
        assertEquals(callback, route.direct(wire))
        assertEquals(callback, route.redirect(callback, "http://icw.buaa.edu.cn/checkbuaa.aspx?ticket=fixture"))
        assertThrows(CampusQueryException::class.java) {
            route.redirect(callback, "https://spoc.buaa.edu.cn/unrelated")
        }
        assertThrows(CampusQueryException::class.java) {
            route.redirect(callback, "https://icw.buaa.edu.cn@outside.example/redirect")
        }
    }

    @Test fun spocUsesLocalDeadlineAndPreservesAnUnknownSubmissionState() {
        val item = parseSpocHomework(JSONObject("""
            {"zyid":"a2","zymc":"Graph review","sskcid":"c1","mf":"满分：20",
              "zyjzsj":"2026-11-20T10:00:00.000+00:00","tjzt":"?"}
        """), mapOf("c1" to "Discrete math"))
        assertEquals("Discrete math", item.course.name)
        assertEquals(LocalDateTime.of(2026, 11, 20, 18, 0), item.due)
        assertEquals(HomeworkState.UNKNOWN, item.state)
        val text = htmlPlainText("<p>Read &amp; reply</p><script>fake score</script><p>Show your result</p>")
        assertEquals("Read & reply\nShow your result", text)
    }

    @Test fun judgeDetailReadsProblemScoresButIgnoresNestedVerdictRows() {
        val course = CampusCourse("8", "Practice")
        val list = parseJudgeAssignments("""
            <a href="assignment/problemContent.jsp?assignID=31">Ignore</a>
            <a href="assignment/index.jsp?assignID=31">Practice one</a>
        """.trimIndent(), course)
        val detail = parseJudgeDetail("""
            <p>作业满分：20，共 2 道题，总分：8</p>
            <p>作业时间：2026-11-19 08:00 至 2026-11-30 23:00</p>
            <table><tr><td>1</td><td>Graph</td><td>10</td><td>已提交 得分：8
              <table><tr><td>1</td><td>Internal case</td><td>10</td><td>Accepted</td></tr></table>
            </td></tr><tr><td>2</td><td>Tree</td><td>10</td><td>未提交</td></tr></table>
        """.trimIndent(), list.single())
        assertEquals(HomeworkState.PARTIAL, detail.homework.state)
        assertEquals(LocalDateTime.of(2026, 11, 30, 23, 0), detail.homework.due)
        assertEquals(listOf("Graph", "Tree"), detail.problems.map { it.name })
        assertEquals("8 / 10", detail.problems.first().score)
    }

    @Test fun incomeTotalsUseExactDecimalsAndTreatMissingAmountsAsUnknown() {
        val (records, total) = parseIncomePage(JSONObject("""
            {"mx":[
                {"lsh":"r1","ffxmmc":"示例补贴","nian":"2026","yue":"3","je":0.10,"se":0,"sfje":0.10},
                {"lsh":"r2","zy":"补贴差额","nian":"2026","yue":"3","je":0.20,"se":0,"sfje":0.20},
                {"lsh":"r3","ffxmmc":"其他记录","nian":"2025","yue":"8","je":null,"sfje":null}
            ],"total":3}
        """))
        assertEquals(3, total)
        assertEquals("03", records.first().month)
        assertEquals(BigDecimal("0.30"), totalMoney(filterIncome(records, "2026", "补贴"), CampusIncome::net))
        assertNull(totalMoney(records, CampusIncome::net))
        assertThrows(CampusQueryException::class.java) { parseIncomePage(JSONObject("""{"mx":[]}""")) }
    }
}
