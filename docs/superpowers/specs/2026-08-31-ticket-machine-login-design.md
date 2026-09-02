# 取票机式激活登录页设计

日期：2026-08-31
状态：已批准，待实施

## 背景

激活登录页当前是一张毛玻璃卡片：一个单行输入框收邀请码，一个「激活」按钮，卡片下方并排放着「豆瓣登录」和「以访客身份进入」，激活成功放一段 Lottie 礼花。功能完整但没有场景感——这一页是用户进入 App 的第一个画面，却只是一张表单。

同期后端已把邀请码从 12 位字母数字改成 6 位纯数字（提交 `77074cad`），码变短之后正好可以做成电影院取票机的键盘输入。

## 目标

把激活登录页改造成电影院取票系统的拟物界面：

- 「激活码」改称「取票码」，「激活」按钮改称「取票」
- 用户在取票机面板上输入 6 位取票码，支持粘贴
- 取票成功后从出票口打印出一张电影票
- 电影票承载 Trakt 登录、豆瓣登录、访客进入三个入口
- 票上打印用户昵称与日期

页面原有的背景（`MovieBackdrop`）、场记板图标、标题副标题、右上角「什么是 Trakt」按钮保持不变。

## 非目标

- 不改 Trakt OAuth 授权流程本身，只换承载它的 UI 容器
- 不改豆瓣登录与访客模式的导航行为
- 不为旧版 12 位字母邀请码提供输入通道（见「决策 5」）
- 不把「激活」这个词从设置页、帮助页等其他页面改掉，本次只改这一页及其独占的字符串

## 决策记录

设计过程中确认的六个分叉，记录结论与理由，避免实施期反复。

**决策 1：已激活回访时票留在出票口。**
用户取票成功后没选登录方式就退出，下次进入这一页时机器保持完整形态，票静态停在出票口，不重播打印动画，三个入口直接可点。刚取票和回访的画面一致，机器与票构成一个连贯场景。

**决策 2：自绘取票机键盘，不弹系统键盘。**
机器面板上画 0-9、退格、粘贴，加一个通栏「取票」键。按压手感沿用 `SwiftieKeypad` 的既有模式。代价是读屏语义和外接键盘要自己接，见「组件设计 / 取票机面板」。

**决策 3：票面印真实信息加派生座位号。**
真实信息是昵称、日期、`ADMIT ONE`；厅号、排号、座号、条码由 6 位取票码按固定算法派生。派生保证同一个码永远得到同一张票，人人不同又不是随机噪声。不编造场次时间、票价、影院名——票会变长到把三个入口挤出首屏。

**决策 4：错误分两层显示。**
像素屏只放短状态（如「码已用过」），完整引导文案仍印在机器下方。现有 `authErrorString` 的八条文案全部保留：像素屏宽度装不下「请向管理员索取新的邀请码」这类句子，只靠屏幕报错会丢掉「下一步该干什么」。

**决策 5：不为旧版 12 位字母码做兼容。**
6 格数字键盘是唯一入口。Worker 自 `77074cad` 起只签发 6 位数字码，App 发版总晚于 Worker 部署，届时在途的 12 位码已过期（管理端默认 7 天、公开申请 72 小时）。上线前把剩下的旧码改发一轮即可。

**决策 6：打印动画取代 Lottie 礼花。**
删除 `ActivationCelebration.kt` 及其 `R.raw.easter_firework` 引用。礼花是通用庆祝语言，圆滑矢量风格与像素拟物不同源，两套动画叠在一起互相抢戏。

## 架构

### 文件划分

`ActivationLoginScreen.kt` 降级为壳，只保留页面骨架：`Surface` 与背景 source 容器、`MovieBackdrop`、场记板与标题副标题、右上角「什么是 Trakt」按钮与弹窗、Trakt OAuth 的 `LaunchedEffect` 与 `TraktAuthCancelGuard`、导航回调。这部分不改。

删除该文件内的 `ActivationCard`（约 210 行）与 `ActivationSecondaryActions`（约 70 行），替换为：

| 文件 | 职责 | 依赖 |
| --- | --- | --- |
| `ui/screen/login/TicketMachine.kt` | 机壳、铭牌、像素屏、6 格取票码、自绘键盘、取票键、出票口。接收状态，回调事件 | Compose、haze 体系 |
| `ui/screen/login/CinemaTicket.kt` | 票面、撕口与齿边、像素条码、三个登录入口 | Compose |
| `ui/screen/login/TicketPrintPhase.kt` | 打印时序纯函数，无 Compose 依赖 | 无 |
| `ui/screen/login/TicketStub.kt` | `deriveTicketSeat` 纯函数与 `TicketStub` 数据类 | 无 |
| `data/local/TicketStubStorage.kt` | DataStore 持久化票根 | DataStore、Hilt |

`TicketPrintPhase.kt`、`TicketStub.kt` 不含 Compose 依赖，可直接在 JVM 单测中调用。

选择这个划分而非单文件重写：`ActivationLoginScreen.kt` 现有 717 行，把取票机、票面、键盘、动画全塞进去会到 1300 行以上，改任何一处都要通读整个文件。也不新建独立 screen 整体替换——那样要维护两份并在导航层分叉，本次没有灰度回退需求。

### 数据流

票面要印昵称，但 `AuthManager.nickname` 目前只在 `/api/auth/check` 成功后填充（`AuthManager.kt:160`），`activate` 的响应里没有昵称。刚取票那一刻昵称是 `null`。

解决方式是让 `activate` 一次带回昵称：`auth-worker/src/auth/activate.ts` 的主查询已经 `JOIN friends f`，`SELECT` 列表加 `f.nickname`，成功响应体加 `nickname` 字段。老版本 App 不受影响——`NetworkModule.kt:64` 配置了 `ignoreUnknownKeys = true`，多出来的字段会被忽略。

取票成功时落盘一份票根，回访直接读，不再等 `check()` 回来：

```kotlin
data class TicketStub(
    val nickname: String,
    val issuedEpochDay: Long,   // 取票当天，不是「今天」
    val hall: Int,
    val row: Int,
    val seat: Int,
)
```

日期存的是取票当天，不是每次读系统当天。票一旦印出来，上面的日期就不该再变——这是实物票的基本逻辑，也避免用户第二天回访发现票"自己改了日期"。

取票码本身不落盘，只存派生结果。像素屏在已取票态显示的 `******` 是固定串，不含信息，也不需要存。

座位派生规则（定死，便于单测）：

```kotlin
hall = code.substring(0, 2).toInt() % 6  + 1   // 1..6 号厅
row  = code.substring(2, 4).toInt() % 12 + 1   // 1..12 排
seat = code.substring(4, 6).toInt() % 20 + 1   // 1..20 座
```

条码同样由取票码派生：每一位数字生成 3 根竖条，共 18 根，间隔固定 1.dp；同一位内第 k 根（k 取 0、1、2）的条宽为 `((digit + k) % 3 + 1).dp`。同码必得同条码。

### 状态机

三态，由 `AuthUiState.activated` 与落盘票根共同决定：

| 状态 | 机器 | 票 | 交互 |
| --- | --- | --- | --- |
| `IDLE` | 完整。屏显「请输入 6 位取票码」，6 格空 | 不存在 | 键盘可按，取票键满 6 位点亮 |
| `PRINTING` | 完整，屏显「正在打印…」 | 逐段推出，约 1.4 秒 | 键盘禁用，三入口不可点 |
| `COLLECTED` | 完整。屏显「已取票」，6 格显示 `******`，键盘置灰 | 静态停在出票口 | 三入口可点 |

`PRINTING` 只在 `activated` 从 `false` 跳到 `true` 的那一次发生，判定沿用现有 `shouldShowActivationCelebration(observedActivated, isActivated)`，函数改名为 `shouldPlayTicketPrint`，它已有单测，一并改名保留。冷启动进入已激活态时 `observedActivated` 初值即为 `true`，不会误触发动画。

错误不是第四态，而是叠在 `IDLE` 上的一层：像素屏切红字短状态，6 格左右抖两下后清空，机器下方那行显示完整文案。迁移取票码提示（`requiresMigrationInvite`）同样留在 `IDLE`，不占像素屏，与错误文案共用机器下方的文字区域。

## 组件设计

### 取票机面板

自上而下七层。

**机壳。** ~~圆角由现在的 20.dp 收到 14.dp，机器不该那么圆。保留 haze，`glassTint` 换成深金属色，顶边加一道 1.dp 高光线模拟金属折边。`GlassSurfaceRole` 沿用 `LoginSurface`。`glassSceneForContent` 的 `contentCount` 要重算：12 个键加 6 格加像素屏加取票键，远多于现在的 7，`contentCapacity` 给 20。~~

> **已被 2026-09-02 的改版取代。** 保留 haze 的方案实测不成立：机壳半透明，背景的爆米花和胶片轮会从机器里透出来 —— 取票机是台设备，不是一块玻璃。现在机壳完全自绘：竖向渐变（只往下压暗）＋上下折边＋四角螺丝＋一层平铺噪点，圆角 16.dp，落地阴影 8.dp。
>
> 由此这一屏不再消费毛玻璃体系：`hazeSource` / `backdropSource` / `glassSceneForContent` / `GlassSurfaceRole.LoginSurface` 全部从登录页移除，**该屏也不再跟随「玻璃/模糊」那项设置**。配色改由 Color.kt 的 `Machine*` 一族固定色提供，护栏见 `LoginMachinePaletteTest`。

**铭牌。** 复用现有 `login_personal_cinema_access` 那行 monospace 小字，文案改为 `TICKET MACHINE · PERSONAL CINEMA`。

**像素屏。** 深色凹槽，高度固定。屏内文案长短不一，不锁高会让整台机器随状态跳动。字号 `pixelFontSize(16.dp)`：现有代码注释已验证，中文九字在这个宽度下用 5 倍网格会顶到右边缘，4 倍才安全。

**6 格取票码。** 每格一道下横线。已填格显示像素数字，字号 `pixelFontSize(24.dp)`；当前输入位显示闪烁光标。整块给一个合并语义节点，读屏播报形如「取票码 4 9 2，还需 3 位」。

**键盘。** 4 行 3 列，前三行 1-9，末行依次为「粘贴」、`0`、退格。按压反馈沿用 `SwiftieKeypad` 的既有模式：`collectIsPressedAsState` 加 scale 0.92 加 ripple，与站内已有键盘手感一致。数字字号 `pixelFontSize(20.dp)`，中文键 `pixelFontSize(16.dp)`。每个键单独给 `contentDescription`。

**取票键。** 通栏，满 6 位才点亮。`isLoading` 时显示 `CircularProgressIndicator`，与现状一致。

**出票口。** 横向凹槽，深色内壁，上下各一道齿边阴影。票从这里推出。

粘贴键读剪贴板后用正则抽 `\d{6}`，不把整段文本塞进格子——剪贴板里通常是「你的取票码是 492013」这类句子。抽不到六位连续数字时像素屏提示「剪贴板没有取票码」，格子不动。**不在进入页面时自动读剪贴板**：Android 12 起每次读取都会弹系统提示，自动读会造成无端弹窗。剪贴板访问走平台 `ClipboardManager` 加 `primaryClip?.getItemAt(0)?.text`，与 `ShareImportDialogs.kt:100` 等既有调用一致，不引入 Compose 新剪贴板 API。

自绘键盘要补两样系统输入法本来白送的能力：读屏语义（上述合并节点与逐键描述），以及外接键盘——机器根节点挂 `onKeyEvent`，接收 0-9、Backspace、Enter。

`COLLECTED` 态键盘置灰但不移除，机器不该只剩半截。

### 票面

票面字体用 `FontFamily.Monospace`，不用像素字体。指定像素风的是取票机界面与取票码；票用等宽字体更像热敏打印机打出来的东西，两种字体分开也让机器和票在视觉上成为两个物件，而不是同一块 UI。

布局：

```
┌─────────────────────────┐
│ ADMIT ONE          ②厅  │   顶行 monospace 小字，右上角厅号
│                         │
│ 小明                    │   昵称，票面最大字号
│ 2026-08-31              │
│ 7 排 12 座              │
│┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈│   dashPathEffect 虚线
│  ›  Trakt 登录           │   44.dp 等高可点行
│  ›  豆瓣登录             │
│  ›  访客进入             │
│┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈│
│ ▌▊▐▌▊▊▐▌▐▊▌▊▐▌▊▐▌▊▐ │   由同一取票码派生
└─────────────────────────┘
```

三个入口不做成三个 Material 按钮——票上贴按钮会毁掉纸质感。做成三行等高可点行，左侧一个 `›`，按下时整行底色压深一档，模拟纸被按住。豆瓣那行保留现有 `DoubanLogo` 组件。Trakt 那行继承现有 `loginState` 的三种表现：`CONNECTING` 显示进度圈、`ERROR` 显示重试、`AUTHORIZING` 显示等待提示与取消入口。这套逻辑不改，只换承载容器。

票是纸不是玻璃，这是本次对全站毛玻璃体系的一次有意偏离。票用不透明暖白纸面加自绘投影：浅色主题 `#FBF6EC`，比页面底色 `#F7EFE2` 略亮；深色主题下页面底色是 `#D9CFC2`，票用更亮的白。理由是票需要实物感，糊上 haze 之后它退化成一张普通卡片，而且票面小字透出背景后可读性下降。

形状用 `Path` 绘制：左右各一个半圆凹口作为票根撕口位，底部齿边。

### 打印动画

总时长 1.4 秒，五段：

| 区间 | 内容 |
| --- | --- |
| 0.00-0.12s | 出票口内壁亮起，票头露出 2.dp |
| 0.12-1.10s | 票推出，分 6 步阶跃，步间停 40ms |
| 1.10-1.25s | 最后一步过冲 4.dp 再回弹 |
| 1.25-1.40s | 三个入口逐行淡入，间隔 50ms；条码最后画出 |

分步阶跃而非匀速滑出：热敏打印机是步进走纸，匀速会失去机械感。每一步阶跃配一次轻触觉反馈，六次连击构成「咔咔咔」的走纸声。触觉走现有 `ui/util/HapticExt.kt`（项目已自行封装一层），强度取 `TextHandleMove` 一档，`LongPress` 六连击会过重。

裁切：票必须从出票口下沿"长"出来，口子上方的票身要被遮住。用一个高度随进度增长的容器加 `clipToBounds()` 实现，比 offset 配合 clip 更直接。

时序全部放在 `TicketPrintPhase.kt` 的纯函数里：

```kotlin
data class PrintPhase(
    val revealFraction: Float,
    val overshootDp: Float,
    val rowsVisible: Int,
    val barcodeVisible: Boolean,
)

fun phaseAt(progress: Float): PrintPhase
```

Composable 只负责把 `Animatable` 的 progress 喂进去。阶跃步数、过冲、逐行淡入的时序因此全部可在 JVM 单测覆盖，不需要 Compose 测试环境。

系统关闭动画时（`Settings.Global.ANIMATOR_DURATION_SCALE == 0`）跳过推出，直接进入 `COLLECTED`。这是无障碍要求，不是可选项。

## 后端改动

`auth-worker/src/auth/activate.ts`：

1. 主查询 `SELECT` 列表加 `f.nickname`，`invite` 的类型声明同步加 `nickname: string`
2. `ActivateResponse` 接口加 `nickname: string`，成功响应带上

昵称是朋友自己在申请时填的，只回给刚用该邀请码激活成功的设备，不构成额外信息暴露。

对应客户端改动：`AuthApiService.kt` 的 `ActivateResponse` 加 `nickname` 字段；`AuthManager.activate()` 成功分支里把昵称写入 `_nickname` 并落盘票根。

## 文案与本地化

四个语言目录（`values`、`values-zh`、`values-ja`、`values-ko`）同步改。以下键经确认只在 `ActivationLoginScreen.kt` 内使用，改文案无连带影响。

改写：

| 键 | 现值（中文） | 改为 |
| --- | --- | --- |
| `auth_invite_code` | 输入 6 位激活码 | 请输入 6 位取票码 |
| `auth_activate` | 激活 | 取票 |
| `login_activation_locked` | ○ 尚未激活 · 输入邀请码解锁观影空间 | ○ 尚未取票 · 输入取票码换票入场 |
| `login_personal_cinema_access` | PERSONAL CINEMA ACCESS | TICKET MACHINE · PERSONAL CINEMA |
| `auth_expired_message` | 请连接网络后重新激活访问权限。 | 请连接网络后重新取票。 |
| `auth_migration_invite_hint` | 含「迁移邀请码」 | 迁移取票码 |
| `auth_error_*` 八条 | 含「邀请码」 | 取票码 |

删除 `login_activation_choose_path`（「激活成功，请选择 Trakt/豆瓣登录，或暂时以访客身份进入」）。票面本身就是那三个选项，再印一行说明是重复。

新增：

- 像素屏短状态，对应 `authErrorString` 每一个分支，一条不漏
- `正在打印…`、`已取票`
- 票面标签：`ADMIT ONE`、厅、排、座
- 键盘无障碍描述十二条（数字 0-9、粘贴、退格）
- 粘贴失败提示

韩语的已知限制：`ark_pixel_12px` 不含谚文字形（`Type.kt:22` 已注明），韩语的像素屏文案会回退系统字体，机器上的韩文不呈现像素风。这是既有限制，但取票机把像素屏放到了视觉中心，会比现在更明显。结论是接受回退，不为此更换字体。

> **2026-09-02 补充：字体子集必须跟着文案走。** 首版子集是为「输入 12 位激活码」一句手工裁的（129 个字形）。像素字体后来成了整台取票机的字体，此后新增的每一个字都静默回退系统字体 —— 而回退字体的字宽不在 12px 网格上，屏上看到的是两个字挤在一起，不是「少了一个字」那种显眼的错。装机截图里「请输**入**取票码」的挤压就是这么来的，「粘贴」键整个不是像素字也是同一个原因。
>
> 现在子集由 `scripts/subset-ark-pixel.py` 从 `machine_*` / `auth_error_*` / `auth_migration_invite_hint` / `login_activation_locked` / `login_personal_cinema_access` 这几族字符串反推字符集（336 个字形，46 KB），`PixelFontCoverageTest` 用同一套规则读 TTF 的 cmap 兜底：往显示屏加一句新文案而忘了重跑脚本，会在单测里红。
>
> 另有三个字上游 Ark Pixel 12px 根本没有（券 U+5238、換 U+63DB、既 U+65E2，七个变体的 cmap 完全一致，24415 个码位里都缺），所以中文的 `login_activation_locked` 末字由「换取入场券」改为「换票入场」，日文的「発券」一族改为「発行」、「入場券を引き換え」改为「チケットと引きかえ」、「既存の」改为「登録済みの」。谚文仍然全部回退，见上一段。

## 测试计划

JVM 单测（`app/src/test`）：

- `deriveTicketSeat`：`000000`、`999999`、取模边界；厅落在 1..6、排落在 1..12、座落在 1..20
- `phaseAt`：progress 为 0、0.5、1 时的相位输出；6 步阶跃的边界值；关闭动画时返回终态
- `shouldPlayTicketPrint`：沿用现有三条断言，随函数改名
- `AuthViewModel`：粘贴路径的数字过滤（键盘只喂数字，粘贴内容不可信）；满 6 位才允许提交；报错后清空 6 格
- 像素屏短状态映射：穷举 `authErrorString` 的每个分支，确认都有对应短状态
- `TicketStubStorage`：写入后读回。teardown 必须清 DataStore——`preferencesDataStore` 委托是进程单例，不清会污染后续用例

Robolectric Compose 测试（同在 `app/src/test`，`ActivationLoginActionsTest` 已验证这条路可用）：

- 6 格渲染与按键填格
- 取票键在不足 6 位时禁用
- `COLLECTED` 态三个入口可点

无法自动化的部分：`app/src/androidTest` 源集当前编译不通过（依赖锁与 `androidx.test:core` 冲突），因此打印动画的真机观感只能靠模拟器截图人工确认，不进 CI。

## 已知限制

- 韩语像素屏文案回退系统字体，见「文案与本地化」
- 打印动画无自动化视觉回归
- 座位派生是纯展示信息，与真实座位无关，不做任何业务承诺
- 票根落盘后若用户清除应用数据，票面昵称与座位会丢失；此时设备也已失去会话，会回到 `IDLE` 重新取票，不构成单独的降级路径

## 实施顺序

1. 后端：`activate.ts` 返回 `nickname`，补测试
2. 客户端数据层：`ActivateResponse` 加字段、`TicketStub` 与 `deriveTicketSeat`、`TicketStubStorage`、`AuthManager.activate()` 落盘
3. `TicketPrintPhase.kt` 与其单测
4. `TicketMachine.kt`
5. `CinemaTicket.kt`
6. `ActivationLoginScreen.kt` 接线，删除 `ActivationCard`、`ActivationSecondaryActions`、`ActivationCelebration.kt`
7. 四语言文案
8. 补齐单测与 Robolectric 测试

前三步与后五步之间没有共享写集，1-3 可先落地验证。







