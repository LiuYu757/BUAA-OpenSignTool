package edu.buaa.signtool.data

import kotlinx.coroutines.CancellationException
import java.io.IOException
import java.net.SocketTimeoutException
import java.time.LocalDate
import java.time.LocalDateTime

internal data class CheckinReply(
    val status: String,
    val message: String,
    val errorCode: String = "",
    val attendanceStatus: String = "",
) {
    /** A successful envelope can still contain an explicit attendance rejection. */
    fun rejection(): String? {
        if (message.contains("已签到") || message.contains("已经签到")) return null
        val accepted = status.isBlank() || isIClassSuccess(status)
        if (accepted && attendanceStatus != "0") return null
        val code = errorCode.ifBlank { if (accepted) "" else status }.takeIf { it.toIntOrNull() != null }
        val reason = message.ifBlank { "iClass 拒绝了签到请求" }
        return if (code == null) reason else "$reason（代码 $code）"
    }
}

/** Rechecks the student's own course records before writing and confirms any ambiguous result. */
internal class CourseSigner(
    private val now: () -> LocalDateTime,
    private val readDay: (LocalDate) -> DaySchedule,
    private val submit: (String) -> CheckinReply,
) {
    fun sign(courses: List<Course>): SignResult {
        val requested = courses.filterNot { it.signed }.groupBy { it.begin?.toLocalDate() }
        var succeeded = 0
        var alreadySigned = 0
        var failed = 0
        var errorMessage = ""
        val unconfirmed = mutableSetOf<String>()
        var unconfirmedReason = ""
        val refreshedDays = mutableListOf<DaySchedule>()

        for ((date, dayCourses) in requested) {
            val ids = dayCourses.flatMap { it.scheduleIds }.filter(String::isNotBlank).distinct()
            if (date == null || ids.isEmpty()) {
                failed += dayCourses.size
                errorMessage = "排课信息不完整，请刷新课表。"
                continue
            }

            val current = try {
                readDay(date)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                failed += ids.size
                errorMessage = (error as? IClassSessionExpired)?.message ?: "无法核对最新课表，请刷新后再签到。"
                continue
            }

            val awaitingRecord = mutableMapOf<String, String>()
            for (id in ids) {
                val course = current.courses.singleOrNull { id in it.scheduleIds }
                if (course?.signed == true) {
                    alreadySigned++
                    continue
                }
                if (course == null || !course.signStatusKnown) {
                    failed++
                    errorMessage = "排课或签到状态已变化，请刷新课表。"
                    continue
                }
                if (!SignPolicy.isEligible(course, now())) {
                    failed++
                    errorMessage = "当前不在该课程的签到时段，本次未提交。"
                    continue
                }

                val rejection = try {
                    submit(id).rejection()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (expired: IClassSessionExpired) {
                    expired.message
                } catch (error: Exception) {
                    // The write may have reached iClass before the response was lost.
                    awaitingRecord[id] = when (error) {
                        is IClassSignUnconfirmed -> error.detail
                        is SocketTimeoutException -> "等待签到响应超时"
                        is IOException -> "签到接口连接异常"
                        else -> "无法读取签到响应"
                    }
                    continue
                }
                if (rejection == null) {
                    awaitingRecord[id] = ""
                } else {
                    failed++
                    errorMessage = rejection
                }
            }

            // Only a fresh course record can confirm a submission or clear a write timeout.
            val updated = if (awaitingRecord.isEmpty()) current else readAfterSubmit(date) ?: current
            refreshedDays += updated
            succeeded += awaitingRecord.keys.count { it in updated.signedScheduleIds }
            val unverified = awaitingRecord.keys - updated.signedScheduleIds
            unconfirmed += unverified
            if (unconfirmedReason.isBlank()) {
                unconfirmedReason = unverified.firstNotNullOfOrNull { id ->
                    awaitingRecord[id]?.takeIf(String::isNotBlank)
                }.orEmpty()
            }
        }

        val message = errorMessage.ifBlank {
            if (courses.isEmpty()) "当前没有可签到的课程。" else ""
        }
        return SignResult(succeeded, alreadySigned, failed, message, unconfirmed, refreshedDays, unconfirmedReason)
    }

    private fun readAfterSubmit(date: LocalDate): DaySchedule? = try {
        readDay(date)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        null
    }
}

internal class IClassSessionExpired : IllegalStateException("iClass 会话已失效，请重新登录。")

internal class IClassSignUnconfirmed(val detail: String) : IllegalStateException(detail)
