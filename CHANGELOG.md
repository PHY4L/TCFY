# Changelog

本文件记录 `PHY4L/TCFY` 的所有重要版本变更与跨会话迭代状态，遵循 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/) 风格。

> **协作约定**：日常开发改动先记录到 `[Unreleased]`；仅在用户明确指令发版时归档为正式版本号并推送 `vX.Y.Z` Tag。

## [Unreleased]

### Added
- 建立长期项目与 AI 协作规范体系：`AGENTS.md`、`docs/ARCHITECTURE.md`、`docs/DECISIONS.md` 与 `CHANGELOG.md`。
- 声学模式横栏支持收起与点开切换，默认选完自动收起并可按需切换「常驻显示」（#6）。
- 新增「轻声 / 超远场」「快语速 / 口音」「自动智能」声学模式与 `LiveAcousticAutoTuner`：支持录音前几分钟自动调参及录音中一键「调参」按钮，并支持安全热重建 VAD（#8）。
- 新增 `LiveGlossaryBuilder`（AI 现场建词库）：根据开讲前几分钟转写推断课程主题与高频术语（含常见同音误识别别名），现场入库并实时投入当前转写纠错，支持一键触发与可选自动触发（#7）。
- 内置 `<1MB` 双基础模型（`silero_vad.onnx` 629KB + `gtcrn_simple.onnx` 524KB）至 APK 资产，首次启动自动释放开箱即用（#4）。
- 扩展多模型离线 ASR 生态（`SherpaOfflineAsrFactory`）：除多语 SenseVoice-Small 外，新增纯中文 `Paraformer-ZH Small`（~78MB）、纯英文 `Whisper-Tiny.en`（~98MB）/`Whisper-Base.en`（~153MB）及粤语/中英粤 `WenetSpeech-Yue`（~128MB），支持自由下载与切换（#4）。
- 新增 `MirrorLatencySelector` 多镜像并发测速引擎：下载前并发探测国内外多组镜像源 RTT 延迟，严格按低到高优先下载并自动降级重试，设置页提供一键「测试镜像源延迟」（#4）。

### Changed
- 底层远场音频与识别管线优化（#1、#2、#3）：
  - 极轻声（< -50 dBFS）增强：新增动态噪声底追踪 AGC（上限提升至 +40 dB）、GTCRN 前置预增益避免弱人声被神经降噪吞掉、送入 ASR 前段级响度归一化 + 120ms 静音留白、VAD 门限下探至 0.10。
  - 扩音器/话筒 PA 抗失真：新增 125 Hz 高通 + 280 Hz 去箱体轰鸣 + 2.6 kHz 辅音清晰度补偿均衡与柔和动态压缩，回调 PA 场景 GTCRN 干湿比，并新增削波遥测。
  - 快语速与口音容错：新增强制切分 450ms 重叠拼接与边界文本去重、扩展 `f/h` 与 `r/l` 区域口音模糊音纠错，并强化自动多语模式下短韩/日假名幻觉过滤。

### Fixed
- 修复从「听课转写」页面切换至其他 Tab 再返回时，转写列表重置回第一条而非定位到最新记录的问题，并新增手动上滑查看历史时的「回到最新」浮动按钮（#5）。

---

## [v1.0.2] - 2026-11-25

### Added
- 仓库纳入固定签名证书 `app/debug.keystore`，支持后续版本直接覆盖安装并保留本地课堂记录与离线模型。
- 新增 `SmartphoneScenarioE2ETest` 端到端场景测试，覆盖 Redmi Note 11 5G 远场录音、暂停恢复、崩溃自愈、多词库导入纠错及弱网/断网 AI 精修导出全链路。
- GitHub Actions（`.github/workflows/android-build.yml`）支持推送 `v*` Tag 时自动打包 `TCFY-v*-arm64.apk`、生成 SHA-256 校验并发布至 GitHub Releases，同时在 `README.md` 补充直链下载指引。

### Fixed
- 修复全部 QA Critical / High / Medium 问题：
  - `RecordingCoordinator`：独立 `partialPreviewJob` + `CONFLATED` 通道消除局部预览对采集线程的阻塞；修复初始化阶段立即取消录音导致的原生资源泄露与状态残留；在 `NonCancellable` 中保证停止落库与错误消息保留。
  - `RecordingForegroundService`：忽略并发重复 `ACTION_START`；仅在 Android 11（API 30）及以上使用 `FOREGROUND_SERVICE_TYPE_MICROPHONE`；录音意外中止时自动释放 WakeLock 并停止前台服务。
  - `TcfyApp` & `TcfyViewModel`：录音期间返回手势自动退入后台保活；Tab 与详情页 ID 改为 `rememberSaveable`；Snackbar 改用 `Channel` 防止重复消息丢失；禁止删除正在录制的会话；开始按钮增加防连点保护。
  - `OfflineTermCorrector`：增加规范词锁定（`isCanonicalLock`），防止复合专业词被子别名二次误拆。
  - `ModelManager`：本地导入不完整时显式报错而不是误判成功。
  - `AndroidManifest.xml`：设置 `android:allowBackup="false"` 保护本地 API Key 与课堂数据。

---

## [v1.0.1] - 2026-11-25

### Added
- 新增内置「分子生物学核心术语」课程词库及可分享的 `glossaries/molecular_biology.csv`。
- `SherpaSenseVoiceEngine.isMeaningfulSpeech` 增加纯标点与单字韩/日假名幻觉噪声过滤。
- 启动时增加 `repairInterruptedRecordingsAsync()` 自动修复异常中断的 WAV 文件头与时长。

### Fixed
- 修复停止录音时 UI 卡死（假死）问题。
- 修复历史记录中录音时长显示为 `00:00` 的问题。
- 修复非主 Tab 与详情页的系统返回手势导航逻辑。

---

## [v1.0.0] - 2026-11-25

### Added
- 初始版本：面向高校教室 3–10m 远场听课场景的离线优先 Android 原生应用。
- 远场声学前端：`UNPROCESSED` / `CAMCORDER` 采集 + `CrashSafeWavWriter` + 软件 DSP（HPF + GTCRN 干湿混合 + 静音冻结 AGC + `tanh` 软限幅）。
- 100% 离线转写：集成 `sherpa-onnx 2.0.3`（SenseVoice-Small int8 + Silero VAD + GTCRN），支持国内镜像下载与本地压缩包导入。
- 专业术语库与三层离线纠错（别名 + 汉语拼音音近 + 英文模糊）及 CSV/TXT/JSON 导入导出。
- 断网保底翻译 + OpenAI 兼容接口课后滑窗精修与 Markdown 课堂总结，支持导出 `.md` / `.txt` / `.srt`。
