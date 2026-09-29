package edu.buaa.signtool.data

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

data class Course(
    val scheduleIds: List<String>,
    val courseId: String,
    val courseNum: String,
    val name: String,
    val beginText: String,
    val endText: String,
    val classroom: String,
    val building: String,
    val floor: String,
    val teacher: String,
    val signed: Boolean,
) {
    val begin: LocalDateTime?
        get() = parseCourseTime(beginText)

    val end: LocalDateTime?
        get() = parseCourseTime(endText)

    val location: String
        get() = listOf(building, floor, classroom)
            .filter { it.isNotBlank() && it != "null" }
            .joinToString(" ")

    companion object {
        private val formatters = listOf(
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"),
        )

        fun parseCourseTime(value: String): LocalDateTime? = formatters.firstNotNullOfOrNull { formatter ->
            runCatching { LocalDateTime.parse(value, formatter) }.getOrNull()
        }
    }
}

data class DaySchedule(
    val date: java.time.LocalDate,
    val courses: List<Course>,
)

enum class NetworkMode { CAMPUS, WEB_VPN }

enum class SignChannel(val label: String) {
    ESCHOOL("教师签到"),
    APP("正常签到"),
}

data class SignResult(
    val succeeded: Int,
    val alreadySigned: Int,
    val failed: Int,
    val message: String,
)
