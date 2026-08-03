# TrackToSearch 背景风格预览 V2 实现计划

> **面向 AI 代理的执行计划：** 保持当前工作区已有 Android 修改和未跟踪截图不变，只提交本次预览文档与 HTML。

**目标：** 按四张真实 App 截图重做页面背景风格比较，修正上一版的通用布局偏差。

**架构：** 新建一个独立静态 HTML，使用 CSS 构造四个真实页面骨架和四种背景层，JavaScript 只负责页面/背景切换。复用已有可视化伴侣会话，使用新的屏幕文件名发布。

### 任务 1：建立 V2 设计基线

**文件：**
- 创建：`docs/superpowers/specs/2026-08-03-tracktosearch-background-style-preview-v2-design.md`
- 创建：`docs/superpowers/plans/2026-08-03-tracktosearch-background-style-preview-v2.md`

- [x] 记录截图基准、方案取舍和验收标准。

### 任务 2：重做真实页面预览

**文件：**
- 创建：`docs/previews/tracktosearch-background-styles-v2.html`

- [x] 建立接近截图比例的手机画布、状态栏、标题区和底部导航。
- [x] 实现 Search、Discover、Mine、Settings 四个页面的真实内容骨架。
- [x] 实现截图基准、Diffuse、Mesh、Aurora 四种背景，并让前景玻璃材质保持不变。
- [x] 确保移动端无页面级横向溢出，保留发现页横向列表的内容露边。

### 任务 3：发布并验证

**文件：**
- 可视化伴侣屏幕：`background-styles-02.html`

- [x] 确认本地服务仍在运行，将 V2 HTML 推送到新屏幕文件。
- [x] 检查 HTTP 200、关键风格/页面标记、切换脚本和 `prefers-reduced-motion`。
- [x] 执行 `git diff --cached --check`，只暂存本次 V2 文件并提交。
