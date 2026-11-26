package io.nekohasekai.sagernet.ui.profile

import io.nekohasekai.sagernet.fmt.v2ray.VMessBean

class VMessSettingsActivity : StandardV2RaySettingsActivity() {

    override fun createEntity() = if (intent?.getBooleanExtra("vless", false) == true) {
        createVlessBean(useXhttp = intent?.getBooleanExtra("xhttp", false) == true)
    } else {
        VMessBean().apply { initializeDefaultValues() }
    }

}

internal fun createVlessBean(useXhttp: Boolean): VMessBean = VMessBean().apply {
    alterId = -1
    if (useXhttp) type = "xhttp"
    initializeDefaultValues()
}
