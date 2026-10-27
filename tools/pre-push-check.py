#!/usr/bin/env python3
"""
@author 雾晚：推送前静态检查
在没有本地编译器的环境下， catching 常见错误：
1. 函数签名与调用点不一致
2. 测试禁用的旧字符串
3. 测试引用的 XML key 缺失
用法: python3 tools/pre-push-check.py
"""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).parent.parent
SRC = ROOT / "app/src/main"
TEST = ROOT / "app/src/test"
ANDROID_TEST = ROOT / "app/src/androidTest"

errors = []

def check_function_signatures():
    """检查 requestMode 等关键函数的签名与调用点是否一致"""
    # 收集 ServiceCommandController 中的函数签名
    controller = SRC / "java/io/nekohasekai/sagernet/bg/ServiceCommandController.kt"
    if not controller.exists():
        return
    text = controller.read_text(encoding='utf-8')
    # 找 fun requestMode 的签名
    m = re.search(r'fun requestMode\(([^)]*)\)', text)
    if m:
        params = m.group(1).strip()
        has_patch_param = 'patch' in params
        # 检查 DashboardFragment 的调用
        dashboard = SRC / "java/io/nekohasekai/sagernet/ui/DashboardFragment.kt"
        if dashboard.exists():
            dtext = dashboard.read_text(encoding='utf-8')
            calls = re.findall(r'requestMode\(([^)]*)\)', dtext)
            for call in calls:
                # 如果签名没有 patch 参数，但调用传了 lambda，就是错的
                if not has_patch_param and '{' in call:
                    errors.append(f"签名不一致: requestMode 签名无 patch 参数，但 DashboardFragment 传了 lambda: {call[:60]}")

def check_forbidden_strings():
    """检查测试禁用的旧字符串"""
    forbidden = [
        "http://www.gstatic.com/generate_204",
    ]
    for f in SRC.rglob("*.kt"):
        try:
            text = f.read_text(encoding='utf-8')
            for term in forbidden:
                if term in text:
                    errors.append(f"禁用字符串: {f.relative_to(ROOT)} 包含 {term}")
        except:
            pass

def check_xml_keys():
    """检查测试引用的 XML key 是否存在"""
    xml_path = SRC / "res/xml/global_preferences.xml"
    if not xml_path.exists():
        return
    xml_text = xml_path.read_text(encoding='utf-8')
    xml_keys = set(re.findall(r'app:key="([^"]+)"', xml_text))

    # 检查 androidTest 引用的 key
    for f in ANDROID_TEST.rglob("*.kt"):
        try:
            text = f.read_text(encoding='utf-8')
            # 找 "categoryXXX" 字符串引用
            for key in re.findall(r'"(category[A-Za-z]+)"', text):
                if key not in xml_keys:
                    errors.append(f"XML key 缺失: {f.name} 引用了 {key}，但 global_preferences.xml 中不存在")
        except:
            pass

def main():
    check_function_signatures()
    check_forbidden_strings()
    check_xml_keys()

    if errors:
        print("❌ 发现问题:")
        for e in errors:
            print(f"  - {e}")
        sys.exit(1)
    else:
        print("✅ 静态检查通过")
        sys.exit(0)

if __name__ == "__main__":
    main()
