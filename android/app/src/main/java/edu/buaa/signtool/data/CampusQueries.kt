package edu.buaa.signtool.data

import java.net.CookieManager
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal interface CampusReader {
    suspend fun spocHomework(): List<CampusHomework>
    suspend fun judgeCourses(): List<CampusCourse>
    suspend fun judgeHomework(course: CampusCourse): List<CampusHomework>
    suspend fun detail(feature: CampusFeature, homework: CampusHomework): CampusHomeworkDetail
    suspend fun incomes(): List<CampusIncome>
}

internal class CampusQueries(mode: NetworkMode, cookies: CookieManager) : CampusReader {
    private val http = CampusHttp(mode, cookies)
    private val spoc = SpocQuery(http)
    private val judge = JudgeQuery(http)
    private val finance = FinanceQuery(http)
    private val reads = Mutex()

    override suspend fun spocHomework() = reads.withLock { spoc.assignments() }
    override suspend fun judgeCourses() = reads.withLock { judge.courses() }
    override suspend fun judgeHomework(course: CampusCourse) = reads.withLock { judge.assignments(course) }
    override suspend fun detail(feature: CampusFeature, homework: CampusHomework) = reads.withLock {
        when (feature) {
            CampusFeature.SPOC -> spoc.detail(homework)
            CampusFeature.JUDGE -> judge.detail(homework)
            CampusFeature.FINANCE -> error("Income records use the finance list")
        }
    }
    override suspend fun incomes() = reads.withLock { finance.incomes() }

    fun close() = http.close()
}
