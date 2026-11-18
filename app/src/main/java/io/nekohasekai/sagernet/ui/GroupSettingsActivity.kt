package io.nekohasekai.sagernet.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.content.DialogInterface
import android.content.Intent
import android.os.Bundle
import android.os.Parcelable
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.LayoutRes
import androidx.appcompat.app.AlertDialog
import androidx.core.view.ViewCompat
import androidx.preference.*
import com.github.shadowsocks.plugin.Empty
import com.github.shadowsocks.plugin.fragment.AlertDialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.GroupType
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.SubscriptionFilterMode
import io.nekohasekai.sagernet.database.*
import io.nekohasekai.sagernet.database.preference.OnPreferenceDataStoreChangeListener
import io.nekohasekai.sagernet.group.GroupUpdater
import io.nekohasekai.sagernet.group.RawUpdater
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.applyDefaultValues
import io.nekohasekai.sagernet.ktx.onMainDispatcher
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import io.nekohasekai.sagernet.widget.ListListener
import io.nekohasekai.sagernet.widget.OutboundPreference
import kotlinx.parcelize.Parcelize
import moe.matsuri.nb4a.ui.SimpleMenuPreference

@Suppress("UNCHECKED_CAST")
class GroupSettingsActivity(
    @LayoutRes resId: Int = R.layout.layout_config_settings,
) : ThemedActivity(resId),
    OnPreferenceDataStoreChangeListener {

    private lateinit var frontProxyPreference: OutboundPreference
    private lateinit var landingProxyPreference: OutboundPreference

    fun ProxyGroup.init() {
        DataStore.groupName = name ?: ""
        DataStore.groupType = type
        DataStore.groupOrder = order
        DataStore.groupIsSelector = isSelector
        DataStore.groupIsUrlTest = DataStore.groupIsUrlTest(id)
        DataStore.groupIsLoadBalance = DataStore.groupIsLoadBalance(id)
        DataStore.groupUrlTestUrlOverride = DataStore.readAndMigrateGroupUrlTestUrlOverride(id)
        DataStore.groupUrlTestInterval = DataStore.groupUrlTestInterval(id).toInt()
        DataStore.groupUrlTestTolerance = DataStore.groupUrlTestTolerance(id)
        DataStore.groupUrlTestIdleTimeout = DataStore.groupUrlTestIdleTimeout(id)
        // N4: interrupt_exist_connections is locked off (clampUrlTestParams); never surface a stale true.
        DataStore.groupUrlTestInterruptExist = false

        DataStore.frontProxy = frontProxy
        DataStore.landingProxy = landingProxy
        DataStore.frontProxyTmp =
            if (frontProxy >= 0) OutboundPreference.VALUE_SELECT_PROFILE.toInt() else 0
        DataStore.landingProxyTmp =
            if (landingProxy >= 0) OutboundPreference.VALUE_SELECT_PROFILE.toInt() else 0

        val subscription = subscription ?: SubscriptionBean().applyDefaultValues()
        DataStore.subscriptionLink = subscription.link
        DataStore.subscriptionForceResolve = subscription.forceResolve
        DataStore.subscriptionDeduplication = subscription.deduplication
        DataStore.subscriptionUpdateWhenConnectedOnly = subscription.updateWhenConnectedOnly
        DataStore.subscriptionUserAgent = subscription.customUserAgent
        DataStore.subscriptionLockUserAgent = subscription.lockUserAgent ?: false
        DataStore.subscriptionAutoUpdate = subscription.autoUpdate
        // B2/O5: clamp legacy sub-15-minute values to the WorkManager minimum on load.
        DataStore.subscriptionAutoUpdateDelay = (subscription.autoUpdateDelay ?: 360).coerceAtLeast(15)
        DataStore.subscriptionFilterMode = subscription.filterMode
        DataStore.subscriptionFilterRegex = subscription.filterRegex
        // Throne upstream 尚未支持自定义服务器 DNS，入口停用期间不再加载其 UI 缓存。
        // 保留原代码以便未来恢复功能：
        // DataStore.subscriptionServerDns = subscription.serverDnsResolver ?: ""
    }

    fun ProxyGroup.serialize() {
        // An edited group's identity is locked to its database row; the saved draft carries no name truth.
        val lockedIdentity = io.nekohasekai.sagernet.group.editor.GroupEditorStore.read(id)
        val rawName = if (lockedIdentity != null) displayName() else DataStore.groupName.trim()
        name = if (rawName.isNotBlank() && !RawUpdater.isDefaultGroupName(rawName)) {
            rawName
        } else if (type == GroupType.SUBSCRIPTION) {
            val candidate = DataStore.subscriptionLink.takeIf { it.isNotBlank() }?.let { link: String ->
                RawUpdater.extractAirportName(link)
            }
            candidate ?: (if (rawName.isNotBlank()) rawName else ("Subscription #" + System.currentTimeMillis()))
        } else {
            if (rawName.isNotBlank()) rawName else "My group"
        }
        if (lockedIdentity == null) type = DataStore.groupType
        order = DataStore.groupOrder
        isSelector = DataStore.groupIsSelector
        DataStore.setGroupIsUrlTest(id, DataStore.groupIsUrlTest)
        DataStore.setGroupIsLoadBalance(id, DataStore.groupIsLoadBalance)
        DataStore.setGroupUrlTestUrl(id, DataStore.groupUrlTestUrlOverride)
        DataStore.setGroupUrlTestInterval(id, DataStore.groupUrlTestInterval.toLong())
        DataStore.setGroupUrlTestTolerance(id, DataStore.groupUrlTestTolerance)
        DataStore.setGroupUrlTestIdleTimeout(id, DataStore.groupUrlTestIdleTimeout)
        // N4: locked off, matching the builder and the editor; saving normalizes legacy values.
        DataStore.setGroupUrlTestInterruptExist(id, false)

        frontProxy =
            if (DataStore.frontProxyTmp == OutboundPreference.VALUE_SELECT_PROFILE.toInt()) {
                DataStore.frontProxy
            } else {
                -1
            }
        landingProxy =
            if (DataStore.landingProxyTmp == OutboundPreference.VALUE_SELECT_PROFILE.toInt()) {
                DataStore.landingProxy
            } else {
                -1
            }

        val isSubscription = type == GroupType.SUBSCRIPTION
        if (isSubscription) {
            subscription = (subscription ?: SubscriptionBean().applyDefaultValues()).apply {
                link = DataStore.subscriptionLink
                forceResolve = DataStore.subscriptionForceResolve
                deduplication = DataStore.subscriptionDeduplication
                updateWhenConnectedOnly = DataStore.subscriptionUpdateWhenConnectedOnly
                customUserAgent = DataStore.subscriptionUserAgent
                lockUserAgent = DataStore.subscriptionLockUserAgent
                autoUpdate = DataStore.subscriptionAutoUpdate
                autoUpdateDelay = DataStore.subscriptionAutoUpdateDelay.coerceAtLeast(15)
                filterMode = DataStore.subscriptionFilterMode
                filterRegex = DataStore.subscriptionFilterRegex
                // 入口停用期间不要用未展示的 UI 缓存覆盖历史数据库值。
                // 保留原代码以便未来恢复功能：
                // serverDnsResolver = DataStore.subscriptionServerDns
            }
        }
    }

    private var editorIdentityLocked = false
    private var isFromClipboard = false

    fun needSave(): Boolean {
        return DataStore.dirty
    }

    fun PreferenceFragmentCompat.createPreferences(
        savedInstanceState: Bundle?,
        rootKey: String?,
    ) {
        addPreferencesFromResource(R.xml.group_preferences)
        if (editorIdentityLocked) {
            findPreference<Preference>(Key.GROUP_NAME)?.isEnabled = false
            findPreference<Preference>(Key.GROUP_TYPE)?.isEnabled = false
            findPreference<Preference>(Key.GROUP_IS_SELECTOR)?.isEnabled = false
        }

        frontProxyPreference = findPreference(Key.GROUP_FRONT_PROXY)!!
        frontProxyPreference.apply {
            setEntries(R.array.front_proxy_entry)
            setEntryValues(R.array.front_proxy_value)
            value = DataStore.frontProxyTmp.toString()
            setOnPreferenceChangeListener { _, newValue ->
                if (newValue.toString() == OutboundPreference.VALUE_SELECT_PROFILE) {
                    selectProfileForAddFront.launch(
                        Intent(
                            this@GroupSettingsActivity, ProfileSelectActivity::class.java
                        ).apply {
                            ProfileManager.getProfile(DataStore.frontProxy)?.let {
                                putExtra(ProfileSelectActivity.EXTRA_SELECTED, it)
                            }
                        }
                    )
                    false
                } else {
                    true
                }
            }
        }
        landingProxyPreference = findPreference(Key.GROUP_LANDING_PROXY)!!
        landingProxyPreference.apply {
            setEntries(R.array.front_proxy_entry)
            setEntryValues(R.array.front_proxy_value)
            value = DataStore.landingProxyTmp.toString()
            setOnPreferenceChangeListener { _, newValue ->
                if (newValue.toString() == OutboundPreference.VALUE_SELECT_PROFILE) {
                    selectProfileForAddLanding.launch(
                        Intent(
                            this@GroupSettingsActivity, ProfileSelectActivity::class.java
                        ).apply {
                            ProfileManager.getProfile(DataStore.landingProxy)?.let {
                                putExtra(ProfileSelectActivity.EXTRA_SELECTED, it)
                            }
                        }
                    )
                    false
                } else {
                    true
                }
            }
        }

        val groupType = findPreference<SimpleMenuPreference>(Key.GROUP_TYPE)!!
        val groupSubscription = findPreference<PreferenceCategory>(Key.GROUP_SUBSCRIPTION)!!
        val subscriptionUpdate = findPreference<PreferenceCategory>(Key.SUBSCRIPTION_UPDATE)!!

        fun updateGroupType(groupType: Int = DataStore.groupType) {
            val isSubscription = groupType == GroupType.SUBSCRIPTION
            groupSubscription.isVisible = isSubscription
            subscriptionUpdate.isVisible = isSubscription
        }
        updateGroupType()
        groupType.setOnPreferenceChangeListener { _, newValue ->
            updateGroupType((newValue as String).toInt())
            true
        }


        val subscriptionAutoUpdate =
            findPreference<SwitchPreference>(Key.SUBSCRIPTION_AUTO_UPDATE)!!
        // B2/O5: plain Preference opening the preset/custom dialog; the minute
        // value itself is unchanged (DataStore.subscriptionAutoUpdateDelay).
        val subscriptionAutoUpdateDelay =
            findPreference<Preference>(Key.SUBSCRIPTION_AUTO_UPDATE_DELAY)!!

        fun updateDelaySummary(enabled: Boolean = subscriptionAutoUpdate.isChecked) {
            subscriptionAutoUpdateDelay.isEnabled = enabled
            subscriptionAutoUpdateDelay.summary = SubscriptionAutoUpdateDialog.formatSummary(
                requireContext(),
                DataStore.subscriptionAutoUpdateDelay,
                enabled
            )
        }
        updateDelaySummary()

        subscriptionAutoUpdateDelay.setOnPreferenceClickListener {
            SubscriptionAutoUpdateDialog.show(
                requireContext(),
                DataStore.subscriptionAutoUpdateDelay
            ) { newMinutes ->
                DataStore.subscriptionAutoUpdateDelay = newMinutes
                DataStore.dirty = true
                updateDelaySummary()
            }
            true
        }

        subscriptionAutoUpdate.setOnPreferenceChangeListener { _, newValue ->
            val enabled = newValue as Boolean
            DataStore.subscriptionAutoUpdate = enabled
            DataStore.dirty = true
            updateDelaySummary(enabled)
            true
        }

        val subscriptionFilterMode =
            findPreference<SimpleMenuPreference>(Key.SUBSCRIPTION_FILTER_MODE)!!
        val subscriptionFilterRegex =
            findPreference<EditTextPreference>(Key.SUBSCRIPTION_FILTER_REGEX)!!

        fun updateFilterMode(filterMode: Int = DataStore.subscriptionFilterMode) {
            subscriptionFilterRegex.isVisible = filterMode != SubscriptionFilterMode.DISABLED
        }
        updateFilterMode()
        subscriptionFilterMode.setOnPreferenceChangeListener { _, newValue ->
            updateFilterMode((newValue as String).toInt())
            true
        }

        // Throne upstream 尚未支持自定义服务器 DNS，XML 入口及对应监听同步停用。
        // 若仅移除 XML 而继续强制查找 Preference，findPreference() 会返回 null，
        // 随后的 !! 会使整个设置页进入 createPreferences 异常处理。
        /* 保留原代码以便未来恢复功能：
        val subscriptionServerDns =
            findPreference<EditTextPreference>(Key.SUBSCRIPTION_SERVER_DNS)!!
        subscriptionServerDns.setOnPreferenceChangeListener { pref, newValue ->
            val value = (newValue as String).trim()
            if (isValidServerDns(value)) {
                if (value != newValue) {
                    (pref as EditTextPreference).text = value
                    false
                } else {
                    true
                }
            } else {
                Toast.makeText(
                    requireContext(),
                    R.string.server_dns_invalid,
                    Toast.LENGTH_LONG,
                ).show()
                false
            }
        }
        */
    }

    class UnsavedChangesDialogFragment : AlertDialogFragment<Empty, Empty>() {
        override fun AlertDialog.Builder.prepare(listener: DialogInterface.OnClickListener) {
            setTitle(R.string.unsaved_changes_prompt)
            setPositiveButton(R.string.yes) { _, _ ->
                runOnDefaultDispatcher {
                    (requireActivity() as GroupSettingsActivity).saveAndExit()
                }
            }
            setNegativeButton(R.string.no) { _, _ ->
                requireActivity().finish()
            }
            setNeutralButton(android.R.string.cancel, null)
        }
    }

    @Parcelize
    data class GroupIdArg(val groupId: Long) : Parcelable
    class DeleteConfirmationDialogFragment : AlertDialogFragment<GroupIdArg, Empty>() {
        override fun AlertDialog.Builder.prepare(listener: DialogInterface.OnClickListener) {
            setTitle(R.string.delete_group_prompt)
            setPositiveButton(R.string.yes) { _, _ ->
                val activity = requireActivity()
                runOnDefaultDispatcher {
                    try {
                        GroupManager.deleteGroup(arg.groupId)
                    } catch (blocked: GroupManager.GroupReferencedByRoutesException) {
                        // N6：阻断时不关闭设置页，对话框定位到该组并可跳转到引用规则。
                        val groupName = SagerDatabase.groupDao.getById(blocked.groupId)?.displayName()
                            ?: return@runOnDefaultDispatcher
                        onMainDispatcher {
                            MaterialAlertDialogBuilder(activity)
                                .setTitle(groupName)
                                .setMessage(activity.getString(R.string.route_policy_group_blocked_delete,
                                    groupName, blocked.ruleName))
                                .setPositiveButton(R.string.route_policy_group_review_rule) { _, _ ->
                                    activity.startActivity(Intent(activity, RouteSettingsActivity::class.java).apply {
                                        putExtra(RouteSettingsActivity.EXTRA_ROUTE_ID, blocked.ruleId)
                                    })
                                }
                                .setNegativeButton(android.R.string.cancel, null)
                                .show()
                        }
                        return@runOnDefaultDispatcher
                    }
                    onMainDispatcher { activity.finish() }
                }
            }
            setNegativeButton(R.string.no, null)
        }
    }

    companion object {
        const val EXTRA_GROUP_ID = "id"
        const val EXTRA_FROM_CLIPBOARD = "fromClipboard"
        const val EXTRA_GROUP_SUBSCRIPTION_LINK = "subscription_link"
    }

    @SuppressLint("CommitTransaction")
    override fun onCreate(savedInstanceState: Bundle?) {
        editorIdentityLocked = savedInstanceState?.getBoolean("editorIdentityLocked", true) ?: true
        super.onCreate(savedInstanceState)
        setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar?.apply {
            setTitle(R.string.group_settings)
            setDisplayHomeAsUpEnabled(true)
            setHomeAsUpIndicator(R.drawable.ic_navigation_close)
        }

        if (savedInstanceState == null) {
            val editingId = intent.getLongExtra(EXTRA_GROUP_ID, 0L)
            isFromClipboard = intent.getBooleanExtra(EXTRA_FROM_CLIPBOARD, false)
            val subscriptionLink = intent.getStringExtra(EXTRA_GROUP_SUBSCRIPTION_LINK)
            DataStore.editingId = editingId
            runOnDefaultDispatcher {
                if (editingId == 0L) {
                    val group = ProxyGroup()
                    group.init()
                    
                    // 如果有订阅链接，设置为订阅类型并填充链接
                    if (!subscriptionLink.isNullOrEmpty()) {
                        DataStore.groupType = GroupType.SUBSCRIPTION
                        DataStore.subscriptionLink = subscriptionLink
                        val candidate = RawUpdater.extractAirportName(subscriptionLink)
                        if (!candidate.isNullOrBlank()) {
                            DataStore.groupName = candidate
                        }
                        DataStore.dirty = true
                    }
                } else {
                    val entity = SagerDatabase.groupDao.getById(editingId)
                    if (entity == null) {
                        onMainDispatcher {
                            finish()
                        }
                        return@runOnDefaultDispatcher
                    }
                    entity.init()
                }

                editorIdentityLocked = io.nekohasekai.sagernet.group.editor.GroupEditorStore.read(editingId) != null
                onMainDispatcher {
                    supportFragmentManager.beginTransaction()
                        .replace(R.id.settings, MyPreferenceFragmentCompat())
                        .commit()

                    DataStore.dirty = false
                    DataStore.profileCacheStore.registerChangeListener(this@GroupSettingsActivity)
                }
            }

        }

    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("editorIdentityLocked", editorIdentityLocked)
        super.onSaveInstanceState(outState)
    }

    suspend fun saveAndExit() {

        val editingId = DataStore.editingId
        if (editingId == 0L) {
            val newGroup = GroupManager.createGroup(ProxyGroup().apply { serialize() })
            if (isFromClipboard && newGroup.type == GroupType.SUBSCRIPTION && !newGroup.subscription?.link.isNullOrEmpty()) {
                GroupUpdater.startUpdate(newGroup, true)
            }
        } else if (needSave()) {
            val entity = SagerDatabase.groupDao.getById(DataStore.editingId)
            if (entity == null) {
                finish()
                return
            }
            val keepUserInfo = (entity.type == GroupType.SUBSCRIPTION &&
                    DataStore.groupType == GroupType.SUBSCRIPTION &&
                    entity.subscription?.link == DataStore.subscriptionLink)
            if (!keepUserInfo) {
                entity.subscription?.subscriptionUserinfo = "";
            }
            GroupManager.updateGroup(entity.apply { serialize() })
            if (DataStore.serviceState.canStop) {
                SagerNet.reloadService()
            }
        }

        finish()

    }

    val child by lazy { supportFragmentManager.findFragmentById(R.id.settings) as MyPreferenceFragmentCompat }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.profile_config_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem) = child.onOptionsItemSelected(item)

    override fun onBackPressed() {
        if (needSave()) {
            UnsavedChangesDialogFragment().apply { key() }.show(supportFragmentManager, null)
        } else super.onBackPressed()
    }

    override fun onSupportNavigateUp(): Boolean {
        if (!super.onSupportNavigateUp()) finish()
        return true
    }

    override fun onDestroy() {
        DataStore.profileCacheStore.unregisterChangeListener(this)
        super.onDestroy()
    }

    override fun onPreferenceDataStoreChanged(store: PreferenceDataStore, key: String) {
        if (key != Key.PROFILE_DIRTY) {
            DataStore.dirty = true
        }
    }

    class MyPreferenceFragmentCompat : PreferenceFragmentCompat() {

        var activity: GroupSettingsActivity? = null

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            preferenceManager.preferenceDataStore = DataStore.profileCacheStore
            try {
                activity = (requireActivity() as GroupSettingsActivity).apply {
                    createPreferences(savedInstanceState, rootKey)
                }
            } catch (e: Exception) {
                Toast.makeText(
                    SagerNet.application,
                    "Error on createPreferences, please try again.",
                    Toast.LENGTH_SHORT
                ).show()
                Logs.e(e)
            }
        }

        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            super.onViewCreated(view, savedInstanceState)

            ViewCompat.setOnApplyWindowInsetsListener(listView, ListListener)
            setDivider(null)
            setDividerHeight(0)
        }

        override fun onOptionsItemSelected(item: MenuItem) = when (item.itemId) {
            R.id.action_delete -> {
                if (DataStore.editingId == 0L) {
                    requireActivity().finish()
                } else {
                    DeleteConfirmationDialogFragment().apply {
                        arg(GroupIdArg(DataStore.editingId))
                        key()
                    }.show(parentFragmentManager, null)
                }
                true
            }

            R.id.action_apply -> {
                runOnDefaultDispatcher {
                    activity?.saveAndExit()
                }
                true
            }

            else -> false
        }

    }

    object PasswordSummaryProvider : Preference.SummaryProvider<EditTextPreference> {

        override fun provideSummary(preference: EditTextPreference): CharSequence {
            val text = preference.text
            return if (text.isNullOrBlank()) {
                preference.context.getString(androidx.preference.R.string.not_set)
            } else {
                "\u2022".repeat(text.length)
            }
        }

    }

    val selectProfileForAddFront = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (it.resultCode == Activity.RESULT_OK) runOnDefaultDispatcher {
            val profile = ProfileManager.getProfile(
                it.data!!.getLongExtra(ProfileSelectActivity.EXTRA_PROFILE_ID, 0)
            ) ?: return@runOnDefaultDispatcher
            DataStore.frontProxy = profile.id
            onMainDispatcher {
                frontProxyPreference.value = OutboundPreference.VALUE_SELECT_PROFILE
            }
        }
    }

    val selectProfileForAddLanding = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (it.resultCode == Activity.RESULT_OK) runOnDefaultDispatcher {
            val profile = ProfileManager.getProfile(
                it.data!!.getLongExtra(ProfileSelectActivity.EXTRA_PROFILE_ID, 0)
            ) ?: return@runOnDefaultDispatcher
            DataStore.landingProxy = profile.id
            onMainDispatcher {
                landingProxyPreference.value = OutboundPreference.VALUE_SELECT_PROFILE
            }
        }
    }

}

/* 自定义服务器 DNS 的 UI 恢复时一并恢复此校验函数。
private fun isValidServerDns(raw: String): Boolean {
    val value = raw.trim()
    if (value.isEmpty()) return true
    if (value.any { it.isISOControl() || it.isWhitespace() }) return false

    if (value.contains("://")) {
        val scheme = value.substringBefore("://").lowercase()
        if (scheme !in setOf("https", "tls", "quic")) return false
        val rest = value.substringAfter("://")
        val host = rest.substringBefore("/").substringBefore("?")
        val bare = host.substringBeforeLast(":").trim('[', ']')
        return bare.isNotEmpty()
    }

    val host = value.substringBeforeLast(":").trim('[', ']')
    return host.isNotEmpty()
}
*/
