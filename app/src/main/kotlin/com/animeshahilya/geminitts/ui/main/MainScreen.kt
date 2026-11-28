package com.animeshahilya.geminitts.ui.main

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.animeshahilya.geminitts.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: MainScreenViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var settingsPage by rememberSaveable { mutableStateOf<SettingsPage?>(null) }
    val tabs = listOf("Voice", "Languages", "Dictionary")
    val snackbarHostState = remember { SnackbarHostState() }

    val navigateBack = { settingsPage = if (settingsPage == SettingsPage.HUB) null else SettingsPage.HUB }

    BackHandler(enabled = settingsPage != null, onBack = navigateBack)

    val exportBackupLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri -> uri?.let(viewModel::exportBackupToUri) }

    val importBackupLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let(viewModel::importBackupFromUri) }

    val exportDicLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri -> uri?.let(viewModel::exportNvdaDicToUri) }

    val importDicLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let(viewModel::importNvdaDicFromUri) }

    LaunchedEffect(state.statusMessage) {
        val msg = state.statusMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(msg)
        viewModel.clearStatusMessage()
    }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refreshEngineCheck() }
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { viewModel.stop() }

    Scaffold(
        topBar = {
            val activeSettings = settingsPage
            if (activeSettings == null) {
                TopAppBar(
                    title = { Text(stringResource(R.string.app_name)) },
                    actions = {
                        TextButton(
                            onClick = { settingsPage = SettingsPage.HUB },
                            modifier = Modifier.defaultMinSize(minHeight = 48.dp)
                        ) { Text("Settings") }
                    }
                )
            } else {
                TopAppBar(
                    title = { Text(activeSettings.title) },
                    navigationIcon = {
                        TextButton(
                            onClick = navigateBack,
                            modifier = Modifier.defaultMinSize(minHeight = 48.dp)
                        ) { Text("Back") }
                    }
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
                .imePadding()
        ) {
            val activeSettings = settingsPage
            if (activeSettings != null) {
                key(activeSettings) {
                    Column(
                        Modifier
                            .fillMaxSize()
                            .semantics { paneTitle = activeSettings.title }
                            .verticalScroll(rememberScrollState())
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        SettingsScreen(
                            page = activeSettings,
                            state = state,
                            viewModel = viewModel,
                            onNavigate = { settingsPage = it },
                            onExportBackup = { exportBackupLauncher.launch("gemini-tts-backup.json") },
                            onImportBackup = { importBackupLauncher.launch(arrayOf("application/json", "*/*")) }
                        )
                    }
                }
            } else {
                Column(Modifier.fillMaxSize()) {
                    PrimaryScrollableTabRow(selectedTabIndex = tab, edgePadding = 8.dp) {
                        tabs.forEachIndexed { index, title ->
                            Tab(selected = tab == index, onClick = { tab = index }, text = { Text(title) })
                        }
                    }
                    key(tab) {
                        if (tab == 2) {
                            DictionaryTab(
                                state = state,
                                viewModel = viewModel,
                                onImportDic = { importDicLauncher.launch(arrayOf("*/*")) },
                                onExportDic = { exportDicLauncher.launch("gemini-tts-dictionary.dic") },
                                modifier = Modifier
                                    .fillMaxSize()
                                    .semantics { paneTitle = tabs[tab] }
                            )
                        } else {
                            Column(
                                Modifier
                                    .fillMaxSize()
                                    .semantics { paneTitle = tabs[tab] }
                                    .verticalScroll(rememberScrollState())
                                    .padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                when (tab) {
                                    0 -> VoiceTab(state, viewModel, onOpenSettings = { settingsPage = SettingsPage.HUB })
                                    else -> LanguagesTab(state, viewModel)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
