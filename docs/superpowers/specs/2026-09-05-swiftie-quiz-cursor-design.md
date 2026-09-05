# 霉粉彩蛋出题页闪烁光标设计

日期：2026-09-05
状态：已批准（头脑风暴定稿）

## 背景与回归修复

### 回归事实

`c1d426a8`（出题页改为原海报全屏复刻）删除了 `SwiftieBillboard.kt`，题面改为
`SwiftiePoster.kt` 全屏海报：唯一占位符是答案槽里的 `X`，没有 `X = ?` 下行。
天空饱和度调优（`1169d0a4` P3→sRGB、`079e68cc` 换抹字图）也在这条线上。

9 月 2 日 merge `2985d403` 把视觉迭代分支 `3b35c4be`（从 `d69beefb` 分出，早于海报
复刻，还是 Billboard 版）合入：`SwiftieEggScreen.kt` 的题面舞台取了迭代分支侧，
Billboard 复活；但 `SwiftieBillboard.kt`、`SwiftieGlitterTextCompat.kt` 未进提交，
HEAD 实际编译不过。工作区靠两份未跟踪文件（正是这两份）修补编译，导致：

- `X = ?` 下行复活（用户以为已删）
- `SwiftiePoster.kt`、`SwiftiePosterInk.kt`、饱和度调优过的 `swiftie_poster_sky.webp`
  成为死代码，9 月 2 日的饱和度工作未生效
- 用户未察觉：`quizSolved` 已置 true，入口不再给题面

### 修复内容

1. `SwiftieEggScreen.kt` 的 `SwiftieQuizStage` 恢复为 `c1d426a8` 的海报版实现：
   整屏 `SwiftiePoster` + `swiftiePosterFit` 反算版面 + `hazeState` 键盘毛玻璃采
   海报源。**只回退题面舞台这一段**，保留视觉迭代分支的其他改动
   （Lover 箭、昵称珠、终局背景等）。
2. 恢复海报版的前奏链路：QuizStage 的 `prerollMs` 参数（答对后键盘退场、
   算式归位、手写体落笔），调用方一并接回。
3. 删除未跟踪文件 `SwiftieBillboard.kt`、`SwiftieGlitterTextCompat.kt`
   （引用面已核实：仅 Screen 实际调用 Billboard，Signature 只在注释提及）。
4. 键盘 haze 支持完好（`SwiftieKeypad` 的 `hazeState: HazeState? = null` 可空参数
   仍在），无需改动。

## 光标设计

### 视觉规格

- **形态**：基线下划线，颜色 `SwiftiePalette.RoyalBlue`（与原 X 同色）
- **尺寸**：宽度 = 槽位单个数字字宽（按 `SwiftiePosterInk` 度量），
  厚度 = 字高的 6%，悬在基线下方约字高的 8% 处
- **节奏**：硬闪——1060ms 周期，前 530ms 显示、后 530ms 隐藏，阶跃无渐变
  （标准文本光标节奏）

### 状态行为矩阵

| 状态 | 光标 |
|------|------|
| INPUT · 空槽 | 槽位中心闪（替代原来的 X） |
| INPUT · 有数字 | 跟在最后一位数字右缘 + 字高 15% 间距处闪 |
| 输满 2 位（`MAX_INPUT_LENGTH`） | 消失（提交键点亮即是指引） |
| WRONG（300ms 摇晃窗口） | 隐藏——洋红描边 + 摇晃已是视觉焦点 |
| clearWrong 后 | 回到空槽中心重闪 |
| SOLVED | 消失（闪粉 13 落位） |
| 减少动效（reducedMotion） | 常显不闪——功能性提示不能整个消失 |

### 实现要点

- 光标画在 `SwiftiePoster.drawEquation` 里，跟算式共用 shake/settle/pop 变换——
  落字弹跳时光标跟着数字一起弹
- 位置从 `slotPath` 的 bounds 推：空槽取 X 的 bounds，有输入取 `bounds.right`；
  不新增度量代码
- 闪烁相位用 `rememberInfiniteTransition`，只在 draw lambda 里读，不触发重组
  （与现有 twinkle 相位同模式）
- 退格时光标随 `bounds.right` 回退，无额外处理
- 空槽时光标本身即占位：`UNKNOWN` 常量与 `slotPath(UNKNOWN)` 分支从绘制路径移除
  （a11y 文案 `swiftie_quiz_a11y` 不受影响，它由 TalkBack 读、不依赖视觉）

## 测试

- 状态逻辑零改动（`SwiftieQuizState` 不动），既有单测全保留
- 光标是纯绘制：用 eggPreview 预览变体 + 真机截图验收
  （空槽 / 输 1 位 / 输满 / 答错四态）
- 恢复海报版后跑既有 `SwiftieEggScreenTest`（androidTest）
- 构建验证：`assembleDebug` 通过且 Billboard 相关引用清零
