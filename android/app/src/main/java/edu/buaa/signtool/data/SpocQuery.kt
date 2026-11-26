package edu.buaa.signtool.data

import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

private const val spoc = "https://spoc.buaa.edu.cn/spocnewht"
private const val currentTermQuery =
    "YHrxtTavu6raCwC0/qdgYffB9evWHBkTng/XS4W6j3f/TPo02iEPSoegscDTRNzIPRG49o3RHl4JiFCXAiBkkA=="
private const val assignmentsQueryId = "1713252980496efac7d5d9985e81693116d3e8a52ebf2b"
internal val campusJsonType = "application/json; charset=utf-8".toMediaType()

internal class SpocQuery(private val http: CampusHttp) {
    private val feature = CampusFeature.SPOC
    private var credential: Map<String, String>? = null

    suspend fun assignments(): List<CampusHomework> {
        val term = spocObject(read("inco/ht/queryOne", json = JSONObject().put("param", currentTermQuery)))
            .value("mrxq") ?: invalidSpoc("学校未返回 SPOC 当前学期。")
        val courses = try {
            (read("jxkj/queryKclb", query = arrayOf("kcmc" to "", "xnxq" to term)) as? JSONArray)
                ?.objects()?.mapNotNull { row ->
                    val id = row.value("kcid") ?: return@mapNotNull null
                    row.value("kcmc")?.let { id to it }
                }?.toMap().orEmpty()
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            emptyMap()
        }
        val tasks = linkedMapOf<String, CampusHomework>()
        for (page in 1..150) {
            val payload = JSONObject()
                .put("pageSize", 15).put("pageNum", page).put("sqlid", assignmentsQueryId)
                .put("xnxq", term).put("kcid", "").put("yzwz", "")
            val result = spocObject(read("inco/ht/queryListByPage", json = JSONObject().put("param", encryptSpoc(payload.toString()))))
            val rows = result.optJSONArray("list") ?: invalidSpoc()
            for (row in rows.objects()) {
                val item = parseSpocHomework(row, courses)
                if (tasks.putIfAbsent(item.id, item) != null) invalidSpoc("SPOC 作业分页返回了重复记录。")
            }
            val next = result.optBoolean("hasNextPage", page < result.optInt("pages", 1))
            if (!next) {
                val total = result.optInt("total", tasks.size)
                if (total > 0 && total != tasks.size) invalidSpoc("SPOC 作业未完整加载，请刷新。")
                return tasks.values.toList()
            }
            if (rows.length() == 0) invalidSpoc("SPOC 作业未完整加载，请刷新。")
        }
        invalidSpoc("SPOC 作业页数过多，请稍后重试。")
    }

    suspend fun detail(homework: CampusHomework): CampusHomeworkDetail {
        val row = spocObject(read("kczy/queryKczyInfoByid", query = arrayOf("id" to homework.id)))
        if (row.value("id") != homework.id) invalidSpoc()
        // A submission query may explicitly return null for an unsubmitted assignment.
        val submission = try {
            read("kczy/queryXsSubmitKczyInfo", query = arrayOf("kczyid" to homework.id)) as? JSONObject
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
        val status = submission?.value("tjzt")?.let(::parseSpocStatus) ?: homework.state
        return CampusHomeworkDetail(
            homework.copy(
                starts = campusTime(row.value("zykssj")) ?: homework.starts,
                due = campusTime(row.value("zyjzsj")) ?: homework.due,
                state = status,
                score = row.value("zyfs") ?: homework.score,
            ),
            description = htmlPlainText(row.value("zynr")),
            submittedAt = campusTime(submission?.value("tjsj")),
        )
    }

    private suspend fun read(
        path: String, json: JSONObject? = null, query: Array<Pair<String, String>> = emptyArray(),
    ): Any? {
        repeat(2) { attempt ->
            val headers = credential ?: login().also { credential = it }
            val target = campusUrl("$spoc/$path", *query)
            val reply = http.request(feature, target, json?.toString()?.toRequestBody(campusJsonType), headers)
            val envelope = parseJson(reply)
            if (reply.code == 401 || reply.code in 300..399 || envelope?.optInt("code") in setOf(401, 403)) {
                credential = null
                if (attempt == 1) campusAuthRequired()
            } else {
                if (reply.code != 200 || envelope?.optInt("code") != 200) invalidSpoc()
                return envelope.opt("content").takeUnless { it == JSONObject.NULL }
            }
        }
        campusAuthRequired()
    }

    private suspend fun login(): Map<String, String> {
        val page = http.getSchoolPage(feature, campusUrl("$spoc/cas")) {
            it.host == "spoc.buaa.edu.cn" && it.path == "/spocnew/cas" && it.queryValue("token") != null
        }
        val token = page.takeIf { it.url.host == "spoc.buaa.edu.cn" && it.url.path == "/spocnew/cas" }
            ?.url?.queryValue("token") ?: campusAuthRequired()
        val headers = mapOf("X-Requested-With" to "XMLHttpRequest", "Token" to "Inco-$token")
        val reply = http.request(
            feature, campusUrl("$spoc/sys/casLogin"),
            JSONObject().put("token", token).toString().toRequestBody(campusJsonType),
            headers,
        )
        val response = parseJson(reply)
        if (reply.code != 200 || response?.optInt("code") != 200) campusAuthRequired()
        val content = response.optJSONObject("content") ?: campusAuthRequired()
        val role = listOf("jsdm", "rolecode", "jsdmList").firstNotNullOfOrNull { key ->
            content.value(key) ?: content.optJSONArray(key)?.let { array ->
                (0 until array.length()).firstNotNullOfOrNull { index ->
                    array.opt(index)?.takeIf { it is String || it is Number }?.toString()?.takeIf(String::isNotBlank)
                }
            }
        } ?: campusAuthRequired()
        return headers + ("RoleCode" to role)
    }
}

internal fun parseSpocHomework(row: JSONObject, courses: Map<String, String> = emptyMap()): CampusHomework {
    val courseId = row.value("sskcid").orEmpty()
    return CampusHomework(
        id = row.value("zyid") ?: invalidSpoc(),
        course = CampusCourse(courseId, row.value("kcmc") ?: courses[courseId] ?: "课程未命名"),
        title = row.value("zymc") ?: invalidSpoc(),
        starts = campusTime(row.value("zykssj")),
        due = campusTime(row.value("zyjzsj")),
        state = parseSpocStatus(row.value("tjzt")),
        score = row.value("mf"),
    )
}

private fun parseSpocStatus(value: String?): HomeworkState = when (value) {
    "1", "已做", "已提交" -> HomeworkState.DONE
    "0", "未做", "未提交" -> HomeworkState.TODO
    else -> HomeworkState.UNKNOWN
}

internal fun parseJson(reply: CampusHttpReply): JSONObject? =
    runCatching { JSONObject(reply.body) }.getOrNull()

private fun spocObject(value: Any?): JSONObject = value as? JSONObject ?: invalidSpoc()

private fun invalidSpoc(message: String = "SPOC 返回的数据无法读取，请刷新后重试。"): Nothing =
    throw CampusQueryException(message)

internal fun encryptSpoc(value: String): String {
    val bytes = value.toByteArray(Charsets.UTF_8)
    val padded = bytes.copyOf((bytes.size + 15) / 16 * 16)
    val cipher = Cipher.getInstance("AES/CBC/NoPadding")
    cipher.init(
        Cipher.ENCRYPT_MODE,
        SecretKeySpec("inco12345678ocni".toByteArray(Charsets.US_ASCII), "AES"),
        IvParameterSpec("ocni12345678inco".toByteArray(Charsets.US_ASCII)),
    )
    return Base64.getEncoder().encodeToString(cipher.doFinal(padded))
}
