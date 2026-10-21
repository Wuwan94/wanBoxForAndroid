# @author 雾晚
"""Check both source and merged manifests; fail on exposed control broadcasts."""
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

root = Path(__file__).resolve().parents[1]
manifest = Path(sys.argv[1]) if len(sys.argv) > 1 else root / "app/src/main/AndroidManifest.xml"
android = "{http://schemas.android.com/apk/res/android}"
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
