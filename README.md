# 卡拉彼丘 Wiki 语音下载器 (CalabiyauWikiVoice)

这是一个使用 Kotlin 编写的工具，用于从卡拉彼丘 Wiki (wiki.biligame.com/klbq/) 上批量下载指定角色的语音文件。

This is a tool written in Kotlin for batch downloading character voice lines from the Calabiyau(Strinova) Wiki (wiki.biligame.com/klbq/).

## ✨ 功能 (Features)

*   **批量下载**: 输入角色 Wiki 页面的 URL，即可下载该页面上所有的语音文件。
*   **多语言支持**: 可通过修改常量选择下载中文 (CN)、日文 (JP) 或英文 (EN) 语音。
*   **结构兼容**: 能够解析 Wiki 页面上两种常见的语音表格结构 (`rowspan="3"` 和 `rowspan="2"`）。
*   **并发下载**: 利用 Kotlin 协程进行并发下载，显著提高下载效率 (可配置并发数)。
*   **文件名处理**:
    *   自动处理重复的语音条目名 (如多个“选择角色”)，生成带序号的唯一文件名 (例如 `选择角色_CN(1).mp3`)。
    *   自动添加语言后缀 (如 `_CN`, `_JP`, `_EN`)。
    *   清理文件名中的非法字符，确保跨平台兼容性。
*   **目录清理**: 可选在每次运行时自动清空目标保存目录。
*   **配置简单**: 通过修改 Kotlin 脚本顶部的常量即可轻松配置。

## 🚀 先决条件 (Prerequisites)

*   **JDK (Java Development Kit)**: 需要 JDK 8 或更高版本。请确保已正确安装并配置环境变量。你可以通过在终端运行 `java -version` 来检查。
*   **Gradle (推荐)**: 虽然可以直接使用 Kotlin 编译器 (`kotlinc`)，但推荐使用 Gradle 来管理依赖和构建项目。你可以从 [Gradle 官网](https://gradle.org/install/) 下载安装，或使用 IDE (如 IntelliJ IDEA) 自带的 Gradle 功能。

## ⚙️ 配置 (Configuration)

在运行脚本之前，请打开 `Main.kt`，修改文件顶部的常量：

```kotlin
// --- Constants ---
// 目标角色 Wiki 页面 URL
// const val TARGET_URL = "https://wiki.biligame.com/klbq/%E5%BF%A7%E9%9B%BE" // 忧雾
const val TARGET_URL = "https://wiki.biligame.com/klbq/%E6%98%9F%E7%BB%98" // 星绘 (测试 rowspan=2)

// 语音文件保存目录 (请确保路径有效)
const val SAVE_DIR = "E:/角色语音/"  // Windows 示例路径
// const val SAVE_DIR = "./角色语音/" // Linux/macOS 或相对路径示例

// 是否在运行前清空保存目录 (true: 清空, false: 不清空)
const val CLEAR_DIR = true

// 同时进行的最大下载数 (根据网络情况调整)
const val MAX_CONCURRENT_DOWNLOADS = 10

// --- 选择下载语言 ---
// 可选值: "CN", "JP", "EN"
const val DOWNLOAD_LANGUAGE = "CN" // 修改这里来选择语言
```

*   `TARGET_URL`: 必须修改为你想要下载语音的角色的 Wiki 页面完整 URL。
*   `SAVE_DIR`: 下载文件的保存路径。请使用你操作系统支持的格式。相对路径 (如 `./角色语音/`) 也是可以的。
*   `CLEAR_DIR`: 设置为 `true` 会在下载开始前删除 `SAVE_DIR` 目录下的所有内容。**请谨慎使用！**
*   `MAX_CONCURRENT_DOWNLOADS`: 同时下载的文件数量，可以根据你的网络状况调整。
*   `DOWNLOAD_LANGUAGE`: 设置为 `"CN"`, `"JP"`, 或 `"EN"` 来选择下载的语音语言。注意：部分角色或语音条目可能没有所有语言。

## 🤔 工作原理 (How it Works)

1.  使用 OkHttp 获取指定 `TARGET_URL` 的 HTML 内容。
2.  使用 Jsoup 解析 HTML DOM 结构。
3.  查找包含 `rowspan="3"` 或 `rowspan="2"` 属性的 `<td>` 元素，这些元素标记了语音条目的开始。
4.  根据 `DOWNLOAD_LANGUAGE` 常量和 HTML 结构 (判断是 `rowspan=3` 还是 `rowspan=2`)，定位到包含目标语言 MP3 链接的 `<td>` 元素。
5.  对每个有效的语音条目：
    *   提取并清理语音名称 (如 "选择角色")。
    *   结合语言后缀生成基础文件名 (如 "选择角色_CN")。
    *   使用基于 `ConcurrentHashMap` 的内存原子计数器和 `Mutex` 锁，为相同基础文件名的条目分配唯一的序号 (0, 1, 2, ...)，生成最终文件名 (如 `选择角色_CN.mp3`, `选择角色_CN(1).mp3`)。
    *   使用 Kotlin 协程 (`launch`, `Dispatchers.IO`) 启动一个并发下载任务。
    *   使用 `Semaphore` 控制同时进行的下载任务不超过 `MAX_CONCURRENT_DOWNLOADS`。
    *   使用 OkHttp 执行实际的文件下载。
6.  使用 `joinAll()` 等待所有下载协程执行完毕。

## 🤝 贡献 (Contributing)

欢迎各种形式的贡献！如果你发现任何 Bug 或有改进建议，请随时提出 Issue 或提交 Pull Request。

Possible areas for contribution:
*   改进错误处理和日志记录。
*   添加对更多 Wiki 结构或网站的支持。
*   增加命令行参数配置代替硬编码常量。
*   添加图形用户界面 (GUI)。

## 📄 许可证 (License)

该项目采用 [MIT](LICENSE.txt) 许可证。

## 🙏 致谢 (Acknowledgements)

*   [Jsoup](https://jsoup.org/): 用于解析 HTML。
*   [OkHttp](https://square.github.io/okhttp/): 用于执行 HTTP 网络请求。
*   [Kotlin Coroutines](https://kotlinlang.org/docs/coroutines-overview.html): 用于实现并发下载。
*   [卡拉彼丘 Wiki](https://wiki.biligame.com/klbq/): 提供语音数据来源。
```