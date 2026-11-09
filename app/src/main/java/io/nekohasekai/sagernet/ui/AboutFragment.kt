package io.nekohasekai.sagernet.ui

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.util.Linkify
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.component1
import androidx.activity.result.component2
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.doOnPreDraw
import androidx.recyclerview.widget.RecyclerView
import com.danielstone.materialaboutlibrary.MaterialAboutFragment
import com.danielstone.materialaboutlibrary.items.MaterialAboutActionItem
import com.danielstone.materialaboutlibrary.model.MaterialAboutCard
import com.danielstone.materialaboutlibrary.model.MaterialAboutList
import com.google.android.material.card.MaterialCardView
import io.nekohasekai.sagernet.BuildConfig
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.databinding.LayoutAboutBinding
import io.nekohasekai.sagernet.ktx.*
import io.nekohasekai.sagernet.plugin.PluginManager.loadString
import io.nekohasekai.sagernet.utils.PackageCache
import io.nekohasekai.sagernet.utils.ArtifactDownload
import io.nekohasekai.sagernet.utils.UpdateArtifacts
import io.nekohasekai.sagernet.utils.UpdateDownload
import io.nekohasekai.sagernet.utils.UpdateRelease
import io.nekohasekai.sagernet.widget.ListListener
import libcore.Libcore
import moe.matsuri.nb4a.plugin.Plugins
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import androidx.core.net.toUri
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.DataStore
import moe.matsuri.nb4a.utils.Util
import io.nekohasekai.sagernet.core.CoreRuntime

class AboutFragment : ToolbarFragment(R.layout.layout_about) {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val binding = LayoutAboutBinding.bind(view)

        ViewCompat.setOnApplyWindowInsetsListener(view, ListListener)
        toolbar.setTitle(R.string.menu_about)

        binding.license.maxLines = LICENSE_COLLAPSED_MAX_LINES
        var isLicenseExpanded = false
        binding.licenseToggle.setOnClickListener {
            val scrollY = binding.aboutScroll.scrollY
            isLicenseExpanded = !isLicenseExpanded
            binding.license.maxLines = if (isLicenseExpanded) {
                Int.MAX_VALUE
            } else {
                LICENSE_COLLAPSED_MAX_LINES
            }
            binding.licenseIndicator.text = if (isLicenseExpanded) "▲" else "▼"
            binding.aboutScroll.doOnPreDraw {
                binding.aboutScroll.scrollTo(0, scrollY)
            }
        }

        childFragmentManager.beginTransaction()
            .replace(R.id.about_fragment_holder, AboutContent())
            .commitAllowingStateLoss()

        runOnDefaultDispatcher {
            val license = view.context.assets.open("LICENSE").bufferedReader().readText()
            onMainDispatcher {
                binding.license.text = license
                Linkify.addLinks(binding.license, Linkify.EMAIL_ADDRESSES or Linkify.WEB_URLS)
            }
        }
    }

    companion object {
        private const val LICENSE_COLLAPSED_MAX_LINES = 8
    }

    class AboutContent : MaterialAboutFragment() {

        val requestIgnoreBatteryOptimizations = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { (resultCode, _) ->
            if (resultCode == Activity.RESULT_OK) {
                parentFragmentManager.beginTransaction()
                    .replace(R.id.about_fragment_holder, AboutContent())
                    .commitAllowingStateLoss()
            }
        }

        override fun getMaterialAboutList(activityContext: Context): MaterialAboutList {
            return MaterialAboutList.Builder()
                .addCard(
                    MaterialAboutCard.Builder()
                        .outline(true)
                        // phase2: 「版本 / 检查正式版 / 检查预览版」三行合并为一行，点开再选（去重复入口）
                        .addItem(
                            MaterialAboutActionItem.Builder()
                                .icon(R.drawable.ic_minimal_refresh)
                                .text(R.string.app_version)
                                .subText(SagerNet.appVersionNameForDisplay + " · " + getString(R.string.about_check_update_hint))
                                .setOnClickAction {
                                    com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                                        .setTitle(R.string.app_version)
                                        .setItems(arrayOf(
                                            getString(R.string.check_update_release),
                                            getString(R.string.check_update_preview),
                                            getString(R.string.update_export_recent_module),
                                            getString(R.string.about_open_releases)
                                        )) { _, which ->
                                            when (which) {
                                                0 -> checkUpdate(isPreview = false)
                                                1 -> checkUpdate(isPreview = true)
                                                // APK 安装会重启应用：重启后仍能把同版模块交给 Root 管理器。
                                                2 -> exportRecentModule()
                                                else -> requireContext().launchCustomTab(
                                                    "https://github.com/Wuwan94/wanBoxForAndroid/releases"
                                                )
                                            }
                                        }
                                        .show()
                                }
                                .build())
                        .apply {
                            // @author 雾晚：瘦管理器不含内嵌核心，这一行只会重复显示应用版本，隐藏
                            if (CoreRuntime.available) addItem(
                                MaterialAboutActionItem.Builder()
                                    .icon(R.drawable.ic_minimal_layers)
                                    .text(getString(R.string.version_x, "sing-box"))
                                    .subText(CoreRuntime.versionBox())
                                    .setOnClickAction { }
                                    .build())
                        }
                        .apply {
                            PackageCache.awaitLoadSync()
                            for ((_, pkg) in PackageCache.installedPluginPackages) {
                                try {
                                    val pluginId =
                                        pkg.providers?.get(0)?.loadString(Plugins.METADATA_KEY_ID)
                                    if (pluginId.isNullOrBlank()) continue
                                    addItem(
                                        MaterialAboutActionItem.Builder()
                                            .icon(R.drawable.ic_minimal_wifi)
                                            .text(
                                                getString(
                                                    R.string.version_x,
                                                    pluginId
                                                ) + " (${Plugins.displayExeProvider(pkg.packageName)})"
                                            )
                                            .subText("v" + pkg.versionName)
                                            .setOnClickAction {
                                                startActivity(Intent().apply {
                                                    action =
                                                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS
                                                    data = Uri.fromParts(
                                                        "package", pkg.packageName, null
                                                    )
                                                })
                                            }
                                            .build())
                                } catch (e: Exception) {
                                    Logs.w(e)
                                }
                            }
                        }
                        .apply {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                val pm = app.getSystemService(Context.POWER_SERVICE) as PowerManager
                                if (!pm.isIgnoringBatteryOptimizations(app.packageName)) {
                                    addItem(
                                        MaterialAboutActionItem.Builder()
                                            .icon(R.drawable.ic_line_gauge)
                                            .text(R.string.ignore_battery_optimizations)
                                            .subText(R.string.ignore_battery_optimizations_sum)
                                            .setOnClickAction {
                                                requestIgnoreBatteryOptimizations.launch(
                                                    Intent(
                                                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                                        "package:${app.packageName}".toUri()
                                                    )
                                                )
                                            }
                                            .build())
                                }
                            }
                        }
                        .build())
                .addCard(
                    MaterialAboutCard.Builder()
                        .outline(true)
                        .title(R.string.project)
                        .addItem(
                            MaterialAboutActionItem.Builder()
                                .icon(R.drawable.ic_line_heart)
                                .text(R.string.github)
                                .setOnClickAction {
                                    requireContext().launchCustomTab(
                                        "https://github.com/Wuwan94/wanBoxForAndroid"
                                    )
                                }
                                .build())
                        .addItem(
                            MaterialAboutActionItem.Builder()
                                .icon(R.drawable.ic_line_share)
                                .text(R.string.telegram)
                                .setOnClickAction {
                                    requireContext().launchCustomTab(
                                        "https://t.me/OwnBoxs"
                                    )
                                }
                                .build())
                        .build())
                .build()

        }

        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            super.onViewCreated(view, savedInstanceState)

            view.layoutParams = (view.layoutParams ?: ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )).apply {
                height = ViewGroup.LayoutParams.WRAP_CONTENT
            }
            view.findViewById<RecyclerView>(R.id.mal_recyclerview)?.apply {
                isNestedScrollingEnabled = false
                overScrollMode = View.OVER_SCROLL_NEVER
                layoutParams = (layoutParams ?: ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )).apply {
                    height = ViewGroup.LayoutParams.WRAP_CONTENT
                }

                val cardStrokeWidth = resources.getDimensionPixelSize(
                    R.dimen.card_stroke_width
                )
                val cardStrokeColor = ContextCompat.getColor(
                    requireContext(),
                    R.color.card_stroke
                )
                val cardCornerRadius = resources.getDimension(
                    R.dimen.card_corner_radius
                )
                fun applyApplicationCardStyle(child: View) {
                    (child as? MaterialCardView)?.apply {
                        strokeWidth = cardStrokeWidth
                        strokeColor = cardStrokeColor
                        radius = cardCornerRadius
                        cardElevation = 0f
                    }
                }

                addOnChildAttachStateChangeListener(
                    object : RecyclerView.OnChildAttachStateChangeListener {
                        override fun onChildViewAttachedToWindow(child: View) {
                            applyApplicationCardStyle(child)
                        }

                        override fun onChildViewDetachedFromWindow(child: View) = Unit
                    }
                )
                for (index in 0 until childCount) {
                    applyApplicationCardStyle(getChildAt(index))
                }
            }
        }

        /**
         * 两个入口共用一条渠道规则：正式检查只选正式版，预览检查只选预览版。
         * 版本身份只取校验后的 tag_name；展示标题、发布时间和 API 数组顺序都不参与判定。
         * 分页有硬边界，选中的 Release 资产不与其他 Release 拼接。 @author 雾晚
         */
        fun checkUpdate(isPreview: Boolean = false) {
            val channel = if (isPreview) UpdateRelease.Channel.PREVIEW else UpdateRelease.Channel.FORMAL
            runOnIoDispatcher {
                try {
                    val client = CoreRuntime.httpClient().apply {
                        modernTLS()
                        tryProxyOutbound()
                    }
                    val records = mutableListOf<UpdateRelease.Record>()
                    var page: Int? = 1
                    while (page != null) {
                        val url = "https://api.github.com/repos/Wuwan94/wanBoxForAndroid/releases" +
                            "?per_page=${UpdateRelease.PAGE_SIZE}&page=$page"
                        val response = client.newRequest().apply { setURL(url) }.execute()
                        val parsed = UpdateRelease.parsePage(Util.getStringBox(response.contentString))
                        records += parsed
                        page = UpdateRelease.nextPage(page, parsed.size)
                    }
                    val selection = UpdateRelease.select(records, channel, BuildConfig.VERSION_NAME)
                    runOnMainDispatcher { showUpdateResult(selection, channel) }
                } catch (e: Exception) {
                    Logs.w(e)
                    runOnMainDispatcher {
                        Toast.makeText(app, e.readableMessage, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        private fun showUpdateResult(selection: UpdateRelease.Selection, channel: UpdateRelease.Channel) {
            val context = requireContext()
            val openReleases = {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW, "https://github.com/Wuwan94/wanBoxForAndroid/releases".toUri())
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
            val openRelease = { url: String ->
                context.startActivity(
                    Intent(Intent.ACTION_VIEW, url.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
            val record = selection.record
            when {
                selection.reason == UpdateRelease.Reason.INVALID_LOCAL_VERSION -> {
                    MaterialAlertDialogBuilder(context)
                        .setTitle(R.string.update_dialog_title)
                        .setMessage(R.string.update_local_version_unknown)
                        .setPositiveButton(R.string.update_open_release) { _, _ -> openReleases() }
                        .setNegativeButton(R.string.no, null)
                        .show()
                }
                selection.reason == UpdateRelease.Reason.NO_RELEASE || record == null -> {
                    MaterialAlertDialogBuilder(context)
                        .setTitle(R.string.update_dialog_title)
                        .setMessage(
                            if (channel == UpdateRelease.Channel.PREVIEW) R.string.update_no_preview_channel
                            else R.string.update_no_formal_channel
                        )
                        .setPositiveButton(R.string.update_open_release) { _, _ -> openReleases() }
                        .setNegativeButton(R.string.no, null)
                        .show()
                }
                !selection.newer -> {
                    Toast.makeText(app, R.string.check_update_no, Toast.LENGTH_SHORT).show()
                }
                else -> {
                    val baseMessage = context.getString(
                        R.string.update_dialog_message,
                        SagerNet.appVersionNameForDisplay,
                        record.tagName,
                    ) + "\n" + record.displayName
                    val dialog = MaterialAlertDialogBuilder(context)
                        .setTitle(R.string.update_dialog_title)
                        .setNeutralButton(R.string.update_open_release) { _, _ -> openRelease(record.htmlUrl) }
                        .setNegativeButton(R.string.no, null)
                    if (selection.bundle == null) {
                        // 资产不全：明确提示 + 发布页入口，绝不说成「已经最新」，也不跨 Release 拼文件。
                        dialog.setMessage(baseMessage + "\n" + context.getString(R.string.update_assets_missing))
                            .setPositiveButton(R.string.update_open_release) { _, _ -> openRelease(record.htmlUrl) }
                    } else {
                        dialog.setMessage(baseMessage)
                            .setPositiveButton(R.string.update_download_verify) { _, _ ->
                                downloadBundle(record.assets)
                            }
                    }
                    dialog.show()
                }
            }
        }

        /**
         * 下载同一 Release 的 APK、Root 模块与 SHA256SUMS，校验通过后才交给用户；
         * 不静默安装，模块 ZIP 通过受限 FileProvider + 系统分享交给用户保存/刷入。 @author 雾晚
         */
        private fun downloadBundle(assets: List<UpdateArtifacts.ReleaseAsset>) {
            val context = requireContext()
            val dir = File(context.cacheDir, "updates")
            runOnIoDispatcher {
                try {
                    val ready = UpdateDownload.prepare(assets, dir) { asset, target ->
                        downloadFile(asset.downloadUrl, target)
                    }
                    runOnMainDispatcher { showReadyActions(context, ready) }
                } catch (e: UpdateDownload.Failure) {
                    // Fixed localized errors never expose asset URLs, response bodies or credentials.
                    val message = when (e.reason) {
                        UpdateDownload.Reason.CHECKSUM -> R.string.update_checksum_failed
                        UpdateDownload.Reason.ASSETS -> R.string.update_assets_missing
                        UpdateDownload.Reason.STORAGE -> R.string.update_storage_failed
                        UpdateDownload.Reason.TRANSFER -> R.string.update_transfer_failed
                    }
                    runOnMainDispatcher {
                        Toast.makeText(app, message, Toast.LENGTH_LONG).show()
                    }
                }
            }
        }

        /** 校验通过后的两个明确动作：先保存模块 ZIP，再安装 APK；不要求用户手输私有路径。 */
        private fun showReadyActions(context: Context, ready: UpdateDownload.Ready) {
            val apkUri: Uri
            val moduleUri: Uri
            try {
                apkUri = FileProvider.getUriForFile(context, UPDATE_FILE_PROVIDER, ready.apk)
                moduleUri = FileProvider.getUriForFile(context, UPDATE_FILE_PROVIDER, ready.module)
            } catch (_: Exception) {
                // FileProvider 路径不可用时立即释放，不把已校验文件留在缓存里却无法交接。
                UpdateDownload.release(ready)
                Toast.makeText(app, R.string.update_install_unavailable, Toast.LENGTH_LONG).show()
                return
            }
            val installApk = {
                try {
                    context.startActivity(Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(apkUri, "application/vnd.android.package-archive")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                        clipData = ClipData.newUri(context.contentResolver, "wanBox manager APK", apkUri)
                    })
                } catch (_: Exception) {
                    UpdateDownload.release(ready)
                    Toast.makeText(app, R.string.update_install_unavailable, Toast.LENGTH_LONG).show()
                }
            }
            val saveModule = { shareModuleZip(context, moduleUri) }
            MaterialAlertDialogBuilder(context)
                .setTitle(R.string.update_ready_title)
                .setMessage(
                    context.getString(
                        R.string.update_ready_message,
                        UpdateDownload.RETENTION_MS / (60L * 60 * 1000),
                        UpdateDownload.MAX_RETAINED,
                    )
                )
                .setPositiveButton(R.string.update_save_module) { _, _ -> saveModule() }
                .setNeutralButton(R.string.update_install_apk) { _, _ -> installApk() }
                .setNegativeButton(R.string.update_later, null)
                .show()
        }

        /** 升级重启后仍可导出最近一次已校验的模块：只授予单个 ZIP 的读取 URI 权限。 */
        private fun exportRecentModule() {
            val context = requireContext()
            runOnIoDispatcher {
                val ready = UpdateDownload.recentReady(File(context.cacheDir, "updates"))
                runOnMainDispatcher {
                    if (ready == null) {
                        Toast.makeText(app, R.string.update_no_recent_module, Toast.LENGTH_LONG).show()
                    } else {
                        try {
                            val uri = FileProvider.getUriForFile(context, UPDATE_FILE_PROVIDER, ready.module)
                            shareModuleZip(context, uri)
                        } catch (_: Exception) {
                            Toast.makeText(app, R.string.update_module_export_unavailable, Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
        }

        private fun shareModuleZip(context: Context, uri: Uri) {
            try {
                val share = Intent(Intent.ACTION_SEND).apply {
                    type = "application/zip"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    clipData = ClipData.newUri(context.contentResolver, "wanBox Root module", uri)
                }
                context.startActivity(
                    Intent.createChooser(share, context.getString(R.string.update_save_module))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (_: Exception) {
                Toast.makeText(app, R.string.update_module_export_unavailable, Toast.LENGTH_LONG).show()
            }
        }

        private fun downloadFile(url: String, target: File) {
            val request = Request.Builder().url(url).build()
            downloadClient.newCall(request).execute().use { response ->
                val body = response.body
                if (!response.isSuccessful || body == null) {
                    throw IOException("HTTP ${response.code}")
                }
                body.byteStream().use { input ->
                    ArtifactDownload.write(target, input)
                }
            }
        }

        companion object {

            private val downloadClient by lazy { OkHttpClient() }

            /** 专用 FileProvider 只映射 cache/updates/，不开放整个缓存目录。 */
            private val UPDATE_FILE_PROVIDER = BuildConfig.APPLICATION_ID + ".updates"

        }

    }

}
