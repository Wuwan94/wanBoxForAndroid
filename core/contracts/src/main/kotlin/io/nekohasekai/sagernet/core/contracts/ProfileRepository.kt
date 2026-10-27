// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package io.nekohasekai.sagernet.core.contracts

import kotlinx.coroutines.flow.Flow

/**
 * 节点读取与选择。真值在模块侧，实现需以管理面读回为准；
 * 本地 Room 只作缓存与草稿（差距 G2）。
 */
interface ProfileRepository {
    fun observeProfiles(): Flow<List<ProfileSummary>>

    fun observeSelected(): Flow<Selection?>

    suspend fun get(profileId: Long): ProfileSummary?

    suspend fun select(profileId: Long)

    suspend fun rename(profileId: Long, name: String)

    suspend fun delete(profileId: Long)

    suspend fun importFrom(uri: PlatformUri): ImportResult
}
