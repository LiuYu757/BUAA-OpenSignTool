package edu.buaa.signtool.data

import java.time.LocalDateTime

/** Shared by the course cards, batch controls, and the request sender. */
object SignPolicy {
    fun opensAt(course: Course): LocalDateTime? = course.begin?.minusMinutes(10)

    fun isEligible(course: Course, now: LocalDateTime): Boolean {
        if (course.signed || !course.signStatusKnown || course.scheduleIds.none(String::isNotBlank)) return false
        val start = course.begin ?: return false
        val end = course.end ?: return false
        return end > start && now >= start.minusMinutes(10) && now < end
    }
}
