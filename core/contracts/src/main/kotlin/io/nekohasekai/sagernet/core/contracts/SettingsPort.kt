// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package io.nekohasekai.sagernet.core.contracts

import kotlinx.coroutines.flow.Flow

/** 取代 DataStore 的双面角色：偏好与会话分开暴露。 */
interface SettingsPort {
    fun observeSettings(): Flow<AppSettings>

    suspend fun update(settings: AppSettings)

    interface Session {
        fun observeSession(): Flow<SessionState>

        suspend fun selectGroup(groupId: Long)
    }
}
