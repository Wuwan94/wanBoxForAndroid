package io.nekohasekai.sagernet.ui.profile

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.databinding.LayoutAddEntityBinding
import io.nekohasekai.sagernet.databinding.LayoutProfileBinding
import io.nekohasekai.sagernet.fmt.BALANCER_MAX_TIERS
import io.nekohasekai.sagernet.fmt.BalancerMemberRef
import io.nekohasekai.sagernet.fmt.BalancerTierRule
import io.nekohasekai.sagernet.fmt.applyPriorityOrder
import io.nekohasekai.sagernet.fmt.balancerRegexError
import io.nekohasekai.sagernet.fmt.balancerTierRulesError
import io.nekohasekai.sagernet.fmt.decodeBalancerTierRules
import io.nekohasekai.sagernet.fmt.decodePriorityOrderIds
import io.nekohasekai.sagernet.fmt.encodeBalancerTierRules
import io.nekohasekai.sagernet.fmt.formatPriorityNumber
import io.nekohasekai.sagernet.fmt.internal.BalancerBean
import io.nekohasekai.sagernet.fmt.moveTierRuleDown
import io.nekohasekai.sagernet.fmt.moveTierRuleUp
import io.nekohasekai.sagernet.fmt.removeTierRule
import io.nekohasekai.sagernet.fmt.resolveBalancerMembers
import io.nekohasekai.sagernet.ktx.*
import io.nekohasekai.sagernet.ui.ProfileSelectActivity
import io.nekohasekai.sagernet.widget.OutboundPreference
import moe.matsuri.nb4a.Protocols.getProtocolColor
import moe.matsuri.nb4a.ui.SimpleMenuPreference

class BalancerSettingsActivity : ProfileSettingsActivity<BalancerBean>(R.layout.layout_balancer_settings) {

    private lateinit var frontProxyPreference: OutboundPreference
    private lateinit var landingProxyPreference: OutboundPreference

    val selectProfileForAddFront = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (it.resultCode == Activity.RESULT_OK) runOnDefaultDispatcher {
            val profile = ProfileManager.getProfile(
                it.data!!.getLongExtra(ProfileSelectActivity.EXTRA_PROFILE_ID, 0)
            ) ?: return@runOnDefaultDispatcher
            DataStore.balancerFrontProxy = profile.id
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
            DataStore.balancerLandingProxy = profile.id
            onMainDispatcher {
                landingProxyPreference.value = OutboundPreference.VALUE_SELECT_PROFILE
            }
        }
    }

    override fun createEntity(): BalancerBean {
        val count = runCatching { SagerDatabase.proxyDao.getByType(ProxyEntity.TYPE_BALANCER).size }.getOrDefault(0) + 1
        return BalancerBean().apply {
            name = String.format("Balancer %02d", count)
        }
    }

    val proxyList = ArrayList<ProxyEntity>()

    override fun BalancerBean.init() {
        DataStore.profileName = name
        DataStore.balancerType = balancerType
        val gids = if (targetGroupIds != null && targetGroupIds.isNotEmpty()) {
            targetGroupIds
        } else if (targetGroupId > 0L) {
            listOf(targetGroupId)
        } else emptyList()
        DataStore.balancerTargetGroups = gids.joinToString(",")
        DataStore.balancerTargetGroup = gids.firstOrNull() ?: 0L
        DataStore.balancerStrategy = when (strategy) {
            "round_robin" -> BalancerBean.STRATEGY_ROUND_ROBIN
            "failover", "stable" -> BalancerBean.STRATEGY_LEAST_PING
            "consistent_hash", "consistentHash" -> BalancerBean.STRATEGY_CONSISTENT_HASH_CAMEL
            null, "" -> BalancerBean.STRATEGY_LEAST_PING
            else -> strategy
        }
        DataStore.balancerTestUrl = testUrl
        DataStore.balancerInterval = interval
        DataStore.balancerTolerance = tolerance
        DataStore.balancerToleranceUnit = if (toleranceUnit.isNullOrBlank()) "ms" else toleranceUnit
        DataStore.balancerUseFrontProxy = useFrontProxy
        DataStore.balancerUseLandingProxy = useLandingProxy
        DataStore.balancerFrontProxy = frontProxy
        DataStore.balancerFrontProxyTmp = if (frontProxy > 0L) OutboundPreference.VALUE_SELECT_PROFILE.toInt() else 0
        DataStore.balancerLandingProxy = landingProxy
        DataStore.balancerLandingProxyTmp = if (landingProxy > 0L) OutboundPreference.VALUE_SELECT_PROFILE.toInt() else 0
        DataStore.balancerNameExclude = nameExclude ?: ""
        DataStore.balancerNameInclude = nameInclude ?: ""
        DataStore.balancerMaxMembers = maxMembers
        // B7c/O7：层级规则与节点优先级顺序/排除名单（v7 字段的编辑草稿）。
        DataStore.balancerTierRules = tierRules ?: ""
        DataStore.balancerPriorityOrder = (priorityOrder ?: emptyList()).joinToString(",")
        DataStore.balancerPriorityExcluded = (priorityExcluded ?: emptyList()).joinToString(",")
        DataStore.serverProtocol = proxies.joinToString(",")
    }

    override fun BalancerBean.serialize() {
        name = DataStore.profileName
        balancerType = DataStore.balancerType
        val gids = DataStore.balancerTargetGroups.split(",")
            .mapNotNull { it.trim().toLongOrNull() }
            .filter { it > 0L }
        targetGroupIds = ArrayList(gids)
        targetGroupId = gids.firstOrNull() ?: 0L
        strategy = DataStore.balancerStrategy
        testUrl = DataStore.balancerTestUrl
        interval = DataStore.balancerInterval
        tolerance = DataStore.balancerTolerance
        toleranceUnit = DataStore.balancerToleranceUnit
        useFrontProxy = DataStore.balancerUseFrontProxy
        useLandingProxy = DataStore.balancerUseLandingProxy
        frontProxy = if (DataStore.balancerFrontProxyTmp == OutboundPreference.VALUE_SELECT_PROFILE.toInt()) {
            DataStore.balancerFrontProxy
        } else {
            -1L
        }
        landingProxy = if (DataStore.balancerLandingProxyTmp == OutboundPreference.VALUE_SELECT_PROFILE.toInt()) {
            DataStore.balancerLandingProxy
        } else {
            -1L
        }
        nameExclude = DataStore.balancerNameExclude
        nameInclude = DataStore.balancerNameInclude
        // B7a/O7：0 = 不限；非法负值归一化为 0（与 initializeDefaultValues 同口径）。
        maxMembers = DataStore.balancerMaxMembers.coerceAtLeast(0)
        // B7c/O7：层级规则与节点优先级顺序/排除名单（v7 字段的编辑草稿）。
        tierRules = DataStore.balancerTierRules
        priorityOrder = ArrayList(decodePriorityOrderIds(DataStore.balancerPriorityOrder))
        priorityExcluded = ArrayList(decodePriorityOrderIds(DataStore.balancerPriorityExcluded))
        proxies = proxyList.map { it.id }
        initializeDefaultValues()
    }

    // B7a/O7：当前编辑态拼出的 bean（与 serialize() 同字段来源），供匹配计数与保存校验共用。
    private fun currentBalancerBean(): BalancerBean {
        return BalancerBean().apply {
            balancerType = DataStore.balancerType
            val gids = DataStore.balancerTargetGroups.split(",")
                .mapNotNull { it.trim().toLongOrNull() }
                .filter { it > 0L }
            targetGroupIds = ArrayList(gids)
            targetGroupId = gids.firstOrNull() ?: 0L
            nameExclude = DataStore.balancerNameExclude
            nameInclude = DataStore.balancerNameInclude
            maxMembers = DataStore.balancerMaxMembers.coerceAtLeast(0)
            // B7c/O7：层级规则与优先级顺序/排除名单（优先级摘要与层级校验用）。
            tierRules = DataStore.balancerTierRules
            priorityOrder = ArrayList(decodePriorityOrderIds(DataStore.balancerPriorityOrder))
            priorityExcluded = ArrayList(decodePriorityOrderIds(DataStore.balancerPriorityExcluded))
            proxies = proxyList.map { it.id }
        }
    }

    private var memberCountPref: Preference? = null

    // B7a/O7：混搭来源实时匹配计数（分组正则过滤 + 散装节点去重后）。
    // 正则语法错误时摘要直接显示错误（保存时另行拦截）。
    private fun updateMemberCount() {
        val pref = memberCountPref ?: return
        val bean = currentBalancerBean()
        val resolution = runCatching {
            resolveBalancerMembers(bean) { gid ->
                runCatching { SagerDatabase.proxyDao.getByGroup(gid) }.getOrDefault(emptyList())
                    .map { BalancerMemberRef(it.id, it.displayName()) }
            }
        }.getOrNull()
        pref.summary = when {
            resolution == null -> getString(R.string.balancer_member_count_empty)
            resolution.hasRegexError -> getString(
                R.string.balancer_member_count_regex_error,
                listOfNotNull(resolution.includeRegexError, resolution.excludeRegexError).first(),
            )
            resolution.totalMatched == 0 -> getString(R.string.balancer_member_count_empty)
            else -> getString(
                R.string.balancer_member_count_matched,
                resolution.totalMatched,
                resolution.groupMatched,
                resolution.looseMatched,
                resolution.duplicatesRemoved,
            )
        }
        // B7c/O7：成员变化后优先级摘要也要刷新（数量与前 3 名预览）。
        updatePrioritySummary()
    }

    // ---- B7c/O7：层级规则编辑与节点优先级二级页 ----

    private var priorityPref: Preference? = null
    private var addTierPref: Preference? = null
    private var tierCategory: PreferenceCategory? = null

    /** 节点优先级摘要：数量 + 前 3 个节点预览（按显式优先级顺序）。 */
    private fun updatePrioritySummary() {
        val pref = priorityPref ?: return
        val bean = currentBalancerBean()
        val namesById = HashMap<Long, String>()
        proxyList.forEach { namesById[it.id] = it.displayName() }
        val resolution = runCatching {
            resolveBalancerMembers(bean) { gid ->
                runCatching { SagerDatabase.proxyDao.getByGroup(gid) }.getOrDefault(emptyList())
                    .map { BalancerMemberRef(it.id, it.displayName()) }
                    .also { refs -> refs.forEach { namesById[it.id] = it.name } }
            }
        }.getOrNull()
        val memberIds = resolution?.memberIds.orEmpty()
        if (memberIds.isEmpty()) {
            pref.summary = getString(R.string.balancer_priority_empty)
            return
        }
        val orderedIds = applyPriorityOrder(
            memberIds,
            decodePriorityOrderIds(DataStore.balancerPriorityOrder),
        )
        val preview = orderedIds.take(3).mapNotNull { namesById[it] }.joinToString(", ")
        pref.summary = getString(
            R.string.balancer_priority_summary,
            orderedIds.size,
            preview.ifBlank { "—" },
        )
    }

    /** 层级行摘要：包含正则 / 排除正则（空白包含显示 *，空白排除显示 —）。 */
    private fun tierRuleSummary(rule: BalancerTierRule): String {
        val include = rule.includeRegex.ifBlank { "*" }
        val exclude = rule.excludeRegex.ifBlank { "—" }
        return "$include / $exclude"
    }

    /** 重建层级行（每条规则一行，点开编辑；无规则时显示一行空态说明）。 */
    private fun updateTierRows() {
        val category = tierCategory ?: return
        category.removeAll()
        val rules = decodeBalancerTierRules(DataStore.balancerTierRules)
        rules.forEachIndexed { index, rule ->
            category.addPreference(
                Preference(this).apply {
                    key = "balancerTier_$index"
                    title = getString(R.string.balancer_tier, index + 1)
                    summary = tierRuleSummary(rule)
                    setOnPreferenceClickListener {
                        showTierEditor(index)
                        true
                    }
                }
            )
        }
        if (rules.isEmpty()) {
            category.addPreference(
                Preference(this).apply {
                    key = "balancerTierEmpty"
                    title = getString(R.string.balancer_tier_empty)
                    isSelectable = false
                }
            )
        }
    }

    private fun applyTierRules(rules: List<BalancerTierRule>) {
        DataStore.balancerTierRules = encodeBalancerTierRules(rules)
        DataStore.dirty = true
        updateTierRows()
    }

    /** 层级编辑弹窗：包含/排除正则 + 上移/下移/删除（同一弹窗内完成，不另开页面）。 */
    private fun showTierEditor(index: Int) {
        val rules = decodeBalancerTierRules(DataStore.balancerTierRules)
        if (index !in rules.indices) return
        val rule = rules[index]
        val density = resources.displayMetrics.density
        val pad = (16 * density).toInt()
        val includeInput = EditText(this).apply {
            setText(rule.includeRegex)
            hint = getString(R.string.balancer_tier_include)
        }
        val excludeInput = EditText(this).apply {
            setText(rule.excludeRegex)
            hint = getString(R.string.balancer_tier_exclude)
        }
        val buttonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            val moveUp = Button(this@BalancerSettingsActivity).apply {
                text = getString(R.string.balancer_tier_move_up)
            }
            val moveDown = Button(this@BalancerSettingsActivity).apply {
                text = getString(R.string.balancer_tier_move_down)
            }
            val delete = Button(this@BalancerSettingsActivity).apply {
                text = getString(R.string.balancer_tier_delete)
            }
            addView(moveUp)
            addView(moveDown)
            addView(delete)
            moveUp.setOnClickListener {
                applyTierRules(moveTierRuleUp(rules, index))
                tierDialog?.dismiss()
            }
            moveDown.setOnClickListener {
                applyTierRules(moveTierRuleDown(rules, index))
                tierDialog?.dismiss()
            }
            delete.setOnClickListener {
                applyTierRules(removeTierRule(rules, index))
                tierDialog?.dismiss()
            }
        }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            addView(buttonRow)
            addView(includeInput)
            addView(excludeInput)
        }
        tierDialog = MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.balancer_tier, index + 1))
            .setView(layout)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val updated = rules.toMutableList()
                updated[index] = BalancerTierRule(
                    includeInput.text.toString(),
                    excludeInput.text.toString(),
                )
                applyTierRules(updated)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        tierDialog?.show()
    }

    private var tierDialog: androidx.appcompat.app.AlertDialog? = null

    /** 打开节点优先级二级页：当前解析成员 + 名称/延迟 + 层级规则 + 草稿顺序随 intent 过去，改完随结果回传（见 editPriorityOrder）。 */
    private fun openPriorityPage() {
        val bean = currentBalancerBean()
        val resolution = runCatching {
            resolveBalancerMembers(bean) { gid ->
                runCatching { SagerDatabase.proxyDao.getByGroup(gid) }.getOrDefault(emptyList())
                    .map { BalancerMemberRef(it.id, it.displayName()) }
            }
        }.getOrNull() ?: return
        val memberIds = resolution.memberIds
        runOnDefaultDispatcher {
            val profiles = ProfileManager.getProfiles(memberIds).associateBy { it.id }
            val names = memberIds.map { profiles[it]?.displayName() ?: "" }.toTypedArray()
            val pings = memberIds.map { id ->
                val entity = profiles[id]
                if (entity != null && entity.ping > 0) entity.ping else -1
            }.toIntArray()
            val intent = Intent(this@BalancerSettingsActivity, BalancerPriorityActivity::class.java).apply {
                putExtra(BalancerPriorityActivity.EXTRA_MEMBER_IDS, memberIds.toLongArray())
                putExtra(BalancerPriorityActivity.EXTRA_MEMBER_NAMES, names)
                putExtra(BalancerPriorityActivity.EXTRA_MEMBER_PINGS, pings)
                putExtra(
                    BalancerPriorityActivity.EXTRA_PRIORITY_ORDER,
                    decodePriorityOrderIds(DataStore.balancerPriorityOrder).toLongArray(),
                )
                putExtra(
                    BalancerPriorityActivity.EXTRA_EXCLUDED_IDS,
                    decodePriorityOrderIds(DataStore.balancerPriorityExcluded).toLongArray(),
                )
                putExtra(BalancerPriorityActivity.EXTRA_TIER_RULES, DataStore.balancerTierRules)
                putExtra(BalancerPriorityActivity.EXTRA_LOOSE_IDS, proxyList.map { it.id }.toLongArray())
            }
            onMainDispatcher { editPriorityOrder.launch(intent) }
        }
    }

    /** 优先级页返回：顺序/排除/散装列表随结果回传，写回草稿并刷新列表与摘要（返回即自动生效，无需在二级页另存）。 */
    val editPriorityOrder = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
        val data = result.data ?: return@registerForActivityResult
        val order = data.getLongArrayExtra(BalancerPriorityActivity.EXTRA_PRIORITY_ORDER)?.toList().orEmpty()
        val excluded = data.getLongArrayExtra(BalancerPriorityActivity.EXTRA_EXCLUDED_IDS)?.toList().orEmpty()
        val loose = data.getLongArrayExtra(BalancerPriorityActivity.EXTRA_LOOSE_IDS)?.toList().orEmpty()
        DataStore.balancerPriorityOrder = order.joinToString(",")
        DataStore.balancerPriorityExcluded = excluded.joinToString(",")
        DataStore.dirty = true
        runOnDefaultDispatcher {
            val profiles = ProfileManager.getProfiles(loose).associateBy { it.id }
            val newList = loose.mapNotNull { profiles[it] }
            onMainDispatcher {
                proxyList.clear()
                proxyList.addAll(newList)
                configurationAdapter.notifyDataSetChanged()
                updateMemberCount()
                updatePrioritySummary()
            }
        }
    }

    // B7a/O7：名称正则语法错误阻止保存（delta spec 要求）；其余字段沿用既有保存路径。
    override suspend fun saveAndExit() {
        val errors = listOfNotNull(
            balancerRegexError(DataStore.balancerNameInclude),
            balancerRegexError(DataStore.balancerNameExclude),
        )
        // B7c/O7：层级正则语法错误同样阻止保存。
        val tierError = balancerTierRulesError(decodeBalancerTierRules(DataStore.balancerTierRules))
        if (errors.isNotEmpty() || tierError != null) {
            val message = buildString {
                if (tierError != null) {
                    append(getString(R.string.balancer_tier_invalid_message, tierError))
                }
                if (errors.isNotEmpty()) {
                    if (isNotEmpty()) append("\n")
                    append(getString(R.string.balancer_regex_invalid_message, errors.joinToString("\n")))
                }
            }
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.balancer_regex_invalid_title)
                .setMessage(message)
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return
        }
        super.saveAndExit()
    }

    override fun PreferenceFragmentCompat.createPreferences(
        savedInstanceState: Bundle?,
        rootKey: String?,
    ) {
        addPreferencesFromResource(R.xml.balancer_preferences)

        frontProxyPreference = findPreference("balancerFrontProxy")!!
        frontProxyPreference.apply {
            setEntries(R.array.front_proxy_entry)
            setEntryValues(R.array.front_proxy_value)
            value = DataStore.balancerFrontProxyTmp.toString()
            setOnPreferenceChangeListener { _, newValue ->
                if (newValue.toString() == OutboundPreference.VALUE_SELECT_PROFILE) {
                    selectProfileForAddFront.launch(
                        Intent(
                            this@BalancerSettingsActivity, ProfileSelectActivity::class.java
                        ).apply {
                            ProfileManager.getProfile(DataStore.balancerFrontProxy)?.let {
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

        landingProxyPreference = findPreference("balancerLandingProxy")!!
        landingProxyPreference.apply {
            setEntries(R.array.front_proxy_entry)
            setEntryValues(R.array.front_proxy_value)
            value = DataStore.balancerLandingProxyTmp.toString()
            setOnPreferenceChangeListener { _, newValue ->
                if (newValue.toString() == OutboundPreference.VALUE_SELECT_PROFILE) {
                    selectProfileForAddLanding.launch(
                        Intent(
                            this@BalancerSettingsActivity, ProfileSelectActivity::class.java
                        ).apply {
                            ProfileManager.getProfile(DataStore.balancerLandingProxy)?.let {
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

        val groupPref = findPreference<Preference>("balancerTargetGroup")
        val allGroups = runCatching { SagerDatabase.groupDao.allGroups() }.getOrDefault(emptyList())

        fun updateGroupSummary() {
            val selectedGids = DataStore.balancerTargetGroups.split(",")
                .mapNotNull { it.trim().toLongOrNull() }
                .filter { it > 0L }
                .toSet()
            val matchedGroups = allGroups.filter { selectedGids.contains(it.id) }
            groupPref?.summary = when {
                matchedGroups.isEmpty() -> getString(androidx.preference.R.string.not_set)
                matchedGroups.size <= 2 -> matchedGroups.joinToString(", ") { it.displayName() }
                else -> "${matchedGroups.size} 个分组: " + matchedGroups.take(2).joinToString(", ") { it.displayName() } + "..."
            }
        }
        updateGroupSummary()

        groupPref?.setOnPreferenceClickListener {
            val currentSelected = DataStore.balancerTargetGroups.split(",")
                .mapNotNull { it.trim().toLongOrNull() }
                .filter { it > 0L }
                .toMutableSet()
            if (currentSelected.isEmpty() && DataStore.balancerTargetGroup > 0L) {
                currentSelected.add(DataStore.balancerTargetGroup)
            }
            val groupNames = allGroups.map { g ->
                val count = runCatching { SagerDatabase.proxyDao.getByGroup(g.id).size }.getOrDefault(0)
                "${g.displayName()} ($count)"
            }.toTypedArray()
            val checkedStates = allGroups.map { currentSelected.contains(it.id) }.toBooleanArray()

            MaterialAlertDialogBuilder(this@BalancerSettingsActivity)
                .setTitle(R.string.balancer_select_group)
                .setMultiChoiceItems(groupNames, checkedStates) { _, which, isChecked ->
                    checkedStates[which] = isChecked
                }
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    val newGids = allGroups.filterIndexed { index, _ -> checkedStates[index] }.map { it.id }
                    DataStore.balancerTargetGroups = newGids.joinToString(",")
                    DataStore.balancerTargetGroup = newGids.firstOrNull() ?: 0L
                    updateGroupSummary()
                    updateMemberCount()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            true
        }

        val useFrontProxyPref = findPreference<Preference>("balancerUseFrontProxy")
        val useLandingProxyPref = findPreference<Preference>("balancerUseLandingProxy")
        val nameExcludePref = findPreference<EditTextPreference>("balancerNameExclude")
        val nameIncludePref = findPreference<EditTextPreference>("balancerNameInclude")
        val maxMembersPref = findPreference<EditTextPreference>("balancerMaxMembers")
        memberCountPref = findPreference<Preference>("balancerMemberCount")
        priorityPref = findPreference<Preference>("balancerPriority")
        addTierPref = findPreference<Preference>("balancerAddTier")
        // B7c/O7：层级行分类（动态行，见 updateTierRows）；优先级入口与添加层级
        // 只在「按优先级顺序」模式显示（见 updateStrategyVisibility）。
        val category = PreferenceCategory(this@BalancerSettingsActivity).apply {
            key = "balancerTierCategory"
            title = getString(R.string.balancer_tiers)
        }
        tierCategory = category
        preferenceScreen.addPreference(category)

        priorityPref?.setOnPreferenceClickListener {
            openPriorityPage()
            true
        }

        addTierPref?.setOnPreferenceClickListener {
            val rules = decodeBalancerTierRules(DataStore.balancerTierRules)
            if (rules.size >= BALANCER_MAX_TIERS) {
                updateTierRows()
                return@setOnPreferenceClickListener true
            }
            val newRules = rules + BalancerTierRule("", "")
            applyTierRules(newRules)
            // 新层级直接打开编辑框（空白包含 = 匹配全部，再由用户收窄）。
            showTierEditor(newRules.size - 1)
            true
        }

        fun updateTypeVisibility(type: Int) {
            // B7a/O7：混搭同时显示分组选择与散装节点列表。
            val isGroup = type == BalancerBean.TYPE_GROUP || type == BalancerBean.TYPE_MIXED
            val isList = type == BalancerBean.TYPE_LIST || type == BalancerBean.TYPE_MIXED
            groupPref?.isVisible = isGroup
            memberCountPref?.isVisible = isGroup
            useFrontProxyPref?.isVisible = isGroup
            useLandingProxyPref?.isVisible = isGroup
            nameExcludePref?.isVisible = isGroup
            nameIncludePref?.isVisible = isGroup
            configurationList.isVisible = isList
            listCell.isVisible = isList
        }

        val typePref = findPreference<SimpleMenuPreference>("balancerType")
        typePref?.setOnPreferenceChangeListener { _, newValue ->
            val type = (newValue as? String)?.toIntOrNull() ?: 0
            DataStore.balancerType = type
            updateTypeVisibility(type)
            updateMemberCount()
            true
        }

        val urlPref = findPreference<EditTextPreference>("balancerTestUrl")
        urlPref?.summaryProvider = Preference.SummaryProvider<EditTextPreference> { pref ->
            if (pref.text.isNullOrBlank()) {
                getString(androidx.preference.R.string.not_set)
            } else {
                pref.text
            }
        }

        val intervalPref = findPreference<EditTextPreference>("balancerInterval")
        intervalPref?.summaryProvider = Preference.SummaryProvider<EditTextPreference> { pref ->
            val text = pref.text?.trim()
            val value = text?.toIntOrNull()
            if (value != null && value > 0) {
                value.toString()
            } else {
                "300"
            }
        }

        val tolerancePref = findPreference<EditTextPreference>("balancerTolerance")
        val toleranceUnitPref = findPreference<SimpleMenuPreference>("balancerToleranceUnit")

        // Strategy-based visibility: only leastPing uses tolerance / url / interval probe settings
        // B7c/O7：优先级模式同样走 urltest 探测，复用同一套探针设置；
        // 层级相关入口（添加层级、层级行、节点优先级列表）只在「按优先级顺序」
        // 模式显示——「最快最稳」（leastPing）与其它模式 MUST 隐藏。
        fun updateStrategyVisibility(strategy: String) {
            val isLeastPing = strategy == BalancerBean.STRATEGY_LEAST_PING
            val isPriority = strategy == BalancerBean.STRATEGY_PRIORITY_ORDER
            tolerancePref?.isVisible = isLeastPing || isPriority
            toleranceUnitPref?.isVisible = isLeastPing || isPriority
            urlPref?.isVisible = isLeastPing || isPriority
            intervalPref?.isVisible = isLeastPing || isPriority
            priorityPref?.isVisible = isPriority
            addTierPref?.isVisible = isPriority
            tierCategory?.isVisible = isPriority
        }

        fun updateToleranceSummary() {
            val text = tolerancePref?.text?.trim()
            val unit = DataStore.balancerToleranceUnit
            val value = text?.toIntOrNull()
            tolerancePref?.summary = when {
                value != null && value >= 0 -> "$value $unit"
                unit == "s" -> "1 s"
                else -> "300 ms"
            }
        }
        updateToleranceSummary()
        updateStrategyVisibility(DataStore.balancerStrategy)

        val strategyPref = findPreference<SimpleMenuPreference>("balancerStrategy")
        strategyPref?.value = DataStore.balancerStrategy
        strategyPref?.setOnPreferenceChangeListener { _, newValue ->
            val strategy = (newValue as? String) ?: BalancerBean.STRATEGY_LEAST_PING
            DataStore.balancerStrategy = strategy
            updateStrategyVisibility(strategy)
            // B7c/O7：切到优先级模式时刷新层级行与优先级摘要；切走时隐藏（数据保留）。
            updateTierRows()
            updatePrioritySummary()
            true
        }

        tolerancePref?.setOnPreferenceChangeListener { _, newValue ->
            val str = (newValue as? String)?.trim().orEmpty()
            val v = str.toIntOrNull()
            if (v != null && v >= 0) {
                DataStore.balancerTolerance = v
                tolerancePref.text = v.toString()
                updateToleranceSummary()
            }
            true
        }

        toleranceUnitPref?.setOnPreferenceChangeListener { _, newValue ->
            val unit = (newValue as? String) ?: "ms"
            DataStore.balancerToleranceUnit = unit
            updateToleranceSummary()
            true
        }

        // Name filter summary providers
        nameExcludePref?.summaryProvider = Preference.SummaryProvider<EditTextPreference> { pref ->
            if (pref.text.isNullOrBlank()) getString(androidx.preference.R.string.not_set) else pref.text
        }
        nameIncludePref?.summaryProvider = Preference.SummaryProvider<EditTextPreference> { pref ->
            if (pref.text.isNullOrBlank()) getString(androidx.preference.R.string.not_set) else pref.text
        }

        // B7a/O7：正则改动后刷新匹配计数（值由 store 写入 DataStore 后再算，
        // 故 post 到下一帧）；非法正则只在计数摘要里提示，保存时拦截。
        fun refreshCountAfterChange(): Boolean {
            view?.post { updateMemberCount() }
            return true
        }
        nameExcludePref?.setOnPreferenceChangeListener { _, _ -> refreshCountAfterChange() }
        nameIncludePref?.setOnPreferenceChangeListener { _, _ -> refreshCountAfterChange() }

        // B7a/O7：成员上限（0 = 不限）；负值归一化为 0。
        fun updateMaxMembersSummary() {
            val text = maxMembersPref?.text?.trim()
            val value = text?.toIntOrNull()
            maxMembersPref?.summary = when {
                value != null && value > 0 -> value.toString()
                else -> getString(R.string.balancer_max_members_unlimited)
            }
        }
        updateMaxMembersSummary()
        maxMembersPref?.setOnPreferenceChangeListener { _, newValue ->
            val str = (newValue as? String)?.trim().orEmpty()
            val v = str.toIntOrNull()?.coerceAtLeast(0)
            if (v != null) {
                DataStore.balancerMaxMembers = v
                maxMembersPref.text = v.toString()
                updateMaxMembersSummary()
                view?.post { updateMemberCount() }
            }
            true
        }


        updateTypeVisibility(DataStore.balancerType)
        updateMemberCount()
        // B7c/O7：层级行与优先级摘要随策略可见性初始化（updateMemberCount 内
        // 已调用 updatePrioritySummary，此处补一次层级行）。
        updateTierRows()
        updatePrioritySummary()
    }

    lateinit var configurationList: RecyclerView
    lateinit var configurationAdapter: ProxiesAdapter
    lateinit var layoutManager: LinearLayoutManager
    lateinit var listCell: View

    @SuppressLint("InlinedApi")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        supportActionBar!!.setTitle(R.string.balancer_settings)
        configurationList = findViewById(R.id.configuration_list)
        listCell = findViewById(R.id.list_cell)
        layoutManager = FixedLinearLayoutManager(configurationList)
        configurationList.layoutManager = layoutManager
        configurationAdapter = ProxiesAdapter()
        configurationList.adapter = configurationAdapter
        configurationList.isNestedScrollingEnabled = false

        ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN, ItemTouchHelper.START
        ) {
            override fun getSwipeDirs(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
            ) = if (viewHolder is ProfileHolder) {
                super.getSwipeDirs(recyclerView, viewHolder)
            } else 0

            override fun getDragDirs(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
            ) = if (viewHolder is ProfileHolder) {
                super.getDragDirs(recyclerView, viewHolder)
            } else 0

            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder,
            ): Boolean {
                return if (target !is ProfileHolder) false else {
                    configurationAdapter.move(
                        viewHolder.bindingAdapterPosition, target.bindingAdapterPosition
                    )
                    true
                }
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                configurationAdapter.remove(viewHolder.bindingAdapterPosition)
            }

        }).attachToRecyclerView(configurationList)
    }

    override fun PreferenceFragmentCompat.viewCreated(view: View, savedInstanceState: Bundle?) {
        view.rootView.findViewById<RecyclerView>(R.id.recycler_view)?.apply {
            isNestedScrollingEnabled = false
            (layoutParams ?: ViewGroup.LayoutParams(-1, -2)).apply {
                height = -2
                layoutParams = this
            }
        }

        runOnDefaultDispatcher {
            configurationAdapter.reload()
        }
    }

    inner class ProxiesAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        suspend fun reload() {
            val idList = DataStore.serverProtocol.split(",")
                .mapNotNull { it.takeIf { it.isNotBlank() }?.toLong() }
            if (idList.isNotEmpty()) {
                val profiles = ProfileManager.getProfiles(idList).map { it.id to it }.toMap()
                for (id in idList) {
                    proxyList.add(profiles[id] ?: continue)
                }
            }
            onMainDispatcher {
                notifyDataSetChanged()
                // B7a/O7：散装节点异步加载完成后再算一次匹配计数。
                updateMemberCount()
            }
        }

        fun move(from: Int, to: Int) {
            val toMove = proxyList[to - 1]
            proxyList[to - 1] = proxyList[from - 1]
            proxyList[from - 1] = toMove
            notifyItemMoved(from, to)
            DataStore.dirty = true
        }

        fun remove(index: Int) {
            proxyList.removeAt(index - 1)
            notifyItemRemoved(index)
            DataStore.dirty = true
            updateMemberCount()
        }

        override fun getItemId(position: Int): Long {
            return if (position == 0) 0 else proxyList[position - 1].id
        }

        override fun getItemViewType(position: Int): Int {
            return if (position == 0) 0 else 1
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            return if (viewType == 0) {
                AddHolder(LayoutAddEntityBinding.inflate(layoutInflater, parent, false))
            } else {
                ProfileHolder(LayoutProfileBinding.inflate(layoutInflater, parent, false))
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            if (holder is AddHolder) {
                holder.bind()
            } else if (holder is ProfileHolder) {
                holder.bind(proxyList[position - 1])
            }
        }

        override fun getItemCount(): Int {
            return proxyList.size + 1
        }

    }

    fun testProfileAllowed(profile: ProxyEntity): Boolean {
        if (profile.id == DataStore.editingId) return false
        if (profile.id == DataStore.balancerFrontProxy || profile.id == DataStore.balancerLandingProxy) return false
        if (profile.type == ProxyEntity.TYPE_BALANCER) {
            val bean = profile.balancerBean ?: return true
            if (bean.proxies.contains(DataStore.editingId)) return false
            if (bean.frontProxy == DataStore.editingId || bean.landingProxy == DataStore.editingId) return false
        }
        return true
    }

    var replacing = 0

    val selectProfileForAdd =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) runOnDefaultDispatcher {
                DataStore.dirty = true
                val data = result.data ?: return@runOnDefaultDispatcher
                val multiIds = data.getLongArrayExtra(ProfileSelectActivity.EXTRA_PROFILE_IDS)
                if (multiIds != null) {
                    val profiles = ProfileManager.getProfiles(multiIds.toList()).associateBy { it.id }
                    var circularFound = false
                    val newProxyList = ArrayList<ProxyEntity>()
                    val seen = HashSet<Long>()
                    for (id in multiIds) {
                        if (seen.add(id)) {
                            val p = profiles[id] ?: continue
                            if (!testProfileAllowed(p)) {
                                circularFound = true
                                continue
                            }
                            newProxyList.add(p)
                        }
                    }
                    onMainDispatcher {
                        if (circularFound) {
                            MaterialAlertDialogBuilder(this@BalancerSettingsActivity)
                                .setTitle(R.string.circular_reference)
                                .setMessage(R.string.circular_reference_sum)
                                .setPositiveButton(android.R.string.ok, null)
                                .show()
                        }
                        proxyList.clear()
                        proxyList.addAll(newProxyList)
                        configurationAdapter.notifyDataSetChanged()
                        updateMemberCount()
                    }
                } else {
                    val singleId = data.getLongExtra(ProfileSelectActivity.EXTRA_PROFILE_ID, 0L)
                    if (singleId > 0L) {
                        val profile = ProfileManager.getProfile(singleId)
                        if (profile != null) {
                            if (!testProfileAllowed(profile)) {
                                onMainDispatcher {
                                    MaterialAlertDialogBuilder(this@BalancerSettingsActivity)
                                        .setTitle(R.string.circular_reference)
                                        .setMessage(R.string.circular_reference_sum)
                                        .setPositiveButton(android.R.string.ok, null)
                                        .show()
                                }
                            } else {
                                onMainDispatcher {
                                    if (replacing != 0 && replacing <= proxyList.size) {
                                        proxyList[replacing - 1] = profile
                                        configurationAdapter.notifyItemChanged(replacing)
                                    } else {
                                        if (proxyList.none { it.id == profile.id }) {
                                            proxyList.add(profile)
                                            configurationAdapter.notifyItemInserted(proxyList.size)
                                        }
                                    }
                                    updateMemberCount()
                                }
                            }
                        }
                    }
                }
            }
        }

    inner class AddHolder(val binding: LayoutAddEntityBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind() {
            binding.root.setOnClickListener {
                replacing = 0
                val excludeList = mutableListOf<Long>()
                if (DataStore.editingId > 0L) excludeList.add(DataStore.editingId)
                if (DataStore.balancerFrontProxy > 0L) excludeList.add(DataStore.balancerFrontProxy)
                if (DataStore.balancerLandingProxy > 0L) excludeList.add(DataStore.balancerLandingProxy)

                selectProfileForAdd.launch(
                    Intent(
                        this@BalancerSettingsActivity, ProfileSelectActivity::class.java
                    ).apply {
                        putExtra(ProfileSelectActivity.EXTRA_MULTI_SELECT, true)
                        putExtra(
                            ProfileSelectActivity.EXTRA_SELECTED_IDS,
                            proxyList.map { it.id }.toLongArray()
                        )
                        if (excludeList.isNotEmpty()) {
                            putExtra(ProfileSelectActivity.EXTRA_EXCLUDE_IDS, excludeList.toLongArray())
                        }
                    }
                )
            }
        }
    }

    inner class ProfileHolder(val binding: LayoutProfileBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(proxyEntity: ProxyEntity) {
            val profile = proxyEntity.requireBean()

            // B7c/O7：优先级模式下内嵌节点顺序列表显示紧凑编号（1. 名称）；
            // 其它模式保持原样（无编号）。编号 = 散装列表内下标 + 1。
            val positionNumber = if (DataStore.balancerStrategy == BalancerBean.STRATEGY_PRIORITY_ORDER) {
                "${formatPriorityNumber(bindingAdapterPosition - 1)}. "
            } else {
                ""
            }
            binding.profileName.text = positionNumber + profile.displayName()
            binding.profileType.text = proxyEntity.displayType()
            binding.profileType.setTextColor(getProtocolColor(proxyEntity.type))

            val serverAddress = profile.displayAddress().trim()
            val group = if (proxyEntity.groupId > 0L) {
                runCatching { SagerDatabase.groupDao.getById(proxyEntity.groupId) }.getOrNull()
            } else null
            val groupName = group?.takeIf { !it.ungrouped }?.name?.trim()?.takeIf {
                it.isNotBlank() && it != "null" && it != "[]"
            }

            val detailText = if (groupName != null) {
                if (serverAddress.isNotBlank()) "$serverAddress      [$groupName]" else "[$groupName]"
            } else {
                serverAddress
            }

            binding.profileAddress.text = detailText
            binding.profileAddress.isSelected = true
            (binding.profileAddress.parent as View).isVisible = detailText.isNotBlank()
            binding.trafficText.text = ""
            binding.trafficText.isGone = true

            binding.edit.setImageResource(R.drawable.ic_line_edit)
            binding.edit.setOnClickListener {
                replacing = bindingAdapterPosition
                selectProfileForAdd.launch(
                    Intent(
                        this@BalancerSettingsActivity, ProfileSelectActivity::class.java
                    )
                )
            }
            binding.remove.isVisible = true
            binding.remove.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos > 0 && pos <= proxyList.size) {
                    proxyList.removeAt(pos - 1)
                    configurationAdapter.notifyItemRemoved(pos)
                    updateMemberCount()
                }
            }
            binding.share.isVisible = false
        }
    }
}
