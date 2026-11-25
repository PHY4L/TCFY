package com.phy4l.tcfy

import android.app.Application
import com.phy4l.tcfy.ai.LectureRefinerAndSummarizer
import com.phy4l.tcfy.ai.OpenAiCompatibleClient
import com.phy4l.tcfy.ai.TranslationRouter
import com.phy4l.tcfy.asr.ModelManager
import com.phy4l.tcfy.data.db.TcfyDatabase
import com.phy4l.tcfy.data.prefs.AppSettingsRepository
import com.phy4l.tcfy.service.RecordingCoordinator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class TcfyApplication : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    lateinit var database: TcfyDatabase
        private set
    lateinit var settingsRepository: AppSettingsRepository
        private set
    lateinit var modelManager: ModelManager
        private set
    lateinit var llmClient: OpenAiCompatibleClient
        private set
    lateinit var translationRouter: TranslationRouter
        private set
    lateinit var refinerAndSummarizer: LectureRefinerAndSummarizer
        private set
    lateinit var recordingCoordinator: RecordingCoordinator
        private set

    override fun onCreate() {
        super.onCreate()
        database = TcfyDatabase.getInstance(this)
        settingsRepository = AppSettingsRepository(this)
        modelManager = ModelManager(this)
        llmClient = OpenAiCompatibleClient()
        translationRouter = TranslationRouter(llmClient)
        refinerAndSummarizer = LectureRefinerAndSummarizer(llmClient)
        recordingCoordinator = RecordingCoordinator(
            context = this,
            database = database,
            settingsRepo = settingsRepository,
            modelManager = modelManager,
            translationRouter = translationRouter
        )

        appScope.launch {
            TcfyDatabase.seedStarterGlossariesIfEmpty(database)
            recordingCoordinator.repairInterruptedRecordingsAsync()
        }
    }
}
