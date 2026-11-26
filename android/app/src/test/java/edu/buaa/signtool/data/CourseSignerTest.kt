package edu.buaa.signtool.data

import java.net.SocketTimeoutException
import java.time.LocalDate
import java.time.LocalDateTime
import org.junit.Assert.*
import org.junit.Test

class CourseSignerTest {
    private val start = LocalDateTime.of(2026, 11, 20, 8, 0)
    private val end = start.plusMinutes(95)
    private val date = start.toLocalDate()
    private val course = Course(
        scheduleIds = listOf("one"), courseId = "test-course", courseNum = "001", name = "测试课程",
        beginText = "2026-11-20 08:00:00", endText = "2026-11-20 09:35:00",
        classroom = "201", building = "", floor = "", teacher = "", signed = false,
    )

    @Test fun oneWindowCoversBeforeAndDuringClass() {
        assertFalse(SignPolicy.isEligible(course, start.minusMinutes(10).minusNanos(1)))
        assertTrue(SignPolicy.isEligible(course, start.minusMinutes(10)))
        assertTrue(SignPolicy.isEligible(course, start))
        assertTrue(SignPolicy.isEligible(course, end.minusNanos(1)))
        assertFalse(SignPolicy.isEligible(course, end))
        assertFalse(SignPolicy.isEligible(course, start.plusDays(1)))
    }

    @Test fun incompleteOrAlreadySignedRecordsDoNotOfferSignIn() {
        val unavailable = listOf(
            course.copy(signed = true),
            course.copy(signStatusKnown = false),
            course.copy(scheduleIds = emptyList()),
            course.copy(beginText = "invalid"),
            course.copy(endText = ""),
            course.copy(endText = "2026-11-20 07:59:00"),
        )
        assertTrue(unavailable.none { SignPolicy.isEligible(it, start) })
    }

    @Test fun submissionUsesFreshCourseThenConfirmsReadback() {
        val calls = mutableListOf<String>()
        var reads = 0
        val signer = signer(
            read = { day ->
                assertEquals(date, day)
                DaySchedule(date, listOf(course.copy(signed = ++reads == 2)))
            },
            submit = { id ->
                calls += id
                CheckinReply("0", "")
            },
        )
        val result = signer.sign(listOf(course, course))
        assertEquals(listOf("one"), calls)
        assertEquals(2, reads)
        assertEquals(1, result.succeeded)
        assertTrue(result.unconfirmedIds.isEmpty())
        assertTrue(result.refreshedDays.single().courses.single().signed)
    }

    @Test fun freshScheduleSkipsAlreadySignedPartOfMergedCourse() {
        val other = course.copy(scheduleIds = listOf("two"))
        val merged = course.copy(scheduleIds = listOf("one", "two"))
        var reads = 0
        val signer = signer(
            read = {
                DaySchedule(date, listOf(course.copy(signed = true), other.copy(signed = ++reads == 2)))
            },
            submit = { id ->
                assertEquals("two", id)
                CheckinReply("0", "")
            },
        )
        val result = signer.sign(listOf(merged))
        assertEquals(1, result.alreadySigned)
        assertEquals(1, result.succeeded)
        assertEquals(setOf("one", "two"), result.refreshedDays.single().signedScheduleIds)
    }

    @Test fun missingOrUnknownFreshRecordNeverSubmits() {
        for (fresh in listOf(emptyList(), listOf(course.copy(signStatusKnown = false)))) {
            val result = signer(
                read = { DaySchedule(date, fresh) },
                submit = { error("Unverified course submitted") },
            ).sign(listOf(course))
            assertEquals(1, result.failed)
            assertTrue(result.message.contains("刷新课表"))
        }
    }

    @Test fun unavailableFreshScheduleReportsFailureBeforeWrite() {
        val result = signer(
            read = { throw SocketTimeoutException() },
            submit = { error("No fresh schedule") },
        ).sign(listOf(course))
        assertEquals(1, result.failed)
        assertTrue(result.message.contains("无法核对最新课表"))
        assertTrue(result.unconfirmedIds.isEmpty())
    }

    @Test fun crossingEndWhileLoadingFreshCourseClosesTheWindow() {
        var now = end.minusSeconds(1)
        val result = signer(
            now = { now },
            read = {
                now = end
                DaySchedule(date, listOf(course))
            },
            submit = { error("Window closed before submit") },
        ).sign(listOf(course))
        assertEquals(1, result.failed)
        assertTrue(result.message.contains("本次未提交"))
    }

    @Test fun successfulEnvelopeWithoutRecordStaysUnconfirmed() {
        val result = signer(submit = { CheckinReply("200", "签到成功", attendanceStatus = "1") }).sign(listOf(course))
        assertEquals(0, result.succeeded)
        assertEquals(0, result.failed)
        assertEquals(setOf("one"), result.unconfirmedIds)
    }

    @Test fun attendanceRejectionInsideSuccessfulEnvelopeKeepsItsErrorCode() {
        val result = signer(
            submit = { CheckinReply("success", "签到失败！", "100", attendanceStatus = "0") },
        ).sign(listOf(course))
        assertEquals(1, result.failed)
        assertEquals("签到失败！（代码 100）", result.message)
        assertTrue(result.unconfirmedIds.isEmpty())
    }

    @Test fun postTimeoutChecksRecordWithoutReplayingWrite() {
        var writes = 0
        val result = signer(
            read = { DaySchedule(date, listOf(course.copy(signed = writes > 0))) },
            submit = { writes++; throw SocketTimeoutException() },
        ).sign(listOf(course))
        assertEquals(1, writes)
        assertEquals(1, result.succeeded)
        assertTrue(result.unconfirmedIds.isEmpty())
    }

    @Test fun httpErrorRemainsUnconfirmedOnlyWithoutAnAttendanceRecord() {
        for (recorded in listOf(false, true)) {
            var writes = 0
            var reads = 0
            val message = "签到接口返回 HTTP 502"
            val result = signer(
                read = { DaySchedule(date, listOf(course.copy(signed = ++reads == 2 && recorded))) },
                submit = { writes++; throw IClassSignUnconfirmed(message) },
            ).sign(listOf(course))

            assertEquals(1, writes)
            assertEquals(2, reads)
            assertEquals(if (recorded) 1 else 0, result.succeeded)
            assertEquals(0, result.failed)
            assertEquals(if (recorded) emptySet<String>() else setOf("one"), result.unconfirmedIds)
            assertEquals(if (recorded) "" else message, result.unconfirmedReason)
        }
    }

    @Test fun sessionExpiryDuringReadbackKeepsOutcomeUnconfirmed() {
        var writes = 0
        val result = signer(
            read = { if (writes > 0) throw IClassSessionExpired() else DaySchedule(date, listOf(course)) },
            submit = { writes++; CheckinReply("0", "") },
        ).sign(listOf(course))
        assertEquals(1, writes)
        assertEquals(0, result.succeeded)
        assertEquals(setOf("one"), result.unconfirmedIds)
    }

    private fun signer(
        now: () -> LocalDateTime = { start.plusMinutes(4) },
        read: (LocalDate) -> DaySchedule = { DaySchedule(it, listOf(course)) },
        submit: (String) -> CheckinReply,
    ) = CourseSigner(now, read, submit)
}
