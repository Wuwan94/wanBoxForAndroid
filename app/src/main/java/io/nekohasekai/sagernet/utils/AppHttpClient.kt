// @author 雾晚
package io.nekohasekai.sagernet.utils

import io.nekohasekai.sagernet.database.DataStore
import okhttp3.Credentials
import okhttp3.OkHttpClient
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit

/** HTTP for foreground-only app tasks; proxy traffic goes through the Root module's local mixed inlet. */
object AppHttpClient {
    fun create(useModuleProxy: Boolean = DataStore.serviceState.connected): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .addInterceptor { chain ->
                SecureNetwork.requireSecure(chain.request().url)
                chain.proceed(chain.request())
            }
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .callTimeout(90, TimeUnit.SECONDS)
        if (useModuleProxy && !DataStore.mixedInboundDisabled) {
            builder.proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", DataStore.mixedPort)))
            if (DataStore.mixedInboundNeedsAuth) {
                builder.proxyAuthenticator { _, response ->
                    if (response.request.header("Proxy-Authorization") != null) null
                    else response.request.newBuilder()
                        .header("Proxy-Authorization", Credentials.basic(DataStore.mixedUsername, DataStore.mixedPassword))
                        .build()
                }
            }
        }
        return builder.build()
    }
}
