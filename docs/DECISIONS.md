# TCFY 架构决策记录（ADR）与历史踩坑档案

本文档记录 `PHY4L/TCFY` 演进过程中的关键架构权衡与已修复的高危问题，防止跨会话长期迭代中重复踩坑或无意破坏历史约束。

> **新增记录模板**：
> ```markdown
> ## ADR-NNN：决策标题（YYYY-MM-DD）
> - **状态**：生效中 / 已取代
> - **背景**：遇到了什么问题或约束
> - **决策**：最终采取的方案
> - **约束与影响**：后续修改必须遵守什么，禁止做什么
> ```

---

## ADR-001：固定仓库内 `app/debug.keystore` 发布 ARM64 APK（2026-11-25）

- **状态**：生效中
- **背景**：项目面向同学自用与 GitHub Releases 直接分发，CI 每次全新 Runner 上生成的默认 debug keystore 签名不同，会导致手机无法覆盖安装（必须卸载重装，进而清空本地录音与 230MB 离线模型）。
- **决策**：从 `v1.0.2` 起，将固定签名文件 `app/debug.keystore` 纳入 Git 版本控制（`.gitignore` 中显式 `!app/debug.keystore`），CI 发布产物沿用 `assembleDebug`（`com.phy4l.tcfy.debug`）并重命名为 `TCFY-${TAG}-arm64.apk`。
- **约束与影响**：**严禁**删除、重新生成 `app/debug.keystore`，或修改 `applicationId` / `debug.applicationIdSuffix`。若未来切换到正式 Release 签名，必须先与用户明确升级迁移方案。

---

## ADR-002：离线优先与无 GMS 架构，仅构建 `arm64-v8a`（2026-11-25）

- **状态**：生效中
- **背景**：目标设备为国内安卓机（基准机型 Redmi Note 11 5G / 天玑 810 / 8GB / MIUI-HyperOS / 无 GMS），教学楼常见弱网或无网环境，且同学间常通过微信/QQ 互传离线模型。
- **决策**：
  - 语音全链路采用 `sherpa-onnx 2.0.3`（SenseVoice-Small int8 + Silero VAD + GTCRN），不依赖任何 GMS / Firebase / ML Kit；
  - `abiFilters` 仅保留 `arm64-v8a`，兼顾 NEON 性能与包体大小；
  - 模型管理内置国内镜像（`hf-mirror.com` / `gh-proxy.com`）与本地 `.onnx` / `.zip` / `.tar.bz2` 导入；
  - `sherpa-onnx-2.0.3.aar` 不入库，由 `scripts/fetch_deps.sh` 按固定 SHA-256 拉取校验。
- **约束与影响**：禁止引入 GMS 依赖；修改 `ModelCatalog` 下载地址时必须同时提供国内镜像；升级 `sherpa-onnx` 版本时需同步更新 `scripts/fetch_deps.sh` 的版本号与 SHA-256 及 `app/build.gradle.kts`。

---

## ADR-003：远场音频先原始落盘，再做软件 DSP + GTCRN 干湿混合（2026-11-25）

- **状态**：生效中
- **背景**：手机厂商默认近场降噪（如 `VOICE_RECOGNITION`）常把 3–10 米外讲师声音当背景噪声门限切掉；且过度神经降噪会抹掉英文高频辅音（`/s/`, `/t/`, `/f/`, `/k/`）。
- **决策**：
  - `ClassroomAudioCapturer` 优先请求 `UNPROCESSED` → `CAMCORDER`（红米顶部远场麦）→ `MIC`，环形缓冲 ≥ 400ms；
  - `CrashSafeWavWriter` 在 DSP/ASR 之前先将原始 16kHz PCM16 持续写入磁盘并每 5 秒 `fsync` 刷新 WAV 头；
  - `FarFieldDspPipeline` 执行「去直流 → 85Hz 二阶 Butterworth HPF → 可选 GTCRN（干湿混合）→ 静音冻结型 AGC → `tanh` 软限幅」。
- **约束与影响**：不得把 DSP 处理后的音频替代原始音频落盘，以保留课后更换声学预设/模型离线重转写的能力。

---

## ADR-004：停止录音防卡死、防 `00:00` 时长与原生资源零泄漏（2026-11-25，v1.0.1 / v1.0.2）

- **状态**：生效中
- **背景**：v1.0.0 在停止录音时曾出现 UI 假死、音频时长写为 `00:00`、局部预览阻塞音频采集线程，以及初始化阶段立即取消时泄露原生 JNI 对象等问题。
- **决策**：
  - `stopSession()` 在获取 `lifecycleMutex` 前先置 `isStoppingFlag = true`，并给 `captureJob` 与 `asrWorkerJob` 设置有界等待超时（2.5s / 3s / 4s）；
  - `captureJob` 的 `finally` 放入 `withContext(NonCancellable)`，保证即使协程被 cancel，也必定关闭 WAV 头、释放 VAD/GTCRN、把 `finalDuration` 写入 Room 并重置 UI 状态；
  - 局部实时预览改为独立低优先级 `partialPreviewJob` + `Channel.CONFLATED`，在主 ASR 忙碌时自动跳过，绝不阻塞采集线程；
  - 应用启动时由 `repairInterruptedRecordingsAsync()` 扫描修复异常中断的 WAV 头并根据字节数回填 `durationMs`。
- **约束与影响**：后续改动 `RecordingCoordinator` 严禁在音频采集回调中同步执行推理或网络请求，且必须保留 `NonCancellable` 清理块与启动自愈逻辑。

---

## ADR-005：录音期间系统返回手势退入后台与前台服务防护（2026-11-25，v1.0.1 / v1.0.2）

- **状态**：生效中
- **背景**：用户在录音页误滑全面屏返回手势会直接销毁 Activity；快速连点开始录音可能触发重复 `ACTION_START` 覆盖或误释放 WakeLock；Android 10（API 29）不识别 `FOREGROUND_SERVICE_TYPE_MICROPHONE`。
- **决策**：
  - `TcfyApp.kt`：详情页按返回退回列表，非首 Tab 按返回回录音页，录音页且正在录音时按返回执行 `moveTaskToBack(true)` 并提示转入后台；Tab 与 `openSessionId` 用 `rememberSaveable` 保存；
  - `RecordingForegroundService`：重复 `ACTION_START` 在 `isRecording || isInitializing` 时直接忽略；仅在 `Build.VERSION_CODES.R`（API 30）及以上传入 `FOREGROUND_SERVICE_TYPE_MICROPHONE`；监听录音意外中止后自动释放 WakeLock 并 `stopSelf()`；
  - `TcfyViewModel`：一次性 Snackbar 改用 `Channel<String>(Channel.BUFFERED)`，避免相同提示文本被 `StateFlow` 吞掉；对正在录制的 Session 禁止删除。
- **约束与影响**：修改导航、权限回调或前台服务时，必须保留上述边界防护。

---

## ADR-006：SenseVoice 幻觉过滤与离线术语规范词锁定（2026-11-25，v1.0.1 / v1.0.2）

- **状态**：生效中
- **背景**：SenseVoice 在自动多语模式下的嘈杂环境中偶发纯标点或单字韩/日假名幻觉；术语纠错器在存在复合词（如 `CRISPR-Cas9`）及其子别名（如 `CRISPR`、`Cas9`）时可能误触发二次拆分替换。
- **决策**：
  - `SherpaSenseVoiceEngine.isMeaningfulSpeech` 过滤纯标点及自动模式下单字韩文/假名噪声幻觉；
  - `OfflineTermCorrector` 对所有规范词添加 `isCanonicalLock = true` 并按「长度降序 + 规范词锁优先」排序，锁定已正确的规范词不被子别名改写；同时支持英文规范词的中文译名同音纠错。
- **约束与影响**：优化纠错规则时必须保留规范词锁定语义，并确保 `OfflineTermCorrectorTest` 与 `SmartphoneScenarioE2ETest` 全部通过。

---

## ADR-007：禁用系统云备份（`allowBackup="false"`）与本地隐私保护（2026-11-25，v1.0.2）

- **状态**：生效中
- **背景**：`AppSettingsRepository` 在本地 DataStore 中保存用户配置的 LLM API Key，本地数据库保存课堂记录。
- **决策**：在 `AndroidManifest.xml` 中设置 `android:allowBackup="false"`，阻止 Android Auto Backup 将本地配置与录音记录同步至第三方云端。
- **约束与影响**：不得改回 `allowBackup="true"`；后续若引入导出/备份功能，应做成用户显式触发的本地文件导出（且不含 API Key）。

---

## ADR-008：轻量手动装配（无 DI 框架）与纯 JVM 单测优先（2026-11-25）

- **状态**：生效中
- **背景**：项目规模适中且高度依赖云端/无模拟器环境快速回归验证。
- **决策**：在 `TcfyApplication` 中手动装配单例依赖，不引入 Hilt/Koin；将 DSP、WAV 容灾、术语纠错、导入导出、AI 翻译/精修与场景 E2E 全部保持为纯 JVM 可测试代码。
- **约束与影响**：除非架构规模显著膨胀且经用户明确同意，否则不引入 DI 框架；新核心逻辑应优先设计为不依赖 Android `Context` 的纯 Kotlin 类。

---

## ADR-009：Room 数据库后续演化必须禁用破坏性迁移（2026-11-26，规范确立）

- **状态**：生效中（待首次 Schema 变更时落地代码改造）
- **背景**：当前 `TcfyDatabase` 为 `version = 1` 且配置了 `.fallbackToDestructiveMigration()`。随着用户长期积累课堂录音记录与自定义术语库，未来若升至 `version = 2` 仍保留破坏性迁移，将直接清空老用户全部本地数据。
- **决策**：约定从下一次涉及 Room 实体/表结构的改动起，必须：
  1. 编写显式 `Migration(1, 2)`（及后续增量迁移）；
  2. 移除 `.fallbackToDestructiveMigration()`；
  3. 同步验证升级后已有记录与术语库完整保留。
- **约束与影响**：任何未提供显式 `Migration` 的数据库版本递增都视为违反红线。

---

## ADR-010：极轻声前置预增益、PA 抗混响均衡与录音中自适应调参（2026-11-27）

- **状态**：生效中
- **背景**：实测中存在三类远场痛点：① 轻声汇报人（< -50 dBFS）直接进入 GTCRN 会被误判为底噪吞掉，且固定 `-54 dBFS` 静音门限导致 AGC 不工作；② 教室扩音器 PA 声音含 100–300 Hz 箱体轰鸣与峰值削波，强 GTCRN 干湿比会涂抹谐波；③ 快语速/口音汇报人长时间不停顿易触发 VAD 强制腰斩。
- **决策**：
  - `FarFieldDspPipeline` 升级为「HPF → 可选 PA 抗共振均衡 → 动态噪声底追踪 + GTCRN 前置预增益 → 自适应 AGC（最高 +40 dB）→ PA 软膝压缩 + tanh 限幅」；
  - `AudioChunkPreprocessor` 在送入离线 ASR 前统一做段级 RMS 归一化与 120ms 前后静音留白，`SpeechChunkOverlapBuffer` 在 VAD 强制切分时保留 450ms 重叠并做边界词去重；
  - 新增 `AcousticPreset.AUTO` 与 `LiveAcousticAutoTuner`，支持前几分钟自动校准及一键手动「调参」，需要变更 VAD 门限时在采集协程内安全 flush 并热重建 `SherpaSileroVadEngine`。
- **约束与影响**：保持原始 WAV 落盘位于所有 DSP/均衡之前；VAD 热重建必须在采集线程内完成旧实例 `close()`，杜绝原生内存泄漏。

---

## ADR-011：内置 <1MB 基础模型、多语/单语 ASR 工厂与多镜像测速降级（2026-11-27）

- **状态**：生效中
- **背景**：Silero VAD（629 KB）与 GTCRN（524 KB）合计仅约 1.15 MB，却要求用户首次逐一下载；同时部分同学仅需纯中文、纯英文或粤语轻量模型，而单一镜像源在国内校园网偶发波动。
- **决策**：
  - 将 `silero_vad.onnx` 与 `gtcrn_simple.onnx` 内置至 `app/src/main/assets/models/`，`ModelManager` 初始化时幂等释放至 `files/models/`；
  - 新增 `SherpaOfflineAsrFactory` 统一封装 `SenseVoice`、`Paraformer-ZH Small`、`Whisper-Tiny/Base.en` 与 `WenetSpeech-Yue`；`isCoreAsrReady()` 改为「VAD 就绪且至少安装任意一个 ASR 模型」；
  - 新增 `MirrorLatencySelector` 在下载前并发发送轻量化 HTTP `HEAD`/1-byte `Range GET` 测速多组 HF 与 GitHub 加速镜像，按延迟从低到高排序并在失败时逐级降级。
- **约束与影响**：不删除已有 `ModelType` 与 `AppSettingsRepository` 键名，老用户默认沿用 `sensevoice_small_int8` 且无损兼容。

