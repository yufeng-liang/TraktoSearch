# Watchlist 性能优化实施计划

> **面向 AI 代理的执行说明：** 按任务顺序执行，每个测试步骤都要先看到预期失败，再实现最小改动使其通过。

**目标：** 在不改变功能和视觉行为的前提下，减少 Watchlist enrich 引起的重复状态发布，并缩短 MovieCard 解码 Bitmap 的生命周期。

**技术栈：** Kotlin、Jetpack Compose、Hilt、Coil、JUnit、Android Gradle Plugin。

### 任务 1：补充列表状态更新行为测试

**文件：**
- 修改：`app/src/test/java/com/tracktosearch/ui/screen/watchlist/WatchlistViewModelTest.kt`
- 修改：`app/src/main/java/com/tracktosearch/ui/screen/watchlist/WatchlistViewModel.kt`

- [ ] 编写测试，验证一次 Watchlist 加载最终发布完整 enrich 列表，且加载状态归还完成。
- [ ] 运行 `./gradlew :app:testDebugUnitTest --tests '*WatchlistViewModelTest*'`，确认测试先因当前可观察行为缺失或辅助边界不存在而失败。
- [ ] 实现最小状态更新调整，保留现有渐进加载语义；避免无关 ViewModel 重构。
- [ ] 重新运行同一测试并确认通过。

### 任务 2：补充 MovieCard Bitmap 生命周期测试边界

**文件：**
- 修改：`app/src/test/java/com/tracktosearch/ui/component/MovieCardTest.kt`
- 修改：`app/src/main/java/com/tracktosearch/ui/component/MovieCard.kt`

- [ ] 先增加可测试的 Bitmap 释放/主色提取边界测试，测试应先失败。
- [ ] 将 Bitmap 生命周期管理收敛到最小范围，不改变图片请求参数和 UI 渲染。
- [ ] 运行 MovieCard 单元测试，确认通过。

### 任务 3：构建与回归验证

**文件：** 无新增代码文件。

- [ ] 运行 `./gradlew :app:testDebugUnitTest`。
- [ ] 运行 `./gradlew :app:assembleDebug`。
- [ ] 使用 `android-emulator-qa`/真机验证 Watchlist 加载、详情跳转、返回、滚动和主色效果。
- [ ] 在 `eeb30d23` 上重复相同流程，比较 Simpleperf/gfxinfo/meminfo；若指标无改善或功能有回归，停止并回滚本轮改动。

### 任务 4：提交

- [ ] 只提交设计文档和本轮代码/测试变更，不提交 `perf-artifacts/`。
- [ ] 使用中文 Conventional Commit，例如 `perf: 优化我的页列表与图片内存占用`。
