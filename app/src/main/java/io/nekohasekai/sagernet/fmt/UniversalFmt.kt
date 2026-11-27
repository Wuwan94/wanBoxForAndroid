package io.nekohasekai.sagernet.fmt

import androidx.annotation.Keep
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.ProxyGroup
import moe.matsuri.nb4a.proxy.anytls.AnyTLSBean
import moe.matsuri.nb4a.utils.Util

@Keep
fun parseUniversal(link: String): AbstractBean {
    return if (link.contains("?")) {
        val type = link.substringAfter("sn://").substringBefore("?")
        ProxyEntity(type = TypeMap[type] ?: error("Type $type not found")).apply {
            putByteArray(Util.zlibDecompress(Util.b64Decode(link.substringAfter("?"))))
        }.requireBean()
    } else {
        val type = link.substringAfter("sn://").substringBefore(":")
        ProxyEntity(type = TypeMap[type] ?: error("Type $type not found")).apply {
            putByteArray(Util.b64Decode(link.substringAfter(":").substringAfter(":")))
        }.requireBean()
    }
}

/** 分享前的副本：AnyTLS 的 mTLS 客户端证书与私钥只留在本机，不进入任何分享链接。原 bean 不变。 */
internal fun AbstractBean.shareableCopy(): AbstractBean = when (this) {
    is AnyTLSBean -> clone().apply {
        clientCertificate = ""
        clientKey = ""
    }
    else -> this
}

@Keep
fun AbstractBean.toUniversalLink(): String {
    val sharedBean = shareableCopy()
    var link = "sn://"
    link += TypeMap.reversed[ProxyEntity().putBean(sharedBean).type]
    link += "?"
    link += Util.b64EncodeUrlSafe(Util.zlibCompress(KryoConverters.serialize(sharedBean), 9))
    return link
}


fun ProxyGroup.toUniversalLink(): String {
    var link = "sn://subscription?"
    export = true
    link += Util.b64EncodeUrlSafe(Util.zlibCompress(KryoConverters.serialize(this), 9))
    export = false
    return link
}
