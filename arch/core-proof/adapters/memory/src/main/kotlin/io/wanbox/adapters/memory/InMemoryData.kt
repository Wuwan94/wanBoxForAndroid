package io.wanbox.adapters.memory

import io.wanbox.contracts.FileGateway
import io.wanbox.contracts.LogGateway
import io.wanbox.contracts.RootModuleGateway
import io.wanbox.contracts.StorageHealthPort
import io.wanbox.contracts.dto.BackupPreview
import io.wanbox.contracts.dto.ExportBundle
import io.wanbox.contracts.dto.ImportResult
import io.wanbox.contracts.dto.LogExport
import io.wanbox.contracts.dto.RootModuleInfo
import io.wanbox.contracts.dto.StorageState

/**
 * 备份文件网关。用固定 magic 头判断格式，足以验证"先校验后写入"这条规则；
 * 真实实现应换成 zip/json + 事务。
 */
class InMemoryFileGateway(private val nodes: InMemoryProfileRepository? = null) : FileGateway {

    var lastExport: ExportBundle? = null
    var importCalls: Int = 0
        private set

    override suspend fun preview(source: String): BackupPreview {
        if (!source.startsWith(MAGIC)) {
            return BackupPreview(false, 0, 0, "不是 wanbox 备份文件（缺少文件头）")
        }
        val lines = source.lineSequence().count { it.startsWith("profile\t") }
        return BackupPreview(true, lines, source.lineSequence().count { it.startsWith("group\t") })
    }

    override suspend fun importFrom(source: String): ImportResult {
        importCalls++
        if (preview(source).valid.not()) return ImportResult(0, 0, listOf("非法备份"))
        var imported = 0
        for (line in source.lineSequence().filter { it.startsWith("profile\t") }) {
            val cols = line.split('\t')
            if (cols.size < 4 || nodes == null) continue
            nodes.create(
                io.wanbox.contracts.dto.ProfileSpec(
                    protocol = cols[1],
                    name = cols[2],
                    fields = mapOf("server" to cols[3], "port" to (cols.getOrNull(4) ?: "443")),
                )
            )
            imported++
        }
        return ImportResult(imported, 0, emptyList())
    }

    override suspend fun export(includePrivate: Boolean): ExportBundle {
        val rows = buildString {
            append(MAGIC).append('\n')
            if (includePrivate) append("group\t1\t订阅A\n")
            nodes?.current?.forEach { profile ->
                append("profile\t").append(profile.protocol).append('\t')
                    .append(profile.name).append('\t').append(profile.server)
                    .append('\t').append(profile.port).append('\n')
            }
        }
        val bundle = ExportBundle(
            fileName = if (includePrivate) "wanbox-backup-private.txt" else "wanbox-backup.txt",
            mimeType = "text/plain",
            bytes = rows.toByteArray(Charsets.UTF_8),
        )
        lastExport = bundle
        return bundle
    }

    companion object {
        const val MAGIC = "wanbox-backup\tv1"
    }
}

class InMemoryLogGateway(
    private val content: String = DEFAULT_LOG,
) : LogGateway {

    var exportCalls: Int = 0
        private set

    override suspend fun export(level: String): LogExport {
        exportCalls++
        return LogExport("wanbox-log-$level.txt", content, 0)
    }

    companion object {
        val DEFAULT_LOG = """
            I/core connected profile=1 香港01
            D/http GET https://a.example/sub  Authorization: Bearer abc.def.ghi
            D/db open sager_net.db password=hunter2
            I/vmess uuid=11111111-2222-3333-4444-555555555555
            I/core token=plain-token-value
        """.trimIndent()
    }
}

class FakeRootModuleGateway(
    var installResult: RootModuleInfo = RootModuleInfo(true, "1.2.3"),
) : RootModuleGateway {

    val executed = mutableListOf<String>()

    override suspend fun exec(command: String): String {
        executed += command
        return if (command.contains("--status")) "running" else "ok"
    }

    override suspend fun install(bundle: String): RootModuleInfo = installResult

    override suspend fun installedVersion(): String? = installResult.version
}

class FakeStorage(
    var state: StorageState = StorageState.Healthy,
    var recoverable: Boolean = true,
) : StorageHealthPort {

    var recoverCalls: Int = 0
        private set

    override suspend fun check(): StorageState = state

    override suspend fun recover(): Boolean {
        recoverCalls++
        if (recoverable) state = StorageState.Healthy
        return recoverable
    }
}
