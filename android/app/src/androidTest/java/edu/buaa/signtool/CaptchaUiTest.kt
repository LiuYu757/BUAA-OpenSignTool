package edu.buaa.signtool

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import edu.buaa.signtool.data.LoginCaptcha
import java.io.ByteArrayOutputStream
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Uses generated test artwork and local callbacks only; no SSO credentials or requests. */
class CaptchaUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun refreshClearsOldInputAndSendsOnlyManuallyEnteredCode() {
        val initial = image("样例 A")
        val refreshed = image("样例 B")
        val challenge = mutableStateOf(LoginCaptcha(initial, 1))
        var answer: String? = null
        var refreshes = 0
        compose.setContent {
            MaterialTheme(colorScheme = lightColorScheme()) {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.systemBarsPadding().widthIn(max = 520.dp).fillMaxWidth().padding(24.dp)) {
                        CaptchaForm(challenge.value, false, false,
                            onRefresh = { refreshes++; challenge.value = LoginCaptcha(refreshed, 2) },
                            onSubmit = { answer = it }, onCancel = {},
                        )
                    }
                }
            }
        }
        compose.onNodeWithContentDescription("图形验证码图片").assertIsDisplayed()
        compose.onNodeWithText("验证并登录").assertIsNotEnabled()
        capture("captcha-light")
        compose.onNodeWithText("图形验证码").performTextInput("old")
        compose.onNodeWithText("验证并登录").assertIsEnabled()
        compose.onNodeWithText("刷新验证码").performClick()
        compose.onNodeWithText("验证并登录").assertIsNotEnabled()
        compose.onNodeWithContentDescription("图形验证码图片").assertIsDisplayed()
        compose.onNodeWithText("图形验证码").performTextInput("new")
        compose.onNodeWithText("验证并登录").performClick()
        compose.runOnIdle { assertEquals(1, refreshes); assertEquals("new", answer) }
    }

    @Test fun failedImageOffersRefreshAndReturnWithoutAllowingSubmit() {
        val challenge = mutableStateOf(LoginCaptcha(null, 1, "验证码图片加载失败，请刷新重试。"))
        val replacement = image("样例 C")
        var cancelled = false
        compose.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.systemBarsPadding().widthIn(max = 520.dp).fillMaxWidth().padding(24.dp)) {
                        CaptchaForm(challenge.value, false, false,
                            onRefresh = { challenge.value = LoginCaptcha(replacement, 2) },
                            onSubmit = { error("Missing CAPTCHA must not submit") },
                            onCancel = { cancelled = true },
                        )
                    }
                }
            }
        }
        compose.onNodeWithText("验证码图片加载失败，请刷新重试。").assertIsDisplayed()
        compose.onNodeWithText("验证并登录").assertIsNotEnabled()
        capture("captcha-retry-dark")
        compose.onNodeWithText("刷新验证码").performClick()
        compose.onNodeWithContentDescription("图形验证码图片").assertIsDisplayed()
        compose.onNodeWithText("返回账号登录").performClick()
        compose.runOnIdle { assertTrue(cancelled) }
    }

    private fun image(label: String): ByteArray {
        val bitmap = Bitmap.createBitmap(280, 90, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(android.graphics.Color.WHITE)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 38f; color = android.graphics.Color.BLACK }
        canvas.drawText(label, 35f, 58f, paint)
        return ByteArrayOutputStream().use { stream ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
            bitmap.recycle()
            stream.toByteArray()
        }
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val root = File(instrumentation.targetContext.getExternalFilesDir(null), "captcha-qa")
        root.mkdirs()
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        File(root, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
