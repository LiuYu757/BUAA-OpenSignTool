package edu.buaa.signtool

import android.app.DatePickerDialog
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import edu.buaa.signtool.data.CampusFeature
import edu.buaa.signtool.data.Course
import edu.buaa.signtool.data.DaySchedule
import edu.buaa.signtool.data.SignPolicy
import edu.buaa.signtool.data.WeekCalendar
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.delay

@Composable
internal fun ScheduleScreen(state: ScheduleUiState, model: ScheduleViewModel) {
    val context = LocalContext.current
    var campusFeature by rememberSaveable { mutableStateOf<CampusFeature?>(null) }
    val feature = campusFeature
    if (feature != null) {
        val queries = remember(model, feature) { model.campusQueries() }
        if (queries != null) CampusFeatureScreen(feature, queries, onBack = { campusFeature = null })
        return
    }
    var now by remember { mutableStateOf(model.currentServerTime()) }
    LaunchedEffect(state.days, state.signing) {
        while (true) {
            now = model.currentServerTime()
            delay(15_000)
        }
    }
    ScheduleContent(
        state = state,
        now = now,
        onSelectDate = model::selectDate,
        onPreviousWeek = model::previousWeek,
        onNextWeek = model::nextWeek,
        onToday = model::jumpToCurrentWeek,
        onRefresh = model::refresh,
        onChangeBaseline = {
            DatePickerDialog(
                context,
                { _, year, month, day -> model.setSemesterBaseline(LocalDate.of(year, month + 1, day)) },
                state.semesterBaseline.year,
                state.semesterBaseline.monthValue - 1,
                state.semesterBaseline.dayOfMonth,
            ).show()
        },
        onLogout = model::logout,
        onSignCourse = model::signCourse,
        onSignWeek = model::signCurrentWeek,
        onDismissMessage = model::dismissMessage,
        onOpenCampusFeature = { campusFeature = it },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ScheduleContent(
    state: ScheduleUiState,
    now: LocalDateTime,
    onSelectDate: (LocalDate) -> Unit,
    onPreviousWeek: () -> Unit = {},
    onNextWeek: () -> Unit = {},
    onToday: () -> Unit = {},
    onRefresh: () -> Unit = {},
    onChangeBaseline: () -> Unit = {},
    onLogout: () -> Unit = {},
    onSignCourse: (Course) -> Unit = {},
    onSignWeek: () -> Unit = {},
    onDismissMessage: (String) -> Unit = {},
    onOpenCampusFeature: (CampusFeature) -> Unit = {},
) {
    val colors = MaterialTheme.colorScheme
    val today = now.toLocalDate()
    val weekStart = WeekCalendar.weekStart(state.semesterBaseline, state.weekNumber)
    val week = remember(weekStart, state.days) {
        List(7) { offset ->
            val date = weekStart.plusDays(offset.toLong())
            state.days.firstOrNull { it.date == date } ?: DaySchedule(date, emptyList())
        }
    }
    val courses = remember(week, state.selectedDate) {
        week.firstOrNull { it.date == state.selectedDate }?.courses.orEmpty().sortedBy { it.begin }
    }
    val weekCourses = remember(week) { week.flatMap { it.courses } }
    val batchCourses = weekCourses.filter { course ->
        course.scheduleIds.none { it in state.unconfirmedSignIds }
    }
    val canBatch = !state.loading && !state.signing && batchCourses.any { SignPolicy.isEligible(it, now) }
    var overview by rememberSaveable { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var batch by remember { mutableStateOf(false) }
    var courseToSign by remember { mutableStateOf<Course?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val scrollToTop = remember { mutableStateOf(0) }
    BackHandler(enabled = overview && !batch) { overview = false }
    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it, withDismissAction = true, duration = SnackbarDuration.Long)
            onDismissMessage(it)
        }
    }

    Scaffold(
        containerColor = colors.surface,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(if (overview) "周课表" else "我的课表") },
                navigationIcon = {
                    if (overview) IconButton(onClick = { overview = false }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回课程列表")
                    }
                },
                actions = {
                    TextButton(onClick = { overview = false; onToday(); scrollToTop.value++ }, enabled = !state.signing) { Text("今天") }
                    if (!overview) IconButton(onClick = { overview = true }) {
                        Icon(Icons.Default.CalendarMonth, contentDescription = "查看周总览")
                    }
                    Box {
                        IconButton(onClick = { menu = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "更多操作")
                        }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            CampusFeature.entries.forEach { item ->
                                DropdownMenuItem(text = { Text(item.title) }, enabled = !state.signing, onClick = {
                                    menu = false
                                    onOpenCampusFeature(item)
                                })
                            }
                            HorizontalDivider()
                            DropdownMenuItem(text = { Text("本周批量签到") }, enabled = !state.loading && !state.signing && weekCourses.isNotEmpty(), onClick = {
                                menu = false
                                batch = true
                            })
                            DropdownMenuItem(text = { Text("刷新课表") }, enabled = !state.loading && !state.signing, onClick = {
                                menu = false
                                onRefresh()
                            })
                            DropdownMenuItem(text = { Text("设置学期基准日") }, enabled = !state.signing, onClick = {
                                menu = false
                                onChangeBaseline()
                            })
                            HorizontalDivider()
                            DropdownMenuItem(text = { Text("退出登录") }, onClick = { menu = false; onLogout() })
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = colors.surface),
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            // One scrolling surface, so navigation and feedback cannot squeeze the course viewport.
            key(if (overview) weekStart else state.selectedDate, overview) {
                val listState = rememberLazyListState()
                LaunchedEffect(scrollToTop.value) { if (scrollToTop.value > 0) listState.scrollToItem(0) }
                LazyColumn(
                    modifier = Modifier.widthIn(max = 720.dp).fillMaxSize().testTag("schedule-scroll"),
                    state = listState,
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item("week-navigation") {
                        WeekNavigation(state.weekNumber, weekStart, onPreviousWeek, onNextWeek, !state.signing)
                    }
                    if (!overview) {
                        item("dates") {
                            DateStrip(week, state.selectedDate, today, onSelectDate)
                        }
                    }
                    if (state.loading || state.signing) {
                        item("progress") {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                LinearProgressIndicator(Modifier.fillMaxWidth())
                                Text(
                                    if (state.signing) "正在处理签到…" else "正在加载课表…",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = colors.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    when {
                        state.scheduleError != null -> item("schedule-error") {
                            ScheduleNotice("课表加载失败", state.scheduleError, "重新加载", onRefresh)
                        }
                        state.loading && state.days.isEmpty() -> item("loading") { SchedulePlaceholder() }
                        overview -> weekOverviewItems(week, state.selectedDate, today) {
                            onSelectDate(it)
                            overview = false
                        }
                        else -> {
                            item("date-heading") {
                                DayHeading(state.selectedDate, today, courses, onBatch = if (canBatch) ({ batch = true }) else null)
                            }
                            if (courses.isEmpty()) {
                                item("empty-day") {
                                    ScheduleNotice("这一天没有课程", "可以点选上方的日期，查看其他日程。", "查看周总览") { overview = true }
                                }
                            } else {
                                items(courses, key = { "course:${it.scheduleIds.joinToString("-")}" }) { course ->
                                    CourseCard(
                                        course = course,
                                        signing = state.signing || state.loading,
                                        now = now,
                                        unconfirmed = course.scheduleIds.any { it in state.unconfirmedSignIds },
                                        onRefreshStatus = onRefresh,
                                        onSign = { courseToSign = course },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (batch) {
        ModalBottomSheet(
            onDismissRequest = { batch = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            Column(Modifier.fillMaxWidth().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("本周批量签到", style = MaterialTheme.typography.titleLarge)
                Text("提交本周当前可签到的课程。", color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                SignActionsBar(batchCourses, state.signing || state.loading, now) {
                    batch = false
                    onSignWeek()
                }
            }
        }
    }

    courseToSign?.let { course ->
        val stillEligible = SignPolicy.isEligible(course, now)
        AlertDialog(
            onDismissRequest = { courseToSign = null },
            title = { Text("确认课堂签到") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(course.name)
                    Text(
                        if (stillEligible) courseTime(course) else "当前不在签到时段。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = stillEligible && !state.signing && !state.loading,
                    onClick = { courseToSign = null; onSignCourse(course) },
                ) { Text("确认签到") }
            },
            dismissButton = { TextButton(onClick = { courseToSign = null }) { Text("取消") } },
        )
    }
}

@Composable
private fun WeekNavigation(weekNumber: Int, weekStart: LocalDate, previous: () -> Unit, next: () -> Unit, enabled: Boolean) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = previous, enabled = enabled && weekNumber > 1) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "上一周")
        }
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("第 $weekNumber 周", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text("${shortDate(weekStart)} – ${shortDate(weekStart.plusDays(6))}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        IconButton(onClick = next, enabled = enabled && weekNumber < 18) {
            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "下一周")
        }
    }
}

@Composable
private fun DateStrip(week: List<DaySchedule>, selected: LocalDate, today: LocalDate, onSelect: (LocalDate) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth().selectableGroup()) {
        val gap = if (maxWidth >= 48.dp * 7 + 8.dp * 6) 8.dp else 4.dp
        val cell = (maxWidth - gap * 6) / 7
        val fontScale = LocalDensity.current.fontScale
        if (cell >= 48.dp && fontScale <= 1.3f) {
            Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                week.forEach { day -> DateTile(day, day.date == selected, day.date == today, Modifier.weight(1f)) { onSelect(day.date) } }
            }
        } else {
            val index = week.indexOfFirst { it.date == selected }.coerceAtLeast(0)
            val list = rememberLazyListState(initialFirstVisibleItemIndex = index)
            LaunchedEffect(index) {
                if (list.layoutInfo.visibleItemsInfo.none { it.index == index }) list.scrollToItem(index)
            }
            LazyRow(state = list, modifier = Modifier.testTag("schedule-date-strip"), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(week, key = { it.date.toEpochDay() }) { day ->
                    DateTile(day, day.date == selected, day.date == today, Modifier.width(if (fontScale > 1.3f) 68.dp else 52.dp)) { onSelect(day.date) }
                }
            }
        }
    }
}

@Composable
private fun DateTile(day: DaySchedule, selected: Boolean, today: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val foreground = if (selected) colors.onPrimary else colors.onSurface
    val description = "${longDate(day.date)} ${weekdayLong(day.date)}，${if (today) "今天，" else ""}${day.courses.size} 门课"
    Surface(
        modifier = modifier.heightIn(min = 64.dp)
            .selectable(selected, role = Role.RadioButton, onClick = onClick)
            .semantics { contentDescription = description },
        shape = RoundedCornerShape(12.dp),
        color = if (selected) colors.primary else colors.surfaceVariant,
        contentColor = foreground,
        border = if (today && !selected) BorderStroke(1.dp, colors.outline) else null,
    ) {
        Column(Modifier.padding(horizontal = 2.dp, vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(weekdayShort(day.date), style = MaterialTheme.typography.labelMedium)
            Text(day.date.dayOfMonth.toString(), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(if (day.courses.isEmpty()) "–" else "${day.courses.size}节", style = MaterialTheme.typography.labelSmall)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DayHeading(date: LocalDate, today: LocalDate, courses: List<Course>, onBatch: (() -> Unit)?) {
    val colors = MaterialTheme.colorScheme
    val prefix = if (date == today) "今天 · " else ""
    Column(Modifier.padding(top = 2.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text("$prefix${longDate(date)}", Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "${weekdayLong(date)} · ${courses.size} 门课 · ${courses.count { it.signed }} 门已签到",
                modifier = Modifier.align(Alignment.CenterVertically),
                color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium,
            )
            if (onBatch != null) TextButton(onClick = onBatch, modifier = Modifier.heightIn(min = 48.dp)) { Text("批量签到") }
        }
    }
}

private fun LazyListScope.weekOverviewItems(days: List<DaySchedule>, selected: LocalDate, today: LocalDate, onSelect: (LocalDate) -> Unit) {
    items(days, key = { "overview:${it.date}" }) { day ->
        val colors = MaterialTheme.colorScheme
        Column(
            Modifier.fillMaxWidth().clickable(role = Role.Button) { onSelect(day.date) }.padding(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("${weekdayLong(day.date)} ${shortDate(day.date)}${if (day.date == today) " · 今天" else ""}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            day.courses.sortedBy { it.begin }.forEach { course ->
                Text("${course.begin?.format(timeFormat) ?: "时间待定"}  ${course.name}", style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
            }
            Text(
                if (day.courses.isEmpty()) "没有课程" else "${day.courses.size} 门课 · ${if (day.date == selected) "已选日期" else "查看当日课程"}",
                style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant,
            )
            HorizontalDivider(Modifier.padding(top = 6.dp))
        }
    }
}

@Composable
private fun ScheduleNotice(title: String, detail: String, action: String, onAction: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedButton(onClick = onAction, modifier = Modifier.heightIn(min = 48.dp)) { Text(action) }
    }
}

@Composable
private fun SchedulePlaceholder() {
    Column(Modifier.fillMaxWidth().semantics { contentDescription = "正在加载课程" }, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        repeat(2) { Surface(Modifier.fillMaxWidth().heightIn(min = 84.dp), shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant) {} }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CourseCard(
    course: Course,
    signing: Boolean,
    now: LocalDateTime,
    unconfirmed: Boolean = false,
    onRefreshStatus: () -> Unit = {},
    onSign: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val start = course.begin
    val end = course.end
    val opensAt = SignPolicy.opensAt(course)
    val canSign = !unconfirmed && SignPolicy.isEligible(course, now)
    val status = when {
        course.signed -> "已签到"
        unconfirmed -> "签到结果待确认"
        !course.signStatusKnown -> "签到状态未知"
        canSign -> "可签到"
        start == null -> "上课时间暂不可用"
        end == null || end <= start -> "下课时间暂不可用"
        opensAt != null && now < opensAt -> "${opensAt.format(timeFormat)} 起可签"
        else -> "未签到"
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = colors.surface,
        border = BorderStroke(1.dp, colors.outlineVariant),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(courseTime(course), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (course.signed) Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(15.dp), tint = colors.secondary)
                    Text(status, style = MaterialTheme.typography.labelMedium, color = if (course.signed) colors.secondary else colors.onSurfaceVariant)
                }
            }
            Text(course.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            if (course.location.isNotBlank()) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Place, contentDescription = null, modifier = Modifier.size(16.dp), tint = colors.onSurfaceVariant)
                    Text(course.location, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                }
            }
            if (course.teacher.isNotBlank()) Text(course.teacher, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            if (canSign) {
                Button(onClick = onSign, enabled = !signing, modifier = Modifier.heightIn(min = 48.dp)) { Text("签到") }
            } else if (!course.signed && (unconfirmed || !course.signStatusKnown)) {
                OutlinedButton(onClick = onRefreshStatus, enabled = !signing, modifier = Modifier.heightIn(min = 48.dp)) { Text("刷新状态") }
            }
        }
    }
}

/** Used inside the on-demand batch sheet, never as a permanent schedule footer. */
@Composable
internal fun SignActionsBar(courses: List<Course>, signing: Boolean, now: LocalDateTime, onSign: () -> Unit) {
    val count = courses.count { SignPolicy.isEligible(it, now) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (count == 0) Text("当前没有可签到的课程。", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        Button(onClick = onSign, enabled = count > 0 && !signing, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Text("确认签到（$count 门）")
        }
    }
}

private fun courseTime(course: Course): String {
    val start = course.begin ?: return "时间待定"
    val end = course.end
    return if (end == null || end <= start) start.format(timeFormat) else "${start.format(timeFormat)} – ${end.format(timeFormat)}"
}

private val timeFormat = DateTimeFormatter.ofPattern("HH:mm")
private val shortDateFormat = DateTimeFormatter.ofPattern("M/d")
private val longDateFormat = DateTimeFormatter.ofPattern("M月d日")
private fun shortDate(date: LocalDate): String = date.format(shortDateFormat)
private fun longDate(date: LocalDate): String = date.format(longDateFormat)
private fun weekdayShort(date: LocalDate): String = listOf("一", "二", "三", "四", "五", "六", "日")[date.dayOfWeek.value - 1]
private fun weekdayLong(date: LocalDate): String = "星期${weekdayShort(date)}"
