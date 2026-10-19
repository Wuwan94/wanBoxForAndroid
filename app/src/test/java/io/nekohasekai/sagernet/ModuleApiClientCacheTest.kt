// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package io.nekohasekai.sagernet

import io.nekohasekai.sagernet.bg.ModuleApiClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test

/**
 * 客户端缓存必须绑定凭据代次（baseUrl + certSha256）：
 * 模块重启后端口会变、证书会轮换，只按超时档位缓存会把旧连接池复用到新凭据上，
 * 表现为「凭据已更新但仍连不上 / 证书钉扎失败」。
 * @author 雾晚
 */
class ModuleApiClientCacheTest {

    private fun credentials(port: Int, certSha256: String) = ModuleApiClient.Credentials(
        baseUrl = "https://127.0.0.1:$port",
        token = "a".repeat(64),
        certSha256 = certSha256,
    )

    @Before fun clear() = ModuleApiClient.invalidateCredentials()

    @After fun cleanup() = ModuleApiClient.invalidateCredentials()

    @Test fun sameCredentialsReuseOneClient() {
        val creds = credentials(18443, "a".repeat(64))
        val first = ModuleApiClient.clientForTest(creds, ModuleApiClient.ApiTimeout.SHORT)
        val again = ModuleApiClient.clientForTest(creds, ModuleApiClient.ApiTimeout.SHORT)
        assertSame("同一凭据同一档位必须复用客户端", first, again)
        // 超时档位仍然是独立的客户端。
        val long = ModuleApiClient.clientForTest(creds, ModuleApiClient.ApiTimeout.LONG)
        assertNotSame(first, long)
        assertEquals(2, ModuleApiClient.cachedClientCount())
    }

    @Test fun rotatedPortOrCertificateNeverReusesStaleClient() {
        val first = ModuleApiClient.clientForTest(
            credentials(18443, "a".repeat(64)),
            ModuleApiClient.ApiTimeout.SHORT,
        )
        // 端口轮换
        val newPort = ModuleApiClient.clientForTest(
            credentials(18444, "a".repeat(64)),
            ModuleApiClient.ApiTimeout.SHORT,
        )
        assertNotSame(first, newPort)
        // 证书轮换（端口相同）
        val newCert = ModuleApiClient.clientForTest(
            credentials(18443, "b".repeat(64)),
            ModuleApiClient.ApiTimeout.SHORT,
        )
        assertNotSame(first, newCert)
        assertEquals(3, ModuleApiClient.cachedClientCount())
    }

    @Test fun invalidateCredentialsDropsEveryCachedClient() {
        ModuleApiClient.clientForTest(credentials(18443, "a".repeat(64)), ModuleApiClient.ApiTimeout.SHORT)
        ModuleApiClient.clientForTest(credentials(18444, "b".repeat(64)), ModuleApiClient.ApiTimeout.LONG)
        assertEquals(2, ModuleApiClient.cachedClientCount())
        ModuleApiClient.invalidateCredentials()
        assertEquals("凭据失效必须清空缓存", 0, ModuleApiClient.cachedClientCount())
    }
}
