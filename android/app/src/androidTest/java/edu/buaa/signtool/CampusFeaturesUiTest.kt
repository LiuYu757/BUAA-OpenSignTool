package edu.buaa.signtool

import android.graphics.Bitmap
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import edu.buaa.signtool.data.CampusCourse
import edu.buaa.signtool.data.CampusFeature
import edu.buaa.signtool.data.CampusHomework
import edu.buaa.signtool.data.CampusHomeworkDetail
import edu.buaa.signtool.data.CampusIncome
import edu.buaa.signtool.data.CampusReader
import edu.buaa.signtool.data.HomeworkProblem
import edu.buaa.signtool.data.HomeworkState
import java.io.File
import java.math.BigDecimal
import java.time.LocalDateTime
import org.junit.Rule
import org.junit.Test

class CampusFeaturesUiTest {
    @get:Rule val compose = createComposeRule()
    private val courses = CampusCourse("fixture-course", "模拟课程")
    private val fixture = object : CampusReader {
        override suspend fun spocHomework() = listOf(
            CampusHomework("a", courses, "阅读作业", due = LocalDateTime.of(2026, 11, 24, 23, 0), state = HomeworkState.TODO),
        )
        override suspend fun judgeCourses() = listOf(courses)
        override suspend fun judgeHomework(course: CampusCourse) = listOf(CampusHomework("b", course, "图论练习"))
        override suspend fun detail(feature: CampusFeature, homework: CampusHomework) = if (feature == CampusFeature.SPOC) {
            CampusHomeworkDetail(homework, "请阅读第三章并回答问题。")
        } else {
            CampusHomeworkDetail(homework, problems = listOf(HomeworkProblem("遍历图", "已提交", "8 / 10")))
        }
        override suspend fun incomes() = listOf(
            income("r1", "示例助学金", "2026", "500.00"),
            income("r2", "示例酬金", "2025", "200.00"),
        )
    }

    @Test fun spocShowsDeadlineAndOpensReadOnlyAssignmentDescription() {
        open(CampusFeature.SPOC)
        compose.onNodeWithText("截止 2026-11-24 23:00 · 未提交").assertIsDisplayed()
        compose.onNodeWithText("阅读作业").performClick()
        compose.onNodeWithText("请阅读第三章并回答问题。").assertIsDisplayed()
        capture("spoc-detail")
    }

    @Test fun judgeLoadsSelectedCourseAndShowsProblemScores() {
        open(CampusFeature.JUDGE)
        compose.onNodeWithText("模拟课程").performClick()
        compose.onNodeWithText("图论练习").performClick()
        compose.onNodeWithText("已提交 · 8 / 10").assertIsDisplayed()
    }

    @Test fun financeYearFilterRecalculatesPersonalIncomeTotal() {
        open(CampusFeature.FINANCE)
        compose.onNodeWithText("¥700.00", substring = false).assertIsDisplayed()
        compose.onNodeWithText("全部年份").performClick()
        compose.onNodeWithText("2026年").performClick()
        compose.onNodeWithText("¥500.00", substring = false).assertIsDisplayed()
        compose.onNodeWithText("示例酬金").assertDoesNotExist()
        capture("finance-filtered")
    }

    private fun open(feature: CampusFeature) {
        compose.setContent { MaterialTheme { CampusFeatureScreen(feature, fixture, onBack = {}) } }
    }

    private fun income(id: String, name: String, year: String, value: String): CampusIncome {
        val amount = BigDecimal(value)
        return CampusIncome(id, name, null, year, "11", null, amount, BigDecimal.ZERO, amount, "0%")
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val root = File(instrumentation.targetContext.getExternalFilesDir(null), "campus-qa")
        root.mkdirs()
        val image = instrumentation.uiAutomation.takeScreenshot()
        File(root, "$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        image.recycle()
    }
}
