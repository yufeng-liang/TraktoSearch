# AI 自看自改 Compose UI 工作流 — 环境上下文

> 用途：新会话开场直接 read 本文件，避免重复踩坑。
> 关联痛点：Compose UI 朴素、看效果要开 AS 跑模拟器麻烦、AI 照网页稿生成但转 Compose 变味。

## 目标

让 AI **自己持有模拟器闭环**自改 UI，不是让用户来跑：

1. AI 出网页级设计稿（HTML 原型，用户先审风格）
2. AI 写 Compose 实现
3. AI 自己 `installDebug` + `adb screencap` + **读截图** + 对照设计稿改间距/圆角/阴影/字体
4. 循环到截图接近设计稿
5. 把最终截图给用户确认

这一条能力（AI 自跑模拟器截图闭环）即可解三个痛点：用户不用开 AS、AI 对照稿迭代、最终结果贴网页感。

## 已排除的方案

换 Flutter 写 UI —— 杀鸡用牛刀，短期净增大量 Platform Channel 桥接成本，且 Flutter 默认 Material 也不美，解决不了"美观"痛点。正解是 AI 自看自改，不是换框架。

## 环境事实（关键，避免新会话重新踩坑）

- **模拟器在运行**：`emulator-5554`（sdk_gphone64 x86_64），状态 `device`
- ⚠️ **盘符是 `H:` 不是 `C:`**。`ANDROID_HOME` 指向 `H:\android\Sdk`
- `adb` **不在 PATH**，必须用全路径：`H:/android/Sdk/platform-tools/adb.exe`
- 示例命令：
  ```
  H:/android/Sdk/platform-tools/adb.exe devices -l
  H:/android/Sdk/platform-tools/adb.exe -s emulator-5554 exec-out screencap -p > shot.png
  H:/android/Sdk/platform-tools/adb.exe -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk
  ```
- 项目用 gradle wrapper 构建，命令 `./gradlew :app:assembleDebug`（win32 用 bash）
- 项目 `local.properties` 被 gitignore；用 git worktree 时新 worktree 没有该文件，需 `cp 父项目/local.properties worktree路径/local.properties` 否则编译说 SDK 未找到（此经验已写入 `AGENTS.md` / `CLAUDE.md` 的「常见陷阱与经验」）

## 推进状态

| 步骤 | 内容 | 状态 |
|------|------|------|
| 1 | 验证闭环（installDebug → screencap → 读图） | ✅ 已完成 |
| 2 | 头脑风暴设计（网页感风格、设计系统、生成流程） | ✅ 已完成 |
| 3 | 建立 Compose 设计 token（颜色/间距/圆角/字体/动效） | ✅ 已完成 |
| 4 | 网页效果图 → Compose 还原流程 |   进行中 |

### 已验证的闭环命令

```bash
# 构建
./gradlew :app:assembleDebug

# 安装
H:/android/Sdk/platform-tools/adb.exe -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk

# 启动
H:/android/Sdk/platform-tools/adb.exe -s emulator-5554 shell monkey -p com.tracktosearch -c android.intent.category.LAUNCHER 1

# 截图
sleep 4 && H:/android/Sdk/platform-tools/adb.exe -s emulator-5554 exec-out screencap -p > shot.png

# 结构校验
bash scripts/verify-ui.sh
```

### 发送截图到邮箱（远程审查）

当用户不在电脑前（如通过 Telegram 远程对话），用 SMTP 发送截图：

```bash
# QQ 邮箱 SMTP 发送（附件截图）
SMTP_HOST=smtp.qq.com SMTP_PORT=465 SMTP_SSL=true \
SMTP_USERNAME=1577865546@qq.com SMTP_PASSWORD=<授权码> \
SMTP_FROM=1577865546@qq.com \
py "C:/Users/15778/.agents/skills/email-smtp-send/scripts/smtp_send.py" send \
  --to 1577865546@qq.com \
  --subject "搜索页截图" \
  --body "AI 自看自查截图。" \
  --attach shot.png
```

- 技能来源：`npx skills add tiangong-ai/skills@email-smtp-send -g -y`
- SMTP 授权码在 QQ 邮箱设置 → 账户 → POP3/IMAP/SMTP 生成
- `py` 命令（Windows Python Launcher），不要用 `python3`

### 亮暗色模式切换

```bash
# 切亮色模式
H:/android/Sdk/platform-tools/adb.exe -s emulator-5554 shell cmd uimode night no

# 切暗色模式
H:/android/Sdk/platform-tools/adb.exe -s emulator-5554 shell cmd uimode night yes
```

## 用户明确倾向

不想换框架，只想让 AI 能边做边看实际截图自改，使结果达到网页设计稿水平。
