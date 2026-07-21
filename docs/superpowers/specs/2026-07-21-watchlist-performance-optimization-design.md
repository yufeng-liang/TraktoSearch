# Watchlist 性能优化设计

## 背景

Phase 1 CPU 采样显示 Compose 重组、布局测量和绘制是主要应用侧热点。Phase 3 Heap Dump 未发现应用或三方泄漏，但流程后 Java/Native Heap 仍高于基线，Bitmap 总占用约 86 MB。

## 目标

- 减少 Watchlist 列表加载过程中的重复 Compose 状态更新。
- 缩短 MovieCard 对解码 Bitmap 的持有时间，避免卡片离开组合后继续保留引用。
- 保持现有功能、渐进式加载、图片尺寸、主色提取和 UI 外观不变。

## 非目标

- 本轮不调整 Coil 内存缓存上限或磁盘缓存上限。
- 本轮不删除或降低 PageBackground 的动画和渐变效果。
- 本轮不改变网络请求、缓存命中策略或 Watchlist 数据语义。

## 方案

1. 将 Watchlist enrich 结果更新逻辑收敛到可验证的列表更新边界，避免每个后台任务都复制并发布整份列表状态。
2. MovieCard 的 Bitmap 仅服务于延迟主色提取，提取完成或卡片销毁后立即释放；不改变 Coil 的图片请求和缓存行为。
3. 通过现有 ViewModel/Compose 测试覆盖列表内容与加载状态不回归，之后用同一台设备重新验证 CPU、gfxinfo 和 meminfo。

## 验收标准

- Watchlist 仍能显示完整 enrich 结果，加载失败和空列表行为不变。
- MovieCard 图片、点击、共享元素和主色效果不变。
- 单元测试通过，debug 构建通过。
- 同流程下不新增 Activity/View；空闲内存与 Draw 长尾不恶化。
