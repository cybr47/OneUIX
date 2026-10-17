"""Regression guards for reviewed FOD boundaries, not a general security certification."""
import argparse
import hashlib
from pathlib import Path
import re
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
ANDROID = "{http://schemas.android.com/apk/res/android}"


def require(condition, message):
    if not condition:
        raise SystemExit("FOD security guard failed: " + message)


def check_permission(root, package):
    name = package + ".permission.FOD_REFRESH"
    definitions = [p for p in root.findall("permission") if p.get(ANDROID + "name") == name]
    require(len(definitions) == 1, "refresh permission missing or duplicated")
    require(definitions[0].get(ANDROID + "protectionLevel") in ("signature", "0x2", "0x00000002", "2"),
            "refresh permission must be signature-only")
    require(any(p.get(ANDROID + "name") == name for p in root.findall("uses-permission")),
            "settings app must request its refresh permission")


def check_sources():
    check_permission(ET.parse(ROOT / "app/src/main/AndroidManifest.xml").getroot(), "${applicationId}")
    hook = ROOT / "hook/src/main/java/io/github/soclear/oneuix/hook"
    common = ROOT / "common/src/main/java/io/github/soclear/oneuix/common"
    ui = ROOT / "app/src/main/java/io/github/soclear/oneuix/ui"
    runtime = (hook / "fod/FodRuntime.kt").read_text()
    require("FodConfig.changePermission, main, Context.RECEIVER_EXPORTED" in runtime,
            "receiver must enforce the permission at registration")
    require("FodRefreshSecurity.acceptsSender(moduleUid, sentFromUid, sentFromPackage)" in runtime,
            "receiver must verify Android-provided sender identity")
    require("PermissionInfo.PROTECTION_SIGNATURE" in runtime and "permission.packageName" in runtime,
            "permission owner and strength must be verified")
    require("setShareIdentityEnabled(true)" in (ui / "SettingViewModel.kt").read_text(),
            "settings app must share its framework identity")
    require("FodPreferences.read(it)" in runtime and "FodReloadGate()" in runtime and
            "ArrayBlockingQueue<Runnable>(1)" in runtime, "host reads and work queue must be bounded")
    require("getImageSize" not in (hook / "fod/FodBiometrics.kt").read_text().split("fun install()", 1)[1].split("private fun apply", 1)[0],
            "do not restore the unsupported sensor-info requirement")

    fod_files = list((hook / "fod").glob("*.kt")) + list(common.glob("Fod*.kt")) + [ui / "FodIconStore.kt"]
    forbidden = re.compile(r"ProcessBuilder|Runtime\.getRuntime|java\.net\.|okhttp|DexClassLoader|"
                           r"addJavascriptInterface|setWorldReadable|MODE_WORLD|"
                           r"FingerprintManager|BiometricService|LockSettingsStrongAuth|GateKeeper|"
                           r"intent\.(?:extras|get\w*Extra)")
    for path in fod_files:
        require(not forbidden.search(path.read_text()), "unexpected sensitive API or intent payload in " + path.name)

    vector = ET.parse(ROOT / "app/src/main/res/drawable/fingerprint.xml").getroot()
    group = vector.find("group")
    require(group is not None and group.get(ANDROID + "translateY") == "960", "Google SVG viewport transform changed")
    path = group.find("path").get(ANDROID + "pathData")
    require(hashlib.sha256(path.encode()).hexdigest() ==
            "8370cecaaec898c9b153a359bfffbd3584f315a6207bbe2c210f3f3f918f7c84",
            "fingerprint path differs from the pinned Google asset")

    workflow = (ROOT / ".github/workflows/fod-debug.yml").read_text()
    require("debug.keystore" not in workflow and "actions/cache@" not in workflow,
            "do not cache the FOD signing key")
    for action in re.findall(r"uses:\s*(\S+)", workflow):
        require(re.fullmatch(r"[\w./-]+@[0-9a-f]{40}", action), "pin action revisions: " + action)
    print(f"FOD security boundary guards passed ({len(fod_files)} feature files; permissions, identity, queue, icon and CI).")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--manifest", type=Path)
    args = parser.parse_args()
    check_sources()
    if args.manifest:
        root = ET.parse(args.manifest).getroot()
        package = root.get("package")
        require(package == "io.github.cybr47.oneuix.fodmod", "unexpected debug application ID")
        check_permission(root, package)
        print("Packaged APK has the expected signature-only FOD permission.")
