package edu.buaa.signtool

import android.app.DatePickerDialog
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import edu.buaa.signtool.data.Course
import edu.buaa.signtool.data.DaySchedule
import edu.buaa.signtool.data.NetworkMode
import edu.buaa.signtool.data.SignChannel
import edu.buaa.signtool.data.WeekCalendar
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val palette = if (isSystemInDarkTheme()) darkColorScheme(
                primary = Color(0xFFF5F5F5),
                onPrimary = Color(0xFF111111),
                background = Color(0xFF111111),
                surface = Color(0xFF171717),
                onSurface = Color(0xFFF5F5F5),
                surfaceVariant = Color(0xFF292929),
                onSurfaceVariant = Color(0xFFBDBDBD),
                outline = Color(0xFF888888),
                outlineVariant = Color(0xFF3A3A3A),
                secondary = Color(0xFF63D99B),
                onSecondary = Color(0xFF10251A),
                error = Color(0xFFFFB4AB),
            ) else lightColorScheme(
                primary = Color(0xFF111111),
                onPrimary = Color.White,
                background = Color.White,
                surface = Color.White,
                onSurface = Color(0xFF111111),
                surfaceVariant = Color(0xFFF4F4F4),
                onSurfaceVariant = Color(0xFF646464),
                outline = Color(0xFF747474),
                outlineVariant = Color(0xFFE7E7E7),
                secondary = Color(0xFF16834A),
                onSecondary = Color.White,
                error = Color(0xFFB3261E),
            )
            MaterialTheme(colorScheme = palette) {
                val scheduleViewModel: ScheduleViewModel = viewModel()
                val state by scheduleViewModel.state.collectAsState()
                if (state.loggedIn) {
                    ScheduleScreen(state, scheduleViewModel)
                } else {
                    LoginScreen(state, scheduleViewModel)
                }
            }
        }
    }
}

@Composable
private fun LoginScreen(state: ScheduleUiState, model: ScheduleViewModel) {
    val colors = MaterialTheme.colorScheme
    val Ink = colors.onSurface
    val Muted = colors.onSurfaceVariant
    val Paper = colors.surface
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    val context = LocalContext.current
    Scaffold(containerColor = Paper) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).imePadding(), contentAlignment = Alignment.Center) {
          Column(
            modifier = Modifier.widthIn(max = 520.dp).fillMaxWidth().fillMaxSize()
                .verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.Center,
          ) {
            Text("BUAA", fontSize = 13.sp, color = Muted, letterSpacing = 3.sp)
            Spacer(Modifier.height(8.dp))
            Text("课程签到", fontSize = 32.sp, fontWeight = FontWeight.SemiBold, color = Ink)
            Spacer(Modifier.height(8.dp))
            Text("使用统一身份认证登录 iClass", color = Muted, fontSize = 15.sp)
            Spacer(Modifier.height(28.dp))
            Text("连接方式", fontSize = 13.sp, color = Muted)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ModeChoice("校园网", state.networkMode == NetworkMode.CAMPUS) { model.setNetworkMode(NetworkMode.CAMPUS) }
                ModeChoice("WebVPN", state.networkMode == NetworkMode.WEB_VPN) { model.setNetworkMode(NetworkMode.WEB_VPN) }
            }
            if (state.savedLogin != null) {
                Spacer(Modifier.height(20.dp))
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    color = colors.surfaceVariant,
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text("已记住登录账号", fontSize = 13.sp, color = Muted)
                        Text(state.savedLogin.username, fontSize = 17.sp, fontWeight = FontWeight.Medium, color = Ink, modifier = Modifier.padding(top = 4.dp))
                        Text("密码使用系统密钥加密保存在本机", fontSize = 12.sp, color = Muted, modifier = Modifier.padding(top = 4.dp))
                        Button(
                            onClick = model::loginSaved,
                            enabled = !state.loading,
                            modifier = Modifier.fillMaxWidth().padding(top = 12.dp).height(48.dp),
                            shape = RoundedCornerShape(8.dp),
                        ) {
                            if (state.loading) CircularProgressIndicator(Modifier.size(19.dp), color = Paper, strokeWidth = 2.dp)
                            else Text("一键登录")
                        }
                        TextButton(onClick = model::clearSavedLogin, modifier = Modifier.align(Alignment.End)) {
                            Text("清除记忆", color = Muted)
                        }
                    }
                }
                Spacer(Modifier.height(18.dp))
                Text("使用其他账号登录", fontSize = 13.sp, color = Muted)
                Spacer(Modifier.height(8.dp))
            } else {
                Spacer(Modifier.height(18.dp))
            }
            OutlinedTextField(
                value = username,
                onValueChange = { username = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("统一认证账号") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("密码") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            )
            if (state.message != null) {
                Spacer(Modifier.height(12.dp))
                Text(state.message, color = MaterialTheme.colorScheme.error, fontSize = 14.sp)
            }
            Spacer(Modifier.height(20.dp))
            Button(
                onClick = { model.login(username, password) },
                enabled = !state.loading && username.isNotBlank() && password.isNotBlank(),
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(8.dp),
            ) {
                if (state.loading) CircularProgressIndicator(Modifier.size(20.dp), color = Paper, strokeWidth = 2.dp)
                else Text("登录 iClass")
            }
            Spacer(Modifier.height(18.dp))
            Text("登录成功后会记住此账号；密码仅以系统密钥加密保存在本机。", color = Muted, fontSize = 12.sp)
            Spacer(Modifier.height(24.dp))
            OutlinedButton(onClick = {
                DatePickerDialog(
                    context,
                    { _, year, month, day -> model.setSemesterBaseline(LocalDate.of(year, month + 1, day)) },
                    state.semesterBaseline.year,
                    state.semesterBaseline.monthValue - 1,
                    state.semesterBaseline.dayOfMonth,
                ).show()
            }) {
                androidx.compose.material3.Icon(Icons.Default.CalendarMonth, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("学期基准日：${state.semesterBaseline.format(DateTimeFormatter.ofPattern("yyyy年M月d日"))}")
            }
          }
        }
    }
}

@Composable
private fun ModeChoice(label: String, selected: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier.heightIn(min = 48.dp).selectable(selected, role = Role.RadioButton, onClick = onClick),
        shape = RoundedCornerShape(7.dp),
        color = if (selected) colors.primary else colors.surface,
        border = BorderStroke(1.dp, if (selected) colors.primary else colors.outlineVariant),
    ) {
        Text(label, color = if (selected) colors.onPrimary else colors.onSurface, modifier = Modifier.padding(horizontal = 15.dp, vertical = 10.dp), fontSize = 14.sp)
    }
}

@Composable
private fun ScheduleScreen(state: ScheduleUiState, model: ScheduleViewModel) {
    val colors = MaterialTheme.colorScheme
    val Ink = colors.onSurface
    val Muted = colors.onSurfaceVariant
    val Rule = colors.outlineVariant
    val Paper = colors.surface
    val context = LocalContext.current
    val openBaselinePicker = {
        DatePickerDialog(
            context,
            { _, year, month, day -> model.setSemesterBaseline(LocalDate.of(year, month + 1, day)) },
            state.semesterBaseline.year,
            state.semesterBaseline.monthValue - 1,
            state.semesterBaseline.dayOfMonth,
        ).show()
    }
    var now by remember { mutableStateOf(model.currentServerTime()) }
    var showWeekView by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(20_000)
            now = model.currentServerTime()
        }
    }
    val weekStart = WeekCalendar.weekStart(state.semesterBaseline, state.weekNumber)
    val selected = state.days.firstOrNull { it.date == state.selectedDate }
        ?: DaySchedule(state.selectedDate, emptyList())
    val eligibleCount = state.days.flatMap { it.courses }.count { isEligibleNow(it, now) }

    Scaffold(containerColor = Paper, bottomBar = {
        Surface(color = Paper, shadowElevation = 4.dp) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Row(
                    Modifier.widthIn(max = 640.dp).fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    listOf(SignChannel.ESCHOOL, SignChannel.APP).forEach { channel ->
                        Button(
                            onClick = { model.signCurrentWeek(channel) },
                            enabled = eligibleCount > 0 && !state.signing,
                            modifier = Modifier.weight(1f).height(50.dp),
                            shape = RoundedCornerShape(8.dp),
                        ) {
                            if (state.signing) CircularProgressIndicator(Modifier.size(18.dp), color = Paper, strokeWidth = 2.dp)
                            else Text(if (eligibleCount > 0) "${channel.label}（$eligibleCount）" else channel.label)
                        }
                    }
                }
            }
        }
    }) { padding ->
      Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 720.dp).fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 16.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("我的课表", fontSize = 23.sp, fontWeight = FontWeight.SemiBold, color = Ink)
                    Text(state.displayName, fontSize = 13.sp, color = Muted)
                }
                TextButton(onClick = model::refresh, enabled = !state.loading) {
                    androidx.compose.material3.Icon(Icons.Default.Refresh, contentDescription = "刷新课表", tint = Ink)
                }
                TextButton(onClick = model::logout) { Text("退出", color = Muted) }
            }
            Divider(color = Rule)
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = model::previousWeek, enabled = state.weekNumber > 1) {
                    androidx.compose.material3.Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "上一周", tint = Ink)
                }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("第 ${state.weekNumber} 周", fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
                    Text("${shortDate(weekStart)} — ${shortDate(weekStart.plusDays(6))}", color = Muted, fontSize = 12.sp)
                }
                IconButton(onClick = model::nextWeek, enabled = state.weekNumber < 18) {
                    androidx.compose.material3.Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "下一周", tint = Ink)
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("查看方式", fontSize = 13.sp, color = Muted, modifier = Modifier.weight(1f))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.selectableGroup()) {
                    ModeChoice("周视图", showWeekView) { showWeekView = true }
                    ModeChoice("日列表", !showWeekView) { showWeekView = false }
                }
                if (showWeekView) {
                    TextButton(onClick = openBaselinePicker) {
                        androidx.compose.material3.Icon(Icons.Default.CalendarMonth, contentDescription = "选择学期基准日", modifier = Modifier.size(17.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("基准日", fontSize = 12.sp)
                    }
                }
            }
            if (showWeekView) {
                WeekOverview(
                    days = state.days,
                    weekStart = weekStart,
                    selectedDate = state.selectedDate,
                    onSelectDate = model::selectDate,
                )
            } else {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp).selectableGroup(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    state.days.ifEmpty { (0..6).map { DaySchedule(weekStart.plusDays(it.toLong()), emptyList()) } }.forEach { day ->
                        DatePill(day.date, day.date == state.selectedDate) { model.selectDate(day.date) }
                    }
                }
            }
            if (showWeekView) {
                Text("已选日期课程", fontSize = 16.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(start = 20.dp, top = 12.dp, bottom = 5.dp))
            } else {
                Row(
                    Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 17.dp, bottom = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(state.selectedDate.format(DateTimeFormatter.ofPattern("M月d日")), fontSize = 18.sp, fontWeight = FontWeight.Medium)
                        Text(weekdayLong(state.selectedDate), fontSize = 12.sp, color = Muted)
                    }
                    TextButton(onClick = openBaselinePicker) {
                        androidx.compose.material3.Icon(Icons.Default.CalendarMonth, contentDescription = "选择学期基准日", modifier = Modifier.size(17.dp))
                        Spacer(Modifier.width(5.dp))
                        Text("基准日", fontSize = 12.sp)
                    }
                }
            }
            if (state.message != null) {
                Surface(color = colors.surfaceVariant, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), shape = RoundedCornerShape(7.dp)) {
                    Text(state.message, modifier = Modifier.padding(12.dp), fontSize = 13.sp)
                }
            }
            if (state.loading) {
                Row(Modifier.fillMaxWidth().padding(20.dp), horizontalArrangement = Arrangement.Center) {
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                }
            }
            if (selected.courses.isEmpty() && !state.loading) {
                Column(Modifier.fillMaxWidth().padding(top = 42.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("这一天没有课程", fontSize = 16.sp, color = Ink)
                    Text("学期基准日已保存在本机", fontSize = 13.sp, color = Muted, modifier = Modifier.padding(top = 6.dp))
                }
            } else {
                LazyColumn(Modifier.weight(1f), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 20.dp)) {
                    items(selected.courses, key = { it.scheduleIds.joinToString("-") }) { course ->
                        CourseCard(course, state.signing, now, onSign = { channel -> model.signCourse(course, channel) })
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClick = model::jumpToCurrentWeek).padding(horizontal = 20.dp, vertical = 9.dp),
                horizontalArrangement = Arrangement.Center,
            ) {
                Text("学期基准日 ${state.semesterBaseline.format(DateTimeFormatter.ofPattern("yyyy/M/d"))} · 回到本周", fontSize = 12.sp, color = Muted)
            }
        }
      }
    }
}

@Composable
private fun DatePill(date: LocalDate, selected: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier = Modifier.widthIn(min = 40.dp).heightIn(min = 48.dp)
            .selectable(selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 3.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(weekdayShort(date), color = if (selected) colors.onSurface else colors.onSurfaceVariant, fontSize = 11.sp)
        Spacer(Modifier.height(6.dp))
        Surface(shape = CircleShape, color = if (selected) colors.primary else colors.surface, modifier = Modifier.size(34.dp)) {
            androidx.compose.foundation.layout.Box(contentAlignment = Alignment.Center) {
                Text(date.dayOfMonth.toString(), color = if (selected) colors.onPrimary else colors.onSurface, fontSize = 13.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
            }
        }
    }
}

@Composable
private fun WeekOverview(
    days: List<DaySchedule>,
    weekStart: LocalDate,
    selectedDate: LocalDate,
    onSelectDate: (LocalDate) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val weekDays = (0..6).map { offset ->
        days.firstOrNull { it.date == weekStart.plusDays(offset.toLong()) }
            ?: DaySchedule(weekStart.plusDays(offset.toLong()), emptyList())
    }
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(weekDays, key = { it.date.toString() }) { day ->
            val selected = day.date == selectedDate
            Surface(
                modifier = Modifier.width(124.dp).height(180.dp).clickable { onSelectDate(day.date) },
                shape = RoundedCornerShape(12.dp),
                color = if (selected) colors.primaryContainer else colors.surface,
                border = BorderStroke(1.dp, if (selected) colors.primary else colors.outlineVariant),
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text(weekdayLong(day.date), fontSize = 12.sp, color = if (selected) colors.onPrimaryContainer else colors.onSurfaceVariant)
                    Text(
                        "${day.date.monthValue}/${day.date.dayOfMonth}",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (selected) colors.onPrimaryContainer else colors.onSurface,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                    Spacer(Modifier.height(10.dp))
                    if (day.courses.isEmpty()) {
                        Text("无课", fontSize = 13.sp, color = colors.onSurfaceVariant)
                    } else {
                        day.courses.take(3).forEach { course ->
                            Text(
                                course.beginText.takeLast(8).take(5),
                                fontSize = 10.sp,
                                color = if (selected) colors.onPrimaryContainer else colors.onSurfaceVariant,
                                modifier = Modifier.padding(top = 3.dp),
                            )
                            Text(
                                course.name,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = if (selected) colors.onPrimaryContainer else colors.onSurface,
                            )
                        }
                        if (day.courses.size > 3) {
                            Text("+${day.courses.size - 3} 门课", fontSize = 11.sp, color = colors.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CourseCard(course: Course, signing: Boolean, now: LocalDateTime, onSign: (SignChannel) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val start = course.begin
    val eligible = !course.signed && start != null && now >= start.minusMinutes(10) && now < start
    val statusText = when {
        course.signed -> "已签到"
        eligible -> "现在可以签到（开课前 10 分钟内）"
        start == null -> "上课时间暂不可用"
        now >= start -> "签到时间已过，无法补签"
        now < start.minusMinutes(10) -> "签到将在 ${(java.time.Duration.between(now, start.minusMinutes(10)).toMinutes() + 1).coerceAtLeast(1)} 分钟后开放"
        else -> "签到时间已过，无法补签"
    }
    val statusColor = if (course.signed) colors.secondary else if (eligible) colors.onSurface else colors.onSurfaceVariant
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        shape = RoundedCornerShape(9.dp),
        colors = CardDefaults.cardColors(containerColor = colors.surface),
        border = BorderStroke(1.dp, colors.outlineVariant),
    ) {
        Row(Modifier.fillMaxWidth().padding(15.dp), verticalAlignment = Alignment.Top) {
            Column(Modifier.width(67.dp)) {
                Text(course.beginText.takeLast(8).take(5), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text("— ${course.endText.takeLast(8).take(5)}", fontSize = 11.sp, color = colors.onSurfaceVariant)
            }
            Divider(Modifier.height(68.dp).width(1.dp), color = colors.outlineVariant)
            Column(Modifier.weight(1f).padding(start = 13.dp)) {
                Text(course.name, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                if (course.location.isNotBlank()) Text(course.location, fontSize = 12.sp, color = colors.onSurfaceVariant, modifier = Modifier.padding(top = 5.dp))
                if (course.teacher.isNotBlank()) Text(course.teacher, fontSize = 12.sp, color = colors.onSurfaceVariant, modifier = Modifier.padding(top = 3.dp))
                Text(statusText, fontSize = 12.sp, color = statusColor, modifier = Modifier.padding(top = 8.dp))
            }
            if (eligible) {
                Column(horizontalAlignment = Alignment.End) {
                    OutlinedButton(
                        onClick = { onSign(SignChannel.ESCHOOL) },
                        enabled = !signing,
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 11.dp, vertical = 4.dp),
                    ) {
                        Text(if (signing) "提交" else SignChannel.ESCHOOL.label, fontSize = 12.sp)
                    }
                    OutlinedButton(
                        onClick = { onSign(SignChannel.APP) },
                        enabled = !signing,
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 11.dp, vertical = 4.dp),
                    ) {
                        Text(if (signing) "提交" else SignChannel.APP.label, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

private fun isEligibleNow(course: Course, now: LocalDateTime): Boolean {
    val start = course.begin
    return !course.signed && start != null && now >= start.minusMinutes(10) && now < start
}

private fun shortDate(date: LocalDate): String = date.format(DateTimeFormatter.ofPattern("M/d"))
private fun weekdayShort(date: LocalDate): String = listOf("一", "二", "三", "四", "五", "六", "日")[date.dayOfWeek.value - 1]
private fun weekdayLong(date: LocalDate): String = "星期${weekdayShort(date)}"
