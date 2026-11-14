package io.nekohasekai.sagernet.route

import io.nekohasekai.sagernet.database.RuleEntity
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.fmt.KryoConverters

/** Ephemeral route inputs. Mutable Room entities never escape the frozen storage. */
class RootRouteBuildSnapshot private constructor(
    private val rules: List<RuleEntity>,
    private val profiles: Map<Long, ByteArray>,
) {
    fun enabledRules(): List<RuleEntity> = rules.map { it.copy(packages = it.packages.toSet()) }
    fun allProfiles(): List<ProxyEntity> = profiles.values.map {
        KryoConverters.deserializeStrict(ProxyEntity(), it)
    }
    fun issues(): List<OutboundReferenceValidator.Issue> =
        OutboundReferenceValidator.validate(enabledRules(), profiles.keys)

    companion object {
        suspend fun capture(): RootRouteBuildSnapshot {
            // Preserve existing rule initialization/migration before freezing the build inputs.
            io.nekohasekai.sagernet.database.ProfileManager.getRules()
            return SagerDatabase.instance.runInTransaction(
                java.util.concurrent.Callable {
                    RootRouteBuildSnapshot(
                        SagerDatabase.rulesDao.enabledRules().map { it.copy(packages = it.packages.toSet()) },
                        SagerDatabase.proxyDao.getAll().associate { it.id to KryoConverters.serialize(it) },
                    )
                }
            )
        }
    }
}
