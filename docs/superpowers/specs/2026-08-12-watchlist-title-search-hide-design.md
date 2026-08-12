# Watchlist 标题栏:搜索展开时隐藏标题「我的」

日期:2026-08-12
状态:已批准(用户确认淡入淡出动画方案)

## 背景

watchlist 页标题栏左侧固定显示标题「我的」(28sp ExtraBold)。点击搜索图标后,搜索框从右侧动画展开(240ms)并覆盖标题区域。由于搜索框是半透明毛玻璃,标题文字会从毛玻璃下透出,视觉上重叠凌乱。

## 目标

- 搜索框展开(`isSearchExpanded = true`)时,标题「我的」淡出隐藏
- 搜索框收起(任意收起路径)时,标题淡入恢复
- 动画节奏与搜索框 240ms 宽度动画保持一致

## 方案(已确认)

使用 `AnimatedVisibility(visible = !isSearchExpanded, enter = fadeIn(tween(240)), exit = fadeOut(tween(240)))` 包裹标题 `Text`,将标题的 `Modifier.align(Alignment.CenterStart)` 上移到 AnimatedVisibility。

要点:

- 只传 fade 动画(不带尺寸动画),标题宽度不折叠,布局零跳动,不影响搜索框展开
- 展开态标题从组合树移除:不参与布局、不遮挡搜索框、无障碍(TalkBack)不读取
- 收起态恢复后标题重新进入组合树

不采用 alpha 动画方案:标题留在组合树中,无障碍仍会读到,需额外处理点击穿透。

## 改动点

文件:`app/src/main/java/com/tracktosearch/ui/screen/watchlist/WatchlistScreen.kt`

- 标题 `Text`(约 897-904 行)外包 `AnimatedVisibility(visible = !isSearchExpanded, enter = fadeIn(tween(240)), exit = fadeOut(tween(240)), modifier = Modifier.align(Alignment.CenterStart))`
- 确认 `fadeIn`/`fadeOut` import(`AnimatedVisibility`、`tween` 已存在)
- 其余行为不变:点击标题栏空白处收起搜索(外层 Box clickable)、键盘/返回收起、过滤与切 tab 收起均不受影响

## 验证

- `assembleDebug` 构建通过
- 真机/模拟器:点击搜索图标 → 标题 240ms 内淡出、搜索框同步展开;点击空白/返回/过滤/切 tab 收起 → 标题淡入恢复
- 展开态确认标题完全不可见、无文字透出
