package com.tracktosearch.ui.haptic

/**
 * 触感语义。调用侧只声明「这是什么交互」，不声明「震多重、走哪个常量」——
 * 常量、primitive、厂商效果 ID 的选择是各 HapticBackend 的实现细节。
 *
 * 13 个语义在体感上排成一条由轻到重的梯度：[FREQUENT_TICK] 最轻，[TAP] 是通用点击的顶档，
 * [CONFIRM]、[REJECT]、[DRAG_START]、[THRESHOLD_ARMED] 各自在自己的含义上已到顶。
 * [boosted] 就是沿这条梯度上移一档，「增强」档用它。
 *
 * 四层（RichTap / MIUI / OPlus / AOSP）各自的映射表见设计文档
 * `docs/superpowers/plans/2026-09-01-haptics-overhaul.md` 的「语义词表」一节。
 * 本文件只定义语义与轻重次序，刻意不含任何平台常量，因此可以纯 JVM 单测。
 *
 * 长按没有单独一档：[DRAG_START]（「抓住了」）就是它的语义，长按进入拖动本来也是同一件事。
 * 长按那一记由 `Modifier.hapticCombinedClickable` 发，它固定传 `hapticFeedbackEnabled = false`
 * 关掉 `combinedClickable` 内建的那记 `LongPress` —— 不关就是同一常量背靠背两次，
 * 而且内建那记是硬编码常量，走不到厂商预置效果，也不受三档开关管。
 */
enum class HapticSemantic {
    /** 普通按钮、可点卡片。实心的一击，通用点击里最重的一档。 */
    TAP,

    /** 列表项、次级操作。比 [TAP] 轻一档，适合一屏里反复出现的交互面。 */
    LIGHT_TAP,

    /** Tab 切换、单选、图表选中、分段控件。有「卡进格子」的刻度感，比 [LIGHT_TAP] 再轻一档。 */
    SEGMENT_TICK,

    /** 滑块连续拖动、逐珠划过。梯度最底一档，专给一次手势里连发几十次的场景，单次必须极轻。 */
    FREQUENT_TICK,

    /** 开关打开、chip 选中、展开。开关族里较重的一半，状态「立起来」。 */
    TOGGLE_ON,

    /** 开关关闭、chip 取消、收起。开关族里较轻的一半，状态「落回去」。 */
    TOGGLE_OFF,

    /** 提交成功、标记成功、登录成功。成功语义，本身就是最重的一笔。 */
    CONFIRM,

    /** 操作失败、校验不通过。失败语义，重，但不该重到惊吓。 */
    REJECT,

    /** 拖拽起手、长按进入拖动。沉的一记「抓住了」。 */
    DRAG_START,

    /** 下拉刷新到达阈值、侧滑到阈值。上冲感，告诉用户「可以松手了」。 */
    THRESHOLD_ARMED,

    /** 手势翻页落定、面板吸附。落到位的一记轻响。 */
    GESTURE_END,

    /** 列表滚到尽头、滑条到边界。撞墙的一记轻响，且会被反复顶到。 */
    SCROLL_EDGE,

    /** 弹窗、底部面板出现。让面板「落」得出来，又不抢注意力。 */
    POPUP_SHOW,
    ;

    /**
     * 「增强」档用：把语义整体上移一档，已在顶档的返回自身。纯函数，可单测。
     *
     * 「上移一档」= 换成表达力更强、体感更重的那个语义，而不是给振幅乘系数：
     * 系统 `VibrationScaler` 已按用户的触感强度档位乘过 0.6 / 0.8 / 1.0 / 1.2 / 1.4，
     * 我们再叠一层就是重复缩放。也不绕过系统总开关，系统关了就是关了。
     *
     * `when` 刻意穷举、不写 `else`：以后往枚举里加语义时，编译器会逼着补上映射。
     *
     * 映射无环，反复调用会收敛到不动点（[TAP]、[CONFIRM]、[REJECT]、[DRAG_START]、
     * [THRESHOLD_ARMED]），最长链 3 步：[FREQUENT_TICK] → [SEGMENT_TICK] → [LIGHT_TAP] → [TAP]。
     * 但「增强」档只升一档，调用方不要连着调。
     */
    fun boosted(): HapticSemantic = when (this) {
        // 通用点击的顶档：再往上只剩 CONFIRM 这类带特定含义的语义，不能拿来当「更重的点击」
        TAP -> TAP
        // 次级操作升成主操作那记实心点击
        LIGHT_TAP -> TAP
        // 刻度感升成列表项那档轻点，仍然克制，不至于吵
        SEGMENT_TICK -> LIGHT_TAP
        // 连发场景只升到刻度感：升太多会在一次拖动里累出几十下重震
        FREQUENT_TICK -> SEGMENT_TICK
        // 开关族内已无更重的一档，升到实心点击；不升 CONFIRM —— 那是「操作成功」，不是「状态切换」
        TOGGLE_ON -> TAP
        // 关比开轻，先升到同族的开，保住「这是一次开关」的辨识度
        TOGGLE_OFF -> TOGGLE_ON
        // 成功反馈已是最重的一笔，顶档
        CONFIRM -> CONFIRM
        // 失败反馈同理，顶档；再重就成了报错惊吓
        REJECT -> REJECT
        // 起手那记本来就要沉才抓得住，顶档
        DRAG_START -> DRAG_START
        // 阈值命中是「可以松手了」的强提示，本就顶档
        THRESHOLD_ARMED -> THRESHOLD_ARMED
        // 落定与吸附要的是「到位」的实感，直接给实心点击
        GESTURE_END -> TAP
        // 到边界是撞墙，升一档到刻度感就够；边界会被连着顶，升太重变成惩罚
        SCROLL_EDGE -> SEGMENT_TICK
        // 弹窗出现升成列表项那档，面板「落」得更明确，又不至于像按下一个按钮
        POPUP_SHOW -> LIGHT_TAP
    }
}
