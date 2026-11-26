package edu.buaa.signtool

import android.graphics.Bitmap
import android.util.Log
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasText
import androidx.test.platform.app.InstrumentationRegistry
import edu.buaa.signtool.data.Course
import edu.buaa.signtool.data.DaySchedule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** Simulated schedules only: no login, network requests, or real attendance submission. */
class ScheduleLayoutTest {
    @get:Rule val compose = createComposeRule()
    private val start = LocalDate.of(2026, 11, 9)
    private val friday = start.plusDays(4)
    private val saturday = friday.plusDays(1)
    private val now = friday.atTime(12, 4)
    private val names = listOf("矩阵理论与应用", "先进制造与数字化工程实践", "科技写作与学术报告", "工程伦理", "计算机视觉", "智能系统设计")
    private val timestamp = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    @Test fun courseListScrollsPastNavigationAndCanReachLastCourse() {
        show()
        compose.onNodeWithText("今天 · 11月13日").assertIsDisplayed()
        val last = compose.onNodeWithText(names.last())
        val needsScroll = !last.isDisplayed()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val density = instrumentation.targetContext.resources.displayMetrics.density
        val height = compose.onNodeWithTag("schedule-scroll").fetchSemanticsNode().boundsInRoot.height / density
        Log.i("BUAA_LAYOUT_QA", "agenda_scroll_dp=$height")
        capture("agenda-light")
        compose.onNodeWithTag("schedule-scroll").performScrollToNode(hasText(names.last()))
        last.assertIsDisplayed()
        compose.onNodeWithText("我的课表").assertIsDisplayed()
        if (needsScroll) compose.onNodeWithContentDescription("上一周").assertIsNotDisplayed()
        capture("agenda-scrolled")
        compose.onNodeWithText("今天", substring = false).performClick()
        compose.onNodeWithText("今天 · 11月13日").assertIsDisplayed()
    }

    @Test fun tapDateThenTodaySwitchesContentAndResetsListPosition() {
        show()
        val saturdayTile = compose.onNodeWithContentDescription("11月14日 星期六，1 门课")
        if (!saturdayTile.isDisplayed()) {
            compose.onNodeWithTag("schedule-date-strip").performScrollToIndex(5)
        }
        saturdayTile.performClick()
        compose.onNodeWithText("11月14日").assertIsDisplayed()
        compose.onNodeWithText("周六研讨课").assertIsDisplayed()
        compose.onNodeWithText("今天", substring = false).performClick()
        compose.onNodeWithText("今天 · 11月13日").assertIsDisplayed()
        compose.onNodeWithText(names.first()).assertIsDisplayed()
    }

    @Test fun overviewOpensSeparatelyAndSelectingDayReturnsToItsCourses() {
        show()
        compose.onNodeWithContentDescription("查看周总览").performClick()
        compose.onNodeWithText("周课表").assertIsDisplayed()
        compose.onNodeWithTag("schedule-scroll").performScrollToNode(hasText("星期六 11/14"))
        compose.onNodeWithText("星期六 11/14").performClick()
        compose.onNodeWithText("我的课表").assertIsDisplayed()
        compose.onNodeWithText("周六研讨课").assertIsDisplayed()
    }

    @Test fun batchActionsOnlyAppearOnRequestAndRequireConfirmation() {
        var submissions = 0
        show(dark = true, onSignWeek = { submissions++ })
        compose.onNodeWithText("确认签到（1 门）").assertDoesNotExist()
        capture("agenda-dark")
        compose.onNodeWithText("批量签到", substring = false).performClick()
        compose.runOnIdle { assertEquals(0, submissions) }
        compose.onNodeWithText("确认签到（1 门）").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, submissions) }
    }

    @Test fun singleCourseActionShowsTheCourseBeforeSubmitting() {
        var submitted: Course? = null
        show(onSignCourse = { submitted = it })
        compose.onNodeWithTag("schedule-scroll").performScrollToNode(hasText("签到"))
        compose.onNodeWithText("签到").performClick()
        compose.onNodeWithText("确认课堂签到").assertIsDisplayed()
        compose.runOnIdle { assertEquals(null, submitted) }
        compose.onNodeWithText("确认签到").performClick()
        compose.runOnIdle { assertEquals(names[2], submitted?.name) }
    }

    @Test fun failedScheduleShowsRetryInsteadOfAnEmptyDay() {
        var retries = 0
        show(error = "网络连接超时，请稍后重试。", onRefresh = { retries++ })
        compose.onNodeWithText("课表加载失败").assertIsDisplayed()
        compose.onNodeWithText("这一天没有课程").assertDoesNotExist()
        compose.onNodeWithText("重新加载").performClick()
        compose.runOnIdle { assertEquals(1, retries) }
    }

    private fun show(
        dark: Boolean = false,
        error: String? = null,
        onRefresh: () -> Unit = {},
        onSignWeek: () -> Unit = {},
        onSignCourse: (Course) -> Unit = {},
    ) {
        val courses = names.mapIndexed { index, name ->
            val begin = friday.atTime(8 + index * 2, 0)
            sample(name, begin, index, index == 0)
        }
        val days = List(7) { index ->
            val date = start.plusDays(index.toLong())
            DaySchedule(date, when (date) {
                friday -> courses
                saturday -> listOf(sample("周六研讨课", saturday.atTime(10, 0), 7, false))
                else -> emptyList()
            })
        }
        var state by mutableStateOf(ScheduleUiState(loggedIn = true, semesterBaseline = start, selectedDate = friday, days = days, scheduleError = error))
        compose.setContent {
            val colors = if (dark) darkColorScheme(primary = Color(0xfff5f5f5), onPrimary = Color(0xff111111), surface = Color(0xff171717), onSurface = Color(0xfff5f5f5), surfaceVariant = Color(0xff292929))
                else lightColorScheme(primary = Color(0xff111111), onPrimary = Color.White, surface = Color.White, surfaceVariant = Color(0xfff4f4f4))
            MaterialTheme(colorScheme = colors) {
                ScheduleContent(state, now, onSelectDate = { state = state.copy(selectedDate = it) },
                    onToday = { state = state.copy(selectedDate = friday) }, onRefresh = onRefresh,
                    onSignWeek = onSignWeek, onSignCourse = onSignCourse)
            }
        }
    }

    private fun sample(name: String, begin: LocalDateTime, index: Int, signed: Boolean) = Course(
        listOf("agenda-$index"), "c$index", "$index", name, begin.format(timestamp), begin.plusMinutes(95).format(timestamp),
        "教学楼 301", "沙河校区", "", "示例教师", signed,
    )

    private fun capture(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val root = File(instrumentation.targetContext.getExternalFilesDir(null), "agenda-qa")
        root.mkdirs()
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        File(root, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
