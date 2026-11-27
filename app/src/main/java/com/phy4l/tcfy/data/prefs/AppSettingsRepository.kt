package com.phy4l.tcfy.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.phy4l.tcfy.asr.AsrLanguageMode
import com.phy4l.tcfy.audio.AcousticPreset
import com.phy4l.tcfy.audio.AudioSourceMode
import com.phy4l.tcfy.audio.DenoiseLevel
import com.phy4l.tcfy.audio.FarFieldDspConfig
import com.phy4l.tcfy.data.db.TranslationDirection
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.tcfyDataStore: DataStore<Preferences> by preferencesDataStore(name = "tcfy_settings")

data class LlmApiConfig(
    val providerName: String = "DeepSeek",
    val baseUrl: String = "https://api.deepseek.com/v1",
    val apiKey: String = "",
    val modelName: String = "deepseek-chat",
    val enableLiveOnlineTranslation: Boolean = false
) {
    val isConfigured: Boolean
        get() = baseUrl.isNotBlank() && apiKey.isNotBlank() && modelName.isNotBlank()
}

data class AppSettings(
    val dspConfig: FarFieldDspConfig = AcousticPreset.CLASSROOM_FAR_FIELD.config,
    val acousticPresetName: String = AcousticPreset.CLASSROOM_FAR_FIELD.name,
    val asrLanguageMode: AsrLanguageMode = AsrLanguageMode.AUTO,
    val asrThreads: Int = 4,
    val translationDirection: TranslationDirection = TranslationDirection.AUTO_BILINGUAL,
    val enablePinyinTermCorrection: Boolean = true,
    val useDomesticMirror: Boolean = true,
    val customMirrorBase: String = "",
    val largeSubtitleFont: Boolean = false,
    val pinAcousticPresetBar: Boolean = false,
    val llmConfig: LlmApiConfig = LlmApiConfig()
)

class AppSettingsRepository(private val context: Context) {

    private object Keys {
        val ACOUSTIC_PRESET = stringPreferencesKey("acoustic_preset")
        val AUDIO_SOURCE = stringPreferencesKey("audio_source")
        val DENOISE_LEVEL = stringPreferencesKey("denoise_level")
        val ENABLE_HPF = booleanPreferencesKey("enable_hpf")
        val ENABLE_AGC = booleanPreferencesKey("enable_agc")
        val MAX_GAIN_DB = floatPreferencesKey("max_gain_db")
        val VAD_THRESHOLD = floatPreferencesKey("vad_threshold")
        val VAD_MIN_SILENCE = floatPreferencesKey("vad_min_silence")

        val ASR_LANGUAGE = stringPreferencesKey("asr_language")
        val ASR_THREADS = intPreferencesKey("asr_threads")
        val TRANS_DIRECTION = stringPreferencesKey("trans_direction")
        val ENABLE_PINYIN_FIX = booleanPreferencesKey("enable_pinyin_fix")
        val USE_DOMESTIC_MIRROR = booleanPreferencesKey("use_domestic_mirror")
        val CUSTOM_MIRROR = stringPreferencesKey("custom_mirror")
        val LARGE_FONT = booleanPreferencesKey("large_font")
        val PIN_ACOUSTIC_BAR = booleanPreferencesKey("pin_acoustic_bar")

        val LLM_PROVIDER = stringPreferencesKey("llm_provider")
        val LLM_BASE_URL = stringPreferencesKey("llm_base_url")
        val LLM_API_KEY = stringPreferencesKey("llm_api_key")
        val LLM_MODEL = stringPreferencesKey("llm_model")
        val LLM_LIVE_TRANS = booleanPreferencesKey("llm_live_trans")
    }

    val settingsFlow: Flow<AppSettings> = context.tcfyDataStore.data.map { prefs ->
        val presetName = prefs[Keys.ACOUSTIC_PRESET] ?: AcousticPreset.CLASSROOM_FAR_FIELD.name
        val basePreset = runCatching { AcousticPreset.valueOf(presetName) }
            .getOrDefault(AcousticPreset.CLASSROOM_FAR_FIELD)
        val defaultDsp = basePreset.config

        val dsp = FarFieldDspConfig(
            highPassCutoffHz = defaultDsp.highPassCutoffHz,
            enableHighPass = prefs[Keys.ENABLE_HPF] ?: defaultDsp.enableHighPass,
            enableAgc = prefs[Keys.ENABLE_AGC] ?: defaultDsp.enableAgc,
            targetRmsDbfs = defaultDsp.targetRmsDbfs,
            maxGainDb = prefs[Keys.MAX_GAIN_DB] ?: defaultDsp.maxGainDb,
            agcSilenceFloorDbfs = defaultDsp.agcSilenceFloorDbfs,
            denoiseLevel = prefs[Keys.DENOISE_LEVEL]?.let {
                runCatching { DenoiseLevel.valueOf(it) }.getOrNull()
            } ?: defaultDsp.denoiseLevel,
            vadThreshold = prefs[Keys.VAD_THRESHOLD] ?: defaultDsp.vadThreshold,
            vadMinSilenceSec = prefs[Keys.VAD_MIN_SILENCE] ?: defaultDsp.vadMinSilenceSec,
            vadMaxSpeechSec = defaultDsp.vadMaxSpeechSec,
            audioSourceMode = prefs[Keys.AUDIO_SOURCE]?.let {
                runCatching { AudioSourceMode.valueOf(it) }.getOrNull()
            } ?: defaultDsp.audioSourceMode
        )

        AppSettings(
            dspConfig = dsp,
            acousticPresetName = basePreset.name,
            asrLanguageMode = prefs[Keys.ASR_LANGUAGE]?.let {
                runCatching { AsrLanguageMode.valueOf(it) }.getOrNull()
            } ?: AsrLanguageMode.AUTO,
            asrThreads = prefs[Keys.ASR_THREADS] ?: 4,
            translationDirection = prefs[Keys.TRANS_DIRECTION]?.let {
                runCatching { TranslationDirection.valueOf(it) }.getOrNull()
            } ?: TranslationDirection.AUTO_BILINGUAL,
            enablePinyinTermCorrection = prefs[Keys.ENABLE_PINYIN_FIX] ?: true,
            useDomesticMirror = prefs[Keys.USE_DOMESTIC_MIRROR] ?: true,
            customMirrorBase = prefs[Keys.CUSTOM_MIRROR] ?: "",
            largeSubtitleFont = prefs[Keys.LARGE_FONT] ?: false,
            pinAcousticPresetBar = prefs[Keys.PIN_ACOUSTIC_BAR] ?: false,
            llmConfig = LlmApiConfig(
                providerName = prefs[Keys.LLM_PROVIDER] ?: "DeepSeek",
                baseUrl = prefs[Keys.LLM_BASE_URL] ?: "https://api.deepseek.com/v1",
                apiKey = prefs[Keys.LLM_API_KEY] ?: "",
                modelName = prefs[Keys.LLM_MODEL] ?: "deepseek-chat",
                enableLiveOnlineTranslation = prefs[Keys.LLM_LIVE_TRANS] ?: false
            )
        )
    }

    suspend fun applyAcousticPreset(preset: AcousticPreset) {
        val c = preset.config
        context.tcfyDataStore.edit { p ->
            p[Keys.ACOUSTIC_PRESET] = preset.name
            p[Keys.AUDIO_SOURCE] = c.audioSourceMode.name
            p[Keys.DENOISE_LEVEL] = c.denoiseLevel.name
            p[Keys.ENABLE_HPF] = c.enableHighPass
            p[Keys.ENABLE_AGC] = c.enableAgc
            p[Keys.MAX_GAIN_DB] = c.maxGainDb
            p[Keys.VAD_THRESHOLD] = c.vadThreshold
            p[Keys.VAD_MIN_SILENCE] = c.vadMinSilenceSec
        }
    }

    suspend fun updateDspConfig(dsp: FarFieldDspConfig) {
        context.tcfyDataStore.edit { p ->
            p[Keys.AUDIO_SOURCE] = dsp.audioSourceMode.name
            p[Keys.DENOISE_LEVEL] = dsp.denoiseLevel.name
            p[Keys.ENABLE_HPF] = dsp.enableHighPass
            p[Keys.ENABLE_AGC] = dsp.enableAgc
            p[Keys.MAX_GAIN_DB] = dsp.maxGainDb
            p[Keys.VAD_THRESHOLD] = dsp.vadThreshold
            p[Keys.VAD_MIN_SILENCE] = dsp.vadMinSilenceSec
        }
    }

    suspend fun updateAsrLanguage(mode: AsrLanguageMode) {
        context.tcfyDataStore.edit { it[Keys.ASR_LANGUAGE] = mode.name }
    }

    suspend fun updateAsrThreads(threads: Int) {
        context.tcfyDataStore.edit { it[Keys.ASR_THREADS] = threads.coerceIn(1, 6) }
    }

    suspend fun updateTranslationDirection(direction: TranslationDirection) {
        context.tcfyDataStore.edit { it[Keys.TRANS_DIRECTION] = direction.name }
    }

    suspend fun updatePinyinFix(enabled: Boolean) {
        context.tcfyDataStore.edit { it[Keys.ENABLE_PINYIN_FIX] = enabled }
    }

    suspend fun updateMirrorSettings(useDomestic: Boolean, customBase: String) {
        context.tcfyDataStore.edit {
            it[Keys.USE_DOMESTIC_MIRROR] = useDomestic
            it[Keys.CUSTOM_MIRROR] = customBase.trim()
        }
    }

    suspend fun updateLargeFont(enabled: Boolean) {
        context.tcfyDataStore.edit { it[Keys.LARGE_FONT] = enabled }
    }

    suspend fun updatePinAcousticBar(pinned: Boolean) {
        context.tcfyDataStore.edit { it[Keys.PIN_ACOUSTIC_BAR] = pinned }
    }

    suspend fun updateLlmConfig(config: LlmApiConfig) {
        context.tcfyDataStore.edit { p ->
            p[Keys.LLM_PROVIDER] = config.providerName
            p[Keys.LLM_BASE_URL] = config.baseUrl.trim()
            p[Keys.LLM_API_KEY] = config.apiKey.trim()
            p[Keys.LLM_MODEL] = config.modelName.trim()
            p[Keys.LLM_LIVE_TRANS] = config.enableLiveOnlineTranslation
        }
    }
}
