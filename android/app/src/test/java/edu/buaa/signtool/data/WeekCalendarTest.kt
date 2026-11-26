package edu.buaa.signtool.data

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class WeekCalendarTest {
    private val baseline = LocalDate.of(2026, 9, 7)

    @Test fun todayRemainsSelectedWhenItIsAlreadyInTheDisplayedWeek() {
        val friday = LocalDate.of(2026, 11, 13)
        val week = WeekCalendar.weekNumber(baseline, friday)
        assertEquals(friday, WeekCalendar.dateInWeek(baseline, week, friday))
    }

    @Test fun weekNavigationKeepsTheSameDayOfWeek() {
        val friday = LocalDate.of(2026, 11, 13)
        val week = WeekCalendar.weekNumber(baseline, friday)
        assertEquals(friday.plusWeeks(1), WeekCalendar.dateInWeek(baseline, week + 1, friday.plusWeeks(1)))
    }

    @Test fun datesOutsideTheDisplayedWeekFallBackToItsFirstDay() {
        assertEquals(baseline, WeekCalendar.dateInWeek(baseline, 1, baseline.minusDays(1)))
        assertEquals(baseline.plusWeeks(17), WeekCalendar.dateInWeek(baseline, 18, baseline.plusWeeks(20)))
    }
}
