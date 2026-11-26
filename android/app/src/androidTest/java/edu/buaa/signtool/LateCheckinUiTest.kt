package edu.buaa.signtool

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import edu.buaa.signtool.data.Course
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.time.LocalDateTime

/** Uses fixture courses only. No login, credentials, or iClass transport. */
class LateCheckinUiTest {
    @get:Rule val compose = createComposeRule()
    private val now = LocalDateTime.of(2026, 10, 30, 8, 5)
    private val course = Course(listOf("fixture"), "fixture", "001", "线性代数（示例课程）",
        "2026-10-30 08:00:00", "2026-10-30 09:35:00", "201", "教学楼", "二层", "任课教师", false)

    @Test fun inProgressCourseUsesTheSameStudentSignInAction() {
        var clicks = 0
        compose.setContent {
            MaterialTheme(colorScheme = lightColorScheme()) {
                Surface(Modifier.fillMaxSize()) {
                    Column { CourseCard(course, false, now) { clicks++ } }
                }
            }
        }
        compose.onNodeWithText("签到").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, clicks) }
        compose.onNodeWithText("正常签到").assertDoesNotExist()
        compose.onNodeWithText("教师签到").assertDoesNotExist()
        capture("late-course-light")
    }

    @Test fun uncertainResultOffersReadOnlyRefreshInsteadOfSubmittingAgain() {
        var refreshes = 0
        compose.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(Modifier.fillMaxSize()) {
                    Column {
                        CourseCard(course, false, now, unconfirmed = true, onRefreshStatus = { refreshes++ }) {
                            error("Unconfirmed attendance submitted again")
                        }
                    }
                }
            }
        }
        compose.onNodeWithText("签到结果待确认").assertExists()
        compose.onNodeWithText("签到").assertDoesNotExist()
        compose.onNodeWithText("刷新状态").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, refreshes) }
        capture("unconfirmed-dark")
    }

    @Test fun endedClassCannotSubmitAndShowsTheReason() {
        compose.setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    Column {
                        CourseCard(course, false, now.plusHours(2)) { error("Ended class submitted") }
                        SignActionsBar(listOf(course), false, now.plusHours(2)) { error("Ended batch submitted") }
                    }
                }
            }
        }
        compose.onNodeWithText("未签到").assertExists()
        compose.onNodeWithText("确认签到（0 门）").assertIsNotEnabled()
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val root = File(instrumentation.targetContext.getExternalFilesDir(null), "late-checkin-qa")
        root.mkdirs()
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        File(root, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
