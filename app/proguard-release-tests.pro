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
    # GroupEditorPersistenceTest deletes subscription members through the real producer.
    public java.lang.Object deleteProfile(long, long, kotlin.coroutines.Continuation);
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
    # GroupUrlTestSnapshotTest:64,69,75,89,92,94,95 calls UrlTest(null) across APKs.
    # B5 added defaulted constructor parameters, so the call binds to the synthetic
    # default-argument constructor; R8 folds the in-app call to the no-arg
    # constructor and drops the synthetic one — keep it, as GroupSettingsActivity does.
    public <init>(java.lang.String, io.nekohasekai.sagernet.bg.proto.RunningProfileTagResolver, io.nekohasekai.sagernet.bg.proto.ThinControllerDelayClient, kotlin.jvm.functions.Function3, int, kotlin.jvm.internal.DefaultConstructorMarker);
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

# Batch 4a / Verify 43807329236: exact cross-APK APIs, verification builds only.
# GroupEditorPersistenceTest:116,138,209,293,310 restores preference fixtures.
-keep interface io.nekohasekai.sagernet.database.preference.KeyValuePair$Dao {
    public void insert(java.util.List);
    public int reset();
}
# GroupEditorPersistenceTest:28,111,172-173 freezes and inspects editor inputs.
-keep class io.nekohasekai.sagernet.route.RootRouteBuildSnapshot {
    public io.nekohasekai.sagernet.route.RootRouteBuildSnapshot withEditorDrafts(java.util.Map);
    public java.util.Map editorDrafts();
    public java.util.List allGroups();
}
# GroupEditorPersistenceTest:122,139-140 saves/restores the temporary profile cache.
-keep class moe.matsuri.nb4a.TempDatabase$Companion {
    public io.nekohasekai.sagernet.database.preference.KeyValuePair$Dao getProfileCacheDao();
}
# GroupEditorBlockedUiTest:24,64,81-86 and PersistenceTest:165-167,218-235,243-266:
# durable preparation, fault compensation and pending-journal cleanup.
-keep class io.nekohasekai.sagernet.group.editor.GroupLocalPreparation {
    public static final io.nekohasekai.sagernet.group.editor.GroupLocalPreparation$Companion Companion;
    public <init>(io.nekohasekai.sagernet.group.editor.GroupDraft);
    public void prepare(java.lang.String);
    public static void prepare$default(io.nekohasekai.sagernet.group.editor.GroupLocalPreparation, java.lang.String, int, java.lang.Object);
    public void restore();
    public void finish();
}
-keep class io.nekohasekai.sagernet.group.editor.GroupLocalPreparation$Companion {
    public boolean pending();
    public io.nekohasekai.sagernet.group.editor.GroupLocalPreparation$Companion$UndoRecord undoRecord();
    public void restoreRecord(io.nekohasekai.sagernet.group.editor.GroupLocalPreparation$Companion$UndoRecord);
    public void restorePendingLocal();
    public void clearRecord();
}
# GroupEditorPersistenceTest:222-224 inspects the durable undo record.
-keep class io.nekohasekai.sagernet.group.editor.GroupLocalPreparation$Companion$UndoRecord {
    public long getId();
    public boolean getExisted();
    public java.lang.String getBaseline();
}
# GroupEditorBlockedUiTest:48-51; PersistenceTest:41-43,84-112,152-174,215-305:
# construct, copy, persist and compare stable-ID drafts / five URL-test parameters.
-keep class io.nekohasekai.sagernet.group.editor.GroupDraft {
    public <init>(long, java.lang.String, io.nekohasekai.sagernet.group.editor.GroupMode, java.util.List, io.nekohasekai.sagernet.group.editor.UrlTestParameters);
    public long getId();
    public java.lang.String getName();
    public java.util.List getMembers();
    public io.nekohasekai.sagernet.group.editor.UrlTestParameters getParameters();
    public static io.nekohasekai.sagernet.group.editor.GroupDraft copy$default(io.nekohasekai.sagernet.group.editor.GroupDraft, long, java.lang.String, io.nekohasekai.sagernet.group.editor.GroupMode, java.util.List, io.nekohasekai.sagernet.group.editor.UrlTestParameters, int, java.lang.Object);
    public boolean equals(java.lang.Object);
}
-keep class io.nekohasekai.sagernet.group.editor.UrlTestParameters {
    public <init>(java.lang.String, long, int, java.lang.String, boolean);
    public static io.nekohasekai.sagernet.group.editor.UrlTestParameters copy$default(io.nekohasekai.sagernet.group.editor.UrlTestParameters, java.lang.String, long, int, java.lang.String, boolean, int, java.lang.Object);
}
-keep enum io.nekohasekai.sagernet.group.editor.GroupMode {
    public static final io.nekohasekai.sagernet.group.editor.GroupMode SELECTOR;
    public static final io.nekohasekai.sagernet.group.editor.GroupMode URL_TEST;
}
# GroupEditorPersistenceTest:86-112,228,276,304-307; BlockedUiTest:50:
# persist and reopen accepted drafts (including the object INSTANCE cross-APK field).
-keep class io.nekohasekai.sagernet.group.editor.GroupEditorStore {
    public static final io.nekohasekai.sagernet.group.editor.GroupEditorStore INSTANCE;
    public void write(io.nekohasekai.sagernet.group.editor.GroupDraft);
    public io.nekohasekai.sagernet.group.editor.GroupDraft read(long);
}
# GroupEditorPersistenceTest:43,93 patches the real producer snapshot.
-keep class io.nekohasekai.sagernet.group.editor.GroupEditor {
    public static final io.nekohasekai.sagernet.group.editor.GroupEditor INSTANCE;
    public com.google.gson.JsonObject patchSnapshot(com.google.gson.JsonObject, io.nekohasekai.sagernet.group.editor.GroupDraft, java.lang.String, java.lang.String, java.lang.Long);
    # Kotlin call sites omit selectedProfileId; preserve the cross-APK default bridge too.
    public static com.google.gson.JsonObject patchSnapshot$default(io.nekohasekai.sagernet.group.editor.GroupEditor, com.google.gson.JsonObject, io.nekohasekai.sagernet.group.editor.GroupDraft, java.lang.String, java.lang.String, java.lang.Long, int, java.lang.Object);
}
# GroupEditorPersistenceTest:174,279,282 validates references and reopens saved state.
-keep class io.nekohasekai.sagernet.group.editor.GroupEditorService {
    public static final io.nekohasekai.sagernet.group.editor.GroupEditorService INSTANCE;
    public java.util.List validate(io.nekohasekai.sagernet.group.editor.GroupDraft);
    public io.nekohasekai.sagernet.group.editor.GroupDraft load(long);
}
-keep class io.nekohasekai.sagernet.group.editor.GroupEditor$Issue {
    public java.lang.String getCode();
}
# GroupEditorPersistenceTest:279-281 cancels an edited in-memory session.
-keep class io.nekohasekai.sagernet.group.editor.GroupEditSession {
    public <init>(io.nekohasekai.sagernet.group.editor.GroupDraft);
    public void setDraft(io.nekohasekai.sagernet.group.editor.GroupDraft);
    public io.nekohasekai.sagernet.group.editor.GroupDraft cancel();
}
# GroupEditorPersistenceTest:103-114,170,217-227,242-254,273-308:
# actual entity lookup, creation, snapshot enumeration and fixture cleanup.
-keep interface io.nekohasekai.sagernet.database.ProxyGroup$Dao {
    public io.nekohasekai.sagernet.database.ProxyGroup getById(long);
    public long createGroup(io.nekohasekai.sagernet.database.ProxyGroup);
    public int deleteById(long);
    public java.util.List allGroups();
}
# GroupEditorPersistenceTest:169 inserts the balancer after real group creation.
-keep interface io.nekohasekai.sagernet.database.ProxyEntity$Dao {
    public void insert(java.util.List);
}
# GroupEditorPersistenceTest:130-132 exercises the ordinary settings round trip.
-keep class io.nekohasekai.sagernet.ui.GroupSettingsActivity {
    public <init>();
    # Verify 43812842528: Kotlin default-argument calls cross the APK boundary.
    public <init>(int);
    public <init>(int, int, kotlin.jvm.internal.DefaultConstructorMarker);
    public void init(io.nekohasekai.sagernet.database.ProxyGroup);
    public void serialize(io.nekohasekai.sagernet.database.ProxyGroup);
}
# GroupEditorPersistenceTest:244-248 registers a cross-APK preference fault callback.
-keep interface io.nekohasekai.sagernet.database.preference.OnPreferenceDataStoreChangeListener {
    public void onPreferenceDataStoreChanged(androidx.preference.PreferenceDataStore, java.lang.String);
}
# preview.4 subscription/discard regressions: exact new cross-APK public boundaries only.
# These rules are used solely by -PwanboxReleaseTests, never shipping APKs.
-keep class io.nekohasekai.sagernet.group.RawUpdater {
    public static final io.nekohasekai.sagernet.group.RawUpdater INSTANCE;
    public java.lang.Object doUpdate(io.nekohasekai.sagernet.database.ProxyGroup, io.nekohasekai.sagernet.database.SubscriptionBean, io.nekohasekai.sagernet.database.GroupManager$Interface, boolean, kotlin.coroutines.Continuation);
}
-keep class io.nekohasekai.sagernet.group.editor.GroupEditorService {
    public java.lang.String inputVersion();
}
-keep class io.nekohasekai.sagernet.group.editor.GroupEditor$Issue {
    public io.nekohasekai.sagernet.group.editor.EntityId getMember();
}
-keep class io.nekohasekai.sagernet.group.editor.EntityId$Node {
    public <init>(long);
    public boolean equals(java.lang.Object);
}
-keep class io.nekohasekai.sagernet.bg.RootModuleClient {
    public java.lang.Object abandonGroupDraft(kotlin.coroutines.Continuation);
}
-keep class io.nekohasekai.sagernet.bg.RootModuleClient$Status {
    public java.lang.String getPhase();
}
-keep interface io.nekohasekai.sagernet.database.ProxyEntity$Dao {
    public java.util.List getAll();
    public java.util.List getByGroup(long);
}
-keep interface io.nekohasekai.sagernet.database.RuleEntity$Dao {
    public long createRule(io.nekohasekai.sagernet.database.RuleEntity);
}
