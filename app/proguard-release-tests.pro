# @author 雾晚
-keep class io.nekohasekai.sagernet.bg.InstallerDataCommit** { *; }
# @author 雾晚: test coroutine classes call this instance accessor across APK boundaries.
-keep class io.nekohasekai.sagernet.SagerNet$Companion {
    public io.nekohasekai.sagernet.SagerNet getApplication();
}
# @author 雾晚: run 43574984965 observed five cross-APK linkage failures:
# SagerNet getter, warning Companion field, RootModuleClient commandProcess accessor,
# Issue constructor, and RootRouteBuildSnapshot capture.
# Remaining keeps are direct Batch 5 test call safeguards found by source review.
-keep class io.nekohasekai.sagernet.SagerNet {
    public static final io.nekohasekai.sagernet.SagerNet$Companion Companion;
}
-keep class io.nekohasekai.sagernet.SagerNet$Companion {
    public io.nekohasekai.sagernet.bg.ServiceCommandController getCommandController();
}
-keep class io.nekohasekai.sagernet.bg.RootModuleClient {
    public static final io.nekohasekai.sagernet.bg.RootModuleClient INSTANCE;
    public kotlin.jvm.functions.Function1 getCommandProcess$app_ossRelease();
    public void setCommandProcess$app_ossRelease(kotlin.jvm.functions.Function1);
    public void invalidateStatusCache();
    public java.lang.Object prepareOutboundWarning(boolean, kotlin.coroutines.Continuation);
    public java.lang.Object continueOutboundWarning(java.lang.String, boolean, kotlin.coroutines.Continuation);
    public void cancelOutboundWarning(java.lang.String);
}
# OutboundReferenceAlarmTest:160-194 catches this exception and reads its warning.
-keep class io.nekohasekai.sagernet.bg.RootModuleClient$OutboundReferenceWarningRequired {
    public io.nekohasekai.sagernet.bg.OutboundReferenceWarning getWarning();
}
# OutboundReferenceAlarmTest:112,566 calls the factory and reads the dialog token.
-keep class io.nekohasekai.sagernet.ui.OutboundReferenceWarningDialog {
    public static final io.nekohasekai.sagernet.ui.OutboundReferenceWarningDialog$Companion Companion;
    public java.lang.String getRequestToken();
}
-keep class io.nekohasekai.sagernet.ui.OutboundReferenceWarningDialog$Companion {
    public io.nekohasekai.sagernet.ui.OutboundReferenceWarningDialog newResult(io.nekohasekai.sagernet.bg.OutboundReferenceWarning);
}
# OutboundReferenceAlarmTest:364,642 calls this base-class method across APKs.
-keepclassmembers class androidx.lifecycle.Lifecycle {
    public androidx.lifecycle.Lifecycle$State getCurrentState();
}

# OutboundReferenceAlarmTest:488 selects the string-code overload.
-keep class io.nekohasekai.sagernet.bg.RootModuleErrors {
    public static final io.nekohasekai.sagernet.bg.RootModuleErrors INSTANCE;
    public java.lang.String message(android.content.Context, java.lang.String);
}
# OutboundReferenceAlarmTest:727-775 exercises route row recycling and publication.
-keep class io.nekohasekai.sagernet.ui.MainActivity {
    public boolean displayFragmentWithId(int);
}
-keep class io.nekohasekai.sagernet.ui.RouteFragment {
    public androidx.recyclerview.widget.RecyclerView getRuleListView();
    public io.nekohasekai.sagernet.ui.RouteFragment$RuleAdapter getRuleAdapter();
}
-keep class io.nekohasekai.sagernet.ui.RouteFragment$RuleAdapter {
    public java.util.ArrayList getRuleList();
    public java.lang.Object reload(kotlin.coroutines.Continuation);
    public java.lang.Object onRemoved(long, kotlin.coroutines.Continuation);
    public java.lang.Object onCleared(kotlin.coroutines.Continuation);
    public androidx.recyclerview.widget.RecyclerView$ViewHolder onCreateViewHolder(android.view.ViewGroup, int);
    public void onBindViewHolder(androidx.recyclerview.widget.RecyclerView$ViewHolder, int);
    public int getItemViewType(int);
}
# Exact shared RecyclerView descriptor types and inherited APIs used at 730,738,756.
-keep class androidx.recyclerview.widget.RecyclerView {
    public androidx.recyclerview.widget.RecyclerView$Adapter getAdapter();
}
-keep class androidx.recyclerview.widget.RecyclerView$ViewHolder {
    public final android.view.View itemView;
}
-keep class androidx.recyclerview.widget.RecyclerView$Adapter {
    public void registerAdapterDataObserver(androidx.recyclerview.widget.RecyclerView$AdapterDataObserver);
}
-keep class androidx.recyclerview.widget.RecyclerView$AdapterDataObserver {
    public <init>();
    public void onChanged();
}
# DAO interfaces are separate from the already-kept entity classes / Room implementations.
# AlarmTest:41-42,90,168; SnapshotTest:31-32,81,85,91,205-206.
-keep interface io.nekohasekai.sagernet.database.RuleEntity$Dao {
    public java.util.List allRules();
    public void reset();
    public void insert(java.util.List);
    public void updateRule(io.nekohasekai.sagernet.database.RuleEntity);
}
-keep interface io.nekohasekai.sagernet.database.ProxyEntity$Dao {
    public io.nekohasekai.sagernet.database.ProxyEntity getById(long);
    public int updateProxy(io.nekohasekai.sagernet.database.ProxyEntity);
}
-keep interface io.nekohasekai.sagernet.database.preference.KeyValuePair$Dao {
    public java.util.List all();
}
-keep class io.nekohasekai.sagernet.bg.OutboundReferenceWarning {
    public static final io.nekohasekai.sagernet.bg.OutboundReferenceWarning$Companion Companion;
    public <init>(java.lang.String, boolean, java.util.List);
    public java.lang.String getRequestToken();
    public boolean getStartIfStopped();
    public java.util.List getIssues();
}
-keep class io.nekohasekai.sagernet.bg.OutboundReferenceWarning$Companion {
    public io.nekohasekai.sagernet.bg.OutboundReferenceWarning$PendingReview queuePendingReview(android.content.Context, boolean);
    public io.nekohasekai.sagernet.bg.OutboundReferenceWarning$PendingReview peekPendingReview(android.content.Context);
    public boolean clearPendingReview(android.content.Context, java.lang.String);
}
-keep class io.nekohasekai.sagernet.bg.OutboundReferenceWarning$PendingReview {
    public <init>(java.lang.String, boolean);
    public java.lang.String getRequestId();
    public boolean getStartIfStopped();
    public boolean equals(java.lang.Object);
}
-keep class io.nekohasekai.sagernet.route.OutboundReferenceValidator$Issue {
    public <init>(long, java.lang.String, io.nekohasekai.sagernet.route.OutboundReferenceValidator$ReferenceKind, long);
    public long getMissingId();
    public boolean equals(java.lang.Object);
}
-keep class io.nekohasekai.sagernet.route.OutboundReferenceValidator$ReferenceKind {
    public static final io.nekohasekai.sagernet.route.OutboundReferenceValidator$ReferenceKind NODE_OR_STRATEGY_GROUP;
}
-keep class io.nekohasekai.sagernet.route.RootRouteBuildSnapshot {
    public static final io.nekohasekai.sagernet.route.RootRouteBuildSnapshot$Companion Companion;
    public java.util.List issues();
}
-keep class io.nekohasekai.sagernet.route.RootRouteBuildSnapshot$Companion {
    public java.lang.Object capture(kotlin.coroutines.Continuation);
}
# Descriptor types: independently shrunk app/test APKs require stable shared descriptor names.
# Keep these exact types, not their packages or all members.
# OutboundReferenceAlarmTest directly calls this controller/state ABI across APKs.
-keep class io.nekohasekai.sagernet.bg.ServiceCommandController {
    public kotlinx.coroutines.flow.StateFlow getState();
    public boolean requestOutboundConfirmation(io.nekohasekai.sagernet.bg.OutboundReferenceWarning, java.lang.String);
    public static boolean requestOutboundConfirmation$default(io.nekohasekai.sagernet.bg.ServiceCommandController, io.nekohasekai.sagernet.bg.OutboundReferenceWarning, java.lang.String, int, java.lang.Object);
    public boolean cancelOutboundConfirmation(java.lang.String);
    public void acknowledge();
    public boolean isBusy();
}
-keep interface kotlinx.coroutines.flow.StateFlow {
    public java.lang.Object getValue();
}
-keep class io.nekohasekai.sagernet.bg.ServiceCommandController$CommandState
-keep class io.nekohasekai.sagernet.bg.ServiceCommandController$CommandState$Idle {
    public static final io.nekohasekai.sagernet.bg.ServiceCommandController$CommandState$Idle INSTANCE;
}
-keep class io.nekohasekai.sagernet.bg.ServiceCommandController$CommandState$ConfirmationRequired {
    public io.nekohasekai.sagernet.bg.OutboundReferenceWarning getWarning();
    public java.lang.String getPendingReviewId();
}
-keep interface kotlin.jvm.functions.Function1
-keep interface kotlin.coroutines.Continuation
# Only -PwanboxReleaseTests uses this file. R8 and resource shrinking remain enabled.
# AGP removes shared dependencies from the test APK. Preserve their external public APIs
# and the precise app facades invoked by androidTest; do not keep the whole business package.
-keep,allowobfuscation class kotlin.** { public *; }
# @author 雾晚: separate instrumentation APK calls these shared coroutine facades.
-keep,allowobfuscation class kotlinx.coroutines.** { public *; protected *; }
-keep,allowobfuscation class io.nekohasekai.sagernet.bg.RootModuleDataUpdate { public *; }
-keep,allowobfuscation class io.nekohasekai.sagernet.bg.RootModuleSnapshot { public *; }
-keep,allowobfuscation class io.nekohasekai.sagernet.bg.proto.ProxyInstance { public *; }
-keep,allowobfuscation class io.nekohasekai.sagernet.bg.proto.BoxInstance { public *; }
-keep,allowobfuscation class io.nekohasekai.sagernet.route.DomesticRoutingPreset { public *; }
-keep,allowobfuscation class io.nekohasekai.sagernet.route.DomesticRoutingPreset$* { public *; }
-keep class androidx.tracing.Trace { *; }
-keep,allowobfuscation class androidx.core.** { public *; }
-keep,allowobfuscation class androidx.appcompat.** { public *; }
-keep,allowobfuscation class androidx.preference.** { public *; }
-keep,allowobfuscation class androidx.room.** { public *; }
-keep,allowobfuscation class androidx.arch.core.** { public *; protected *; }
-keep,allowobfuscation class androidx.sqlite.** { public *; }
-keep,allowobfuscation class com.google.android.material.** { public *; }
# @author 雾晚 Shared APIs invoked only by migration/scanner/serialization tests.
# Room migration-bundle adapters in the separate test APK also use TypeToken/tree/stream APIs.
-keep,allowobfuscation class com.google.gson.** { public *; protected *; }
-keep,allowobfuscation class com.google.zxing.qrcode.QRCodeWriter { public *; }
-keep,allowobfuscation class com.google.zxing.common.BitMatrix { public *; }
-keep,allowobfuscation class com.google.zxing.Result { public *; }
-keep,allowobfuscation class com.king.zxing.util.CodeUtils { public *; }
-keep,allowobfuscation class okhttp3.OkHttpClient {
    public boolean followRedirects();
}
-keep,allowobfuscation class io.nekohasekai.sagernet.fmt.v2ray.V2RayFmtKt {
    public static java.lang.String toV2rayN(io.nekohasekai.sagernet.fmt.v2ray.VMessBean);
    public static io.nekohasekai.sagernet.fmt.v2ray.VMessBean parseV2RayN(java.lang.String);
}

-keep,allowobfuscation class io.nekohasekai.sagernet.database.BackupRestore { public *; }
-keep,allowobfuscation class io.nekohasekai.sagernet.database.BackupRestore$* { public *; }
-keep,allowobfuscation class io.nekohasekai.sagernet.database.PortableBackup { public *; }
-keep,allowobfuscation class io.nekohasekai.sagernet.database.PortableBackup$* { public *; }
-keep,allowobfuscation class io.nekohasekai.sagernet.database.DataStore { public *; }
# @author 雾晚: MinimalUiTest accesses these delegated properties across APK boundaries.
-keep class io.nekohasekai.sagernet.database.DataStore {
    public static *** getSelectedProxy(...);
    public static *** setSelectedProxy(...);
    public static *** getSelectedGroup(...);
    public static *** setSelectedGroup(...);
    public static *** getGroupLayoutMode(...);
    public static *** setGroupLayoutMode(...);
    public static *** getNodeColumnCount(...);
    public static *** setNodeColumnCount(...);
    public static *** getShowSubscriptionInfoCard(...);
    public static *** setShowSubscriptionInfoCard(...);
}
-keep class io.nekohasekai.sagernet.database.SagerDatabase$Companion { public *; }
-keep class io.nekohasekai.sagernet.database.preference.PublicDatabase$Companion { public *; }
-keep,allowobfuscation class io.nekohasekai.sagernet.database.preference.RoomPreferenceDataStore { public *; }
-keep,allowobfuscation class io.nekohasekai.sagernet.fmt.KryoConverters { public *; }
-keep,allowobfuscation class io.nekohasekai.sagernet.fmt.ConfigBuilderKt {
    public static *** buildConfig(...);
    public static *** buildConfig$default(...);
}
-keep,allowobfuscation class io.nekohasekai.sagernet.fmt.ConfigBuildResult { public *; }
-keep,allowobfuscation class io.nekohasekai.sagernet.route.RouteRuleEditor { public *; }
-keep,allowobfuscation class io.nekohasekai.sagernet.route.RouteRuleEditor$* { public *; }
-keep,allowobfuscation class io.nekohasekai.sagernet.route.InputMethodDirectPolicy { public *; }
-keep,allowobfuscation class io.nekohasekai.sagernet.route.InputMethodDirectPolicy$* { public *; }
-keep,allowobfuscation class io.nekohasekai.sagernet.route.InputMethodRouteMigration { public *; }
-keep,allowobfuscation class io.nekohasekai.sagernet.ui.UiChrome { public *; }
-keep,allowobfuscation class io.nekohasekai.sagernet.ui.UiLayoutPolicy { public *; }
-keep,allowobfuscation class io.nekohasekai.sagernet.ui.GlassPalette { public *; }
-keep,allowobfuscation class io.nekohasekai.sagernet.ui.GlassPalette$* { public *; }
-keep,allowobfuscation class io.nekohasekai.sagernet.ui.ProfileCardStyle { public *; }
-keep,allowobfuscation class io.nekohasekai.sagernet.utils.Theme { public *; }
-keep,allowobfuscation class io.nekohasekai.sagernet.utils.BackupHelper { public *; }
-keep,allowobfuscation class io.nekohasekai.sagernet.utils.BackupFiles { public *; }
-keep,allowobfuscation class io.nekohasekai.sagernet.utils.BackupFiles$* { public *; }
-keep,allowobfuscation class io.nekohasekai.sagernet.utils.BoundedImageDecoder { public *; }
-keep,allowobfuscation class io.nekohasekai.sagernet.utils.SecureNetwork { public *; }
-keep,allowobfuscation class moe.matsuri.nb4a.ui.ExpandablePreferenceCategory { public *; }

# 跨 APK Instrumentation 测试入口：允许重命名，但禁止成员内联、参数移除及签名重写
-keep,allowobfuscation class io.nekohasekai.sagernet.database.GroupManager {
    public static final io.nekohasekai.sagernet.database.GroupManager INSTANCE;
    public java.lang.Object deleteGroup(long, kotlin.coroutines.Continuation);
}

-keep,allowobfuscation class io.nekohasekai.sagernet.database.ProfileManager {
    public static final io.nekohasekai.sagernet.database.ProfileManager INSTANCE;
    public java.lang.Object createProfile(long, io.nekohasekai.sagernet.fmt.AbstractBean, kotlin.coroutines.Continuation);
    public java.lang.Object updateProfile(io.nekohasekai.sagernet.database.ProxyEntity, kotlin.coroutines.Continuation);
    public java.lang.Object getRules(kotlin.coroutines.Continuation);
}

# -----------------------------------------------------------------------------
# MinimalUiTest 跨 APK 实际引用的 UI / 工具类入口（允许混淆重命名，禁止裁剪/内联/修改签名）
# -----------------------------------------------------------------------------

-keep,allowobfuscation class io.nekohasekai.sagernet.utils.AppLocale {
    public static final io.nekohasekai.sagernet.utils.AppLocale INSTANCE;
    public final void apply(java.lang.String);
}

-keep,allowobfuscation class io.nekohasekai.sagernet.ui.MainActivity {
    public final io.nekohasekai.sagernet.databinding.LayoutMainBinding getBinding();
}

-keep,allowobfuscation class io.nekohasekai.sagernet.databinding.LayoutMainBinding {
    public final com.google.android.material.bottomnavigation.BottomNavigationView bottomNav;
    public final androidx.coordinatorlayout.widget.CoordinatorLayout fragmentHolder;
}

-keep,allowobfuscation class io.nekohasekai.sagernet.ui.ToolbarFragment {
    public final androidx.appcompat.widget.Toolbar getToolbar();
    public final boolean isToolbarInitialized();
}

-keep,allowobfuscation class io.nekohasekai.sagernet.ui.ConfigurationFragment {
    public static final long ALL_GROUPS_SENTINEL_ID;
    public final io.nekohasekai.sagernet.ui.ConfigurationFragment$GroupPagerAdapter getAdapter();
    public final androidx.viewpager2.widget.ViewPager2 getGroupPager();
    public final io.nekohasekai.sagernet.ui.ConfigurationFragment$GroupFragment getCurrentGroupFragment();
}

-keep,allowobfuscation class io.nekohasekai.sagernet.ui.ConfigurationFragment$GroupPagerAdapter {
    public final java.util.ArrayList getGroupList();
    public final java.util.HashMap getGroupFragments();
}

-keep,allowobfuscation class io.nekohasekai.sagernet.ui.ConfigurationFragment$GroupFragment {
    public final io.nekohasekai.sagernet.ui.ConfigurationFragment$GroupFragment$ConfigurationAdapter getAdapter();
    public final androidx.recyclerview.widget.RecyclerView$LayoutManager getLayoutManager();
    public final androidx.recyclerview.widget.RecyclerView getConfigurationListView();
}

-keep,allowobfuscation class io.nekohasekai.sagernet.ui.ConfigurationFragment$GroupFragment$ConfigurationAdapter {
    public int getItemCount();
}

-keep,allowobfuscation class io.nekohasekai.sagernet.ui.GroupFragment {
    public final void toggleGroupSelection(long);
}

-keep,allowobfuscation class io.nekohasekai.sagernet.ui.SettingsFragment {
    public boolean onBackPressed();
}

-keep,allowobfuscation class io.nekohasekai.sagernet.ui.SettingsPreferenceFragment

# MinimalUiTest 断言跨 APK 直接调用的外部 RecyclerView 布局管理器方法
-keep,allowobfuscation class androidx.recyclerview.widget.GridLayoutManager {
    public int getSpanCount();
}

# MinimalUiTest 检查 RecyclerView 内部待定更新、布局状态与动画器
-keep,allowobfuscation class androidx.recyclerview.widget.RecyclerView {
    public boolean isComputingLayout();
    public boolean hasPendingAdapterUpdates();
    public androidx.recyclerview.widget.RecyclerView$ItemAnimator getItemAnimator();
}

-keep,allowobfuscation class androidx.recyclerview.widget.RecyclerView$ItemAnimator {
    public boolean isRunning();
}

# @author 雾晚: ThinManagerTest calls these APIs from the separate test APK.
# Thin release R8 folds/inlines CoreRuntime, so retain only the exercised facades
# in verification builds; shipping builds never include this file.
# Preserve this cross-APK test entry's original name and signature while R8 stays enabled.
-keep class io.nekohasekai.sagernet.bg.proto.UrlTest {
    public java.lang.String resolveLink(io.nekohasekai.sagernet.database.ProxyEntity);
}
-keep,allowobfuscation class io.nekohasekai.sagernet.core.CoreRuntime {
    public static final io.nekohasekai.sagernet.core.CoreRuntime INSTANCE;
    public boolean getAvailable();
    public io.nekohasekai.sagernet.core.WanHttpClient httpClient();
}
-keep,allowobfuscation class io.nekohasekai.sagernet.core.WanHttpClient {
    public io.nekohasekai.sagernet.core.WanHttpClient modernTLS();
    public io.nekohasekai.sagernet.core.WanHttpRequest newRequest();
}
-keep,allowobfuscation class io.nekohasekai.sagernet.core.WanHttpRequest {
    public io.nekohasekai.sagernet.core.WanHttpRequest setURL(java.lang.String);
}
