package edu.buaa.signtool.data

import java.io.IOException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SsoLoginFlowTest {
    private val tunnel = "https://d.buaa.edu.cn/https/0123456789abcdef0123456789abcdef"
    private val loginUrl = "$tunnel/login?service=https%3A%2F%2Fd.buaa.edu.cn%2Flogin"
    private val success = SsoPage(200, "https://d.buaa.edu.cn/", "<html>WebVPN portal</html>")

    @Test fun placeholderDoesNotInterruptNormalLogin() {
        val client = Fixture(form())
        assertNull(client.flow.begin(loginUrl, " student ", "secret"))
        assertEquals(1, client.posts.size)
        val post = client.posts.single()
        assertEquals(loginUrl, post.url)
        assertEquals("student", post.fields["username"])
        assertEquals("first", post.fields["execution"])
        assertFalse("captcha" in post.fields)
        assertTrue(client.images.isEmpty())
    }

    @Test fun challengeOnFirstPageWaitsForUserAndRefreshReusesForm() {
        val client = Fixture(form(challenge = "captcha-one"))
        val first = checkNotNull(client.flow.begin(loginUrl, "student", "secret"))
        assertTrue(client.posts.isEmpty())
        assertArrayEquals(byteArrayOf(1), first.imageBytes)
        assertEquals("$tunnel/captcha?captchaId=captcha-one&t=500", client.images.single().first)

        val refreshed = client.flow.refresh()
        assertArrayEquals(byteArrayOf(2), refreshed.imageBytes)
        assertTrue(refreshed.revision > first.revision)
        assertEquals("$tunnel/captcha?captchaId=captcha-one&t=501", client.images.last().first)
        assertEquals(loginUrl, client.images.last().second)
        assertEquals(1, client.gets)
        assertTrue(client.posts.isEmpty())

        assertNull(client.flow.answer("student", "secret", " AbC4 "))
        val post = client.posts.single()
        assertEquals("abc4", post.fields["captcha"])
        assertEquals("first", post.fields["execution"])
        assertEquals(loginUrl, post.referer)
        assertThrows(IllegalStateException::class.java) { client.flow.refresh() }
    }

    @Test fun postCanIntroduceCaptchaAndRejectionReplacesExecutionToken() {
        val client = Fixture(form(), listOf(
            form(execution = "second", challenge = "captcha-two", code = 401),
            form(execution = "third", challenge = "captcha-three", code = 400),
            success,
        ))
        assertArrayEquals(byteArrayOf(1), client.flow.begin(loginUrl, "student", "secret")?.imageBytes)
        val retry = checkNotNull(client.flow.answer("student", "secret", "old1"))
        assertTrue(retry.message.orEmpty().contains("新验证码"))
        assertTrue(client.images.last().first.contains("captchaId=captcha-three"))
        assertNull(client.flow.answer("student", "secret", "new2"))
        assertEquals(listOf("first", "second", "third"), client.posts.map { it.fields["execution"] })
        assertEquals(listOf(null, "old1", "new2"), client.posts.map { it.fields["captcha"] })
        assertEquals(1, client.gets)
    }

    @Test fun imageFailureKeepsChallengeAvailableForRefresh() {
        val client = Fixture(form(challenge = "captcha-one"))
        client.failImage = true
        val failed = checkNotNull(client.flow.begin(loginUrl, "student", "secret"))
        assertNull(failed.imageBytes)
        assertTrue(failed.message.orEmpty().contains("刷新"))
        assertThrows(IllegalArgumentException::class.java) { client.flow.answer("student", "secret", "code") }
        assertTrue(client.posts.isEmpty())
        client.failImage = false
        assertArrayEquals(byteArrayOf(2), client.flow.refresh().imageBytes)
        assertNull(client.flow.answer("student", "secret", "code"))
        assertEquals("first", client.posts.single().fields["execution"])
    }

    @Test fun acceptsExplicitImageAndFieldAndUpdatesOnlyCacheBuster() {
        val page = form().copy(html = form().html.replace(
            "<div id='captchaDiv'></div>",
            "<div id='captchaDiv'><img src='/captcha?captchaId=abc&amp;t=123&amp;lang=zh'></div>" +
                "<input name='validateCode'>",
        ))
        val client = Fixture(page)
        client.flow.begin(loginUrl, "student", "secret")
        assertEquals("$tunnel/captcha?captchaId=abc&lang=zh&t=500", client.images.single().first)
        client.flow.answer("student", "secret", "Z1x2")
        assertEquals("z1x2", client.posts.single().fields["validateCode"])
        assertFalse("captcha" in client.posts.single().fields)
    }

    @Test fun keepsAlreadyRewrittenImagePathAndPreservesHiddenInputs() {
        val html = form().html.replace(
            "<div id='captchaDiv'></div>",
            "<div id='captchaDiv'><img src='${tunnel.removePrefix("https://d.buaa.edu.cn")}/captcha?captchaId=abc'></div>" +
                "<input type='hidden' name='csrf' value='fixture-csrf'>",
        )
        val client = Fixture(form().copy(html = html))
        client.flow.begin(loginUrl, "student", "secret")
        assertEquals("$tunnel/captcha?captchaId=abc&t=500", client.images.single().first)
        client.flow.answer("student", "secret", "code")
        assertEquals("fixture-csrf", client.posts.single().fields["csrf"])
    }

    @Test fun directCampusUsesItsOwnOrigin() {
        val direct = "https://sso.buaa.edu.cn/login?service=fixture"
        val client = Fixture(form(challenge = "direct").copy(url = direct))
        client.flow.begin(direct, "student", "secret")
        assertEquals("https://sso.buaa.edu.cn/captcha?captchaId=direct&t=500", client.images.single().first)
        client.flow.answer("student", "secret", "code")
        assertEquals(direct, client.posts.single().url)
    }

    @Test fun rejectsOffOriginFormAndImageBeforeSendingData() {
        val untrusted = listOf(
            form().html.replace("action='login'", "action='https://example.test/login'"),
            form().html.replace("<div id='captchaDiv'></div>", "<div id='captchaDiv'><img src='//example.test/captcha'></div>"),
            form().html.replace("action='login'", "action='https://sso.buaa.edu.cn/login'"),
        )
        untrusted.forEach { html ->
            val client = Fixture(form().copy(html = html))
            assertThrows(IllegalStateException::class.java) { client.flow.begin(loginUrl, "student", "secret") }
            assertTrue(client.posts.isEmpty())
            assertTrue(client.images.isEmpty())
        }
    }

    @Test fun riskPageContinuationStillUsesReturnedExecutionToken() {
        val risk = SsoPage(200, "$tunnel/risk", """
            <form id='continueForm' action='/login'>
              <input value='risk-token' name='execution' type='hidden'>
            </form>
        """.trimIndent())
        val client = Fixture(form(), listOf(risk, success))
        assertNull(client.flow.begin(loginUrl, "student", "secret"))
        assertEquals("$tunnel/login", client.posts.last().url)
        assertEquals(mapOf("execution" to "risk-token", "_eventId" to "ignoreAndContinue"), client.posts.last().fields)
    }

    @Test fun ordinaryCredentialErrorIsNotReportedAsChallenge() {
        val client = Fixture(form(), listOf(form(code = 401)))
        val error = assertThrows(IllegalStateException::class.java) { client.flow.begin(loginUrl, "student", "secret") }
        assertTrue(error.message.orEmpty().contains("核对账号和密码"))
        assertTrue(client.images.isEmpty())
    }

    @Test fun cancellationDiscardsThePendingTokenAndCannotSubmitItLater() {
        val client = Fixture(form(challenge = "cancelled"))
        client.flow.begin(loginUrl, "student", "secret")
        client.flow.clear()
        assertThrows(IllegalStateException::class.java) { client.flow.answer("student", "secret", "code") }
        assertTrue(client.posts.isEmpty())
    }

    private fun form(execution: String = "first", challenge: String? = null, code: Int = 200): SsoPage {
        // The ordinary WebVPN page has an empty captcha placeholder; an active
        // challenge adds the server-provided ID used by the mobile login script.
        val captcha = if (challenge == null) "<div id='captchaDiv'></div>" else
            "<div id='captchaDiv'><input name='captcha' type='text'></div>"
        val config = challenge?.let { "<script>config.captcha = {id: '$it'};</script>" }.orEmpty()
        return SsoPage(code, loginUrl, """
            <form method='post' id='loginForm' action='login'>
              <input type='text' name='username'><input name='password' type='password'>
              <div id='captchaParent' style='display:none'>$captcha</div>
              <input name='submit' type='submit' value='LOGIN'>
              <input value='$execution' name='execution'><input name='_eventId' value='submit'>
            </form>$config
        """.trimIndent())
    }

    private data class Post(val url: String, val fields: Map<String, String>, val referer: String)

    private inner class Fixture(initial: SsoPage, replies: List<SsoPage> = listOf(success)) {
        var gets = 0
        var failImage = false
        val posts = mutableListOf<Post>()
        val images = mutableListOf<Pair<String, String>>()
        private val responses = ArrayDeque(replies)
        val flow = SsoLoginFlow(
            getPage = { gets++; initial },
            postForm = { url, fields, referer -> posts += Post(url, fields, referer); responses.removeFirst() },
            getImage = { url, referer ->
                images += url to referer
                if (failImage) throw IOException("fixture image failure")
                byteArrayOf(images.size.toByte())
            },
            clockMillis = { 500 },
        )
    }
}
