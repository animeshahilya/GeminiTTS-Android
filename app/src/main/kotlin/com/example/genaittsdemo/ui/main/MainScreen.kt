package com.example.genaittsdemo.ui.main

import android.content.Intent
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.genaittsdemo.data.CacheStats
import com.example.genaittsdemo.data.DictionaryRule
import com.example.genaittsdemo.data.EngineMode
import com.example.genaittsdemo.data.GeminiVoiceCatalog
import com.example.genaittsdemo.data.GeminiVoiceProfile
import com.example.genaittsdemo.data.ModelAvailability
import com.example.genaittsdemo.data.PlaybackStatus
import com.example.genaittsdemo.data.PunctuationMode
import com.example.genaittsdemo.data.TtsEngineState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    modifier: Modifier = Modifier,
    viewModel: MainScreenViewModel = viewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val customState by viewModel.customState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var selectedTab by remember { mutableIntStateOf(0) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primaryContainer,
                            modifier = Modifier.size(36.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.GraphicEq,
                                    contentDescription = "Gemini TTS",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "Google Gemini On-Device TTS",
                                fontWeight = FontWeight.Bold,
                                fontSize = 17.sp
                            )
                            Text(
                                text = if (state.detectedSoc.isNotBlank()) state.detectedSoc else "Hardware NPU Accelerated",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            try {
                                context.startActivity(Intent("com.android.settings.TTS_SETTINGS"))
                            } catch (_: Exception) {
                                context.startActivity(Intent(Settings.ACTION_SETTINGS))
                            }
                        }
                    ) {
                        Icon(Icons.Default.Settings, contentDescription = "TTS Settings")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
        ) {
            // Navigation Tabs
            PrimaryTabRow(
                selectedTabIndex = selectedTab,
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            ) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text("Test Bench", fontSize = 13.sp) },
                    icon = { Icon(Icons.Default.PlayCircle, contentDescription = null, modifier = Modifier.size(18.dp)) }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text("TalkBack & Cache", fontSize = 13.sp) },
                    icon = { Icon(Icons.Default.Speed, contentDescription = null, modifier = Modifier.size(18.dp)) }
                )
                Tab(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    text = { Text("Dictionary (${customState.rules.size})", fontSize = 13.sp) },
                    icon = { Icon(Icons.Default.Translate, contentDescription = null, modifier = Modifier.size(18.dp)) }
                )
            }

            when (selectedTab) {
                0 -> TestBenchTab(state = state, customState = customState, viewModel = viewModel)
                1 -> TalkBackAndCacheTab(state = state, customState = customState, viewModel = viewModel)
                2 -> DictionaryTab(customState = customState, viewModel = viewModel)
            }
        }
    }
}

// -------------------------------------------------------------------------------------
// TAB 0: TEST BENCH
// -------------------------------------------------------------------------------------
@Composable
fun TestBenchTab(
    state: TtsEngineState,
    customState: CustomizationUiState,
    viewModel: MainScreenViewModel
) {
    var inputText by remember {
        mutableStateOf(
            "The Samsung Galaxy S25 Ultra's on-device NPU synthesizes expressive Gemini speech in real-time with sub-100 millisecond latency."
        )
    }

    val samplePrompts = listOf(
        "S25 Ultra NPU" to "The Galaxy S25 Ultra's on-device NPU synthesizes expressive speech with sub-100 ms latency.",
        "TalkBack Nav" to "Home screen, page 1 of 3. Double-tap to activate Google Chrome.",
        "Phone Number" to "Call customer support at +1-800-555-0199 extension 42 for assistance.",
        "Money & Discount" to "Order total is $49.99 with $5.50 shipping fee at 25% discount.",
        "Date & Time" to "Meeting scheduled for Monday, November 24, 2026 at 2:30 PM.",
        "Units & Measure" to "Cruising speed 65 km/h, temperature 25°C, downloaded 150 MB.",
        "Web & Email" to "Inquiries to support@android.com or visit https://android.com/accessibility.",
        "Spelled Code" to "Your two-factor verification code is 4 9 2 0 1 8."
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Hardware & Engine Mode Card
        HardwareInfoCard(
            deviceModel = state.deviceModel,
            androidVersion = state.androidVersion,
            detectedSoc = state.detectedSoc,
            thermalStatus = state.thermalStatus,
            activeEngine = state.activeEngine,
            onEngineChange = { viewModel.setEngineMode(it) }
        )

        // Model Status Card
        ModelStatusCard(
            modelStatus = state.modelStatus,
            statusMessage = state.statusMessage,
            onDownload = { viewModel.downloadModel() }
        )

        // Voice Selector
        if (state.availableVoices.isNotEmpty()) {
            VoiceSelectorCard(
                voices = state.availableVoices,
                selectedIndex = state.selectedVoiceIndex,
                onSelect = { viewModel.selectVoice(it) }
            )
        }

        // Sonic DSP Speed & Pitch Controls
        SonicTuningCard(
            speed = customState.speedMultiplier,
            pitch = customState.pitchMultiplier,
            onSpeedChange = { viewModel.setSpeedMultiplier(it) },
            onPitchChange = { viewModel.setPitchMultiplier(it) }
        )

        // Preset Test Chips
        Text(
            text = "Accessibility & Markup Test Prompts",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            samplePrompts.take(3).forEach { (label, text) ->
                SuggestionChip(
                    onClick = { inputText = text },
                    label = { Text(label, fontSize = 11.sp) }
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            samplePrompts.drop(3).take(3).forEach { (label, text) ->
                SuggestionChip(
                    onClick = { inputText = text },
                    label = { Text(label, fontSize = 11.sp) }
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            samplePrompts.drop(6).forEach { (label, text) ->
                SuggestionChip(
                    onClick = { inputText = text },
                    label = { Text(label, fontSize = 11.sp) }
                )
            }
        }

        // Input Text Area
        OutlinedTextField(
            value = inputText,
            onValueChange = { inputText = it },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 90.dp),
            label = { Text("Utterance to Synthesize") },
            shape = RoundedCornerShape(12.dp)
        )

        // Filtered preview
        val normalizedPreview = remember(inputText, customState.rules, customState.punctuationMode) {
            viewModel.previewFilter(inputText)
        }
        if (normalizedPreview != inputText) {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(10.dp)) {
                    Text(
                        text = "TalkBack Normalized Output:",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = normalizedPreview,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }

        // Live Audio Visualizer & Real-time Metrics Card
        EnhancedMetricsCard(
            playbackStatus = state.playbackStatus,
            latencyMs = state.latencyMs,
            totalTimeMs = state.totalTimeMs,
            realTimeFactor = state.realTimeFactor,
            isCacheHit = state.isCacheHit,
            chunks = state.chunksProcessed
        )

        // Playback Action Controls
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Button(
                onClick = { viewModel.speak(inputText) },
                modifier = Modifier.weight(1f).height(52.dp),
                enabled = state.playbackStatus != PlaybackStatus.SYNTHESIZING,
                shape = RoundedCornerShape(14.dp)
            ) {
                Icon(
                    imageVector = if (state.playbackStatus == PlaybackStatus.PLAYING) Icons.AutoMirrored.Filled.VolumeUp else Icons.Default.PlayArrow,
                    contentDescription = "Speak"
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (state.playbackStatus == PlaybackStatus.PLAYING) "Speaking..." else "Test Voice",
                    fontWeight = FontWeight.SemiBold
                )
            }

            FilledTonalButton(
                onClick = { viewModel.stop() },
                modifier = Modifier.height(52.dp),
                shape = RoundedCornerShape(14.dp)
            ) {
                Icon(imageVector = Icons.Default.Stop, contentDescription = "Stop")
                Spacer(modifier = Modifier.width(4.dp))
                Text("Stop")
            }
        }
    }
}

/**
 * Real-time Speech Rate and Pitch DSP Tuning Card (Bill Cox's Sonic Algorithm).
 */
@Composable
fun SonicTuningCard(
    speed: Float,
    pitch: Float,
    onSpeedChange: (Float) -> Unit,
    onPitchChange: (Float) -> Unit
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Speed,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Sonic DSP Time-Stretching",
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = Color(0xFF2E7D32).copy(alpha = 0.15f)
                ) {
                    Text(
                        text = "Real-Time DSP",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF2E7D32),
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Speech Rate Slider
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Speech Rate (TalkBack Speed)",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = "${"%.2f".format(speed)}x",
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.primary,
                    fontSize = 14.sp
                )
            }
            Slider(
                value = speed,
                onValueChange = onSpeedChange,
                valueRange = 0.5f..3.0f,
                steps = 49
            )

            // Speed Presets
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                listOf(1.0f, 1.25f, 1.5f, 2.0f, 2.5f).forEach { preset ->
                    val isSelected = kotlin.math.abs(speed - preset) < 0.04f
                    FilterChip(
                        selected = isSelected,
                        onClick = { onSpeedChange(preset) },
                        label = { Text("${preset}x", fontSize = 11.sp) },
                        modifier = Modifier.height(32.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Pitch Slider
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Voice Pitch",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = "${"%.2f".format(pitch)}x",
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 14.sp
                )
            }
            Slider(
                value = pitch,
                onValueChange = onPitchChange,
                valueRange = 0.75f..1.5f,
                steps = 14
            )
        }
    }
}

// -------------------------------------------------------------------------------------
// TAB 1: TALKBACK & NPU AUDIO CACHE
// -------------------------------------------------------------------------------------
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TalkBackAndCacheTab(
    state: TtsEngineState,
    customState: CustomizationUiState,
    viewModel: MainScreenViewModel
) {
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // System Integration Card
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Accessibility,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "TalkBack System Integration",
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "To use Gemini TTS for device screen reading, navigate to Android Text-to-Speech settings and select 'Google Gemini TTS (On-Device NPU)' as your Preferred Engine.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f)
                )
                Spacer(modifier = Modifier.height(12.dp))
                Button(
                    onClick = {
                        try {
                            context.startActivity(Intent("com.android.settings.TTS_SETTINGS"))
                        } catch (_: Exception) {
                            context.startActivity(Intent(Settings.ACTION_SETTINGS))
                        }
                    },
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Open Android Text-To-Speech Settings")
                }
            }
        }

        // Architecture Architecture Enhancements (Learned from GoogleTTS_Player)
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.BuildCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Engine Hardening & Architecture",
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Spacer(modifier = Modifier.height(10.dp))

                ArchitectureBadge(
                    title = "Sonic Time-Scale DSP",
                    description = "Dynamic pitch-preserving rate scaling for TalkBack speeds up to 3.5x."
                )
                Spacer(modifier = Modifier.height(8.dp))
                ArchitectureBadge(
                    title = "250ms Startup Reserve",
                    description = "Eliminates audio clicks, pops, and underruns during rapid swipe navigation."
                )
                Spacer(modifier = Modifier.height(8.dp))
                ArchitectureBadge(
                    title = "AtomicLong Generation Cancellation",
                    description = "Lock-free sub-millisecond speech abortion on rapid gestures without thread lock."
                )
                Spacer(modifier = Modifier.height(8.dp))
                ArchitectureBadge(
                    title = "AccessibilitySpanParser (TtsSpan)",
                    description = "Translates phone, currency, date, time, units, and URLs to natural speech."
                )
                Spacer(modifier = Modifier.height(8.dp))
                ArchitectureBadge(
                    title = "ADPF PerformanceHintManager",
                    description = "Signals EAS CPU governor to boost Big/Prime cores for 50ms audio chunk targets."
                )
                Spacer(modifier = Modifier.height(8.dp))
                ArchitectureBadge(
                    title = "ProcTasks Native Thread Priority",
                    description = "Discovers /proc/self/task NPU inference threads and elevates them to URGENT_AUDIO (-19)."
                )
                Spacer(modifier = Modifier.height(8.dp))
                ArchitectureBadge(
                    title = "Screen-Off WakeLock Protection",
                    description = "PARTIAL_WAKE_LOCK guarantees uninterrupted screen reading when the display turns off."
                )
                Spacer(modifier = Modifier.height(8.dp))
                ArchitectureBadge(
                    title = "System TTS Data Handshake",
                    description = "TtsDataActivity responds to CHECK_TTS_DATA, CONFIGURE_ENGINE, and GET_SAMPLE_TEXT."
                )
                Spacer(modifier = Modifier.height(8.dp))
                ArchitectureBadge(
                    title = "OS Memory Pressure Trimming",
                    description = "Automated onTrimMemory hook flushes RAM caches when device memory is low."
                )
            }
        }

        // Ultra-Fast NPU Audio Cache Card
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "Ultra-Fast NPU Audio Cache",
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = "0 ms instant replay for repeated UI phrases",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.Gray
                        )
                    }
                    Switch(
                        checked = customState.isCacheEnabled,
                        onCheckedChange = { viewModel.setCacheEnabled(it) }
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(12.dp))

                val stats = state.cacheStats
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    CacheStatItem(title = "Memory Hits", value = "${stats?.memoryHits ?: 0}")
                    CacheStatItem(title = "Disk Hits", value = "${stats?.diskHits ?: 0}")
                    CacheStatItem(title = "Cache Misses", value = "${stats?.misses ?: 0}")
                    CacheStatItem(
                        title = "NPU Offload",
                        value = "${"%.1f".format(stats?.hitRate ?: 0f)}%",
                        highlight = true
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))
                val diskKb = (stats?.diskSizeBytes ?: 0L) / 1024
                Text(
                    text = "Cached Entries: ${stats?.totalEntries ?: 0} | Disk Storage: ${diskKb} KB",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray
                )

                Spacer(modifier = Modifier.height(12.dp))
                OutlinedButton(
                    onClick = { viewModel.clearAudioCache() },
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.DeleteOutline, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Clear Audio Cache")
                }
            }
        }

        // Voice Tuning & Accessibility Options
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Speech Pipelining & Audio Tuning",
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    color = MaterialTheme.colorScheme.primary
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Smart Sentence Chunking Toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = "Smart Sentence Pipelining", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        Text(
                            text = "Synthesizes long texts in parallel clauses for instant sub-100ms TTFA playback",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.Gray
                        )
                    }
                    Switch(
                        checked = customState.isSmartChunkingEnabled,
                        onCheckedChange = { viewModel.setSmartChunkingEnabled(it) }
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(10.dp))

                // Number & Currency Expansion
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = "Currency & Unit Normalization", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        Text(
                            text = "Automatically expands symbols (\$50 ➔ 50 dollars, 25% ➔ 25 percent)",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.Gray
                        )
                    }
                    Switch(
                        checked = customState.isNumberExpansionEnabled,
                        onCheckedChange = { viewModel.setNumberExpansionEnabled(it) }
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(10.dp))

                // Official Gemini Voice Persona
                Text(
                    text = "Official Gemini Voice Persona",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Select from official Google Gemini Studio & Gemini Live voice profiles:",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))

                var expandedVoiceMenu by remember { mutableStateOf(false) }
                val currentVoice = GeminiVoiceCatalog.findByName(customState.preferredVoiceName)
                    ?: GeminiVoiceCatalog.getDefaultVoiceForGender(customState.preferredGender)

                ExposedDropdownMenuBox(
                    expanded = expandedVoiceMenu,
                    onExpandedChange = { expandedVoiceMenu = it }
                ) {
                    OutlinedTextField(
                        value = "${currentVoice.name} — ${currentVoice.style} (${currentVoice.category.label})",
                        onValueChange = {},
                        readOnly = true,
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedVoiceMenu) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
                        shape = RoundedCornerShape(12.dp)
                    )

                    ExposedDropdownMenu(
                        expanded = expandedVoiceMenu,
                        onDismissRequest = { expandedVoiceMenu = false }
                    ) {
                        GeminiVoiceCatalog.ALL_VOICES.forEach { voice ->
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = "${voice.name} (${if (voice.gender == "FEMALE") "♀" else "♂"})",
                                                fontWeight = FontWeight.Bold
                                            )
                                            Text(
                                                text = voice.category.label,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                        Text(
                                            text = "${voice.style} • ${voice.description}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                },
                                onClick = {
                                    viewModel.setPreferredVoice(voice.name)
                                    expandedVoiceMenu = false
                                },
                                leadingIcon = {
                                    if (customState.preferredVoiceName.equals(voice.name, ignoreCase = true)) {
                                        Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                    }
                                }
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
                Text(text = "Quick Select Flagship Personas:", style = MaterialTheme.typography.labelSmall)
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf("Aoede", "Kore", "Puck", "Charon", "Nova").forEach { personaName ->
                        val isSelected = customState.preferredVoiceName.equals(personaName, ignoreCase = true)
                        FilterChip(
                            selected = isSelected,
                            onClick = { viewModel.setPreferredVoice(personaName) },
                            label = { Text(personaName, fontSize = 12.sp) },
                            leadingIcon = {
                                if (isSelected) {
                                    Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(14.dp))
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ArchitectureBadge(title: String, description: String) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surface
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.CheckCircle,
                contentDescription = null,
                tint = Color(0xFF2E7D32),
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column {
                Text(text = title, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Text(text = description, style = MaterialTheme.typography.bodySmall, color = Color.Gray, fontSize = 11.sp)
            }
        }
    }
}

@Composable
fun CacheStatItem(title: String, value: String, highlight: Boolean = false) {
    Column {
        Text(text = title, style = MaterialTheme.typography.bodySmall, color = Color.Gray, fontSize = 11.sp)
        Text(
            text = value,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            fontSize = 15.sp,
            color = if (highlight) Color(0xFF2E7D32) else MaterialTheme.colorScheme.onSurface
        )
    }
}

// -------------------------------------------------------------------------------------
// TAB 2: PRONUNCIATION DICTIONARY
// -------------------------------------------------------------------------------------
@Composable
fun DictionaryTab(
    customState: CustomizationUiState,
    viewModel: MainScreenViewModel
) {
    var newWord by remember { mutableStateOf("") }
    var newReplacement by remember { mutableStateOf("") }
    var isRegex by remember { mutableStateOf(false) }
    var newDesc by remember { mutableStateOf("") }

    val filteredRules = remember(customState.rules, customState.searchQuery) {
        if (customState.searchQuery.isBlank()) {
            customState.rules
        } else {
            customState.rules.filter {
                it.pattern.contains(customState.searchQuery, ignoreCase = true) ||
                it.replacement.contains(customState.searchQuery, ignoreCase = true) ||
                it.description.contains(customState.searchQuery, ignoreCase = true)
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Punctuation Verbosity
        Card(
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(text = "Punctuation Verbosity:", style = MaterialTheme.typography.labelMedium)
                Spacer(modifier = Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PunctuationMode.entries.forEach { mode ->
                        FilterChip(
                            selected = customState.punctuationMode == mode,
                            onClick = { viewModel.setPunctuationMode(mode) },
                            label = { Text(mode.name) }
                        )
                    }
                }
            }
        }

        // Add Rule Card
        Card(
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = "Add Pronunciation Override",
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = newWord,
                        onValueChange = { newWord = it },
                        placeholder = { Text("Word / Acronym", fontSize = 12.sp) },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        shape = RoundedCornerShape(8.dp)
                    )
                    OutlinedTextField(
                        value = newReplacement,
                        onValueChange = { newReplacement = it },
                        placeholder = { Text("Say As", fontSize = 12.sp) },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        shape = RoundedCornerShape(8.dp)
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = isRegex, onCheckedChange = { isRegex = it })
                        Text(text = "Regex", fontSize = 12.sp)
                    }
                    Button(
                        onClick = {
                            viewModel.addRule(newWord, newReplacement, isRegex, newDesc)
                            newWord = ""
                            newReplacement = ""
                            isRegex = false
                            newDesc = ""
                        },
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Add Rule")
                    }
                }
            }
        }

        // Search & Reset Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = customState.searchQuery,
                onValueChange = { viewModel.setSearchQuery(it) },
                placeholder = { Text("Search ${customState.rules.size} rules...", fontSize = 12.sp) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp)) },
                modifier = Modifier.weight(1f),
                singleLine = true,
                shape = RoundedCornerShape(8.dp)
            )
            IconButton(
                onClick = { viewModel.resetDefaultRules() },
                modifier = Modifier.background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
            ) {
                Icon(Icons.Default.RestartAlt, contentDescription = "Reset Defaults")
            }
        }

        // List of Rules
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            itemsIndexed(filteredRules) { index, rule ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = rule.pattern,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                fontFamily = FontFamily.Monospace
                            )
                            Text(
                                text = "  ➔  ",
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = rule.replacement,
                                fontSize = 13.sp,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        if (rule.description.isNotBlank()) {
                            Text(
                                text = rule.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.Gray,
                                fontSize = 10.sp
                            )
                        }
                    }
                    IconButton(
                        onClick = { viewModel.removeRule(index) },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "Delete", modifier = Modifier.size(16.dp))
                    }
                }
            }
        }
    }
}

// -------------------------------------------------------------------------------------
// SHARED REUSABLE COMPONENTS
// -------------------------------------------------------------------------------------

@Composable
fun HardwareInfoCard(
    deviceModel: String,
    androidVersion: String,
    detectedSoc: String,
    thermalStatus: String,
    activeEngine: EngineMode,
    onEngineChange: (EngineMode) -> Unit
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Memory,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(text = deviceModel, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        Text(
                            text = if (detectedSoc.isNotBlank()) detectedSoc else androidVersion,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = Color(0xFF2E7D32).copy(alpha = 0.15f)
                ) {
                    Text(
                        text = thermalStatus,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFF2E7D32),
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(10.dp))

            Text(text = "Active Synthesis Engine:", style = MaterialTheme.typography.labelSmall)
            Spacer(modifier = Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = activeEngine == EngineMode.GENAI_NPU,
                    onClick = { onEngineChange(EngineMode.GENAI_NPU) },
                    label = { Text("GenAI (NPU Diffusion)") },
                    leadingIcon = {
                        if (activeEngine == EngineMode.GENAI_NPU) {
                            Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                        }
                    }
                )
                FilterChip(
                    selected = activeEngine == EngineMode.SYSTEM_ON_DEVICE,
                    onClick = { onEngineChange(EngineMode.SYSTEM_ON_DEVICE) },
                    label = { Text("System Neural Voice") },
                    leadingIcon = {
                        if (activeEngine == EngineMode.SYSTEM_ON_DEVICE) {
                            Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                        }
                    }
                )
            }
        }
    }
}

@Composable
fun ModelStatusCard(
    modelStatus: ModelAvailability,
    statusMessage: String,
    onDownload: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val (icon, color) = when (modelStatus) {
                        is ModelAvailability.Ready -> Icons.Default.CheckCircle to Color(0xFF2E7D32)
                        is ModelAvailability.Checking -> Icons.Default.Sync to MaterialTheme.colorScheme.primary
                        is ModelAvailability.DownloadRequired -> Icons.Default.CloudDownload to MaterialTheme.colorScheme.tertiary
                        is ModelAvailability.Downloading -> Icons.Default.Download to MaterialTheme.colorScheme.primary
                        is ModelAvailability.Unavailable -> Icons.Default.Warning to MaterialTheme.colorScheme.error
                    }
                    Icon(imageVector = icon, contentDescription = null, tint = color)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = when (modelStatus) {
                            is ModelAvailability.Ready -> "Model Status: Ready (NPU Active)"
                            is ModelAvailability.Checking -> "Model Status: Checking..."
                            is ModelAvailability.DownloadRequired -> "Model Status: Download Needed"
                            is ModelAvailability.Downloading -> "Model Status: Downloading"
                            is ModelAvailability.Unavailable -> "Model Status: Inactive"
                        },
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                }

                if (modelStatus is ModelAvailability.DownloadRequired) {
                    FilledTonalButton(
                        onClick = onDownload,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Icon(Icons.Default.CloudDownload, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Download", fontSize = 12.sp)
                    }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = statusMessage,
                style = MaterialTheme.typography.bodySmall,
                color = Color.Gray
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceSelectorCard(
    voices: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Neural Voice Profile:",
                style = MaterialTheme.typography.labelSmall
            )
            Spacer(modifier = Modifier.height(6.dp))
            ExposedDropdownMenuBox(
                expanded = expanded,
                onExpandedChange = { expanded = it }
            ) {
                OutlinedTextField(
                    value = voices.getOrElse(selectedIndex) { "Select Voice" },
                    onValueChange = {},
                    readOnly = true,
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
                    shape = RoundedCornerShape(10.dp)
                )

                ExposedDropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false }
                ) {
                    voices.forEachIndexed { index, voiceName ->
                        DropdownMenuItem(
                            text = { Text(voiceName) },
                            onClick = {
                                onSelect(index)
                                expanded = false
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun EnhancedMetricsCard(
    playbackStatus: PlaybackStatus,
    latencyMs: Long?,
    totalTimeMs: Long?,
    realTimeFactor: Float?,
    isCacheHit: Boolean,
    chunks: Int
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Live NPU Performance Telemetry",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp
                )
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = if (isCacheHit) Color(0xFF2E7D32).copy(alpha = 0.15f)
                            else if (playbackStatus == PlaybackStatus.PLAYING) MaterialTheme.colorScheme.primaryContainer
                            else Color.Gray.copy(alpha = 0.15f)
                ) {
                    Text(
                        text = if (isCacheHit) "CACHE HIT (0ms NPU)"
                               else if (playbackStatus == PlaybackStatus.PLAYING) "NPU ACTIVE"
                               else "STANDBY",
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = if (isCacheHit) Color(0xFF2E7D32)
                                else if (playbackStatus == PlaybackStatus.PLAYING) MaterialTheme.colorScheme.primary
                                else Color.Gray,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Animated Visualizer
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(34.dp)
                    .background(Color.Black.copy(alpha = 0.05f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                val isPlaying = playbackStatus == PlaybackStatus.PLAYING || playbackStatus == PlaybackStatus.SYNTHESIZING
                repeat(20) { index ->
                    AudioBar(isPlaying = isPlaying, index = index)
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(text = "Time-to-First-Audio (TTFA)", style = MaterialTheme.typography.bodySmall, color = Color.Gray, fontSize = 11.sp)
                    Text(
                        text = if (latencyMs != null) "${latencyMs} ms" else "---",
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 15.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(text = "Total Synthesis", style = MaterialTheme.typography.bodySmall, color = Color.Gray, fontSize = 11.sp)
                    Text(
                        text = if (totalTimeMs != null) "${totalTimeMs} ms" else "---",
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 15.sp
                    )
                }

                Column(horizontalAlignment = Alignment.End) {
                    Text(text = "Real-Time Factor", style = MaterialTheme.typography.bodySmall, color = Color.Gray, fontSize = 11.sp)
                    Text(
                        text = if (realTimeFactor != null) "${"%.2f".format(realTimeFactor)}x" else if (chunks > 0) "$chunks chunks" else "Ready",
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 15.sp,
                        color = if (realTimeFactor != null && realTimeFactor < 0.5f) Color(0xFF2E7D32) else MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}

@Composable
fun AudioBar(isPlaying: Boolean, index: Int) {
    val infiniteTransition = rememberInfiniteTransition(label = "audioBar")
    val heightScale by infiniteTransition.animateFloat(
        initialValue = 0.15f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 280 + (index * 42) % 360, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "height"
    )

    val currentHeight = if (isPlaying) (26.dp * heightScale).coerceAtLeast(3.dp) else 3.dp

    Box(
        modifier = Modifier
            .width(3.5.dp)
            .height(currentHeight)
            .clip(CircleShape)
            .background(if (isPlaying) MaterialTheme.colorScheme.primary else Color.Gray.copy(alpha = 0.35f))
    )
}
