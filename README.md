# TCFY — 教室远场离线语音转写、专业术语翻译与 AI 总结（Android）

专为**高校教室 3–10 米远场听课场景**打造的原生 Android 应用（重点适配 **Redmi Note 11 5G / 天玑 810 / 8GB RAM** 及主流 ARM64 安卓机），面向手机自带麦克风（无需领夹麦）、中英混杂课堂与弱网/断网环境设计。

## 下载与安装（Android）

- **最新版本下载页（推荐）**：前往 [GitHub Releases](https://github.com/PHY4L/TCFY/releases/latest) 下载最新的 `TCFY-v*-arm64.apk`（无需登录 GitHub，点击 Assets 中的 `.apk` 即可直接下载）。
- **直链下载（v1.0.2）**：[TCFY-v1.0.2-arm64.apk](https://github.com/PHY4L/TCFY/releases/download/v1.0.2/TCFY-v1.0.2-arm64.apk)
- **系统要求**：Android 8.0 及以上，64 位 ARM（`arm64-v8a`，近几年主流安卓手机均支持）。
- **安装提示**：
  1. 首次安装需在手机上允许「安装未知来源应用」。
  2. 安装完成后进入 App 底部 **「设置」** 页，下载离线模型（共约 230 MB，默认国内镜像源，支持本地导入）即可开始离线转写。

## 核心特性

1. **教室 3–10m 远场声学前端**
   - 绕过 Android 厂商近场降噪门限，优先采用 `UNPROCESSED` / `CAMCORDER`（红米顶部远场麦）采集。
   - 软件前端 DSP 链：去直流 + 85Hz 二阶 Butterworth 高通滤波（过滤空调/投影仪低频噪声）→ 可选 **GTCRN 轻量神经网络去噪**（带干湿比混合，保留英文高频辅音）→ 静音冻结型远场自适应增益（AGC）→ `tanh` 软限幅防爆音。
   - 防崩溃 `CrashSafeWavWriter`：原始 16kHz PCM 持续落盘并定时同步 WAV 头，断电/杀进程不丢录音，支持课后更换模型离线重转写。

2. **100% 离线中英混杂语音转写（零网络依赖）**
   - 集成 `sherpa-onnx` v2.0.3，运行 **SenseVoice-Small int8**（中/英/日/韩/粤原生混杂识别 + 自动标点/ITN）与 **Silero VAD**。
   - 模型管理页内置国内镜像源（`hf-mirror.com`）与**本地压缩包导入**（同学间微信/QQ传 `.tar.bz2`/`.zip`/`.onnx` 离线导入）。

3. **专业术语名词库与三层纠错体系**
   - 支持按课程分组管理术语库，内置物理/量子力学与计算机/AI 示例词库。
   - 支持 **CSV（自动识别 UTF-8 / GBK）、TXT、JSON** 导入导出，方便同班同学共享课程词表。
   - 离线实时纠错：最长匹配别名替换 + 基于 `pinyin4j` 的**汉语拼音音近模糊纠错**（自动纠正“学定额”→“薛定谔”、“哈密顿良”→“哈密顿量”等）+ 英文拼写模糊纠错。

4. **断网保底翻译 + 课后 AI 精修与总结**
   - 断网/弱网时实时显示离线术语双语注译，绝不卡死或丢字；联网实时译默认 4 秒超时自动降级离线。
   - 支持接入任意 **OpenAI 兼容接口**（内置 DeepSeek、阿里云百炼/通义千问、火山豆包、硅基流动、OpenAI 预设），课后一键滑窗上下文精修并生成结构化 Markdown 课堂笔记。
   - 支持导出 **Markdown (.md)、双语纯文本 (.txt)、字幕 (.srt)**。

## 本地构建与测试

```bash
# 1. 拉取并校验 sherpa-onnx Android AAR (v2.0.3)
./scripts/fetch_deps.sh

# 2. 运行全部单元测试（DSP / WAV 恢复 / 术语纠错 / 导入导出 / AI 断网降级）
./gradlew testDebugUnitTest

# 3. 打包 ARM64 Debug APK
./gradlew assembleDebug
```

构建产物路径：`app/build/outputs/apk/debug/app-debug.apk`

## Redmi Note 11 5G (MIUI / HyperOS) 使用建议

1. 首次安装后在「模型设置」页下载 **SenseVoice-Small**、**Silero VAD** 与 **GTCRN** 三个离线模型（共约 230MB）。
2. 在「模型设置」底部点击**一键前往设置省电白名单**，将应用省电策略设为「无限制」，确保 45–90 分钟息屏听课不被杀后台。
3. 听课时将手机平放课桌，**顶部麦克风朝向讲台方向**；后排无扩音器选「教室远场 (3-10m)」，有扩音器选「大教室有扩音器」。
