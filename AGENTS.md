# TCFY 项目开发与 AI 长期协作规范

> 本文档是 `PHY4L/TCFY` 仓库的**最高优先级工程规范**，面向长期、跨会话、不定时的维护与迭代。
> 所有 AI 编程助手（Antigravity / Claude Code / 其他代理）与开发者在修改代码前**必须先阅读本文档**，并按需查阅：
> - 架构与数据流详述：[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)
> - 架构决策与历史踩坑记录：[docs/DECISIONS.md](docs/DECISIONS.md)
> - 版本与跨会话变更记录：[CHANGELOG.md](CHANGELOG.md)

---

## 1. 项目定位与目标运行环境

- **产品定位**：专为**高校教室 3–10 米远场听课场景**打造的离线优先 Android 原生应用，覆盖远场音频增强、中英混杂离线转写、专业术语三层纠错、断网保底翻译、课后 AI 精修总结与多格式导出。
- **基准适配设备**：**Redmi Note 11 5G（天玑 810 / 8GB RAM / MIUI-HyperOS / 无 GMS）**，同时兼容主流 ARM64 安卓手机。
- **系统与构建基线**：
  - `minSdk = 26`（Android 8.0+），`targetSdk = 35`，`compileSdk = 35`
  - `abiFilters = ["arm64-v8a"]`
  - Kotlin `2.0.21` + AGP `8.7.2` + KSP + Jetpack Compose Material 3 + Java 17
  - 离线推理引擎：`sherpa-onnx` `2.0.3`（SenseVoice-Small int8 + Silero VAD + GTCRN）
- **交互语言**：UI 文案、通知、错误提示统一使用**简体中文**；报错必须可读、可操作，禁止直接向用户暴露原始异常堆栈。

---

## 2. 不可妥协的红线（Hard Invariants）

任何改动都**不得违反**以下原则：

1. **离线优先与零丢录音**
   - 录音采集、远场 DSP、RAW PCM 落盘（`CrashSafeWavWriter`）、Silero VAD、SenseVoice ASR、离线术语纠错与保底注译**严禁依赖网络**。
   - 在线实时翻译必须有严格超时（默认 ≤ 4 秒）并在失败/断网时无感降级为离线术语注译，绝不阻塞转写主链路。
   - 必须先将未处理的原始 16kHz 单声道 PCM16 持续写入 WAV 文件，再执行 DSP/ASR；保持 `CrashSafeWavWriter.repairHeaderIfNeeded` 的向后兼容。

2. **覆盖安装签名与包名兼容**
   - CI 发布的 `TCFY-v*-arm64.apk` 使用仓库内固定证书 `app/debug.keystore` 与 `.debug` 包名后缀（`com.phy4l.tcfy.debug`），以保证老用户覆盖升级不丢失本地录音与 230MB 模型。
   - **严禁**删除、替换、重新生成 `app/debug.keystore`，或未经明确批准修改 `applicationId`、`applicationIdSuffix` 和签名配置。

3. **用户数据零丢失（Room 与 DataStore）**
   - **Room Schema 演化红线**：当前 `TcfyDatabase` 处于 `version = 1` 且带有 `.fallbackToDestructiveMigration()`。未来**首次及以后任何**修改实体字段/表结构的操作，必须：
     1. 递增 `TcfyDatabase.version`；
     2. 编写显式 `Migration(old, new)`；
     3. 移除 `fallbackToDestructiveMigration()`（避免清空老用户课堂记录与自定义术语库）；
     4. 补充迁移验证。
   - **DataStore & 枚举兼容**：严禁直接重命名或删除 `AppSettingsRepository.Keys` 键名，或已持久化到数据库/DataStore 的枚举常量（`AcousticPreset`、`TranslationDirection`、`SegmentSyncStatus`、`AsrLanguageMode`、`DenoiseLevel`、`AudioSourceMode`）；反序列化必须保留 `runCatching { valueOf(...) }.getOrDefault(...)` 兜底。
   - **内置术语库幂等性**：`TcfyDatabase.seedStarterGlossariesIfEmpty` 必须对老用户幂等，不得覆盖用户已编辑的词条或开关状态。

4. **无 GMS 与国内网络友好**
   - 严禁引入 Google Play Services、Firebase、ML Kit 等依赖 GMS 的库。
   - 模型下载必须同时维护直链、国内镜像（`hf-mirror.com` / `gh-proxy.com`）以及本地压缩包离线导入（`.onnx` / `.zip` / `.tar.bz2`）。

5. **安全与隐私底线**
   - 保持 `AndroidManifest.xml` 中 `android:allowBackup="false"`，防止用户 LLM API Key 与课堂本地数据被系统静默云备份。
   - 禁止在日志、Commit、测试夹具或文档中提交真实 API Key 或个人隐私数据。

6. **历史高危 Bug 防回归红线**（详见 [docs/DECISIONS.md](docs/DECISIONS.md)）
   - 停止录音不得卡死 UI（`isStoppingFlag` 先于 `lifecycleMutex` 置位；`captureJob` 的 `finally` 必须在 `withContext(NonCancellable)` 内完成清理与落库）。
   - 录音时长不得为 `00:00`（停止时持久化时长，启动时通过 `repairInterruptedRecordingsAsync` 从 WAV 字节数回填兜底）。
   - 录音期间在主 Tab 按系统返回手势必须退入后台保活（`moveTaskToBack(true)`），不得销毁 Activity 中断录音。
   - 正在录制中的 Session 禁止被删除；开始录音按钮须防连点且忽略并发重复 `ACTION_START`。
   - `ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE` 仅在 Android 11（`Build.VERSION_CODES.R` / API 30）及以上传入。
   - 一次性 Snackbar/Toast 事件须使用 `Channel`（非 `StateFlow`），避免重复内容被 conflate 吞掉；Tab 与详情页 ID 使用 `rememberSaveable`。

---

## 3. 架构与编码规范

详细模块图与并发设计见 [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)，日常编码遵循以下约定：

- **纯 JVM 可测边界**：`audio/`（除 `ClassroomAudioCapturer`）、`glossary/`、`ai/`、`data/export/` 保持纯 Kotlin/JVM，不直接耦合 `android.*` 框架类，确保无模拟器即可秒级运行单测。
- **轻量依赖管理**：
  - 继续采用 `TcfyApplication` 手动轻量装配，不擅自引入 Hilt/Dagger/Koin。
  - 所有库与插件版本统一声明在 `gradle/libs.versions.toml`，禁止在 `app/build.gradle.kts` 中硬编码版本号。
- **代码风格**：遵循 `kotlin.code.style=official`；保持现有注释与文档完整性，不顺手格式化无关文件。
- **协程与线程安全**：
  - 严禁在 `Dispatchers.Main` 执行音频采集、DSP、文件 IO、Room 读写、模型加载/推理或网络请求。
  - 捕获异常时注意不要用宽泛 `runCatching` 吞掉结构化并发的 `CancellationException`（除非在明确的资源清理 `finally` 中）。
- **JNI 原生资源与混淆**：
  - `SherpaSenseVoiceEngine`、`SherpaSileroVadEngine`、`SherpaGtcrnEnhancer` 必须在停止或初始化失败/取消路径中显式 `close()`，防止 8GB 手机 OOM。
  - 新增任何 `@Serializable` 模型或 JNI 封装类时，同步核对 `app/proguard-rules.pro`。
- **术语库同步**：更新 `BuiltInGlossaries.kt` 中的内置术语表时，如对应存在 `glossaries/*.csv` 分享文件，需保持内容同步。

---

## 4. 构建、测试与验收门禁

每次代码改动交付前，**必须依次执行并通过**：

```bash
# 1. 确保 sherpa-onnx AAR 就位且 SHA-256 校验通过（首次或缺失时运行）
./scripts/fetch_deps.sh

# 2. 运行全部 JVM 单元测试与端到端场景测试（必须 100% 通过）
./gradlew testDebugUnitTest

# 3. 编译并打包 ARM64 Debug APK（确认资源、KSP、Compose、R8/依赖无错误）
./gradlew assembleDebug
```

**测试补充原则**：
- **修 Bug**：必须补充能复现并锁死该 Bug 的回归单测。
- **新功能**：涉及 `audio/`、`glossary/`、`ai/`、`data/export/` 的逻辑必须补齐纯 JVM 单元测试。
- **主流程改动**：涉及录音→分句→纠错→翻译→精修/导出的端到端行为变化，须在 `app/src/test/java/com/phy4l/tcfy/scenarios/SmartphoneScenarioE2ETest.kt` 补充或更新场景用例。
- **真机项明示**：涉及麦克风硬件音源、MIUI 后台保活/WakeLock、真实模型下载速度或手机端推理耗时的改动，无法在云端完全模拟，须在交付汇报中明确列出「建议真机验证项」。

---

## 5. Git 工作流、版本与发布规范

- **分支策略（按改动规模区分）**：
  - **小改动**（单点 Bug 修复、文案/配置微调、文档更新、单一术语库补充）：可在 `main` 分支直接提交。
  - **大改动**（新功能模块、跨多文件重构、数据库迁移、核心管线或 UI 结构调整）：**必须新建特性分支**（如 `feat/<topic>`、`refactor/<topic>`），验证通过后通过 PR 合并到 `main`。
  - 严禁未经用户同意执行 `git push --force`、重写远端历史或删除远端分支/Tag。
- **提交信息（Conventional Commits）**：
  - 使用 `feat:` / `fix:` / `refactor:` / `perf:` / `test:` / `docs:` / `ci:` / `chore:` 前缀，简明准确说明变更意图。
- **版本与 Release 触发规则**：
  - **仅在用户明确指示“发版 / 发布新版本”时**才执行发版流程；日常开发提交**不**自动改版本号或打 Tag。
  - 标准发版清单：
    1. 递增 `app/build.gradle.kts` 中的 `versionCode`（+1）和 `versionName`（`X.Y.Z`）；
    2. 将 `CHANGELOG.md` 中 `[Unreleased]` 归档为 `[vX.Y.Z] - YYYY-MM-DD`；
    3. 更新 `README.md` 中的直链版本链接；
    4. 通过 `./gradlew testDebugUnitTest` 与 `./gradlew assembleDebug`；
    5. 提交并打 Tag `vX.Y.Z` 推送，由 `.github/workflows/android-build.yml` 自动构建并发布 `TCFY-vX.Y.Z-arm64.apk` 至 GitHub Releases。

---

## 6. AI 长期协作协议（Agent Working Protocol）

1. **会话启动自检**：
   - 动手修改代码前，先阅读 `AGENTS.md`、查看 `CHANGELOG.md` 的 `[Unreleased]` 及最新条目、执行 `git log -n 10 --oneline` 了解近期上下文。
2. **改动前必须先征求用户确认的触发条件**：
   - 修改 Room 数据库 Schema 或迁移逻辑；
   - 新增、删除或升级外部依赖、AGP、Kotlin 或 `sherpa-onnx` 版本；
   - 修改 `AndroidManifest.xml` 权限声明、备份策略或组件属性；
   - 修改签名配置、包名、CI/Release 流水线或模型下载源；
   - 跨模块大重构或改变主导航/核心交互方式。
3. **范围克制**：严格围绕用户当前需求实施改动，不夹带未要求的重构或依赖升级。
4. **知识沉淀**：
   - 有实质性功能/修复/行为变更时，在 `CHANGELOG.md` 的 `[Unreleased]` 中追加一行简洁记录；
   - 做出重要架构权衡或发现新的底层坑点时，在 `docs/DECISIONS.md` 追加一条 ADR；
   - 调整模块职责或管线结构时，同步更新 `docs/ARCHITECTURE.md`。
5. **交付汇报格式**：任务完成后简明汇报：① 改动摘要与涉及文件；② 实际执行的验证命令与结果；③ 需在真机确认的事项（如有）。
