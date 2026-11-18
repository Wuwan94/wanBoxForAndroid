// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package io.nekohasekai.sagernet.ui.profile

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.databinding.LayoutProfileBinding
import io.nekohasekai.sagernet.fmt.BalancerMemberRef
import io.nekohasekai.sagernet.fmt.BalancerTierRule
import io.nekohasekai.sagernet.fmt.assignBalancerTiers
import io.nekohasekai.sagernet.fmt.decodeBalancerTierRules
import io.nekohasekai.sagernet.fmt.formatPriorityNumber
import io.nekohasekai.sagernet.fmt.movePriorityOrder
import io.nekohasekai.sagernet.fmt.reconcilePriorityOrder
import io.nekohasekai.sagernet.fmt.removePriorityOrder
import io.nekohasekai.sagernet.fmt.replacePriorityOrder
import io.nekohasekai.sagernet.fmt.sortPriorityOrderByLatencyWithinTiers
import io.nekohasekai.sagernet.ktx.onMainDispatcher
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import io.nekohasekai.sagernet.ui.ProfileSelectActivity
import io.nekohasekai.sagernet.ui.ThemedActivity

/**
 * B7c/O7 节点优先级二级页（O9d 并入此处）：策略组设置页的「节点优先级」入口打开。
 *
 * 本页只编草稿，不直接写库：成员、名称/延迟、层级规则、草稿顺序随 intent 进来，
 * 改完随结果 intent 回传，由设置页写回 DataStore 草稿（返回即自动生效，
 * 无需在本页另存）。数据流见 BalancerSettingsActivity.openPriorityPage /
 * editPriorityOrder。
 *
 * 支持：顺位标号（1, 2, 3…）、拖拽调序、滑动/按钮移除、点击替换、
 * 添加/多选节点、「按当前延迟排序」（有层级时按层级分组内延迟排优）。
 * 移除语义：散装成员同时从散装列表删除（真正离组）；分组来源成员进排除名单
 * （解析与配置生成时过滤，见 BalancerBean.priorityExcluded）。
 */
class BalancerPriorityActivity : ThemedActivity(R.layout.layout_balancer_priority) {

    companion object {
        const val EXTRA_MEMBER_IDS = "member_ids"
        const val EXTRA_MEMBER_NAMES = "member_names"
        const val EXTRA_MEMBER_PINGS = "member_pings"
        const val EXTRA_PRIORITY_ORDER = "priority_order"
        const val EXTRA_EXCLUDED_IDS = "excluded_ids"
        const val EXTRA_TIER_RULES = "tier_rules"
        const val EXTRA_LOOSE_IDS = "loose_ids"
    }

    private var orderedIds = mutableListOf<Long>()
    private var excludedIds = mutableSetOf<Long>()
    private var looseIds = mutableListOf<Long>()
    private val namesById = HashMap<Long, String>()
    private val pingById = HashMap<Long, Int?>()
    private var tiersById = mapOf<Long, Int>()
    private var tierRules = listOf<BalancerTierRule>()
    private var tierCount = 1

    private lateinit var adapter: PriorityAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar?.apply {
            setTitle(R.string.balancer_node_priority)
            setDisplayHomeAsUpEnabled(true)
        }

        val memberIds = intent.getLongArrayExtra(EXTRA_MEMBER_IDS)?.toList().orEmpty()
        val names = intent.getStringArrayExtra(EXTRA_MEMBER_NAMES).orEmpty()
        val pings = intent.getIntArrayExtra(EXTRA_MEMBER_PINGS).orEmpty()
        memberIds.forEachIndexed { index, id ->
            namesById[id] = names.getOrNull(index).orEmpty()
            pingById[id] = pings.getOrNull(index)?.takeIf { it > 0 }
        }
        val storedOrder = intent.getLongArrayExtra(EXTRA_PRIORITY_ORDER)?.toList().orEmpty()
        excludedIds = intent.getLongArrayExtra(EXTRA_EXCLUDED_IDS)?.toSet().orEmpty().toMutableSet()
        looseIds = intent.getLongArrayExtra(EXTRA_LOOSE_IDS)?.toList().orEmpty().toMutableList()
        val rules = decodeBalancerTierRules(intent.getStringExtra(EXTRA_TIER_RULES))
        tierRules = rules
        tierCount = rules.size + 1
        tiersById = assignBalancerTiers(
            memberIds.map { BalancerMemberRef(it, namesById[it].orEmpty()) },
            rules,
        ).let { tiers ->
            memberIds.mapIndexed { index, id -> id to tiers[index] }.toMap()
        }
        orderedIds = reconcilePriorityOrder(storedOrder, memberIds, excludedIds).toMutableList()

        val list = findViewById<RecyclerView>(R.id.priority_list)
        list.layoutManager = LinearLayoutManager(this)
        adapter = PriorityAdapter()
        list.adapter = adapter

        ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN, ItemTouchHelper.START
        ) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder,
            ): Boolean {
                val from = viewHolder.bindingAdapterPosition
                val to = target.bindingAdapterPosition
                if (from < 0 || to < 0) return false
                orderedIds = movePriorityOrder(orderedIds, from, to).toMutableList()
                adapter.notifyItemMoved(from, to)
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val position = viewHolder.bindingAdapterPosition
                if (position < 0 || position >= orderedIds.size) return
                removeMember(orderedIds[position])
            }
        }).attachToRecyclerView(list)

        findViewById<View>(R.id.btn_sort_by_latency)?.setOnClickListener {
            orderedIds = sortPriorityOrderByLatencyWithinTiers(
                orderedIds,
                tiersById,
                pingById,
            ).toMutableList()
            adapter.notifyDataSetChanged()
        }
        findViewById<View>(R.id.btn_add_node)?.setOnClickListener {
            selectProfileForAdd.launch(
                Intent(this, ProfileSelectActivity::class.java).apply {
                    putExtra(ProfileSelectActivity.EXTRA_MULTI_SELECT, true)
                    putExtra(
                        ProfileSelectActivity.EXTRA_SELECTED_IDS,
                        orderedIds.toLongArray(),
                    )
                }
            )
        }
    }

    /** 移除：顺序表里删除；散装成员同时从散装列表删除，分组来源成员进排除名单。 */
    private fun removeMember(memberId: Long) {
        val position = orderedIds.indexOf(memberId)
        orderedIds = removePriorityOrder(orderedIds, memberId).toMutableList()
        if (memberId in looseIds) {
            looseIds.remove(memberId)
        } else {
            excludedIds.add(memberId)
        }
        if (position >= 0) adapter.notifyItemRemoved(position)
    }

    /** 替换：原地换位，顺位不变；旧成员按移除语义处理（散装删、新散装补、分组来源进排除）。 */
    private fun replaceMember(oldId: Long, newId: Long) {
        if (newId <= 0L || newId == oldId) return
        orderedIds = replacePriorityOrder(orderedIds, oldId, newId).toMutableList()
        val newLoose = looseIds.toMutableList()
        if (oldId in newLoose) {
            if (newId in newLoose) {
                newLoose.remove(oldId)
            } else {
                newLoose[newLoose.indexOf(oldId)] = newId
            }
        } else {
            excludedIds.add(oldId)
            if (newId !in newLoose) newLoose.add(newId)
        }
        looseIds = newLoose
        excludedIds.remove(newId)
        runOnDefaultDispatcher {
            val profile = ProfileManager.getProfile(newId)
            onMainDispatcher {
                if (profile != null) {
                    val name = profile.displayName()
                    namesById[newId] = name
                    pingById[newId] = profile.ping.takeIf { it > 0 }
                    // 新成员的层级按名称正则重算（不继承被替换成员的层级，
                    // 配置生成时同样按名称重算，两边口径一致）。
                    tiersById = tiersById + (
                        newId to assignBalancerTiers(
                            listOf(BalancerMemberRef(newId, name)),
                            tierRules,
                        ).first()
                        )
                }
                adapter.notifyDataSetChanged()
            }
        }
    }

    private var replacingId = 0L

    val selectProfileForReplace =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
            val newId = result.data?.getLongExtra(ProfileSelectActivity.EXTRA_PROFILE_ID, 0L) ?: 0L
            if (replacingId > 0L) replaceMember(replacingId, newId)
            replacingId = 0L
        }

    val selectProfileForAdd =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
            val data = result.data ?: return@registerForActivityResult
            val newIds = data.getLongArrayExtra(ProfileSelectActivity.EXTRA_PROFILE_IDS)?.toList()
                .orEmpty()
            if (newIds.isEmpty()) return@registerForActivityResult
            runOnDefaultDispatcher {
                val profiles = ProfileManager.getProfiles(newIds).associateBy { it.id }
                onMainDispatcher {
                    for (id in newIds) {
                        excludedIds.remove(id)
                        if (id !in orderedIds) orderedIds.add(id)
                        if (id !in looseIds) looseIds.add(id)
                        profiles[id]?.let {
                            val name = it.displayName()
                            namesById[id] = name
                            pingById[id] = it.ping.takeIf { ping -> ping > 0 }
                            tiersById = tiersById + (
                                id to assignBalancerTiers(
                                    listOf(BalancerMemberRef(id, name)),
                                    tierRules,
                                ).first()
                                )
                        }
                    }
                    adapter.notifyDataSetChanged()
                }
            }
        }

    private fun finishWithResult() {
        setResult(Activity.RESULT_OK, Intent().apply {
            putExtra(EXTRA_PRIORITY_ORDER, orderedIds.toLongArray())
            putExtra(EXTRA_EXCLUDED_IDS, excludedIds.toLongArray())
            putExtra(EXTRA_LOOSE_IDS, looseIds.toLongArray())
        })
        finish()
    }

    override fun onBackPressed() = finishWithResult()

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finishWithResult()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun tierLabel(tierIndex: Int): String =
        if (tierIndex >= tierCount - 1) {
            getString(R.string.balancer_tier_fallback)
        } else {
            getString(R.string.balancer_tier, tierIndex + 1)
        }

    inner class PriorityAdapter : RecyclerView.Adapter<PriorityAdapter.PriorityHolder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PriorityHolder {
            return PriorityHolder(LayoutProfileBinding.inflate(layoutInflater, parent, false))
        }

        override fun onBindViewHolder(holder: PriorityHolder, position: Int) {
            holder.bind(orderedIds[position], position)
        }

        override fun getItemCount(): Int = orderedIds.size

        inner class PriorityHolder(val binding: LayoutProfileBinding) :
            RecyclerView.ViewHolder(binding.root) {

            fun bind(memberId: Long, position: Int) {
                val name = namesById[memberId].orEmpty()
                binding.profileName.text = "${formatPriorityNumber(position)}. $name"
                binding.profileType.text = tierLabel(tiersById[memberId] ?: (tierCount - 1))
                val ping = pingById[memberId]
                binding.profileAddress.text = ping?.let { "$it ms" } ?: "—"
                binding.profileAddress.isSelected = true
                binding.trafficText.isGone = true
                binding.share.isVisible = false

                binding.edit.setImageResource(R.drawable.ic_line_edit)
                binding.edit.setOnClickListener {
                    replacingId = memberId
                    selectProfileForReplace.launch(
                        Intent(
                            this@BalancerPriorityActivity,
                            ProfileSelectActivity::class.java,
                        )
                    )
                }
                binding.remove.isVisible = true
                binding.remove.setOnClickListener { removeMember(memberId) }
            }
        }
    }
}
