package edu.buaa.signtool.data

import java.time.LocalDate
import java.time.temporal.ChronoUnit

object WeekCalendar {
    fun weekNumber(baseline: LocalDate, date: LocalDate): Int =
        (Math.floorDiv(ChronoUnit.DAYS.between(baseline, date), 7L) + 1).toInt().coerceIn(1, 18)

    fun weekStart(baseline: LocalDate, weekNumber: Int): LocalDate =
        baseline.plusWeeks((weekNumber.coerceIn(1, 18) - 1).toLong())

    fun dateInWeek(baseline: LocalDate, weekNumber: Int, preferredDate: LocalDate): LocalDate {
        val start = weekStart(baseline, weekNumber)
        return if (preferredDate in start..start.plusDays(6)) preferredDate else start
    }
}
