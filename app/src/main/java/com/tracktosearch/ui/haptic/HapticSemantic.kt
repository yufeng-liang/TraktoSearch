package com.tracktosearch.ui.haptic

/**
 * 触感语义。调用侧只声明「这是什么交互」，不声明「震多重、走哪个常量」——
 * 常量、primitive、厂商效果 ID 的选择是各 HapticBackend 的实现细节。
 *
 * 13 个语义在体感上排成一条由轻到重的梯度：[FREQUENT_TICK] 最轻，[TAP] 是通用点击的顶档，
 * [CONFIRM]、[REJECT]、[DRAG_START]、[THRESHOLD_ARMED] 各自在自己的含义上已到顶。
 * 这条梯度是各层映射表排轻重的依据；「轻/强」档不再沿它上移语义（2026-09-09 改走强度通道，
 * 上移会改效果性格——滑块连发的轻网格被顶成齿轮就是一次拖动几十下重震），见 [HapticMode]。
 *
 * 四层（RichTap / MIUI / OPlus / AOSP）各自的映射表见设计文档
 * `docs/superpowers/plans/2026-09-01-haptics-overhaul.md` 的「语义词表」一节。
 * 本文件只定义语义与轻重次序，刻意不含任何平台常量，因此可以纯 JVM 单测。
 *
 * 长按没有单独一档：[DRAG_START]（「抓住了」）就是它的语义，长按进入拖动本来也是同一件事。
 * 长按那一记由 `Modifier.hapticCombinedClickable` 发，它固定传 `hapticFeedbackEnabled = false`
 * 关掉 `combinedClickable` 内建的那记 `LongPress` —— 不关就是同一常量背靠背两次，
 * 而且内建那记是硬编码常量，走不到厂商预置效果，也不受档位开关管。
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
}
