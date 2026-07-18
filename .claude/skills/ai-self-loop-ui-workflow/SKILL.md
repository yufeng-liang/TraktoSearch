---
name: ai-self-loop-ui-workflow
description: Use when a UI design has been approved via brainstorming's web prototype and you must now implement it as Jetpack Compose code that precisely replicates the approved design. Triggers the AI-held emulator screenshot loop (installDebug + adb screencap + read screenshot + iterate) so results match the web design draft without the user running Android Studio.
---

# AI 自看自改 Compose UI 工作流

## 概述

让 AI **自己持有模拟器闭环**自改 UI，不要等用户跑 AS。对已通过头脑风暴网页原型审查、用户批准实施的设计稿，本技能确保从网页设计稿到 Compose 代码的精准复刻。

解决三痛点：用户不用开 AS、AI 对照稿迭代、最终结果贴合网页设计稿。

核心闭环：

1. 设计稿已用网页原型（HTML）经头脑风暴展示并获得用户批准
2. AI 写 Compose 实现
3. AI 自己 `installDebug` + `adb screencap` + **读截图** + 严格对照设计稿复刻
4. 循环到截图效果几乎复刻设计稿
5. 把最终截图交用户确认

## 何时使用

- 触发前提：需要将网页设计稿实施为compose UI或头脑风暴网页原型已展示、用户**已批准实施**
- 任务：把批准的网页设计稿转写为 Compose 代码并精准复刻
- 不适用：纯逻辑修改、无需视觉对照的改动

## 环境上下文（本机命令）

> 本机路径，按需调整。adp 路径示例 `H:/android/Sdk/platform-tools/adb.exe`，模拟器序列号示例 `emulator-5554`。

### 编译安装与截图

```bash
# 编译安装
./gradlew installDebug

# 截图并拉到本地
H:/android/Sdk/platform-tools/adb.exe -s emulator-5554 shell screencap -p /sdcard/shot.png
H:/android/Sdk/platform-tools/adb.exe -s emulator-5554 pull /sdcard/shot.png ./shot.png
```

- 确保模拟器已启动且目标页可达（必要时用 adb 启动 Activity / 深度链接）
- 先 `adb devices` 确认设备在线，避免模拟器没启动就 install

### 亮暗色模式切换

```bash
# 切亮色模式
H:/android/Sdk/platform-tools/adb.exe -s emulator-5554 shell cmd uimode night no

# 切暗色模式
H:/android/Sdk/platform-tools/adb.exe -s emulator-5554 shell cmd uimode night yes
```

### 发送截图到邮箱（可选，便于远程审查）

当用户不在电脑前（如通过 Telegram 远程对话），用 SMTP 发送截图：

```bash
SMTP_HOST=smtp.qq.com SMTP_PORT=465 SMTP_SSL=true \
SMTP_USERNAME=1577865546@qq.com SMTP_PASSWORD=<授权码> \
SMTP_FROM=1577865546@qq.com \
py "C:/Users/15778/.agents/skills/email-smtp-send/scripts/smtp_send.py" send \
  --to 1577865546@qq.com \
  --subject "xx页截图" \
  --body "AI 自看自查截图。" \
  --attach shot.png
```

- 技能来源：`npx skills add tiangong-ai/skills@email-smtp-send -g -y`
- SMTP 授权码在 QQ 邮箱设置 → 账户 → POP3/IMAP/SMTP 生成
- `py` 命令（Windows Python Launcher），不要用 `python3`

## 闭环步骤

### 1. 锁定设计稿

- 明确要复刻的网页原型（HTML 文件路径或 OpenPreview 链接）
- 提取关键视觉要素：布局结构、间距、圆角、配色、字号字重、图标、状态（亮/暗色、空态、加载态）
- 要素不清楚，回头脑风暴澄清，不要猜

### 2. 写 Compose 实现

- 优先复用项目现有组件与约定（`F:/trae-project` 为 Jetpack Compose + Hilt + MVVM）
- 不要硬编码想看/已看等状态，按 AGENTS.md 从缓存计算

### 3. AI 自跑模拟器截图

- 用上方「编译安装与截图」命令 `installDebug` + `screencap` + `pull`
- 确认模拟器在线、目标页已导航到再 cap（否则截到旧页面）
- 亮暗色切换命令见上方

### 4. 读截图，严格对照设计稿

- 用 Read 工具读取 `shot.png`
- 逐条对照第 1 步视觉要素，找偏差：间距、圆角、配色、字号、对齐、溢出
- 改 Compose 代码修复偏差，回到步骤 3

### 5. 循环收敛

- 重复 3→4 直到截图几乎复刻设计稿（主观判定：肉眼无显著偏差）
- 多状态（亮/暗、空态）都要各自截图核对

### 6. 交用户终审

- 把最终截图呈现给用户确认
- 用户不在电脑前时，用上方 SMTP 命令发图到邮箱

## 硬性纪律（防止过早停手）

- **读截图是强制的**。没读截图就声称"已复刻"= 没做。
- **不要凭想象判定完成**。每次迭代必须以真实截图为依据。
- 设计稿要素未逐项核对前，不进入下一步。
- 用户没批准前，不要跳过网页原型直接写 Compose（本技能只接"已批准"的设计稿）。

## 常见错误

| 错误 | 修复 |
|------|------|
| 模拟器没启动就 install | 先 `adb devices` 确认设备在线 |
| 截图是旧页面 | 确认 Activity 已导航到目标页再 cap |
| 亮暗色只测一种 | 两种模式都截图核对 |
| 只读设计稿不读截图 | 闭环核心是"读真实截图对照" |

## 红线 — 停下来重来

- 没读真实截图就汇报完成
- "我觉得差不多了" 替代逐项对照
- 跳过用户批准直接进 Compose
- 设计稿要素靠猜

**以上任一发生 = 回到步骤 1 重来。**
