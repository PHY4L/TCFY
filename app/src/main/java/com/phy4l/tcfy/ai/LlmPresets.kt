package com.phy4l.tcfy.ai

data class LlmProviderPreset(
    val name: String,
    val baseUrl: String,
    val defaultModel: String,
    val hint: String
)

object LlmPresets {
    val ALL = listOf(
        LlmProviderPreset(
            name = "DeepSeek",
            baseUrl = "https://api.deepseek.com/v1",
            defaultModel = "deepseek-chat",
            hint = "性价比极高，一节 90 分钟课精修+总结仅需几分钱"
        ),
        LlmProviderPreset(
            name = "阿里云百炼 (通义千问)",
            baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1",
            defaultModel = "qwen-plus",
            hint = "中文与理工科术语理解优秀，国内直连稳定"
        ),
        LlmProviderPreset(
            name = "火山引擎 (豆包)",
            baseUrl = "https://ark.cn-beijing.volces.com/api/v3",
            defaultModel = "doubao-1-5-pro-32k-250115",
            hint = "需填写火山方舟推理接入点或模型 ID"
        ),
        LlmProviderPreset(
            name = "硅基流动 (SiliconFlow)",
            baseUrl = "https://api.siliconflow.cn/v1",
            defaultModel = "Qwen/Qwen2.5-7B-Instruct",
            hint = "支持多款开源大模型与免费额度"
        ),
        LlmProviderPreset(
            name = "OpenAI / 兼容中转",
            baseUrl = "https://api.openai.com/v1",
            defaultModel = "gpt-4o-mini",
            hint = "兼容任意 OpenAI Chat Completions 接口"
        )
    )
}
