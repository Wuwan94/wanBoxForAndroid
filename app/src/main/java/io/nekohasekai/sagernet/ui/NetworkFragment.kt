package io.nekohasekai.sagernet.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import io.nekohasekai.sagernet.core.CoreRuntime
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.databinding.LayoutNetworkBinding
import io.nekohasekai.sagernet.ktx.app
import moe.matsuri.nb4a.utils.SendLog
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class NetworkFragment : NamedFragment(R.layout.layout_network) {

    override fun name0() = app.getString(R.string.tools_network)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val binding = LayoutNetworkBinding.bind(view)

        // @author 雾晚：STUN 探测依赖内嵌核心；瘦管理器（仅 Root 模块）里直接隐藏入口。
        binding.stunCard.isVisible = CoreRuntime.available
        // remove(D2): 瘦构建不绑定 STUN 入口
        if (CoreRuntime.available) binding.stunTest.setOnClickListener {
            startActivity(Intent(requireContext(), StunActivity::class.java))
        }

        binding.dnsLeakTest.setOnClickListener {
            binding.dnsLeakResult.visibility = View.VISIBLE
            binding.dnsLeakResult.text = getString(R.string.testing_dns)
            binding.dnsLeakTest.isEnabled = false

            viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                val sb = StringBuilder()
                try {
                    // 1. Check DNS resolution for Fake-IP
                    val testHost = "www.google.com"
                    val resolvedIps = try {
                        InetAddress.getAllByName(testHost).mapNotNull { it.hostAddress }.distinct()
                    } catch (e: Exception) {
                        emptyList()
                    }

                    val fakeIp = resolvedIps.firstOrNull { it.startsWith("198.18.") || it.startsWith("198.19.") }
                    if (fakeIp != null) {
                        sb.append("• Fake-IP 状态: 已生效 ($fakeIp)\n")
                    } else if (resolvedIps.isEmpty()) {
                        sb.append("• DNS 解析: 失败\n")
                    } else {
                        sb.append("• 解析 IP: ${resolvedIps.joinToString()} (未检测到 Fake-IP)\n")
                    }

                    // 2. Check egress IP & resolver via public API
                    val client = OkHttpClient.Builder()
                        .connectTimeout(5, TimeUnit.SECONDS)
                        .readTimeout(5, TimeUnit.SECONDS)
                        .callTimeout(5, TimeUnit.SECONDS)
                        .build()

                    val req = Request.Builder().url("https://1.1.1.1/cdn-cgi/trace").build()
                    val trace = try {
                        client.newCall(req).execute().use { response ->
                            if (response.isSuccessful) response.body?.string() else null
                        }
                    } catch (_: Exception) {
                        null
                    }

                    if (!trace.isNullOrBlank()) {
                        val lines = trace.lines().associate {
                            val parts = it.split("=")
                            if (parts.size == 2) parts[0] to parts[1] else "" to ""
                        }
                        val ip = lines["ip"]
                        val loc = lines["loc"]
                        sb.append("• 出口公网 IP: ${ip ?: "未知"} (${loc ?: "未知"})\n")
                        sb.append("• Cloudflare trace: 可达（仅表示接口连通，不代表无 DNS 泄漏）\n")
                    } else {
                        sb.append("• Cloudflare trace 未响应（无法据此判断 DNS 泄漏）\n")
                    }
                } catch (e: Exception) {
                    sb.append("• 检测完成: ${e.message}")
                }

                withContext(Dispatchers.Main.immediate) {
                    binding.dnsLeakResult.text = sb.toString().trim()
                    binding.dnsLeakTest.isEnabled = true
                }
            }
        }

        binding.exportCrashLog.setOnClickListener {
            SendLog.sendLog(requireContext(), "OWN_Diagnostics")
        }
    }
}
