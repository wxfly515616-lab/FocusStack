# 路线图与可认领任务 · Roadmap & Claimable Tasks

> 这是一份面向外部贡献者的任务清单。每项任务都有明确目标、实现建议和验收标准，完成即可提 PR。
> A task board for outside contributors. Every task has a goal, implementation hints and acceptance criteria — complete one and open a PR.

[简体中文](#简体中文) | [English](#english) · [README](../README.md) · [开发文档](DEVELOPMENT.md) · [贡献指南](../CONTRIBUTING.md)

---

## 简体中文

### 如何使用这份路线图

1. **选任务**：从下面挑一个你感兴趣、并且有把握完成的任务。新手建议先看 [Good First Issues](#good-first-issues-新手任务)。
2. **认领**：在对应 Issue 下留言说明你要做（没有对应 Issue 就新建一个，标题用任务 ID，例如 `[R1] 图像几何对齐`），避免多人重复劳动。
3. **实现**：Fork 仓库 → 从 `main` 开分支（如 `feat/r1-geometric-alignment`）→ 按验收标准自测。
4. **提交**：PR 描述里写清「改了什么、怎么测的、在什么设备上测的」，链接对应 Issue。
5. **验收**：维护者按验收标准检查；通过后合并。

状态标记：`[ ]` 未开始 · `[~]` 进行中 · `[x]` 已完成

难度说明：🟢 新手友好 · 🟡 需要 Android 经验 · 🔴 需要算法/系统级经验

### 版本规划

| 里程碑 | 主题 | 包含任务 |
| --- | --- | --- |
| **v2.1** | 稳定与可用 | R5、R6、R7、R14、R15，以及所有 Good First Issues |
| **v3.0** | 画质飞跃 | R1、R2、R3、R8 |
| **v3.x** | 体验与进阶 | R4、R9、R10、R11–R13、R16–R20 |

---

### P0 · 画质核心

#### [ ] R1 图像几何对齐（平移 / 旋转配准）

- **现状**：`FocusStacker.consume()` 直接把每帧按同一像素坐标累加，默认所有照片完全重合。手持或支架轻微移动会产生重影，这是当前最大的画质瓶颈。实测还发现：即使手机完全静止，镜头在变焦（改变对焦距离）时**视场角也会变化**（呼吸效应）——同一场景在最远与最近对焦之间，画面中心位移约 0、边缘可达 35 像素（工作分辨率下约 1% 尺度变化）。这正是当前必须用"加权融合"而不能用"逐像素挑最清晰一张"的原因：硬挑会在高对比度边缘撕出锯齿状白斑。
- **目标**：融合前把第 2…N 帧配准到第 1 帧（含呼吸效应引起的尺度变化），显著降低重影与边缘撕裂，同时不损失静物场景的清晰度；配准做好后可以考虑重新评估 argmax 融合。
- **实现建议**：
  - 先做灰度金字塔上的相位相关（phase correlation）估计平移（整数 + 亚像素抛物线插值），复杂度低、对亮度变化鲁棒；
  - 尺度/呼吸效应估计：对画面左右（或四角）分别估计平移，用位移随位置的变化拟合尺度；也可对极坐标幅度谱做相关；
  - 旋转可以用小角度搜索（±2°、0.25° 步长）配合互信息/归一化互相关，或引入 OpenCV 的 `findTransformECC`（新增依赖请先开 Issue 讨论）；
  - 只对降采样工作图（约 3MP）做配准，映射回原图时注意边界裁剪；对齐失败（置信度低）时回退到不对齐；
  - 新增 `ImageAligner` 类，保持 `FocusStacker` API 不变。
- **涉及文件**：`app/src/main/java/com/example/focusstack/FocusStackProcessor.kt`、新增配准类、可能的 `app/build.gradle` 依赖。
- **验收标准**：
  - 手持连拍（无支架）7 张，合成结果无肉眼可见重影；
  - 有支架的静物样张清晰度不低于当前版本；
  - 中端机单帧额外对齐耗时 < 500ms；
  - PR 提供对齐前/后对比图与测试设备信息。
- **难度**：🔴

#### [ ] R2 全分辨率（12MP）分块合成

- **现状**：为控制内存，解码用 `inSampleSize = 2`，输出固定 2000×1500（约 3MP）；工作数组约占 96MB。
- **目标**：支持输出完整 4000×3000（12MP），且在 2GB RAM 设备上不 OOM。
- **实现建议**：
  - 按水平条带（tile）两遍处理：第一遍流式读取各帧、按 tile 计算并缓存清晰度图（可量化压缩存储）；第二遍逐 tile 加权融合并写回输出 `Bitmap`；
  - 或者把每帧的灰度/清晰度图写入临时文件 + 内存映射，只在融合时加载当前 tile；
  - 避免一次性分配 7 套全图 float 数组；每帧处理完立即释放；
  - 内存峰值目标 < 2×单 tile 大小 + 输出图。
- **涉及文件**：`FocusStackProcessor.kt`、`CameraController.kt`（拍摄尺寸选择）、保存逻辑（`MainActivity.kt`）。
- **验收标准**：
  - 相机模式输出 4000×3000 JPEG；
  - 2GB RAM 真机 7 张流程不 OOM、不闪退；
  - 总耗时不超过当前版本 2 倍；
  - 给出内存峰值与耗时数据。
- **难度**：🔴

---

### P1 · 机型兼容与稳定性

#### [ ] R3 按机型校准焦点包围策略

- **现状**：扫焦步长 = 锁定屈光度 / 4，前后各 3 步，标称 7 张（clamp 到有效对焦范围并去重后可能少于 7 张）；不同机型的对焦范围、步进精度差异很大。
- **目标**：根据 `LENS_INFO_FOCUS_DISTANCE_CALIBRATION`、`LENS_INFO_MINIMUM_FOCUS_DISTANCE`、传感器尺寸等参数自适应步数与范围，并允许用户配置张数（3–15）。
- **实现建议**：把扫焦序列生成抽成 `FocusBracketPlanner`；对焦范围不足时自动扩大/缩小；增加一次「预扫」检测可用焦距区间；设置页持久化用户偏好。
- **涉及文件**：`CameraController.kt`、`MainActivity.kt`、`res/layout`。
- **验收标准**：至少 3 个不同品牌机型上焦点覆盖完整（首末帧明显虚化）；配置项生效；文档更新。
- **难度**：🟡

#### [ ] R4 不支持手动对焦机型的降级方案

- **现状**：`minFocusDistance <= 0` 时直接提示「该机型不支持手动对焦距离控制」，功能不可用。
- **目标**：给出可用的降级路径，而不是死路。
- **实现建议**：尝试逐次点按不同区域 AF 后抓拍；或提供「手动对焦滑块」模式（部分 HAL 支持）；至少引导用户改用相册导入模式。
- **验收标准**：不支持机型能完成一次可用的合成，或得到明确指引与示例；相关机型写入文档。
- **难度**：🟡

#### [ ] R5 生命周期、取消与异常路径加固

- **现状**：已有 `onPause` 释放相机、取消、超时和 OOM 兜底，但缺少系统化验证；回调重入、Activity 销毁后合成线程回调等边界仍需覆盖。
- **目标**：拍摄/合成/保存任意阶段切后台、旋转（当前锁定竖屏）、取消、相机断连、内存不足都不崩溃、不泄漏。
- **实现建议**：梳理状态机并补测试；统一 `CancellationSignal`；确保 `stackExecutor`、`ioExecutor`、相机线程在 `onDestroy` 正确关闭；合成线程回调前检查 Activity 存活。
- **验收标准**：按测试矩阵逐项手动验证并记录；`adb logcat` 无异常；LeakCanary（可选）无泄漏。
- **难度**：🟡

#### [x] R6 关闭调试开关并保护隐私（✅ 已随 v2.0 完成）

- **已完成**：调试帧保存逻辑（`debugKeepFrames` / `saveDebugFrame` / `debugScores`）已整体移除，Release 与 Debug 均无任何调试写盘入口；如未来需要调试，建议按下方思路以 `BuildConfig.DEBUG` 重新引入。
- **现状**：`MainActivity.debugKeepFrames = true`，每次拍摄会把每张原始 JPEG 额外写入 `Pictures/FocusStack/_debug`，正式用户也会触发，属于隐私与存储风险。
- **目标**：Debug 构建才允许保存调试帧，Release 默认关闭且无入口。
- **实现建议**：改用 `BuildConfig.DEBUG`；调试帧建议写到应用私有目录并在退出时清理；增加开发者选项开关。
- **验收标准**：Release 包拍摄后相册中不出现 `_debug` 目录；Debug 包可用；文档同步。
- **难度**：🟢

---

### P2 · 产品体验与国际化

#### [ ] R7 文案国际化（strings.xml + 英文界面）

- **现状**：`strings.xml` 只有 `app_name`，所有文案硬编码在 Kotlin / layout 中，界面仅中文。
- **目标**：所有用户可见文案迁移到 `res/values/strings.xml`，新增 `res/values-en/strings.xml`，跟随系统语言切换。
- **实现建议**：可拆分成多个小 PR（每个界面一个）；帮助/错误提示较长，可用带占位符的 format string；同时把 AndroidManifest 的 `label` 改为资源引用。
- **验收标准**：代码中不再有硬编码中文 UI 文案（`grep` 可验证）；中英文界面都能完整走通拍摄/导入/保存；英文截图附在 PR。
- **难度**：🟢（可拆分）

#### [ ] R8 输出选项：分辨率、质量与 EXIF

- **现状**：固定约 3MP、JPEG 质量 95、不写入 EXIF。
- **目标**：设置页可选择输出尺寸（3MP / 6MP / 12MP，依赖 R2）、JPEG 质量；输出保留拍摄时间、机型、方向等基础 EXIF。
- **实现建议**：`ExifInterface` 写回；与 R2 协同；设置项用 `SharedPreferences`；默认值保持当前行为。
- **验收标准**：选项生效；第三方看图软件能读到正确方向与时间；文档更新。
- **难度**：🟡

#### [ ] R9 相册导入体验优化

- **现状**：多选后直接开始合成，无缩略图、无排序、无逐张取消。
- **目标**：导入前可预览缩略图、拖动排序（按焦点顺序）、显示逐张读取进度、支持取消。
- **实现建议**：`LazyColumn`/`RecyclerView` 展示缩略图；使用 `ItemTouchHelper` 排序；读取时用 `ImageDecoder` 缩略图避免 OOM。
- **验收标准**：20 张导入流畅不卡顿；排序结果影响合成权重顺序符合预期；取消后状态复位。
- **难度**：🟡

#### [ ] R10 拍摄交互增强

- **现状**：预览仅有底部按钮和点按对焦框。
- **目标**：增加构图网格、水平仪、拍摄进度指示、对焦点数量提示、结果前后对比、快速重拍。
- **验收标准**：交互可用且不遮挡主体；UI 截图；不影响现有流程。
- **难度**：🟡

---

### P3 · 工程质量

#### [ ] R11 合成内核单元测试（JVM）

- **现状**：无任何自动化测试。
- **目标**：对 `FocusStacker` 的清晰度计算、权重融合、亮度对齐、单帧退化和旋转输出做可重复的 JVM 测试。
- **实现建议**：先把纯计算逻辑从 `Bitmap` 依赖中解耦（输入 `IntArray`），用合成图（棋盘格/高斯模糊块）构造已知答案；`Bitmap` 相关用 Robolectric。
- **验收标准**：`./gradlew test` 通过；关键路径覆盖率 ≥ 60%；CI 中运行。
- **难度**：🟡

#### [ ] R12 仪器测试与手动回归清单

- **目标**：覆盖启动、权限流、相册导入（伪造 Intent）、保存到 MediaStore 的仪器测试；补充 [docs/DEVELOPMENT.md](DEVELOPMENT.md) 的手动回归清单。
- **验收标准**：模拟器上 `./gradlew connectedDebugAndroidTest` 通过；清单可执行。
- **难度**：🟡

#### [ ] R13 完善 CI

- **现状**：已有 GitHub Actions 构建 debug APK 并运行 lint。
- **目标**：加入单元测试、artifact 体积报告、PR 模板检查；后续可加 release 构建（不签名或使用 CI secrets）。
- **验收标准**：PR 上 CI 全绿；构建失败能定位到具体步骤。
- **难度**：🟢

#### [ ] R14 代码清理

- **目标**：重命名 `FocusStackProcessor.kt` → `FocusStacker.kt`；清理未使用 import；统一命名。（早期分位数方案的 `percentile()` / `HIST_*` 死代码已在融合算法重写时删除。）
- **验收标准**：编译与 lint 无新增警告；无行为变化。
- **难度**：🟢

#### [ ] R15 构建与发布工程化

- **现状**：`build_apk.sh` 硬编码本机 JDK/Gradle/SDK 路径；release 依赖本地 keystore。
- **目标**：脚本改用 Gradle Wrapper 与环境变量，可在任意机器构建；发布流程文档化（版本号、变更日志、签名）。
- **验收标准**：按文档在干净环境可构建 debug 包；release 签名仅本地/CI secrets；README 与文档同步。
- **难度**：🟢

---

### P4 · 进阶方向

| ID | 任务 | 说明 |
| --- | --- | --- |
| [ ] R16 | RAW / DNG 采集 | 用 Camera2 RAW 输出保留更多动态范围，需要自研去马赛克与色彩处理，难度高 |
| [ ] R17 | 拍摄速度优化 | burst 预拍缓冲、减少每档等待时间、并行预取 |
| [ ] R18 | 高级融合算法 | 焦点金字塔、深度图估计、去噪与 HDR 融合 |
| [ ] R19 | CameraX / 多摄支持 | 迁移或新增 CameraX 路径，支持长焦/超广角与变焦包围 |
| [ ] R20 | 分享与批量处理 | 分享到社交平台、批量队列、水印/边框 |

---

### Good First Issues 新手任务

| 任务 | 说明 | 难度 |
| --- | --- | --- |
| R14 文件重命名与死代码清理 | 纯重构，不影响行为 | 🟢 |
| 把某一界面的文案迁入 `strings.xml` | R7 的子任务，可只做一个界面 | 🟢 |
| 补充 README 的 FAQ / 故障排查 | 文档类，基于真实使用经验 | 🟢 |
| 更新帮助对话框文案与截图 | 已知旧文案与实际功能不符 | 🟢 |
| 添加机型兼容性反馈模板 | 方便收集 `LENS_FOCUS_DISTANCE` 支持情况 | 🟢 |
| 为 `FocusStacker` 写第一个 JVM 测试 | R11 的起步任务 | 🟡 |

### 非目标（Non-goals）

- 不做视频实时景深合成；
- 不做云端处理或账号体系；
- 不追求替代桌面级堆栈软件（Helicon Focus / Zerene Stacker）的全部功能；
- 不做 iOS 版本（欢迎独立 fork）。

### 完成定义（Definition of Done）

一个任务可以合并，需要满足：

- [ ] 代码可编译，`./gradlew assembleDebug` 通过；
- [ ] 没有提交任何密钥、构建产物或调试文件；
- [ ] 按任务验收标准自测过，PR 中写明设备与步骤；
- [ ] 涉及行为的改动同步更新 README / 开发文档 / 帮助文案；
- [ ] UI 改动附截图，算法改动附前后对比图；
- [ ] 代码风格与现有代码一致，注释解释「为什么」。

---

## English

### How to use this roadmap

1. **Pick a task** below that you are confident you can finish. Newcomers should start with [Good First Issues](#good-first-issues).
2. **Claim it** by commenting on the matching issue (or open one titled with the task ID, e.g. `[R1] Geometric alignment`) so nobody duplicates work.
3. **Implement it** on a branch created from `main` (e.g. `feat/r1-geometric-alignment`), and self-test against the acceptance criteria.
4. **Open a PR** describing what changed, how you tested it and on which device, linking the issue.
5. **Review**: a maintainer checks the acceptance criteria and merges.

Status: `[ ]` todo · `[~]` in progress · `[x]` done

Difficulty: 🟢 newcomer-friendly · 🟡 Android experience needed · 🔴 algorithm/systems experience needed

### Milestones

| Milestone | Theme | Tasks |
| --- | --- | --- |
| **v2.1** | Stability & usability | R5, R6, R7, R14, R15 + all Good First Issues |
| **v3.0** | Image-quality leap | R1, R2, R3, R8 |
| **v3.x** | Experience & advanced | R4, R9, R10, R11–R13, R16–R20 |

---

### P0 · Image quality

#### [ ] R1 Geometric alignment (translation / rotation registration)

- **Current state**: `FocusStacker.consume()` accumulates frames at identical pixel coordinates, assuming perfect overlap. Handheld shots and slight tripod movement cause ghosting — the biggest quality bottleneck today.
- **Goal**: Register frames 2…N to frame 1 before fusion, greatly reducing handheld ghosting without hurting tripod-shot sharpness.
- **Implementation hints**:
  - Start with phase correlation on a grayscale pyramid to estimate translation (integer + subpixel parabolic interpolation) — cheap and robust to brightness changes;
  - Estimate rotation with a small-angle search (±2°, 0.25° step) using mutual information / normalized cross-correlation, or use OpenCV's `findTransformECC` (discuss any new dependency in an issue first);
  - Align on the downsampled working image (~3 MP) and map back with border cropping; fall back to no alignment when confidence is low;
  - Add an `ImageAligner` class without changing the public `FocusStacker` API.
- **Files**: `app/src/main/java/com/example/focusstack/FocusStackProcessor.kt`, new aligner class, possibly `app/build.gradle` dependencies.
- **Acceptance criteria**:
  - Handheld 7-frame capture produces no visible ghosting;
  - Tripod-shot sharpness is not worse than the current version;
  - Extra alignment time < 500 ms per frame on a mid-range phone;
  - PR includes before/after comparisons and test device info.
- **Difficulty**: 🔴

#### [ ] R2 Full-resolution (12 MP) tiled fusion

- **Current state**: Decoding uses `inSampleSize = 2` and output is fixed at 2000×1500 (~3 MP) to keep memory under control; working buffers take ~96 MB.
- **Goal**: Support full 4000×3000 (12 MP) output without OOM on 2 GB devices.
- **Implementation hints**:
  - Two-pass horizontal tiles: first pass streams each frame and caches per-tile sharpness maps (quantized); second pass fuses tile by tile into the output `Bitmap`;
  - Alternatively write per-frame grayscale/sharpness maps to temp files and memory-map them, loading only the current tile;
  - Never allocate 7 full-resolution float buffers; release each frame's buffers immediately;
  - Peak memory target: < 2× one tile + output bitmap.
- **Files**: `FocusStackProcessor.kt`, `CameraController.kt` (capture size), save path in `MainActivity.kt`.
- **Acceptance criteria**:
  - Camera mode outputs 4000×3000 JPEG;
  - 7-frame flow completes without OOM on a 2 GB real device;
  - Total time ≤ 2× the current version;
  - Peak memory and timing numbers included in the PR.
- **Difficulty**: 🔴

---

### P1 · Device compatibility & stability

#### [ ] R3 Per-device focus-bracket calibration

- **Current state**: Sweep step = locked diopter / 4, three steps each side, nominally 7 frames (clamping to the valid focus range and deduplication may yield fewer). Focus range and step precision vary widely across devices.
- **Goal**: Adapt frame count and range to `LENS_INFO_FOCUS_DISTANCE_CALIBRATION`, `LENS_INFO_MINIMUM_FOCUS_DISTANCE`, sensor size, etc., and let users configure the frame count (3–15).
- **Hints**: Extract sweep planning into a `FocusBracketPlanner`; detect the usable focus range with a pre-sweep; persist preferences in a settings screen.
- **Files**: `CameraController.kt`, `MainActivity.kt`, `res/layout`.
- **Acceptance criteria**: Complete focus coverage on at least three brands (first/last frames clearly blurred); setting takes effect; docs updated.
- **Difficulty**: 🟡

#### [ ] R4 Fallback for devices without manual focus

- **Current state**: When `minFocusDistance <= 0`, the app reports that manual focus distance is unsupported and the feature is unusable.
- **Goal**: Provide a usable fallback instead of a dead end.
- **Hints**: Try repeated AF at different regions; offer a manual-focus slider for HALs that support it; at minimum guide users to the gallery-import mode.
- **Acceptance criteria**: A usable stack or clear, actionable guidance on unsupported devices; affected models documented.
- **Difficulty**: 🟡

#### [ ] R5 Harden lifecycle, cancellation and error paths

- **Current state**: `onPause` releases the camera, with cancellation, timeout and OOM handling, but there is no systematic verification; callback re-entrancy and late callbacks after Activity destruction still need coverage.
- **Goal**: No crashes or leaks when backgrounding, cancelling, disconnecting the camera or running out of memory at any stage (portrait is locked today).
- **Hints**: Document the state machine and test it; unify cancellation; shut down `stackExecutor`, `ioExecutor` and the camera thread in `onDestroy`; check Activity liveness before posting results.
- **Acceptance criteria**: Manual test matrix recorded; clean `adb logcat`; no LeakCanary leaks (optional).
- **Difficulty**: 🟡

#### [x] R6 Disable the debug switch and protect privacy (✅ done in v2.0)

- **Done**: The debug-frame saving logic (`debugKeepFrames` / `saveDebugFrame` / `debugScores`) has been removed entirely; neither release nor debug builds have any debug write path. If debugging is needed again in the future, re-introduce it behind `BuildConfig.DEBUG` as described below.
- **Current state**: `MainActivity.debugKeepFrames = true` saves every source JPEG to `Pictures/FocusStack/_debug` — including in release builds. Privacy and storage risk.
- **Goal**: Only debug builds can keep frames; release builds have it off with no UI entry.
- **Hints**: Use `BuildConfig.DEBUG`; write debug frames to app-private storage and clean up; add a developer-options toggle.
- **Acceptance criteria**: No `_debug` folder appears after release-build captures; debug builds still work; docs updated.
- **Difficulty**: 🟢

---

### P2 · UX & internationalization

#### [ ] R7 String extraction and English UI

- **Current state**: `strings.xml` only contains `app_name`; all other text is hard-coded in Kotlin/layout, UI is Chinese-only.
- **Goal**: Move all user-visible strings to `res/values/strings.xml`, add `res/values-en/strings.xml`, and follow the system language.
- **Hints**: Split across several PRs (one screen per PR); long help/error text can use format strings with placeholders; also reference the app label from resources in `AndroidManifest.xml`.
- **Acceptance criteria**: No hard-coded Chinese UI text left (`grep`-verifiable); both languages complete capture/import/save flows; English screenshots in the PR.
- **Difficulty**: 🟢 (splittable)

#### [ ] R8 Output options: resolution, quality and EXIF

- **Current state**: Fixed ~3 MP, JPEG quality 95, no EXIF written.
- **Goal**: Settings for output size (3/6/12 MP, depends on R2) and JPEG quality; preserve basic EXIF (capture time, model, orientation).
- **Hints**: Write with `ExifInterface`; coordinate with R2; persist settings via `SharedPreferences`; keep current defaults.
- **Acceptance criteria**: Options take effect; third-party viewers show correct orientation/time; docs updated.
- **Difficulty**: 🟡

#### [ ] R9 Better gallery-import experience

- **Current state**: Multi-select immediately starts fusion — no thumbnails, reordering or per-image cancellation.
- **Goal**: Preview thumbnails, drag to reorder (focus order), per-image progress, cancel support.
- **Hints**: `RecyclerView` with thumbnails; `ItemTouchHelper` for ordering; decode thumbnails to avoid OOM.
- **Acceptance criteria**: Smooth import of 20 photos; ordering affects fusion as expected; state resets after cancel.
- **Difficulty**: 🟡

#### [ ] R10 Capture UI enhancements

- **Current state**: Only bottom buttons and a tap-focus ring.
- **Goal**: Composition grid, level indicator, capture progress, focus-point count hint, before/after comparison, quick retake.
- **Acceptance criteria**: Usable UI that does not block the subject; screenshots; existing flows unaffected.
- **Difficulty**: 🟡

---

### P3 · Engineering quality

#### [ ] R11 JVM unit tests for the fusion kernel

- **Current state**: No automated tests.
- **Goal**: Repeatable JVM tests for sharpness calculation, weighted fusion, brightness alignment, single-frame degradation and rotated output.
- **Hints**: Decouple pure computation from `Bitmap` (take `IntArray`), build synthetic images (checkerboards / Gaussian-blurred patches) with known expected answers; use Robolectric for `Bitmap`.
- **Acceptance criteria**: `./gradlew test` green; ≥ 60% coverage of key paths; runs in CI.
- **Difficulty**: 🟡

#### [ ] R12 Instrumentation tests and manual regression checklist

- **Goal**: Instrumentation tests for launch, permission flow, gallery import (fake Intent) and MediaStore saving; add a manual regression checklist to [docs/DEVELOPMENT.md](DEVELOPMENT.md).
- **Acceptance criteria**: `./gradlew connectedDebugAndroidTest` green on an emulator; checklist is actionable.
- **Difficulty**: 🟡

#### [ ] R13 Improve CI

- **Current state**: GitHub Actions builds a debug APK and runs lint.
- **Goal**: Add unit tests, artifact-size reporting and PR hygiene checks; optionally release builds with CI secrets.
- **Acceptance criteria**: CI green on PRs; failures point at the exact step.
- **Difficulty**: 🟢

#### [ ] R14 Code cleanup

- **Goal**: Rename `FocusStackProcessor.kt` → `FocusStacker.kt`; remove unused imports; unify naming. (The `percentile()` / `HIST_*` dead code from the early percentile approach was already deleted when the fusion algorithm was rewritten.)
- **Acceptance criteria**: No new compiler/lint warnings; no behaviour change.
- **Difficulty**: 🟢

#### [ ] R15 Build and release engineering

- **Current state**: `build_apk.sh` hard-codes local JDK/Gradle/SDK paths; releases depend on a local keystore.
- **Goal**: Use the Gradle Wrapper and environment variables so the script works on any machine; document the release process (versioning, changelog, signing).
- **Acceptance criteria**: Debug build succeeds on a clean machine following the docs; release signing only via local/CI secrets; README and docs in sync.
- **Difficulty**: 🟢

---

### P4 · Advanced directions

| ID | Task | Notes |
| --- | --- | --- |
| [ ] R16 | RAW / DNG capture | Camera2 RAW output for more dynamic range; requires demosaicing and color work — high effort |
| [ ] R17 | Faster capture | Burst pre-buffering, shorter per-step waits, parallel prefetch |
| [ ] R18 | Advanced fusion | Focus pyramids, depth-map estimation, denoising and HDR fusion |
| [ ] R19 | CameraX / multi-camera | Migrate or add a CameraX path; support telephoto/ultrawide and zoom bracketing |
| [ ] R20 | Sharing & batch | Share to social apps, batch queue, watermark/frame |

---

### Good First Issues

| Task | Notes | Difficulty |
| --- | --- | --- |
| R14 rename file & remove dead code | Pure refactor, no behavior change | 🟢 |
| Move one screen's strings into `strings.xml` | Sub-task of R7; one screen per PR | 🟢 |
| Expand README FAQ / troubleshooting | Documentation, based on real usage | 🟢 |
| Update help-dialog text and screenshots | Existing help text is stale relative to features | 🟢 |
| Add a device-compatibility feedback template | Collect `LENS_FOCUS_DISTANCE` support data | 🟢 |
| Write the first JVM test for `FocusStacker` | Bootstraps R11 | 🟡 |

### Non-goals

- Real-time video depth-of-field stacking;
- Cloud processing or user accounts;
- Replacing full desktop stackers (Helicon Focus / Zerene Stacker) feature-for-feature;
- An iOS version (forks welcome).

### Definition of Done

- [ ] Code compiles and `./gradlew assembleDebug` passes;
- [ ] No secrets, build artifacts or debug files committed;
- [ ] Self-tested against the acceptance criteria; device and steps stated in the PR;
- [ ] Behavior changes update README / development docs / help text;
- [ ] UI changes include screenshots; algorithm changes include before/after images;
- [ ] Style matches the existing code; comments explain *why*.

---

<p align="center"><sub>Pick one, claim it, ship it. · 挑一个，认领它，把它做完。</sub></p>
