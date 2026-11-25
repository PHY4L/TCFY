package com.phy4l.tcfy.ui

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.phy4l.tcfy.ui.glossary.GlossaryScreen
import com.phy4l.tcfy.ui.history.HistoryScreen
import com.phy4l.tcfy.ui.history.SessionDetailScreen
import com.phy4l.tcfy.ui.record.RecordScreen
import com.phy4l.tcfy.ui.settings.SettingsScreen

private enum class MainTab(val label: String, val icon: ImageVector) {
    RECORD("听课转写", Icons.Default.Mic),
    HISTORY("历史记录", Icons.Default.History),
    GLOSSARY("术语词库", Icons.Default.Book),
    SETTINGS("模型设置", Icons.Default.Tune)
}

@Composable
fun TcfyApp(viewModel: TcfyViewModel = viewModel()) {
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val recState by viewModel.recordingState.collectAsState()

    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    var openSessionId by rememberSaveable { mutableStateOf<Long?>(null) }

    LaunchedEffect(Unit) {
        viewModel.toastMessages.collect { msg ->
            snackbarHostState.showSnackbar(msg)
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val micGranted = grants[Manifest.permission.RECORD_AUDIO] == true ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (micGranted) {
            viewModel.startRecording()
        }
    }

    fun requestPermissionsAndStart() {
        val needed = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.RECORD_AUDIO)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (needed.isEmpty()) {
            viewModel.startRecording()
        } else {
            permissionLauncher.launch(needed.toTypedArray())
        }
    }

    val detailId = openSessionId
    if (detailId != null) {
        BackHandler {
            openSessionId = null
        }
        Scaffold(snackbarHost = { SnackbarHost(snackbarHostState) }) { innerPadding ->
            Box(modifier = Modifier.padding(innerPadding)) {
                SessionDetailScreen(
                    sessionId = detailId,
                    viewModel = viewModel,
                    onBack = { openSessionId = null }
                )
            }
        }
        return
    }

    // Pressing back on History / Glossary / Settings returns to main Record tab before exiting
    BackHandler(enabled = selectedTab != 0) {
        selectedTab = 0
    }

    // While actively recording on tab 0, back gesture minimizes to background instead of destroying Activity
    BackHandler(enabled = selectedTab == 0 && recState.isRecording) {
        viewModel.notifyMovedToBackground()
        (context as? Activity)?.moveTaskToBack(true)
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            NavigationBar {
                MainTab.entries.forEachIndexed { idx, tab ->
                    NavigationBarItem(
                        selected = selectedTab == idx,
                        onClick = { selectedTab = idx },
                        icon = { Icon(tab.icon, contentDescription = tab.label) },
                        label = { Text(tab.label) }
                    )
                }
            }
        }
    ) { innerPadding ->
        Box(modifier = Modifier.padding(innerPadding)) {
            when (MainTab.entries[selectedTab]) {
                MainTab.RECORD -> RecordScreen(
                    viewModel = viewModel,
                    onRequestMicPermissionAndStart = ::requestPermissionsAndStart,
                    onNavigateToModels = { selectedTab = MainTab.SETTINGS.ordinal }
                )
                MainTab.HISTORY -> HistoryScreen(
                    viewModel = viewModel,
                    onOpenSession = { id -> openSessionId = id }
                )
                MainTab.GLOSSARY -> GlossaryScreen(viewModel = viewModel)
                MainTab.SETTINGS -> SettingsScreen(viewModel = viewModel)
            }
        }
    }
}
