package io.wanbox.domain

import io.wanbox.contracts.dto.ExportBundle
import io.wanbox.contracts.dto.ImportResult
import io.wanbox.contracts.dto.LogExport
import io.wanbox.contracts.dto.StorageState

// ---------------- UC-DATA-01 导出配置 ----------------

suspend fun AppCore.exportConfig(includePrivate: Boolean = true): ExportBundle {
    val bundle = files.export(includePrivate)
    logger.info("core", "export ${bundle.fileName} (${bundle.bytes.size} bytes, private=$includePrivate)")
    return bundle
}

// ---------------- UC-DATA-02 导入备份 ----------------

/**
 * 先 preview 校验，再落库。校验不通过时一个字节都不写——满足"失败不改动现有数据"。
 */
suspend fun AppCore.importBackup(source: String): ImportResult {
    val preview = files.preview(source)
    if (!preview.valid) {
        val reason = preview.error ?: "备份文件无效"
        logger.warn("core", "import backup rejected: $reason")
        return ImportResult(0, 0, listOf(reason))
    }
    val result = files.importFrom(source)
    logger.info("core", "import backup: +${result.imported} skipped=${result.skipped} groups=${preview.groups}")
    publish()
    return result
}

// ---------------- UC-DATA-03 数据库损坏恢复 ----------------

/** true = 数据可用（原本健康或已恢复）；false = 损坏且恢复失败，界面应进恢复页。 */
suspend fun AppCore.checkAndRecoverStorage(): Boolean {
    val state = storage.check()
    if (state == StorageState.Healthy) return true
    logger.warn("core", "storage corrupted, attempting recovery")
    val recovered = storage.recover()
    if (recovered) {
        logger.info("core", "storage recovered")
        publish()
    } else {
        logger.warn("core", "storage recovery failed")
    }
    return recovered
}

// ---------------- UC-LOG-01 导出日志（脱敏） ----------------

suspend fun AppCore.exportLogs(level: String = "info"): LogExport {
    val raw = logs.export(level)
    val (clean, redacted) = LogRedactor.redact(raw.content)
    logger.info("core", "log export ${raw.fileName}, redacted=$redacted entries")
    return raw.copy(content = clean, redactedCount = redacted)
}

/**
 * 日志脱敏规则属业务，不是平台能力，所以放在 core。
 * 覆盖：password / passwd / pwd、Bearer、secret / token / apiKey、uuid。
 */
object LogRedactor {

    private class Rule(val regex: Regex, val render: (MatchResult) -> String)

    private val RULES = listOf(
        Rule(Regex("(?i)(password|passwd|pwd)(\\s*[=:]\\s*)(\\S+)")) { m ->
            m.groupValues[1] + m.groupValues[2] + "***"
        },
        Rule(Regex("(?i)(bearer)(\\s+)([A-Za-z0-9._~+/=-]+)")) { m ->
            m.groupValues[1] + " ***"
        },
        Rule(Regex("(?i)(secret|token|api[_-]?key)(\\s*[=:]\\s*)(\\S+)")) { m ->
            m.groupValues[1] + m.groupValues[2] + "***"
        },
        Rule(
            Regex(
                "(?i)(uuid)(\\s*[=:]\\s*)([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})"
            )
        ) { m -> m.groupValues[1] + m.groupValues[2] + "***" },
    )

    /** 返回 脱敏后文本 to 命中条数 */
    fun redact(text: String): Pair<String, Int> {
        var output = text
        var hits = 0
        for (rule in RULES) {
            output = rule.regex.replace(output) { match ->
                hits++
                rule.render(match)
            }
        }
        return output to hits
    }
}
