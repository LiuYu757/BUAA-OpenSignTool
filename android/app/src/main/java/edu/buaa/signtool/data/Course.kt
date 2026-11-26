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
    val signStatusKnown: Boolean = true,
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
    val signedScheduleIds: Set<String> = courses.filter { it.signed }.flatMap { it.scheduleIds }.toSet(),
)

enum class NetworkMode { CAMPUS, WEB_VPN }

data class SignResult(
    val succeeded: Int,
    val alreadySigned: Int,
    val failed: Int,
    val message: String,
    val unconfirmedIds: Set<String> = emptySet(),
    val refreshedDays: List<DaySchedule> = emptyList(),
    val unconfirmedReason: String = "",
)
