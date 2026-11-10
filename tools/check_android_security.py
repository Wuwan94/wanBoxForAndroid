# @author 雾晚
"""Check both source and merged manifests; fail on exposed control broadcasts."""
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

root = Path(__file__).resolve().parents[1]
manifest = root / "app/src/main/AndroidManifest.xml"
android = "{http://schemas.android.com/apk/res/android}"

# Exact component names, exact permissions and exact action sets; no blanket dependency exemption.
def check_exported(manifest):
    application = ET.parse(manifest).getroot().find("application")
    assert application is not None, "missing application"
    launchers = {"NekoBoxPlus", "LightMode", "DarkMode", "OldNekoBoxPlus", "NekoBox", "Midnight", "Heavens", "BlackWhite", "Text"}
    allow = {
        ("activity", "io.nekohasekai.sagernet.ui.MainActivity"): (None, {"android.service.quicksettings.action.QS_TILE_PREFERENCES", "android.intent.action.VIEW"}),
        ("service", "io.nekohasekai.sagernet.bg.TileService"): ("android.permission.BIND_QUICK_SETTINGS_TILE", {"android.service.quicksettings.action.QS_TILE"}),
        ("receiver", "io.nekohasekai.sagernet.BootReceiver"): (None, {"android.intent.action.BOOT_COMPLETED", "android.intent.action.LOCKED_BOOT_COMPLETED", "android.intent.action.MY_PACKAGE_REPLACED"}),
        ("service", "androidx.work.impl.background.systemjob.SystemJobService"): ("android.permission.BIND_JOB_SERVICE", set()),
        ("receiver", "androidx.work.impl.diagnostics.DiagnosticsReceiver"): ("android.permission.DUMP", {"androidx.work.diagnostics.REQUEST_DIAGNOSTICS"}),
        ("receiver", "androidx.profileinstaller.ProfileInstallReceiver"): ("android.permission.DUMP", {"androidx.profileinstaller.action." + a for a in ("INSTALL_PROFILE", "SKIP_FILE", "SAVE_PROFILE", "BENCHMARK_OPERATION")}),
    }
    for name in launchers:
        allow[("activity-alias", "io.nekohasekai.sagernet.launcher." + name)] = (None, {"android.intent.action.MAIN"})
    for name in ("OwnBoxWidgetProvider", "OwnBoxWidget1x1", "OwnBoxWidget2x2", "OwnBoxWidget4x1", "OwnBoxWidget4x2"):
        allow[("receiver", "io.nekohasekai.sagernet.widget." + name)] = (None, {"android.appwidget.action.APPWIDGET_UPDATE"})
    for component in application:
        if component.tag not in {"activity", "activity-alias", "service", "receiver", "provider"}:
            continue
        # Old target SDK implicit intent-filter exports are unsafe too, even if a fixture omits exported.
        exported = component.get(android + "exported")
        if exported == "false" or (exported is None and component.find("intent-filter") is None):
            continue
        key = component.tag, component.get(android + "name")
        expected = allow.get(key)
        actual = component.get(android + "permission"), {a.get(android + "name") for a in component.findall("./intent-filter/action")}
        assert expected is not None and actual == expected, f"unauthorized exported component: {key}: {actual}"
        if component.tag == "activity-alias":
            assert component.get(android + "targetActivity") == "io.nekohasekai.sagernet.ui.MainActivity", f"unexpected alias target: {key}"

def check_built_variants(variants):
    for variant in variants:
        assert re.fullmatch(r"[a-zA-Z0-9]+", variant), "invalid variant"
        manifests = sorted((root / "app/build/intermediates/packaged_manifests" / variant).rglob("AndroidManifest.xml"))
        assert manifests, f"No packaged manifest for {variant}; build that variant first (no source fallback)"
        for path in manifests:
            check_exported(path)
            application = ET.parse(path).getroot().find("application")
            assert application.get(android + "networkSecurityConfig") == "@xml/network_security_config", "merged manifest changed TLS policy"
            assert application.get(android + "usesCleartextTraffic") != "true", "merged manifest enabled cleartext traffic"
            print(f"checked packaged manifest: {path.relative_to(root)}")

def main():
    variants = sys.argv[1:]
    assert variants, "Pass built variants, e.g. ossDebug previewDebug (source-only is not a CI gate)"
    check_built_variants(variants)
    tree = ET.parse(manifest)
    app = tree.getroot().find("application")
    receivers = app.findall("receiver")
    private = [r for r in receivers if r.get(android+"name", "").endswith("WidgetControlReceiver")]
    assert len(private) == 1 and private[0].get(android+"exported") == "false", "widget control must be private"
    widgets = [r for r in receivers if "OwnBoxWidget" in r.get(android+"name", "")]
    assert len(widgets) == 5
    for receiver in widgets:
        actions = [a.get(android+"name") for a in receiver.findall("./intent-filter/action")]
        assert actions == ["android.appwidget.action.APPWIDGET_UPDATE"], actions
    for name in ["QuickEnableShortcut", "QuickDisableShortcut", "QuickToggleShortcut"]:
        activities = [a for a in app.findall("activity") if a.get(android+"name", "").endswith("."+name)]
        assert len(activities) == 1 and activities[0].get(android+"exported") == "false", "shortcut control must be private"
        matches = list((root/"app/src/main/java").rglob(name+".kt"))
        text = matches[0].read_text(encoding="utf-8")
        assert "connection.connect(this, this)" in text and "connection.disconnect(this)" in text
        assert "confirmControl" not in text and "shortcut_control_confirm" not in text
    db = (root/"app/src/main/java/io/nekohasekai/sagernet/database/SagerDatabase.kt").read_text(encoding="utf-8")
    assert "fallbackToDestructiveMigration" not in db and "deleteDatabase" not in db
    security = ET.parse(root/"app/src/main/res/xml/network_security_config.xml").getroot()
    assert security.find("base-config").get("cleartextTrafficPermitted") == "false"
    assert {d.text for d in security.findall("./domain-config/domain")} == {"127.0.0.1", "localhost"}
    workflow = (root / ".github/workflows/verify-android.yml").read_text(encoding="utf-8")
    step_matches = list(re.finditer(r"(?m)^      - name: (.+)$", workflow))
    steps = {}
    for index, match in enumerate(step_matches):
        end = step_matches[index + 1].start() if index + 1 < len(step_matches) else len(workflow)
        steps[match.group(1)] = workflow[match.start():end]

    trusted_release = (
        "if: ${{ github.event_name == 'workflow_dispatch' || "
        "github.event.pull_request.head.repo.full_name == github.repository }}")
    for name, body in steps.items():
        if "WANBOX_KEYSTORE_" in body or "secrets.WANBOX_KEYSTORE" in body:
            assert trusted_release in body, f"release signing material is not gated: {name}"
    for name in (
        "Prepare key for the unpublished release comparison build",
        "Build and test signed release variants",
        "Measure same variant and ABI APK payload",
        "Validate release database migrations",
        "Upload release APK",
    ):
        assert trusted_release in steps[name], f"trusted-event gate missing: {name}"

    debug_tests = steps["Test debug unit suite and security contracts"]
    assert "app:testOssDebugUnitTest" in debug_tests and "app:testPreviewDebugUnitTest" in debug_tests
    assert "tools/check_android_security.py" in debug_tests
    assert "WANBOX_KEYSTORE_" not in debug_tests and "app:assembleOssRelease" not in debug_tests
    debug_emulator = steps["Validate database migrations and external control (debug)"]
    assert "app:connectedOssDebugAndroidTest" in debug_emulator
    assert "WANBOX_KEYSTORE_" not in debug_emulator
    print("Manifest, migration, TLS and fork-safe CI signing-boundary checks passed")

    assert "verify_external_control.sh" in debug_emulator
    assert "verify_launcher_shortcuts.sh" not in debug_emulator
    assert "verify_launcher_shortcuts.sh" in steps["Validate release database migrations"]
    assert "ossDebugAndroidTest -name" in debug_emulator
    assert "ossReleaseAndroidTest -name" in steps["Validate release database migrations"]

if __name__ == "__main__":
    main()
