package edu.buaa.signtool

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import edu.buaa.signtool.data.CampusCourse
import edu.buaa.signtool.data.CampusFeature
import edu.buaa.signtool.data.CampusHomework
import edu.buaa.signtool.data.CampusHomeworkDetail
import edu.buaa.signtool.data.CampusIncome
import edu.buaa.signtool.data.CampusReader
import edu.buaa.signtool.data.CampusQueryException
import edu.buaa.signtool.data.HomeworkState
import java.math.BigDecimal
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CancellationException

private sealed interface CampusLoad<out T> {
    data object Loading : CampusLoad<Nothing>
    data class Loaded<T>(val value: T) : CampusLoad<T>
    data class Failed(val reason: String) : CampusLoad<Nothing>
}

@Composable
private fun <T> campusLoad(key: Any?, refresh: Int, fetch: suspend () -> T): State<CampusLoad<T>> =
    produceState<CampusLoad<T>>(CampusLoad.Loading, key, refresh) {
        value = CampusLoad.Loading
        value = try {
            CampusLoad.Loaded(fetch())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            CampusLoad.Failed((error as? CampusQueryException)?.message ?: "查询失败，请检查网络后重试。")
        }
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CampusFeatureScreen(feature: CampusFeature, queries: CampusReader, onBack: () -> Unit) {
    var refresh by remember { mutableIntStateOf(0) }
    var detailRefresh by remember { mutableIntStateOf(0) }
    var course by remember { mutableStateOf<CampusCourse?>(null) }
    var homework by remember { mutableStateOf<CampusHomework?>(null) }
    var income by remember { mutableStateOf<CampusIncome?>(null) }
    var search by remember { mutableStateOf("") }
    var pendingOnly by rememberSaveable { mutableStateOf(false) }
    var selectedYear by rememberSaveable { mutableStateOf<String?>(null) }
    val currentCourse = course
    val selectedHomework = homework
    val selectedIncome = income
    val detailOpen = selectedHomework != null || selectedIncome != null

    fun goBack() {
        when {
            homework != null -> homework = null
            income != null -> income = null
            course != null -> { course = null; search = "" }
            else -> onBack()
        }
    }
    BackHandler { goBack() }

    val judgeCourses = if (feature == CampusFeature.JUDGE) {
        campusLoad(feature, refresh, queries::judgeCourses).value
    } else null
    val assignments = when {
        feature == CampusFeature.SPOC -> campusLoad(feature, refresh, queries::spocHomework).value
        feature == CampusFeature.JUDGE && currentCourse != null ->
            campusLoad(currentCourse, refresh) { queries.judgeHomework(currentCourse) }.value
        else -> null
    }
    val incomes = if (feature == CampusFeature.FINANCE) {
        campusLoad(feature, refresh, queries::incomes).value
    } else null
    val detail = selectedHomework?.let { item ->
        campusLoad(item, detailRefresh) { queries.detail(feature, item) }.value
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                title = { Text(if (detailOpen) "详情" else feature.title) },
                navigationIcon = {
                    IconButton(onClick = { goBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    if (selectedIncome == null) IconButton(onClick = {
                        if (selectedHomework != null) detailRefresh++ else refresh++
                    }) { Icon(Icons.Default.Refresh, contentDescription = "刷新") }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                Modifier.widthIn(max = 720.dp).fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                when {
                    selectedIncome != null -> item("income-detail") { IncomeDetail(selectedIncome) }
                    detail != null -> campusResult(detail, onRetry = { detailRefresh++ }) { result ->
                        item("homework-detail") { HomeworkDetail(result) }
                    }
                    incomes != null -> campusResult(incomes, onRetry = { refresh++ }) { records ->
                        incomeItems(records, search, selectedYear, { search = it }, { selectedYear = it }) { income = it }
                    }
                    assignments != null -> campusResult(assignments, onRetry = { refresh++ }) { records ->
                        homeworkItems(records, currentCourse, search, pendingOnly, { search = it }, { pendingOnly = it }) {
                            homework = it
                        }
                    }
                    judgeCourses != null -> campusResult(judgeCourses, onRetry = { refresh++ }) { courses ->
                        item("choose-course") {
                            Text("选择课程", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                        }
                        if (courses.isEmpty()) item { EmptyMessage("希冀没有返回可查询的课程。") }
                        items(courses, key = { it.id }) { item ->
                            CampusRow(item.name, "查看作业") { course = item }
                        }
                    }
                }
            }
        }
    }
}

private fun <T> LazyListScope.campusResult(
    result: CampusLoad<T>, onRetry: () -> Unit, rows: LazyListScope.(T) -> Unit,
) {
    when (result) {
        CampusLoad.Loading -> item("loading") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text("正在查询学校系统…", style = MaterialTheme.typography.bodyMedium)
            }
        }
        is CampusLoad.Failed -> item("failed") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(result.reason, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = onRetry) { Text("重新查询") }
            }
        }
        is CampusLoad.Loaded -> rows(result.value)
    }
}

private fun LazyListScope.homeworkItems(
    all: List<CampusHomework>,
    course: CampusCourse?,
    search: String,
    pendingOnly: Boolean,
    onSearch: (String) -> Unit,
    onPendingOnly: (Boolean) -> Unit,
    onSelect: (CampusHomework) -> Unit,
) {
    item("homework-filter") {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (course != null) Text(course.name, style = MaterialTheme.typography.titleLarge)
            SearchField("搜索课程或作业", search, onSearch)
            if (course == null) FilterChip(
                selected = pendingOnly, onClick = { onPendingOnly(!pendingOnly) },
                label = { Text("只看待办") },
            )
        }
    }
    val now = LocalDateTime.now(ZoneId.of("Asia/Shanghai"))
    val rows = all.filter { assignment ->
        (search.isBlank() || assignment.title.contains(search.trim(), true) || assignment.course.name.contains(search.trim(), true)) &&
            (!pendingOnly || (assignment.state in setOf(HomeworkState.TODO, HomeworkState.PARTIAL) &&
                (assignment.due == null || !assignment.due.isBefore(now))))
    }.sortedWith(compareBy<CampusHomework> { it.due == null }.thenBy { it.due }.thenBy { it.title })
    if (rows.isEmpty()) item("homework-empty") {
        EmptyMessage(if (all.isEmpty()) "学校没有返回作业记录。" else "没有符合筛选条件的作业。")
    }
    rows.groupBy { it.course }.forEach { (group, homework) ->
        if (course == null) item("course:${group.id}:${group.name}") { GroupHeading(group.name) }
        items(homework, key = { "homework:${it.course.id}:${it.id}" }) { item ->
            val summary = listOfNotNull(item.due?.let { "截止 ${it.format(displayTime)}" }, item.state.label)
            CampusRow(item.title, summary.joinToString(" · ")) { onSelect(item) }
        }
    }
}

private fun LazyListScope.incomeItems(
    records: List<CampusIncome>,
    search: String,
    selectedYear: String?,
    onSearch: (String) -> Unit,
    onYear: (String?) -> Unit,
    onSelect: (CampusIncome) -> Unit,
) {
    val years = records.mapNotNull { it.year }.distinct().sortedDescending()
    val rows = filterIncome(records, selectedYear, search)
    item("finance-filter") {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SearchField("搜索项目或摘要", search, onSearch)
            Box {
                var choosingYear by remember { mutableStateOf(false) }
                TextButton(onClick = { choosingYear = true }) { Text(selectedYear?.let { "${it}年" } ?: "全部年份") }
                DropdownMenu(expanded = choosingYear, onDismissRequest = { choosingYear = false }) {
                    DropdownMenuItem(text = { Text("全部年份") }, onClick = { choosingYear = false; onYear(null) })
                    years.forEach { year ->
                        DropdownMenuItem(text = { Text("${year}年") }, onClick = { choosingYear = false; onYear(year) })
                    }
                }
            }
            if (rows.isNotEmpty()) IncomeTotals(rows)
        }
    }
    if (rows.isEmpty()) item("finance-empty") {
        EmptyMessage(if (records.isEmpty()) "学校财务系统没有返回收入记录。" else "没有符合筛选条件的收入记录。")
    }
    rows.groupBy { incomeMonth(it) }.forEach { (month, entries) ->
        item("income-month:$month") {
            GroupHeading("$month  ·  实发 ${moneyLabel(totalMoney(entries, CampusIncome::net))}")
        }
        items(entries, key = { "income:${it.id}" }) { item ->
            CampusRow(item.project ?: item.description ?: "收入记录", "实发 ${moneyLabel(item.net)}") { onSelect(item) }
        }
    }
}

internal fun filterIncome(records: List<CampusIncome>, year: String?, query: String): List<CampusIncome> {
    val search = query.trim()
    return records.filter { row ->
        (year == null || year == row.year) &&
            (search.isEmpty() || row.project?.contains(search, true) == true || row.description?.contains(search, true) == true)
    }
}

internal fun totalMoney(rows: List<CampusIncome>, amount: (CampusIncome) -> BigDecimal?): BigDecimal? {
    val total = rows.map(amount)
    if (total.any { it == null }) return null
    return total.filterNotNull().fold(BigDecimal.ZERO, BigDecimal::add)
}

@Composable
private fun IncomeTotals(rows: List<CampusIncome>) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.medium) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("实发合计", style = MaterialTheme.typography.labelLarge)
            Text(moneyLabel(totalMoney(rows, CampusIncome::net)), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            Text(
                "${rows.size} 笔 · 应发 ${moneyLabel(totalMoney(rows, CampusIncome::gross))} · 扣税 ${moneyLabel(totalMoney(rows, CampusIncome::tax))}",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun IncomeDetail(income: CampusIncome) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        DetailHeading(income.project ?: "收入记录", income.description)
        DetailLine("实发", moneyLabel(income.net))
        DetailLine("应发", moneyLabel(income.gross))
        DetailLine("扣税", moneyLabel(income.tax))
        DetailLine("税率", income.taxRate)
        DetailLine("所属年月", incomeMonth(income))
        DetailLine("录入日期", income.recordedOn)
        DetailLine("流水号", income.id)
    }
}

@Composable
private fun HomeworkDetail(detail: CampusHomeworkDetail) {
    val item = detail.homework
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        DetailHeading(item.title, item.course.name)
        DetailLine("开始时间", item.starts?.format(displayTime))
        DetailLine("截止时间", item.due?.format(displayTime))
        DetailLine("提交状态", item.state.label)
        detail.submittedAt?.let { DetailLine("提交时间", it.format(displayTime)) }
        item.score?.let { DetailLine("分值", it) }
        detail.description?.let { description ->
            GroupHeading("作业说明")
            Text(description, style = MaterialTheme.typography.bodyLarge)
        }
        if (detail.problems.isNotEmpty()) {
            GroupHeading("题目明细")
            detail.problems.forEach { problem ->
                DetailLine(problem.name, listOfNotNull(problem.result, problem.score).joinToString(" · "))
            }
        }
    }
}

@Composable
private fun DetailHeading(title: String, subtitle: String?) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        subtitle?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        HorizontalDivider()
    }
}

@Composable
private fun DetailLine(label: String, value: String?) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value ?: "未提供", style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun SearchField(label: String, search: String, onSearch: (String) -> Unit) {
    OutlinedTextField(
        value = search, onValueChange = onSearch, modifier = Modifier.fillMaxWidth(),
        label = { Text(label) }, singleLine = true,
    )
}

@Composable
private fun CampusRow(title: String, supporting: String, onClick: () -> Unit) {
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceVariant) {
        ListItem(
            modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
            headlineContent = { Text(title, maxLines = 3, overflow = TextOverflow.Ellipsis) },
            supportingContent = { Text(supporting) },
            trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
            colors = androidx.compose.material3.ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        )
    }
}

@Composable
private fun GroupHeading(title: String) {
    Text(title, modifier = Modifier.padding(top = 6.dp), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun EmptyMessage(message: String) {
    Text(message, modifier = Modifier.padding(vertical = 16.dp), style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}

private val displayTime = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
private fun incomeMonth(row: CampusIncome) =
    if (row.year != null && row.month != null) "${row.year}年${row.month}月" else "未标注月份"
private fun moneyLabel(amount: BigDecimal?): String = amount?.let { "¥${it.setScale(2).toPlainString()}" } ?: "未提供"
