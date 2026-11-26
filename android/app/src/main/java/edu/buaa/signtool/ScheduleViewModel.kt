package edu.buaa.signtool

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import edu.buaa.signtool.data.Course
import edu.buaa.signtool.data.DaySchedule
import edu.buaa.signtool.data.IClassRepository
import edu.buaa.signtool.data.LoginCaptcha
import edu.buaa.signtool.data.LoginPreferences
import edu.buaa.signtool.data.LoginResult
import edu.buaa.signtool.data.NetworkMode
import edu.buaa.signtool.data.SavedLogin
import edu.buaa.signtool.data.SemesterPreferences
import edu.buaa.signtool.data.SignPolicy
import edu.buaa.signtool.data.WeekCalendar
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

data class ScheduleUiState(
    val networkMode: NetworkMode = NetworkMode.WEB_VPN,
    val semesterBaseline: LocalDate = LocalDate.now(),
    val weekNumber: Int = 1,
    val selectedDate: LocalDate = LocalDate.now(),
    val days: List<DaySchedule> = emptyList(),
    val displayName: String = "",
    val loggedIn: Boolean = false,
    val savedLogin: SavedLogin? = null,
    val loading: Boolean = false,
    val captcha: LoginCaptcha? = null,
    val refreshingCaptcha: Boolean = false,
    val signing: Boolean = false,
    val unconfirmedSignIds: Set<String> = emptySet(),
    val scheduleError: String? = null,
    val message: String? = null,
)

class ScheduleViewModel(application: Application) : AndroidViewModel(application) {
    private val semesterPreferences = SemesterPreferences(application)
    private val loginPreferences = LoginPreferences(application)
    private var repository: IClassRepository? = null
    private var pendingLogin: PendingLogin? = null
    private var weekLoad: Job? = null
    private var signJob: Job? = null
    private val mutableState = MutableStateFlow(ScheduleUiState())
    val state: StateFlow<ScheduleUiState> = mutableState.asStateFlow()

    init {
        viewModelScope.launch {
            val baseline = semesterPreferences.semesterBaseline.first()
            val currentWeek = WeekCalendar.weekNumber(baseline, today())
            val selected = WeekCalendar.dateInWeek(baseline, currentWeek, today())
            mutableState.update {
                it.copy(semesterBaseline = baseline, weekNumber = currentWeek, selectedDate = selected)
            }
        }
        loginPreferences.load()?.let { saved ->
            mutableState.update { it.copy(savedLogin = saved, networkMode = saved.networkMode) }
        }
    }

    fun setNetworkMode(mode: NetworkMode) {
        val state = mutableState.value
        if (!state.loggedIn && !state.loading && state.networkMode != mode) {
            cancelCaptcha()
            mutableState.update { it.copy(networkMode = mode, message = null) }
        }
    }

    fun login(username: String, password: String) {
        loginInternal(username, password, remember = true)
    }

    fun loginSaved() {
        mutableState.value.savedLogin?.let { loginInternal(it.username, it.password, remember = false, networkMode = it.networkMode) }
    }

    fun clearSavedLogin() {
        loginPreferences.clear()
        mutableState.update { it.copy(savedLogin = null) }
    }

    private fun loginInternal(
        username: String,
        password: String,
        remember: Boolean,
        networkMode: NetworkMode = mutableState.value.networkMode,
    ) {
        if (mutableState.value.loading || pendingLogin != null) return
        val attempt = PendingLogin(IClassRepository(networkMode), username, password, remember, networkMode)
        pendingLogin = attempt
        runLoginStep(attempt) { attempt.client.login(username, password) }
    }

    fun submitCaptcha(code: String) {
        val attempt = pendingLogin ?: return
        if (mutableState.value.captcha?.imageBytes == null || code.isBlank()) return
        runLoginStep(attempt) { attempt.client.submitCaptcha(attempt.username, attempt.password, code) }
    }

    fun refreshCaptcha() {
        val attempt = pendingLogin ?: return
        if (mutableState.value.captcha == null) return
        runLoginStep(attempt, refreshing = true) { attempt.client.refreshCaptcha() }
    }

    fun cancelCaptcha() {
        if (mutableState.value.loading) return
        pendingLogin?.client?.logout()
        pendingLogin = null
        mutableState.update { it.copy(captcha = null, refreshingCaptcha = false, message = null) }
    }

    private fun runLoginStep(
        attempt: PendingLogin,
        refreshing: Boolean = false,
        step: suspend () -> LoginResult,
    ) {
        if (mutableState.value.loading) return
        mutableState.update {
            it.copy(loading = true, refreshingCaptcha = refreshing, message = null, networkMode = attempt.networkMode)
        }
        viewModelScope.launch {
            try {
                when (val result = step()) {
                    is LoginResult.CaptchaRequired -> mutableState.update { it.copy(captcha = result.captcha) }
                    is LoginResult.Authenticated -> {
                        if (attempt.remember) loginPreferences.save(attempt.username.trim(), attempt.password, attempt.networkMode)
                        repository = attempt.client
                        pendingLogin = null
                        val saved = loginPreferences.load()
                        mutableState.update {
                            it.copy(loggedIn = true, displayName = result.displayName, savedLogin = saved, captcha = null)
                        }
                    }
                }
            } catch (error: Exception) {
                attempt.client.logout()
                pendingLogin = null
                if (error is CancellationException) throw error
                mutableState.update { it.copy(captcha = null, message = loginErrorMessage(error, attempt.networkMode)) }
            } finally {
                mutableState.update { it.copy(loading = false, refreshingCaptcha = false) }
            }
            if (mutableState.value.loggedIn) loadCurrentWeek()
        }
    }

    fun logout() {
        signJob?.cancel()
        signJob = null
        weekLoad?.cancel()
        weekLoad = null
        repository?.logout()
        repository = null
        pendingLogin?.client?.logout()
        pendingLogin = null
        mutableState.update {
            it.copy(loggedIn = false, displayName = "", days = emptyList(), captcha = null,
                loading = false, signing = false, unconfirmedSignIds = emptySet(), scheduleError = null, message = null)
        }
    }

    fun setSemesterBaseline(date: LocalDate) {
        if (mutableState.value.signing || date == mutableState.value.semesterBaseline) return
        viewModelScope.launch {
            semesterPreferences.saveSemesterBaseline(date)
            val week = WeekCalendar.weekNumber(date, today())
            val selected = WeekCalendar.dateInWeek(date, week, today())
            mutableState.update {
                it.copy(semesterBaseline = date, weekNumber = week, selectedDate = selected, days = emptyList(), scheduleError = null)
            }
            if (mutableState.value.loggedIn) loadWeek(week)
        }
    }

    fun previousWeek() {
        navigateWeek((mutableState.value.weekNumber - 1).coerceAtLeast(1))
    }

    fun nextWeek() {
        navigateWeek((mutableState.value.weekNumber + 1).coerceAtMost(18))
    }

    fun jumpToCurrentWeek() {
        val currentDate = today()
        val week = WeekCalendar.weekNumber(mutableState.value.semesterBaseline, currentDate)
        navigateWeek(week, currentDate)
    }

    fun selectDate(date: LocalDate) {
        val state = mutableState.value
        val start = WeekCalendar.weekStart(state.semesterBaseline, state.weekNumber)
        if (date in start..start.plusDays(6)) mutableState.update { it.copy(selectedDate = date) }
    }

    fun refresh() {
        loadWeek(mutableState.value.weekNumber)
    }

    fun signCourse(course: Course) = signCourses(listOf(course))

    fun signCurrentWeek() = signCourses(mutableState.value.days.flatMap { it.courses })

    fun dismissMessage(message: String) = mutableState.update {
        if (it.message == message) it.copy(message = null) else it
    }

    fun currentServerTime(): LocalDateTime = repository?.currentServerTime()
        ?: LocalDateTime.now(ZoneId.of("Asia/Shanghai"))

    internal fun campusQueries() = repository?.campusQueries()

    private fun navigateWeek(week: Int, preferredDate: LocalDate? = null) {
        val state = mutableState.value
        if (state.signing) return
        val selected = WeekCalendar.dateInWeek(
            state.semesterBaseline, week,
            preferredDate ?: state.selectedDate.plusWeeks((week - state.weekNumber).toLong()),
        )
        if (week == state.weekNumber) {
            mutableState.update { it.copy(selectedDate = selected) }
            return
        }
        mutableState.update { it.copy(weekNumber = week, selectedDate = selected, days = emptyList(), scheduleError = null) }
        loadWeek(week)
    }

    private fun loadCurrentWeek() = loadWeek(mutableState.value.weekNumber)

    private fun loadWeek(week: Int) {
        val client = repository ?: return
        val baseline = mutableState.value.semesterBaseline
        val start = WeekCalendar.weekStart(baseline, week)
        weekLoad?.cancel()
        mutableState.update { it.copy(loading = true, scheduleError = null) }
        fun isCurrentWeek() = repository === client && mutableState.value.weekNumber == week &&
            mutableState.value.semesterBaseline == baseline
        weekLoad = viewModelScope.launch {
            try {
                val days = client.loadWeek(start)
                if (isCurrentWeek()) mutableState.update {
                    val recorded = days.flatMap { day -> day.signedScheduleIds }.toSet()
                    it.copy(days = days, loading = false, unconfirmedSignIds = it.unconfirmedSignIds - recorded)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (isCurrentWeek()) mutableState.update {
                    it.copy(loading = false, scheduleError = error.message ?: "请检查网络后重试。")
                }
            }
        }
    }

    private fun signCourses(courses: List<Course>) {
        val client = repository ?: return
        val state = mutableState.value
        if (state.signing || state.loading) return
        val now = client.currentServerTime()
        val eligible = courses.filter { course ->
            SignPolicy.isEligible(course, now) && course.scheduleIds.none { it in state.unconfirmedSignIds }
        }
        if (eligible.isEmpty()) {
            mutableState.update { it.copy(message = "当前没有可提交的签到。") }
            return
        }
        mutableState.update { it.copy(signing = true, message = null) }
        signJob = viewModelScope.launch {
            try {
                if (repository !== client) return@launch
                val result = client.sign(eligible)
                if (repository !== client) return@launch
                val details = buildList {
                    if (result.succeeded > 0) add("已确认 ${result.succeeded} 条签到")
                    if (result.alreadySigned > 0) add("${result.alreadySigned} 条已有签到记录")
                    if (result.failed > 0) add(result.message.ifBlank { "有签到未完成，请查看课表" })
                    if (result.unconfirmedIds.isNotEmpty()) {
                        if (result.unconfirmedReason.isNotBlank()) add(result.unconfirmedReason)
                        add("结果待确认，请刷新状态")
                    }
                }
                val updated = result.refreshedDays.associateBy { it.date }
                val recorded = result.refreshedDays.flatMap { it.signedScheduleIds }.toSet()
                mutableState.update {
                    it.copy(
                        days = it.days.map { day -> updated[day.date] ?: day },
                        unconfirmedSignIds = (it.unconfirmedSignIds + result.unconfirmedIds) - recorded,
                        message = details.joinToString("。").ifBlank { result.message },
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (repository === client) mutableState.update { it.copy(message = error.message ?: "签到失败，请稍后重试") }
            } finally {
                if (repository === client) mutableState.update { it.copy(signing = false) }
            }
        }
    }

    private fun today(): LocalDate = LocalDate.now(ZoneId.of("Asia/Shanghai"))

    private class PendingLogin(
        val client: IClassRepository,
        val username: String,
        val password: String,
        val remember: Boolean,
        val networkMode: NetworkMode,
    )

    private fun loginErrorMessage(error: Exception, mode: NetworkMode): String {
        val detail = error.message.orEmpty()
        val connectionProblem = listOf("timeout", "timed out", "failed to connect", "unable to resolve host")
            .any { detail.contains(it, ignoreCase = true) }
        if (connectionProblem && mode == NetworkMode.CAMPUS) {
            return "校园网 iClass 端口连接超时，请切换到 WebVPN 后重试"
        }
        if (connectionProblem) return "WebVPN 连接超时，请检查网络或稍后重试"
        return detail.ifBlank { "登录失败，请检查网络后重试" }
    }
}
