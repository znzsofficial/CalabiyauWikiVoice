package com.nekolaska

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.net.URLEncoder
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Collections
import java.util.Scanner
import java.util.concurrent.atomic.AtomicInteger

// === 配置区域 ===
const val API_BASE_URL = "https://wiki.biligame.com/klbq/api.php"
const val SAVE_ROOT_DIR = "E:/角色语音/"
const val MAX_CONCURRENT_DOWNLOADS = 16

// === 工具 ===
val client = OkHttpClient.Builder()
    .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
    .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
    .build()

val jsonParser = Json { ignoreUnknownKeys = true; coerceInputValues = true }

// === 数据结构 ===
data class CharacterGroup(
    val characterName: String,     // 角色名 "香奈美"
    val rootCategory: String,      // 主分类 "Category:香奈美语音"
    val subCategories: List<String> // ["Category:香奈美语音", "Category:香奈美个人剧情语音"...]
)

// === API 模型 ===
@Serializable data class WikiResponse(val query: WikiQuery? = null, @SerialName("continue") val continuation: Map<String, String>? = null)
@Serializable data class WikiQuery(val search: List<SearchItem>? = null, val categorymembers: List<CategoryMember>? = null, val pages: Map<String, WikiPage>? = null)
@Serializable data class SearchItem(val title: String)
@Serializable data class CategoryMember(val ns: Int, val title: String)
@Serializable data class WikiPage(val title: String, val imageinfo: List<ImageInfo>? = null)
@Serializable data class ImageInfo(val url: String? = null, val mime: String? = null)

fun main(): Unit = runBlocking {
    val scanner = Scanner(System.`in`)

    println("正在连接 Wiki 获取语音分类列表...")

    // 1. 获取并清洗列表
    val rawList = searchCategories("语音")
    val validList = rawList.filter { it.endsWith("语音") }

    if (validList.isEmpty()) {
        println("未找到分类。")
        return@runBlocking
    }

    // 2. 智能分组
    val groups = groupCategories(validList)

    // 3. 显示角色列表 (一级菜单)
    println("\n=== 角色列表 ===")
    groups.forEachIndexed { index, group ->
        val countInfo = if (group.subCategories.size > 1) "含 ${group.subCategories.size} 个子项" else "单项"
        println(String.format("[%2d] %-12s (%s)", index + 1, group.characterName, countInfo))
    }

    println("\n请输入序号 (多选: 1,3  全选: A  退出: Q): ")
    val input = scanner.nextLine().trim()
    val selectedGroups = parseGroupSelection(input, groups)

    if (selectedGroups.isEmpty()) return@runBlocking

    // 4. 处理选中的角色
    for (group in selectedGroups) {
        println("\n========================================")
        println(">>> 正在处理: ${group.characterName}")

        // --- 核心逻辑分支 ---
        val finalCategories = if (group.subCategories.size > 1) {
            // 分支 A: 多个分类 -> 进入二级选择
            println("    该角色包含多个相关分类，请选择要下载的内容:")

            // 排序：把主分类(最短的)放第一位，其他的按名字排序
            val sortedSubs = group.subCategories.sortedWith(compareBy({ it.length }, { it }))

            sortedSubs.forEachIndexed { idx, cat ->
                val displayName = cat.replace("Category:", "").replace("分类:", "")
                // 标记主分类
                val mark = if (cat == group.rootCategory) " (★主分类)" else ""
                println(String.format("    [%2d] %s%s", idx + 1, displayName, mark))
            }

            println("\n    [A] 全选 (默认)  [序号] 只选指定 (如 1,3)  [S] 跳过此角色")
            print("    请输入: ")
            val subInput = scanner.nextLine().trim()

            if (subInput.equals("S", true)) {
                println("    已跳过。")
                emptyList()
            } else {
                parseStringSelection(subInput, sortedSubs)
            }
        } else {
            // 分支 B: 只有一个分类 -> 直接下载，不打扰用户
            println("    锁定分类: ${group.subCategories.first().replace("Category:", "")}")
            group.subCategories
        }

        if (finalCategories.isEmpty()) continue

        // 5. 执行下载
        println("    正在扫描音频文件...")
        val allFiles = fetchAudioFilesFromCategories(finalCategories.toSet())
        val uniqueFiles = allFiles.distinctBy { it.second } // URL去重

        if (uniqueFiles.isNotEmpty()) {
            println("    共找到 ${uniqueFiles.size} 个文件，准备下载...")

            val saveDir = File(SAVE_ROOT_DIR, sanitizeFileName(group.characterName))
            if (!saveDir.exists()) saveDir.mkdirs()

            val semaphore = Semaphore(MAX_CONCURRENT_DOWNLOADS)
            val counter = AtomicInteger(0)

            uniqueFiles.map { (name, url) ->
                launch(Dispatchers.IO) {
                    semaphore.acquire()
                    try {
                        var safeName = sanitizeFileName(name)
                        // 补全后缀
                        if (!safeName.contains(".")) {
                            if (url.endsWith(".ogg")) safeName += ".ogg"
                            else if (url.endsWith(".mp3")) safeName += ".mp3"
                        }

                        downloadFile(url, File(saveDir, safeName))

                        val c = counter.incrementAndGet()
                        if (c % 10 == 0 || c == uniqueFiles.size) {
                            print("\r    下载进度: $c / ${uniqueFiles.size}")
                        }
                    } finally {
                        semaphore.release()
                    }
                }
            }.joinAll()
            println("\n    [完成] ${group.characterName}")
        } else {
            println("    [提示] 未找到音频文件。")
        }
    }
    println("\n所有任务已结束。")
}

// === 辅助逻辑 ===

/**
 * 智能分组算法
 */
fun groupCategories(rawList: List<String>): List<CharacterGroup> {
    val cleanMap = rawList.associateWith { it.replace(Regex("^(Category:|分类:)"), "") }
    val sortedItems = cleanMap.entries.sortedBy { it.value.length } // 短名优先，作为 Root 候选

    val groups = mutableListOf<CharacterGroup>()
    val assigned = mutableSetOf<String>()

    for ((originalName, cleanName) in sortedItems) {
        if (assigned.contains(originalName)) continue

        val coreName = cleanName.removeSuffix("语音") // 提取 "香奈美"
        if (coreName.isBlank()) continue

        // 查找家族成员：包含核心词且以语音结尾
        val familyMembers = rawList.filter { raw ->
            val cl = cleanMap[raw]!!
            cl.startsWith(coreName) && cl.endsWith("语音")
        }

        groups.add(CharacterGroup(coreName, originalName, familyMembers))
        assigned.addAll(familyMembers)
    }
    return groups.sortedBy { it.characterName }
}

fun parseGroupSelection(input: String, source: List<CharacterGroup>): List<CharacterGroup> {
    if (input.equals("Q", true)) return emptyList()
    if (input.equals("A", true) || input.isBlank()) return source
    val res = mutableListOf<CharacterGroup>()
    input.split("[,\\s]+".toRegex()).forEach {
        it.toIntOrNull()?.let { idx -> if (idx in 1..source.size) res.add(source[idx - 1]) }
    }
    return res
}

fun parseStringSelection(input: String, source: List<String>): List<String> {
    if (input.equals("A", true) || input.isBlank()) return source
    val res = mutableListOf<String>()
    input.split("[,\\s]+".toRegex()).forEach {
        it.toIntOrNull()?.let { idx -> if (idx in 1..source.size) res.add(source[idx - 1]) }
    }
    return res
}

suspend fun searchCategories(keyword: String): List<String> = withContext(Dispatchers.IO) {
    val encoded = URLEncoder.encode(keyword, "UTF-8")
    val url = "$API_BASE_URL?action=query&list=search&srsearch=$encoded&srnamespace=14&format=json&srlimit=500"
    val json = fetchString(url) ?: return@withContext emptyList()
    try { jsonParser.decodeFromString<WikiResponse>(json).query?.search?.map { it.title } ?: emptyList() } catch (_: Exception) { emptyList() }
}

suspend fun fetchAudioFilesFromCategories(categories: Set<String>): List<Pair<String, String>> = withContext(Dispatchers.IO) {
    val results = Collections.synchronizedList(mutableListOf<Pair<String, String>>())
    categories.map { cat -> async { results.addAll(getCategoryFilesDetail(cat)) } }.awaitAll()
    results
}

suspend fun getCategoryFilesDetail(category: String): List<Pair<String, String>> {
    val list = mutableListOf<Pair<String, String>>()
    val encoded = URLEncoder.encode(category, "UTF-8")
    var token: String? = null
    do {
        val cArg = if (token != null) "&gcmcontinue=$token" else ""
        val url = "$API_BASE_URL?action=query&generator=categorymembers&gcmtitle=$encoded&gcmnamespace=6&prop=imageinfo&iiprop=url|mime&format=json&gcmlimit=500$cArg"
        val json = fetchString(url) ?: break
        try {
            val res = jsonParser.decodeFromString<WikiResponse>(json)
            res.query?.pages?.values?.forEach { p ->
                val i = p.imageinfo?.firstOrNull()
                if (i?.url != null && (i.mime?.startsWith("audio/") == true || i.url.endsWith(".ogg") || i.url.endsWith(".mp3"))) {
                    list.add(p.title.replace(Regex("^(File:|文件:)"), "") to i.url)
                }
            }
            token = res.continuation?.get("gcmcontinue")
        } catch (_: Exception) { break }
    } while (token != null)
    return list
}

suspend fun fetchString(url: String): String? = withContext(Dispatchers.IO) {
    try { client.newCall(Request.Builder().url(url).build()).execute().use { if (it.isSuccessful) it.body.string() else null } } catch (_: Exception) { null }
}

fun downloadFile(url: String, targetFile: File) {
    if (targetFile.exists() && targetFile.length() > 0) return
    try {
        client.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (response.isSuccessful) {
                if (!targetFile.parentFile.exists()) targetFile.parentFile.mkdirs()
                val tmp = File(targetFile.parent, targetFile.name + ".tmp")
                response.body.byteStream().use { input -> tmp.outputStream().use { output -> input.copyTo(output) } }
                if (tmp.exists()) Files.move(tmp.toPath(), targetFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        }
    } catch (_: Exception) { }
}
fun sanitizeFileName(name: String) = name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim()