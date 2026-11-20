// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package io.nekohasekai.sagernet.bg

import io.nekohasekai.sagernet.BootReceiver
import io.nekohasekai.sagernet.database.DataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 开机自启的生产装配：模块命令 + 回读 + DataStore/BootReceiver。
 *
 * 用独立的应用级 IO 作用域，所以离开设置页也不会丢掉一次已经开始的下发；
 * 只有「模块回读确认」之后才写 DataStore 与 BOOT_COMPLETED 接收器。
 */
object AutoStartSettings {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val sync = AutoStartSync(object : AutoStartSync.Host {
        override suspend fun writeModule(enabled: Boolean) {
            RootModuleClient.setAutoStart(enabled)
        }

        override suspend fun readModule(): Boolean? = RootModuleClient.callFreshStatus().autoStart

        override fun confirmed(): Boolean = DataStore.persistAcrossReboot

        override fun persist(enabled: Boolean) {
            DataStore.persistAcrossReboot = enabled
            BootReceiver.enabled = enabled
        }
    })

    fun request(enabled: Boolean, onResult: (AutoStartSync.Outcome) -> Unit) {
        scope.launch {
            onResult(sync.request(enabled))
        }
    }

    suspend fun check(): AutoStartSync.Mismatch? = sync.check()

    /** 任何来源改了持久值（开关、备份恢复、旧版本升级）都让接收器立刻跟上。 */
    fun syncBootReceiver() {
        BootReceiver.enabled = DataStore.persistAcrossReboot
    }
}
