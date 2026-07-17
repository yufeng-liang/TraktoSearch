#!/bin/bash
# AI 自看自查 — uiautomator 结构校验
# 用法: bash scripts/verify-ui.sh

ADB="H:/android/Sdk/platform-tools/adb.exe"
DEVICE="emulator-5554"
XML_PATH="/sdcard/ui.xml"
LOCAL_XML="/tmp/ui-dump.xml"

echo "=== 结构校验开始 ==="

# 1. dump UI 树
$ADB -s $DEVICE shell uiautomator dump $XML_PATH
$ADB -s $DEVICE pull $XML_PATH $LOCAL_XML

if [ ! -f "$LOCAL_XML" ]; then
    echo "FAIL: 无法拉取 uiautomator dump"
    exit 1
fi

echo "--- UI 树已保存到 $LOCAL_XML ---"

# 2. 检查关键组件存在性
check_component() {
    local name="$1"
    local pattern="$2"
    if grep -q "$pattern" "$LOCAL_XML"; then
        echo "PASS: $name 存在"
    else
        echo "FAIL: $name 未找到 (pattern: $pattern)"
    fi
}

check_component "搜索栏" "输入片名"
check_component "搜索类型" "电影"
check_component "底部导航-搜索" "搜索"
check_component "底部导航-发现" "发现"
check_component "底部导航-我的" "我的"
check_component "底部导航-设置" "设置"

# 3. 检查标签内容
check_tag() {
    local tag_text="$1"
    if grep -q "$tag_text" "$LOCAL_XML"; then
        echo "PASS: 标签「$tag_text」存在"
    else
        echo "WARN: 标签「$tag_text」未找到"
    fi
}

check_tag "痴迷"
check_tag "森中有林"
check_tag "公民义警"

# 4. 检查视图层级
NESTED_BOXES=$(grep -o "android.widget.FrameLayout" "$LOCAL_XML" | wc -l)
echo "INFO: FrameLayout 数量 = $NESTED_BOXES"

echo ""
echo "=== 结构校验完成 ==="
echo "检查上方输出，FAIL 项需要修复"