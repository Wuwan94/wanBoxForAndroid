package io.nekohasekai.sagernet.fmt.internal;

import androidx.annotation.NonNull;

import com.esotericsoftware.kryo.io.ByteBufferInput;
import com.esotericsoftware.kryo.io.ByteBufferOutput;

import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

import io.nekohasekai.sagernet.fmt.KryoConverters;
import moe.matsuri.nb4a.utils.JavaUtil;

public class BalancerBean extends InternalBean {

    public static final int TYPE_LIST = 0;
    public static final int TYPE_GROUP = 1;
    // B7a/O7: 混搭来源——分组（含名称正则）+ 散装节点，成员解析时去重。
    // v6 字段形状由 wanBox 本批定，不照抄上游 autoselect 形状的 v6。
    public static final int TYPE_MIXED = 2;

    public static final String STRATEGY_LEAST_PING = "leastPing";
    public static final String STRATEGY_LEAST_LOAD = "leastLoad";
    public static final String STRATEGY_RANDOM = "random";
    public static final String STRATEGY_ROUND_ROBIN = "roundRobin";
    public static final String STRATEGY_ROUND_ROBIN_LEGACY = "round_robin";
    public static final String STRATEGY_FAILOVER = "failover";
    public static final String STRATEGY_STABLE = "stable";
    // B7c/O7：「按优先级顺序」——新增模式，与现有模式并存（用户已拍板）。
    // 层内仍按延迟 + 容差择优（urltest），层间按层级顺序调度（嵌套 urltest，
    // 见 ConfigBuilder）；「最快最稳」（leastPing）模式隐藏层级相关入口。
    public static final String STRATEGY_PRIORITY_ORDER = "priorityOrder";
    public static final String STRATEGY_CONSISTENT_HASH = "consistent_hash";
    public static final String STRATEGY_CONSISTENT_HASH_CAMEL = "consistentHash";

    public int balancerType = TYPE_LIST; // 0 = list, 1 = group
    public long targetGroupId = 0L;
    public List<Long> targetGroupIds = new ArrayList<>();
    public List<Long> proxies = new ArrayList<>();
    public String strategy = STRATEGY_LEAST_PING;
    public String testUrl = "";
    public int interval = 300;
    public int tolerance = 300;
    public String toleranceUnit = "ms";
    // v4 fields
    public boolean useFrontProxy = false;
    public boolean useLandingProxy = false;
    public String nameExclude = "";
    public String nameInclude = "";
    // v5 fields
    public long frontProxy = -1L;
    public long landingProxy = -1L;
    // v6 fields (B7a/O7, wanBox 自定形状)：混搭解析后的成员上限，
    // 0 = 不限（显式值，initializeDefaultValues MUST NOT 覆盖它，
    // 见 8.15 同类问题核查）；>0 按解析顺序截取前 N 个唯一成员。
    public int maxMembers = 0;
    // v7 fields (B7c/O7)：「按优先级顺序」模式的层级规则与节点优先级顺序。
    // tierRules：层级规则持久化串（见 fmt/BalancerTierScheduler 的编解码），
    // 空 = 只有保底层级；priorityOrder：显式节点顺序（成员 id 列表，
    // 未在表里的成员按解析顺序追加在后）；priorityExcluded：从优先级页移除的
    // 分组来源成员 id（分组来源成员无法从源分组删除，排除名单是让“移除”
    // 真正生效的唯一记录；散装成员移除时直接从 proxies 删除，不进此表）。
    public String tierRules = "";
    public List<Long> priorityOrder = new ArrayList<>();
    public List<Long> priorityExcluded = new ArrayList<>();

    public long calculateToleranceMs() {
        long value = tolerance;
        if (value < 0L) {
            value = 0L;
        }
        if ("s".equalsIgnoreCase(toleranceUnit)) {
            return Math.min(value * 1000L, (long) Integer.MAX_VALUE);
        }
        return Math.min(value, (long) Integer.MAX_VALUE);
    }

    @Override
    public String displayName() {
        if (JavaUtil.isNotBlank(name)) {
            return name;
        } else {
            return "Balancer 01";
        }
    }

    @Override
    public String displayAddress() {
        String stratName;
        if (STRATEGY_LEAST_PING.equals(strategy)) {
            stratName = "最低延迟";
        } else if (STRATEGY_LEAST_LOAD.equals(strategy)) {
            stratName = "最低负载";
        } else if (STRATEGY_RANDOM.equals(strategy)) {
            stratName = "随机选择";
        } else if (STRATEGY_ROUND_ROBIN.equals(strategy) || STRATEGY_ROUND_ROBIN_LEGACY.equals(strategy)) {
            stratName = "轮询";
        } else if (STRATEGY_FAILOVER.equals(strategy)) {
            stratName = "故障转移";
        } else if (STRATEGY_STABLE.equals(strategy)) {
            stratName = "最稳定";
        } else if (STRATEGY_PRIORITY_ORDER.equals(strategy)) {
            stratName = "按优先级顺序";
        } else if (STRATEGY_CONSISTENT_HASH.equals(strategy) || STRATEGY_CONSISTENT_HASH_CAMEL.equals(strategy)) {
            stratName = "一致性哈希";
        } else {
            stratName = strategy != null ? strategy : STRATEGY_LEAST_PING;
        }
        if (balancerType == TYPE_GROUP) {
            int gCount = targetGroupIds != null && !targetGroupIds.isEmpty() ? targetGroupIds.size() : (targetGroupId > 0 ? 1 : 0);
            return "[分组 (" + gCount + ")] 策略: " + stratName;
        } else if (balancerType == TYPE_MIXED) {
            int gCount = targetGroupIds != null && !targetGroupIds.isEmpty() ? targetGroupIds.size() : (targetGroupId > 0 ? 1 : 0);
            int count = proxies != null ? proxies.size() : 0;
            return "[混合 (分组 " + gCount + " + 节点 " + count + ")] 策略: " + stratName;
        } else {
            int count = proxies != null ? proxies.size() : 0;
            return "[节点 (" + count + ")] 策略: " + stratName;
        }
    }

    @Override
    public void initializeDefaultValues() {
        super.initializeDefaultValues();
        if (name == null) name = "";
        if (proxies == null) proxies = new ArrayList<>();
        if (targetGroupIds == null) targetGroupIds = new ArrayList<>();
        if (strategy == null || strategy.isEmpty()) strategy = STRATEGY_LEAST_PING;
        if (testUrl == null) testUrl = "";
        if (interval <= 0) interval = 300;
        if (tolerance < 0) tolerance = 300;
        if (toleranceUnit == null || toleranceUnit.isEmpty()) toleranceUnit = "ms";
        if (nameExclude == null) nameExclude = "";
        if (nameInclude == null) nameInclude = "";
        if (frontProxy == 0L) frontProxy = -1L;
        if (landingProxy == 0L) landingProxy = -1L;
        // B7a/O7 8.15 同类问题核查结论（见 design.md B7a 实施记录）：
        // 既有字段中只有 tolerance 的 0 是显式有效值（0 = 严格切换），
        // 它早已是 `< 0` 守卫、0 不会被覆盖；interval 的 0 无“不限”语义
        // （探测周期，UI 与 ConfigBuilder 均按 >=10 秒钳制），`<= 0 → 300`
        // 不是 8.15 类覆盖；front/landing 的 0→-1 只是 id 归一化
        // （0 不是合法 profile id，-1 = 无）。新字段 maxMembers 的 0 是
        // 显式“不限”，此处 MUST 只归一化非法负值，0 原样保留。
        if (maxMembers < 0) maxMembers = 0;
        // B7c/O7 v7 字段：只做 null 归一化，不覆盖空值（空规则 = 只有保底层级、
        // 空顺序 = 解析顺序、空排除名单都是合法的显式状态）。
        if (tierRules == null) tierRules = "";
        if (priorityOrder == null) priorityOrder = new ArrayList<>();
        if (priorityExcluded == null) priorityExcluded = new ArrayList<>();
    }

    @Override
    public void serialize(ByteBufferOutput output) {
        output.writeInt(7); // version (B7c/O7：v7 = v6 + 层级规则/优先级顺序/排除名单）
        output.writeInt(balancerType);
        output.writeLong(targetGroupId);
        output.writeString(strategy);
        output.writeString(testUrl);
        output.writeInt(interval);

        output.writeInt(proxies.size());
        for (Long proxy : proxies) {
            output.writeLong(proxy);
        }

        if (targetGroupIds == null) {
            targetGroupIds = new ArrayList<>();
        }
        output.writeInt(targetGroupIds.size());
        for (Long gid : targetGroupIds) {
            output.writeLong(gid);
        }
        output.writeInt(tolerance);
        output.writeString(toleranceUnit != null ? toleranceUnit : "ms");
        // v4 fields
        output.writeBoolean(useFrontProxy);
        output.writeBoolean(useLandingProxy);
        output.writeString(nameExclude != null ? nameExclude : "");
        output.writeString(nameInclude != null ? nameInclude : "");
        // v5 fields
        output.writeLong(frontProxy);
        output.writeLong(landingProxy);
        // v6 fields
        output.writeInt(maxMembers);
        // v7 fields
        output.writeString(tierRules != null ? tierRules : "");
        if (priorityOrder == null) {
            priorityOrder = new ArrayList<>();
        }
        output.writeInt(priorityOrder.size());
        for (Long id : priorityOrder) {
            output.writeLong(id);
        }
        if (priorityExcluded == null) {
            priorityExcluded = new ArrayList<>();
        }
        output.writeInt(priorityExcluded.size());
        for (Long id : priorityExcluded) {
            output.writeLong(id);
        }
    }

    /**
     * B7c/O7 降级导出：按 v6 记录布局写出本 bean 的 v6 子集，供 v6 读取端使用。
     * 与 {@link #serializeV5(ByteBufferOutput, List)} 同一约定：调用方 MUST 先把
     * 层级与顺序解析成唯一成员 id 列表并传入——类型强制降为 TYPE_LIST、proxies
     * 即该解析结果，因此层级规则与优先级顺序都已 baked 进成员列表，不会静默丢失；
     * 其余 v6 字段（策略、测速、前置/落地、名称过滤、成员上限）原样保留。
     * 注意：这只是 v6 记录体，不含 AbstractBean 的 name 附加段；
     * 完整可读 payload 见 {@link #downgradeToV6Bytes(List)}。
     */
    public void serializeV6(ByteBufferOutput output, @NonNull List<Long> resolvedMemberIds) {
        output.writeInt(6); // version
        output.writeInt(TYPE_LIST);
        output.writeLong(0L); // 降级后类型为 LIST，分组 id 不再保留，避免旧端切回 GROUP 时复活过期分组
        output.writeString(strategy);
        output.writeString(testUrl);
        output.writeInt(interval);

        output.writeInt(resolvedMemberIds.size());
        for (Long proxy : resolvedMemberIds) {
            output.writeLong(proxy);
        }

        output.writeInt(0); // v6 的 targetGroupIds：降级后为空
        output.writeInt(tolerance);
        output.writeString(toleranceUnit != null ? toleranceUnit : "ms");
        // v4 fields
        output.writeBoolean(useFrontProxy);
        output.writeBoolean(useLandingProxy);
        output.writeString(nameExclude != null ? nameExclude : "");
        output.writeString(nameInclude != null ? nameInclude : "");
        // v5 fields
        output.writeLong(frontProxy);
        output.writeLong(landingProxy);
        // v6 fields
        output.writeInt(maxMembers);
    }

    /**
     * B7c/O7 降级导出完整 payload：v6 记录体 + AbstractBean 附加段
     *（name / customOutboundJson / customConfigJson），与
     * KryoConverters.serialize 的输出布局一致，可直接喂给 v6 读取端。
     */
    @NonNull
    public byte[] downgradeToV6Bytes(@NonNull List<Long> resolvedMemberIds) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        ByteBufferOutput buffer = io.nekohasekai.sagernet.ktx.KryosKt.byteBuffer(out);
        serializeV6(buffer, resolvedMemberIds);
        buffer.writeInt(1);
        buffer.writeString(name != null ? name : "");
        buffer.writeString(customOutboundJson != null ? customOutboundJson : "");
        buffer.writeString(customConfigJson != null ? customConfigJson : "");
        buffer.flush();
        buffer.close();
        return out.toByteArray();
    }

    /**
     * B7a/O7 降级导出：按 v5 记录布局写出本 bean 的 v5 子集，供旧版读取。
     * 调用方 MUST 先用 BalancerMemberResolver 把混搭来源解析成唯一成员 id
     * 列表并传入——类型强制降为 TYPE_LIST、proxies 即该解析结果，因此
     * 分组来源与 maxMembers 上限都已 baked 进成员列表，不会静默丢失；
     * 其余 v5 字段（策略、测速、前置/落地、名称过滤）原样保留。
     * 注意：这只是 v5 记录体，不含 AbstractBean 的 name 附加段；
     * 完整可读 payload 见 {@link #downgradeToV5Bytes(List)}。
     */
    public void serializeV5(ByteBufferOutput output, @NonNull List<Long> resolvedMemberIds) {
        output.writeInt(5); // version
        output.writeInt(TYPE_LIST);
        output.writeLong(0L); // 降级后类型为 LIST，分组 id 不再保留，避免旧端切回 GROUP 时复活过期分组
        output.writeString(strategy);
        output.writeString(testUrl);
        output.writeInt(interval);

        output.writeInt(resolvedMemberIds.size());
        for (Long proxy : resolvedMemberIds) {
            output.writeLong(proxy);
        }

        output.writeInt(0); // v5 的 targetGroupIds：降级后为空
        output.writeInt(tolerance);
        output.writeString(toleranceUnit != null ? toleranceUnit : "ms");
        // v4 fields
        output.writeBoolean(useFrontProxy);
        output.writeBoolean(useLandingProxy);
        output.writeString(nameExclude != null ? nameExclude : "");
        output.writeString(nameInclude != null ? nameInclude : "");
        // v5 fields
        output.writeLong(frontProxy);
        output.writeLong(landingProxy);
    }

    /**
     * B7a/O7 降级导出完整 payload：v5 记录体 + AbstractBean 附加段
     *（name / customOutboundJson / customConfigJson），与
     * KryoConverters.serialize 的输出布局一致，可直接喂给 v5 读取端。
     */
    @NonNull
    public byte[] downgradeToV5Bytes(@NonNull List<Long> resolvedMemberIds) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        ByteBufferOutput buffer = io.nekohasekai.sagernet.ktx.KryosKt.byteBuffer(out);
        serializeV5(buffer, resolvedMemberIds);
        buffer.writeInt(1);
        buffer.writeString(name != null ? name : "");
        buffer.writeString(customOutboundJson != null ? customOutboundJson : "");
        buffer.writeString(customConfigJson != null ? customConfigJson : "");
        buffer.flush();
        buffer.close();
        return out.toByteArray();
    }

    @Override
    public void deserialize(ByteBufferInput input) {
        int version = input.readInt();
        if (version >= 1) {
            balancerType = input.readInt();
            targetGroupId = input.readLong();
            strategy = input.readString();
            testUrl = input.readString();
            interval = input.readInt();

            int length = input.readInt();
            proxies = new ArrayList<>();
            for (int i = 0; i < length; i++) {
                proxies.add(input.readLong());
            }

            targetGroupIds = new ArrayList<>();
            if (version >= 2) {
                int gCount = input.readInt();
                for (int i = 0; i < gCount; i++) {
                    targetGroupIds.add(input.readLong());
                }
            } else if (targetGroupId > 0L) {
                targetGroupIds.add(targetGroupId);
            }

            if (version >= 3) {
                tolerance = input.readInt();
                toleranceUnit = input.readString();
            } else {
                tolerance = 300;
                toleranceUnit = "ms";
            }

            if (version >= 4) {
                useFrontProxy = input.readBoolean();
                useLandingProxy = input.readBoolean();
                nameExclude = input.readString();
                nameInclude = input.readString();
            } else {
                useFrontProxy = false;
                useLandingProxy = false;
                nameExclude = "";
                nameInclude = "";
            }

            if (version >= 5) {
                frontProxy = input.readLong();
                landingProxy = input.readLong();
            } else {
                frontProxy = -1L;
                landingProxy = -1L;
            }

            if (version >= 6) {
                maxMembers = input.readInt();
            } else {
                // v1–v5 历史数据：无该字段，0 = 不限（与新字段默认值一致，
                // 且 initializeDefaultValues 不会覆盖 0）。
                maxMembers = 0;
            }

            if (version >= 7) {
                tierRules = input.readString();
                int orderLength = input.readInt();
                priorityOrder = new ArrayList<>();
                for (int i = 0; i < orderLength; i++) {
                    priorityOrder.add(input.readLong());
                }
                int excludedLength = input.readInt();
                priorityExcluded = new ArrayList<>();
                for (int i = 0; i < excludedLength; i++) {
                    priorityExcluded.add(input.readLong());
                }
            } else {
                // v1–v6 历史数据：无该字段，取安全默认值（空规则 = 只有保底层级、
                // 空顺序 = 解析顺序、空排除名单），旧字段原值保留。
                tierRules = "";
                priorityOrder = new ArrayList<>();
                priorityExcluded = new ArrayList<>();
            }
        }
    }

    @NotNull
    @Override
    public BalancerBean clone() {
        return KryoConverters.deserialize(new BalancerBean(), KryoConverters.serialize(this));
    }

    public static final Creator<BalancerBean> CREATOR = new CREATOR<BalancerBean>() {
        @NonNull
        @Override
        public BalancerBean newInstance() {
            return new BalancerBean();
        }

        @Override
        public BalancerBean[] newArray(int size) {
            return new BalancerBean[size];
        }
    };
}
