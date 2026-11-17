// @author 雾晚
package io.nekohasekai.sagernet.ui

import android.os.Bundle
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet

class SwitchActivity : ThemedActivity(R.layout.layout_empty),
    ConfigurationFragment.SelectCallback {

    override val isDialog = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        supportFragmentManager.beginTransaction()
            .replace(
                R.id.fragment_holder,
                ConfigurationFragment(true, null, R.string.action_switch)
            )
            .commitAllowingStateLoss()
    }

    override fun returnProfile(profileId: Long) {
        // B1/O2d: 纯节点切换走热切换通道（运行时热切不断现有连接，
        // 停止时保存选择并连接，保留快捷选择原有的“停止时也能连上”的能力）。
        SagerNet.switchProfileService(profileId)
        finish()
    }
}
