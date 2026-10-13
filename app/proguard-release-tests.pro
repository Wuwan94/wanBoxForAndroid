# @author 雾晚
-keep class io.nekohasekai.sagernet.bg.InstallerDataCommit** { *; }
# @author 雾晚: test coroutine classes call this instance accessor across APK boundaries.
-keep,allowobfuscation class io.nekohasekai.sagernet.SagerNet$Companion {
    public io.nekohasekai.sagernet.SagerNet getApplication();
}
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
