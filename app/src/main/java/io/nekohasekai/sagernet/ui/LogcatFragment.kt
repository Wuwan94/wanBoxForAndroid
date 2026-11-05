package io.nekohasekai.sagernet.ui

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import androidx.lifecycle.lifecycleScope
import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
import android.text.style.ForegroundColorSpan
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.widget.Toolbar
import androidx.core.view.ViewCompat
import androidx.core.view.doOnLayout
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.databinding.LayoutLogcatBinding
import io.nekohasekai.sagernet.ktx.*
import io.nekohasekai.sagernet.widget.ListListener
import libcore.Libcore
import moe.matsuri.nb4a.utils.SendLog
import io.nekohasekai.sagernet.core.CoreRuntime

class LogcatFragment : ToolbarFragment(R.layout.layout_logcat),
    Toolbar.OnMenuItemClickListener {

    lateinit var binding: LayoutLogcatBinding

    @SuppressLint("RestrictedApi", "WrongConstant")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        toolbar.setTitle(R.string.menu_log)

        toolbar.inflateMenu(R.menu.logcat_menu)
        toolbar.setOnMenuItemClickListener(this)

        binding = LayoutLogcatBinding.bind(view)

        if (Build.VERSION.SDK_INT >= 23) {
            binding.textview.breakStrategy = 0 // simple
        }

        ViewCompat.setOnApplyWindowInsetsListener(binding.root, ListListener)

        reloadSession()
    }

    private var reloadJob: kotlinx.coroutines.Job? = null

    /**
     * phase2: log file read + span building moved off the main thread, and the module's recent
     * events (wanboxctl logs → events.json, incl. disconnect reason categories 核心退出/网络切换/
     * 节点失效/TUN 失败/未知) are shown above the app log.
     */
    private fun reloadSession() {
        val ctx = requireContext().applicationContext
        val themed = requireContext()
        val colors = mapOf(
            R.color.wb_state_error to themed.getColor(R.color.wb_state_error),
            R.color.wb_state_warning to themed.getColor(R.color.wb_state_warning),
            R.color.wb_state_connected to themed.getColor(R.color.wb_state_connected),
            R.color.minimal_secondary to themed.getColor(R.color.minimal_secondary),
            R.color.minimal_text to themed.getColor(R.color.minimal_text),
        )
        reloadJob?.cancel()
        reloadJob = viewLifecycleOwner.lifecycleScope.launch {
            val text = withContext(Dispatchers.IO) {
                val out = android.text.SpannableStringBuilder()
                val events = try {
                    io.nekohasekai.sagernet.bg.RootModuleClient.call("logs").events
                } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { null }
                val header = ctx.getString(R.string.module_events_title)
                out.append(header + "\n", ForegroundColorSpan(colors.getValue(R.color.minimal_text)), SPAN_EXCLUSIVE_EXCLUSIVE)
                if (events.isNullOrEmpty()) {
                    out.append(ctx.getString(R.string.module_events_empty) + "\n", ForegroundColorSpan(colors.getValue(R.color.minimal_secondary)), SPAN_EXCLUSIVE_EXCLUSIVE)
                } else {
                    val fmt = java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.ROOT)
                    for (event in events.takeLast(40)) {
                        val code = event.code
                        val color = when {
                            code.startsWith("disconnect_") || code.startsWith("core_exit_") || code.contains("limit") || code.contains("failed") -> R.color.wb_state_error
                            code == "node_unhealthy" || code.contains("rebuil") || code.contains("skipped") -> R.color.wb_state_warning
                            code.startsWith("reconnect_") || code.contains("started") || code.contains("failover") -> R.color.wb_state_connected
                            else -> R.color.minimal_secondary
                        }
                        val time = if (event.time > 0) fmt.format(java.util.Date(event.time * (if (event.time < 100_000_000_000L) 1000L else 1L))) else "--"
                        out.append("$time  ${io.nekohasekai.sagernet.bg.RootModuleErrors.eventLabel(ctx, code)}  [$code]\n",
                            ForegroundColorSpan(colors.getValue(color)), SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                }
                out.append("\n")
                for (line in String(SendLog.getNekoLog(50 * 1024)).lines()) {
                    val res = when {
                        line.contains("ERROR[") || line.contains(" [Error]") -> R.color.wb_state_error
                        line.contains("WARN[") || line.contains(" [Warning]") -> R.color.wb_state_warning
                        line.contains("INFO[") || line.contains(" [Info]") -> R.color.wb_state_connected
                        else -> R.color.minimal_secondary
                    }
                    out.append(line + "\n", ForegroundColorSpan(colors.getValue(res)), SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                out
            }
            binding.textview.text = text
            binding.textview.clearFocus()
            // 等 textview 完成最终 layout 再滚动到底部
            binding.textview.doOnLayout {
                binding.scroolview.scrollTo(0, binding.textview.height)
            }
        }
    }

    override fun onMenuItemClick(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_clear_logcat -> {
                runOnDefaultDispatcher {
                    try {
                        CoreRuntime.clearLog()
                        Runtime.getRuntime().exec("/system/bin/logcat -c")
                    } catch (e: Exception) {
                        onMainDispatcher {
                            snackbar(e.readableMessage).show()
                        }
                        return@runOnDefaultDispatcher
                    }
                    onMainDispatcher {
                        binding.textview.text = ""
                    }
                }

            }

            R.id.action_send_logcat -> {
                val context = requireContext()
                runOnDefaultDispatcher {
                    SendLog.sendLog(context, "OWN")
                }
            }

            R.id.action_refresh -> {
                reloadSession()
            }
        }
        return true
    }

}