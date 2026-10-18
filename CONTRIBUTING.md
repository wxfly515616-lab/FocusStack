# 贡献指南 · Contributing Guide

**[简体中文](#简体中文) | [English](#english)**

---

## 简体中文

感谢你关注并参与 FocusStack（景深合成相机）！本文档说明如何报告问题、提交代码以及必须遵守的约定。

### 行为准则

参与本项目即表示你同意遵守 [Contributor Covenant](https://www.contributor-covenant.org/zh-cn/version/2/1/code_of_conduct/) 的核心要求（摘要）：

- 尊重每一位参与者，使用友善、包容的语言，不进行人身攻击或歧视性言论。
- 接受不同观点与建设性批评，聚焦于问题本身与代码质量。
- 不发布他人隐私信息（真实姓名、住址、联系方式等）。
- 维护者有权删除不当内容，并对违规行为作出处理。

### 如何报告 Bug / 提出需求

- **先搜索**：提交前请先搜索[已有 Issue](../../issues)，避免重复。
- **Bug**：使用 [Bug 报告模板](.github/ISSUE_TEMPLATE/bug_report.yml)，尽量附上复现步骤、机型、Android 版本、App 版本、设备是否支持手动对焦、相关 logcat（tag：`MainActivity` / `CameraController` / `FocusStacker`）以及截图。
- **需求**：使用 [功能需求模板](.github/ISSUE_TEMPLATE/feature_request.yml)，说明使用场景与建议方案，可选给出实现思路。
- 安全或隐私问题请勿公开提交 Issue，请直接私下联系维护者（Wxf8555）。

### 开发环境

| 项目 | 要求 |
| --- | --- |
| JDK | 17（推荐 Temurin 17） |
| Android SDK | compileSdk / targetSdk 34，minSdk 26 |
| Gradle | 使用仓库自带的 Gradle Wrapper（8.4），不要自行升级 |
| AGP | 8.2.2（见根目录 `build.gradle`） |
| 构建方式 | Android Studio（推荐）或命令行 `./gradlew` |

命令行构建（macOS / Linux）：

```bash
export JAVA_HOME=/path/to/jdk-17
export ANDROID_HOME=$HOME/Library/Android/sdk   # Linux 下为 Android SDK 路径
./gradlew assembleDebug
```

也会生成 `app/build/outputs/apk/debug/app-debug.apk`。更多环境搭建、架构与调试说明见 [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md)。

### 分支与提交规范

- 从 `main`（或 `master`）切出功能分支，例如 `feat/manual-focus-lock`、`fix/save-crash`。
- 提交信息沿用仓库现有的 **Conventional Commits** 风格，描述可以使用中文：
  - `feat: 新增 XXX 功能`
  - `fix: 修复 XXX 问题`
  - `docs: 更新 XXX 文档`
  - `refactor: 重构 XXX`
  - `test: 补充 XXX 测试`
- 一次提交只做一件事，避免把格式化、重构和功能修改混在同一个 commit 中。
- 不要提交 `local.properties`、签名文件、`build/` 目录或调试帧。

### 代码风格

- Kotlin 遵循 [Kotlin 官方代码风格](https://kotlinlang.org/docs/coding-conventions.html)（`gradle.properties` 中已设置 `kotlin.code.style=official`）。
- Kotlin / Java 使用 **4 空格**缩进，不用 Tab。
- Android XML 资源使用 **4 空格**缩进；YAML 使用 2 空格。
- 文本文件使用 UTF-8、LF 换行，文件末尾保留一个换行（详见 `.editorconfig` / `.gitattributes`）。
- 保持现有架构：Camera2 采集 + 纯 Kotlin 图像合成；默认**不引入第三方图像处理库**。确有必要时（例如 R1 几何对齐考虑 OpenCV），请先在 Issue 中说明性能、包体积与许可证影响，维护者同意后再添加依赖。
- 不要在源码中写死调试开关；提交前确认没有临时日志或调试帧逻辑。

### 提交 PR 前必须做的检查

1. **本机构建通过**：`./gradlew assembleDebug` 无错误。
2. **手动回归三条核心路径**：
   - 拍摄合成（点「拍摄合成」，跟随自动对焦流程完成 7 张采集并合成）；
   - 相册导入（点「相册合成」，选择 2～20 张不同焦点的照片并合成）；
   - 保存到相册（保存到 `Pictures/FocusStack`，确认文件可正常打开）。
3. **UI 改动附截图**：新增或修改界面时，在 PR 描述中附上修改前后截图（可参考 `docs/images/` 下的示例图）。
4. 如涉及相机、存储权限或图像数据，请在 PR 中明确说明影响范围。

### PR 说明要求

PR 描述请使用仓库的 [PR 模板](.github/pull_request_template.md)，至少包含：

- 关联的 Issue（如 `Closes #12`）；
- 改动内容与设计取舍；
- 测试方式与测试设备（机型 / Android 版本）；
- 是否影响权限、隐私或用户数据；
- UI 改动截图；
- 自测清单的勾选结果。

### 签名与密钥安全警告

- `keystore.properties`、`*.jks`、`*.keystore`、`local.properties` **绝对不要提交**，它们包含私钥、密码或本机 SDK 路径。
- 若你误提交了密钥，请立即在 PR 中说明，并轮换密钥；仅在删除文件是不够的，历史记录里仍然存在。
- 不要在任何 Issue、PR、日志或截图中暴露签名密码、密钥库内容或个人信息。
- 本项目 CI 只构建未签名的 debug 包，**不做 release 签名**；release 打包在本地进行。

### 不要提交的内容

- `build/`、`app/build/` 等构建产物（含 `*.apk`、`*.aab`）；
- `.gradle/`、`.kotlin/`、`*.hprof` 等缓存与性能文件；
- `captures/` 目录下的调试帧；
- IDE 配置（`.idea/`、`*.iml`）与系统文件（`.DS_Store`）。

### 领取路线图任务

欢迎认领[路线图](docs/ROADMAP.md)中的任务：在对应 Issue 下留言说明意向，由维护者分配后开始；较大的改动请先开 Issue 讨论方案再动手。

---

## English

Thanks for your interest in FocusStack (depth-of-field stacking camera)! This guide explains how to report issues, submit code, and which conventions to follow.

### Code of Conduct

By participating, you agree to follow the core requirements of the [Contributor Covenant](https://www.contributor-covenant.org/version/2/1/code_of_conduct/) (summary):

- Be respectful and inclusive; no personal attacks or discriminatory language.
- Welcome different viewpoints and constructive criticism; focus on the problem and the code.
- Do not publish other people's private information (real name, address, contact details, etc.).
- Maintainers may remove inappropriate content and act on violations.

### Reporting Bugs / Requesting Features

- **Search first**: check [existing issues](../../issues) before filing a new one.
- **Bugs**: use the [bug report template](.github/ISSUE_TEMPLATE/bug_report.yml) and include reproduction steps, device model, Android version, app version, whether the device supports manual focus, relevant logcat (tags: `MainActivity` / `CameraController` / `FocusStacker`), and screenshots.
- **Features**: use the [feature request template](.github/ISSUE_TEMPLATE/feature_request.yml) and describe the use case, your proposed solution, and optionally implementation ideas.
- For security or privacy issues, do **not** open a public issue; contact the maintainer (Wxf8555) privately.

### Development Environment

| Item | Requirement |
| --- | --- |
| JDK | 17 (Temurin 17 recommended) |
| Android SDK | compileSdk / targetSdk 34, minSdk 26 |
| Gradle | Use the bundled Gradle Wrapper (8.4); do not upgrade it separately |
| AGP | 8.2.2 (see root `build.gradle`) |
| Build | Android Studio (recommended) or command line `./gradlew` |

Command-line build (macOS / Linux):

```bash
export JAVA_HOME=/path/to/jdk-17
export ANDROID_HOME=$HOME/Library/Android/sdk   # use your SDK path on Linux
./gradlew assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`. See [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md) for environment setup, architecture, and debugging details.

### Branches and Commit Conventions

- Branch off `main` (or `master`), e.g. `feat/manual-focus-lock`, `fix/save-crash`.
- Follow the repository's existing **Conventional Commits** style; Chinese descriptions are welcome:
  - `feat: add XXX`
  - `fix: fix XXX`
  - `docs: update XXX docs`
  - `refactor: refactor XXX`
  - `test: add tests for XXX`
- One logical change per commit; do not mix formatting, refactoring, and features.
- Never commit `local.properties`, signing files, the `build/` directory, or debug frames.

### Code Style

- Kotlin follows the [official Kotlin coding conventions](https://kotlinlang.org/docs/coding-conventions.html) (`kotlin.code.style=official` is set in `gradle.properties`).
- Kotlin / Java use **4-space** indentation, no tabs.
- Android XML uses **4-space** indentation; YAML uses 2 spaces.
- Text files are UTF-8 with LF line endings and a trailing newline (see `.editorconfig` / `.gitattributes`).
- Keep the existing architecture: Camera2 capture plus pure Kotlin image stacking; **avoid third-party image processing libraries by default**. If one is truly needed (e.g. OpenCV for the R1 alignment task), open an issue first covering performance, APK size and licensing, and wait for maintainer approval.
- Do not hard-code debug switches in source; make sure no temporary logs or debug-frame code remain.

### Required Checks Before Opening a PR

1. **Build locally**: `./gradlew assembleDebug` succeeds.
2. **Manually regress the three core flows**:
   - Capture & stack (tap "拍摄合成" and complete the 7-shot auto-focus sequence);
   - Gallery import (tap "相册合成" and stack 2–20 photos with different focus points);
   - Save to gallery (save into `Pictures/FocusStack` and verify the file opens).
3. **Attach screenshots for UI changes**: include before/after screenshots in the PR description (see the examples in `docs/images/`).
4. If the change affects camera/storage permissions or image data, state the impact explicitly in the PR.

### PR Description Requirements

Use the repository's [PR template](.github/pull_request_template.md). At minimum include:

- Linked issue (e.g. `Closes #12`);
- What changed and the design trade-offs;
- How it was tested and on which device (model / Android version);
- Whether permissions, privacy, or user data are affected;
- Screenshots for UI changes;
- The completed self-test checklist.

### Signing and Secret Safety Warning

- Never commit `keystore.properties`, `*.jks`, `*.keystore`, or `local.properties` — they contain private keys, passwords, or machine-specific SDK paths.
- If you accidentally commit a key, disclose it in the PR immediately and rotate the key; deleting the file is not enough because it remains in history.
- Never expose signing passwords, keystore contents, or personal data in issues, PRs, logs, or screenshots.
- CI only builds an unsigned **debug** APK and **never performs release signing**; release builds are done locally.

### Do Not Commit

- Build outputs such as `build/`, `app/build/` (including `*.apk`, `*.aab`);
- Caches and profiling files such as `.gradle/`, `.kotlin/`, `*.hprof`;
- Debug frames under `captures/`;
- IDE configuration (`.idea/`, `*.iml`) and OS files (`.DS_Store`).

### Claiming Roadmap Tasks

You are welcome to pick up tasks from the [roadmap](docs/ROADMAP.md): comment on the corresponding issue to express interest, wait for a maintainer to assign it, and discuss larger changes in an issue before starting.
