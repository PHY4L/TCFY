# Changelog

本文件记录 `PHY4L/TCFY` 的所有重要版本变更与跨会话迭代状态，遵循 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/) 风格。

> **协作约定**：日常开发改动先记录到 `[Unreleased]`；仅在用户明确指令发版时归档为正式版本号并推送 `vX.Y.Z` Tag。

## [Unreleased]

### Added
- 建立长期项目与 AI 协作规范体系：`AGENTS.md`、`docs/ARCHITECTURE.md`、`docs/DECISIONS.md` 与 `CHANGELOG.md`。

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
