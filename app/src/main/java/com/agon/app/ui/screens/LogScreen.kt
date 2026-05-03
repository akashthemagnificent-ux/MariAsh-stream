package com.agon.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.agon.app.debug.AppLogger
import com.agon.app.debug.CrashHandler
import kotlinx.coroutines.launch

private val BG = Color(0xFF121212)
private val FG_DIM = Color(0xFF888888)

private fun levelColor(level: String) = when (level) {
    "E" -> Color(0xFFEF5350)
    "W" -> Color(0xFFFF9800)
    "I" -> Color(0xFF4CAF50)
    "D" -> Color(0xFF90CAF9)
    else -> Color(0xFFCCCCCC)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogScreen(navController: NavController) {
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("Live Log", "Crash Log")

    Scaffold(
        containerColor = BG,
        topBar = {
            TopAppBar(
                title = { Text("App Logs", color = Color.White) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF1E1E1E)),
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Default.ArrowBack, "Back", tint = Color.White)
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(BG)
        ) {
            TabRow(
                selectedTabIndex = selectedTab,
                containerColor = Color(0xFF1E1E1E),
                contentColor = Color.White
            ) {
                tabs.forEachIndexed { i, title ->
                    Tab(
                        selected = selectedTab == i,
                        onClick = { selectedTab = i },
                        text = { Text(title, color = if (selectedTab == i) Color.White else FG_DIM) }
                    )
                }
            }
            when (selectedTab) {
                0 -> LiveLogTab()
                1 -> CrashLogTab()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiveLogTab() {
    val allLogs by AppLogger.logs.collectAsState()
    var levelFilter by remember { mutableStateOf("ALL") }
    val listState = rememberLazyListState()
    var autoScroll by remember { mutableStateOf(true) }
    val clipboardManager = LocalClipboardManager.current
    val scope = rememberCoroutineScope()

    val filtered = remember(allLogs, levelFilter) {
        if (levelFilter == "ALL") allLogs
        else allLogs.filter { it.level == levelFilter }
    }

    // Auto-scroll to newest entry unless user scrolled up
    LaunchedEffect(filtered.size) {
        if (autoScroll && filtered.isNotEmpty()) {
            listState.scrollToItem(filtered.size - 1)
        }
    }

    // Detect if user scrolled away from the bottom
    val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
    val total = listState.layoutInfo.totalItemsCount
    LaunchedEffect(listState.isScrollInProgress) {
        if (listState.isScrollInProgress && total > 0) {
            autoScroll = lastVisible >= total - 3
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(BG)) {
        // ── Top toolbar ────────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF1E1E1E))
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Level filter chips
            Row(
                modifier = Modifier
                    .weight(1f)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                listOf("ALL", "E", "W", "I", "D").forEach { level ->
                    val selected = levelFilter == level
                    FilterChip(
                        selected = selected,
                        onClick = { levelFilter = level },
                        label = {
                            Text(
                                level,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                color = if (selected) levelColor(level) else FG_DIM
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = levelColor(level).copy(alpha = 0.15f),
                            containerColor = Color(0xFF2A2A2A)
                        ),
                        border = FilterChipDefaults.filterChipBorder(
                            enabled = true,
                            selected = selected,
                            borderColor = FG_DIM.copy(alpha = 0.3f),
                            selectedBorderColor = levelColor(level).copy(alpha = 0.5f)
                        ),
                        modifier = Modifier.height(28.dp)
                    )
                }
            }

            // Copy button
            IconButton(
                onClick = {
                    val text = if (levelFilter == "ALL") AppLogger.export()
                    else filtered.joinToString("\n") { "${it.timestamp} ${it.level}/${it.tag}: ${it.message}" }
                    clipboardManager.setText(AnnotatedString(text))
                },
                modifier = Modifier.size(32.dp)
            ) {
                Icon(Icons.Default.ContentCopy, "Copy logs", tint = FG_DIM, modifier = Modifier.size(18.dp))
            }

            // Clear button
            IconButton(
                onClick = { AppLogger.clear() },
                modifier = Modifier.size(32.dp)
            ) {
                Icon(Icons.Default.DeleteOutline, "Clear logs", tint = FG_DIM, modifier = Modifier.size(18.dp))
            }
        }

        // Session header
        if (filtered.isNotEmpty()) {
            Text(
                "Session: ${AppLogger.sessionId}  •  ${filtered.size} entries",
                fontSize = 10.sp,
                color = FG_DIM,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF1A1A1A))
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            )
        }

        Box(modifier = Modifier.weight(1f)) {
            if (filtered.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "No log entries yet.\nStart a room session to capture logs.",
                        color = FG_DIM,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        fontSize = 13.sp
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(vertical = 2.dp)
                ) {
                    itemsIndexed(filtered, key = { _, e -> e.id }) { _, entry ->
                        LogEntryRow(entry)
                    }
                }
            }

            // "Follow" FAB — appears when user has scrolled up
            if (!autoScroll && filtered.isNotEmpty()) {
                FloatingActionButton(
                    onClick = {
                        autoScroll = true
                        scope.launch {
                            if (filtered.isNotEmpty()) listState.animateScrollToItem(filtered.size - 1)
                        }
                    },
                    containerColor = Color(0xFF2979FF),
                    contentColor = Color.White,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(16.dp)
                        .size(40.dp)
                ) {
                    Icon(Icons.Default.ArrowDownward, "Follow", modifier = Modifier.size(20.dp))
                }
            }
        }
    }
}

@Composable
private fun LogEntryRow(entry: AppLogger.LogEntry) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp, vertical = 1.dp),
        verticalAlignment = Alignment.Top
    ) {
        // Timestamp
        Text(
            text = entry.timestamp,
            fontSize = 10.sp,
            color = FG_DIM,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.width(86.dp)
        )
        // Level badge
        Text(
            text = entry.level,
            fontSize = 10.sp,
            color = levelColor(entry.level),
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.width(14.dp)
        )
        Spacer(Modifier.width(4.dp))
        // Tag
        Text(
            text = entry.tag,
            fontSize = 10.sp,
            color = Color(0xFF80CBC4),
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.width(96.dp)
        )
        Spacer(Modifier.width(4.dp))
        // Message
        Text(
            text = entry.message,
            fontSize = 10.sp,
            color = levelColor(entry.level).copy(alpha = if (entry.level == "D") 0.75f else 1f),
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.weight(1f),
            softWrap = true
        )
    }
}

@Composable
fun CrashLogTab() {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    var crashData by remember { mutableStateOf(CrashHandler.getLastCrash(context)) }
    val crashReport = crashData.first
    val crashTime = crashData.second

    Column(modifier = Modifier.fillMaxSize().background(BG)) {
        // Toolbar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF1E1E1E))
                .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.End
        ) {
            if (crashReport != null) {
                IconButton(
                    onClick = { clipboardManager.setText(AnnotatedString(crashReport)) },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(Icons.Default.ContentCopy, "Copy crash", tint = FG_DIM, modifier = Modifier.size(18.dp))
                }
                IconButton(
                    onClick = {
                        CrashHandler.clearCrash(context)
                        crashData = Pair(null, null)
                    },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(Icons.Default.DeleteOutline, "Clear crash", tint = FG_DIM, modifier = Modifier.size(18.dp))
                }
            }
        }

        if (crashReport == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("No crash recorded", color = Color(0xFF4CAF50), fontSize = 16.sp)
                    Spacer(Modifier.height(8.dp))
                    Text("Crashes will appear here automatically.", color = FG_DIM, fontSize = 12.sp)
                }
            }
        } else {
            if (crashTime != null) {
                Text(
                    "Crash recorded at: $crashTime",
                    fontSize = 11.sp,
                    color = Color(0xFFEF5350),
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF2A1A1A))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text(
                    text = crashReport,
                    fontSize = 10.sp,
                    color = Color(0xFFEEEEEE),
                    fontFamily = FontFamily.Monospace,
                    softWrap = true
                )
            }
        }
    }
}
