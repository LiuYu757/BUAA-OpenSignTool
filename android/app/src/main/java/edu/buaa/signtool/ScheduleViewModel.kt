package edu.buaa.signtool

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import edu.buaa.signtool.data.Course
import edu.buaa.signtool.data.DaySchedule
import edu.buaa.signtool.data.IClassRepository
import edu.buaa.signtool.data.LoginPreferences
import edu.buaa.signtool.data.NetworkMode
import edu.buaa.signtool.data.SavedLogin
import edu.buaa.signtool.data.SemesterPreferences
import edu.buaa.signtool.data.SignChannel
import edu.buaa.signtool.data.WeekCalendar
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
    val signing: Boolean = false,
    val message: String? = null,
)

class ScheduleViewModel(application: Application) : AndroidViewModel(application) {
    private val semesterPreferences = SemesterPreferences(application)
    private val loginPreferences = LoginPreferences(application)
    private var repository: IClassRepository? = null
    private val mutableState = MutableStateFlow(ScheduleUiState())
    val state: StateFlow<ScheduleUiState> = mutableState.asStateFlow()

    init {
        viewModelScope.launch {
            val baseline = semesterPreferences.semesterBaseline.first()
            val currentWeek = WeekCalendar.weekNumber(baseline, today())
            val start = WeekCalendar.weekStart(baseline, currentWeek)
            val selected = if (today() in start..start.plusDays(6)) today() else start
            mutableState.update {
                it.copy(semesterBaseline = baseline, weekNumber = currentWeek, selectedDate = selected)
            }
        }
        loginPreferences.load()?.let { saved ->
            mutableState.update { it.copy(savedLogin = saved, networkMode = saved.networkMode) }
        }
    }

    fun setNetworkMode(mode: NetworkMode) {
        if (!mutableState.value.loggedIn) mutableState.update { it.copy(networkMode = mode) }
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
        if (mutableState.value.loading) return
        viewModelScope.launch {
            mutableState.update { it.copy(loading = true, message = null) }
            try {
                val client = IClassRepository(networkMode)
                val name = client.login(username, password)
                repository = client
                if (remember) loginPreferences.save(username.trim(), password, networkMode)
                val saved = loginPreferences.load()
                mutableState.update { it.copy(loggedIn = true, displayName = name, networkMode = networkMode, savedLogin = saved) }
                loadCurrentWeek()
            } catch (error: Exception) {
                mutableState.update { it.copy(message = loginErrorMessage(error, it.networkMode)) }
            } finally {
                mutableState.update { it.copy(loading = false) }
            }
        }
    }

    fun logout() {
        repository?.logout()
        repository = null
        mutableState.update {
            it.copy(loggedIn = false, displayName = "", days = emptyList(), message = null)
        }
    }

    fun setSemesterBaseline(date: LocalDate) {
        viewModelScope.launch {
            semesterPreferences.saveSemesterBaseline(date)
            val week = WeekCalendar.weekNumber(date, today())
            val start = WeekCalendar.weekStart(date, week)
            val selected = if (today() in start..start.plusDays(6)) today() else start
            mutableState.update {
                it.copy(semesterBaseline = date, weekNumber = week, selectedDate = selected)
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
        val week = WeekCalendar.weekNumber(mutableState.value.semesterBaseline, today())
        navigateWeek(week)
    }

    fun selectDate(date: LocalDate) {
        val state = mutableState.value
        val start = WeekCalendar.weekStart(state.semesterBaseline, state.weekNumber)
        if (date in start..start.plusDays(6)) mutableState.update { it.copy(selectedDate = date) }
    }

    fun refresh() {
        loadWeek(mutableState.value.weekNumber)
    }

    fun signCourse(course: Course, channel: SignChannel) = signCourses(listOf(course), channel)

    fun signCurrentWeek(channel: SignChannel) = signCourses(mutableState.value.days.flatMap { it.courses }, channel)

    fun dismissMessage() = mutableState.update { it.copy(message = null) }

    fun currentServerTime(): LocalDateTime = repository?.currentServerTime()
        ?: LocalDateTime.now(ZoneId.of("Asia/Shanghai"))

    private fun navigateWeek(week: Int) {
        val state = mutableState.value
        if (week == state.weekNumber) return
        val start = WeekCalendar.weekStart(state.semesterBaseline, week)
        mutableState.update { it.copy(weekNumber = week, selectedDate = start) }
        loadWeek(week)
    }

    private fun loadCurrentWeek() = loadWeek(mutableState.value.weekNumber)

    private fun loadWeek(week: Int) {
        val client = repository ?: return
        val start = WeekCalendar.weekStart(mutableState.value.semesterBaseline, week)
        viewModelScope.launch {
            mutableState.update { it.copy(loading = true, message = null) }
            try {
                val days = client.loadWeek(start)
                mutableState.update { it.copy(days = days) }
            } catch (error: Exception) {
                mutableState.update { it.copy(message = error.message ?: "课表加载失败，请检查网络") }
            } finally {
                mutableState.update { it.copy(loading = false) }
            }
        }
    }

    private fun signCourses(courses: List<Course>, channel: SignChannel) {
        val client = repository ?: return
        if (mutableState.value.signing) return
        viewModelScope.launch {
            mutableState.update { it.copy(signing = true, message = null) }
            try {
                val result = client.sign(courses, channel)
                if (result.succeeded > 0 || result.alreadySigned > 0) {
                    // Let the server be the source of truth; a batch can span a class start
                    // boundary, so never optimistically mark every requested course as signed.
                    val week = mutableState.value.weekNumber
                    runCatching {
                        client.loadWeek(WeekCalendar.weekStart(mutableState.value.semesterBaseline, week))
                    }.onSuccess { days -> mutableState.update { it.copy(days = days) } }
                }
                val detail = buildString {
                    if (result.succeeded > 0) append("签到成功 ${result.succeeded} 门")
                    if (result.alreadySigned > 0) {
                        if (isNotEmpty()) append("，")
                        append("已签到 ${result.alreadySigned} 门")
                    }
                    if (result.failed > 0) {
                        if (isNotEmpty()) append("；")
                        append(result.message.ifBlank { "部分课程签到失败" })
                    }
                    if (isEmpty()) append(result.message)
                }
                mutableState.update { it.copy(message = "${channel.label}：$detail") }
            } catch (error: Exception) {
                mutableState.update { it.copy(message = error.message ?: "签到失败，请稍后重试") }
            } finally {
                mutableState.update { it.copy(signing = false) }
            }
        }
    }

    private fun today(): LocalDate = LocalDate.now(ZoneId.of("Asia/Shanghai"))

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
