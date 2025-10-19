
package se.olle.rostbubbla.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.ui.Alignment
import androidx.compose.foundation.text.ClickableText
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import retrofit2.HttpException
import kotlinx.coroutines.CompletableDeferred
import com.google.accompanist.permissions.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import se.olle.rostbubbla.access.PasteAccessibilityService
import se.olle.rostbubbla.overlay.OverlayService
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Image
import androidx.compose.ui.layout.ContentScale
import se.olle.rostbubbla.R

class MainActivity : ComponentActivity() {
  val pttFlow = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    setContent {
      val brandColors = lightColorScheme(
        primary = Color(0xFF0E8F87),
        onPrimary = Color.White,
        secondary = Color(0xFFF29F05),
        onSecondary = Color.White,
        tertiary = Color(0xFF0B6E66),
        background = Color(0xFFFAF7F2),
        onBackground = Color(0xFF1B1B1B),
        surface = Color.White,
        onSurface = Color(0xFF1B1B1B),
        primaryContainer = Color(0xFFB2DFDB),
        onPrimaryContainer = Color(0xFF08302D),
        secondaryContainer = Color(0xFFFFE0B2),
        onSecondaryContainer = Color(0xFF4A2E00)
      )
      MaterialTheme(colorScheme = brandColors) {
        AppUI()
      }
    }
    if (intent?.action == se.olle.rostbubbla.ACTIONS.ACTION_PTT) pttFlow.tryEmit(Unit)
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    if (intent.action == se.olle.rostbubbla.ACTIONS.ACTION_PTT) pttFlow.tryEmit(Unit)
  }
}

class PttReceiver : android.content.BroadcastReceiver() {
  override fun onReceive(context: android.content.Context, intent: android.content.Intent) {
    if (intent.action == se.olle.rostbubbla.ACTIONS.ACTION_PTT) {
      val i = android.content.Intent(context, MainActivity::class.java).apply {
        setAction(se.olle.rostbubbla.ACTIONS.ACTION_PTT)
        addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)
      }
      context.startActivity(i)
    }
  }
}

@OptIn(ExperimentalPermissionsApi::class, ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
fun AppUI(vm: MainViewModel = viewModel()) {
  val scope = rememberCoroutineScope()
  // UI state variables
  var result by remember { mutableStateOf("") }
  
  // Permissions
  val micPerm = rememberPermissionState(Manifest.permission.RECORD_AUDIO)
  val notifPerm = if (Build.VERSION.SDK_INT >= 33) rememberPermissionState(Manifest.permission.POST_NOTIFICATIONS) else null
  val multiPerms = rememberMultiplePermissionsState(
    permissions = buildList {
      add(Manifest.permission.RECORD_AUDIO)
      if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
    }
  )
  var requestedAll by remember { mutableStateOf(false) }

  var showAbout by remember { mutableStateOf(false) }
  var showAutoSteps by remember { mutableStateOf(false) }
  var showWelcome by remember { mutableStateOf(false) }
  var showAutoPromptInfo by remember { mutableStateOf(false) }
  var showGroundingInfo by remember { mutableStateOf(false) }
  var showThinkingInfo by remember { mutableStateOf(false) }
  var showOpenAIInfo by remember { mutableStateOf(false) }
  // Hoist info-bubble states for audio settings
  var showGainInfo by remember { mutableStateOf(false) }
  var showCarModeInfo by remember { mutableStateOf(false) }
  // Prompt-picker dialog state (declared early so other UI can use it)
  data class PromptPickState(val options: List<String>, val onPick: (String?) -> Unit)
  var pickState by remember { mutableStateOf<PromptPickState?>(null) }
  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text("TapScribe") },
        actions = {
          var menu by remember { mutableStateOf(false) }
          IconButton(onClick = { menu = true }) { Text("⋯") }
          DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text("Getting Started") }, onClick = { menu = false; showWelcome = true })
            DropdownMenuItem(text = { Text("About") }, onClick = { menu = false; showAbout = true })
          }
        }
      )
    }
  ) { pad ->
    var busy by remember { mutableStateOf(false) }
    var retryMessage by remember { mutableStateOf<String?>(null) }
    LazyColumn(Modifier.padding(pad).fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      item {
        val ctx = LocalContext.current
      val act = ctx as? MainActivity
      val startedHeadless = remember(act) { (act?.intent?.getBooleanExtra("headless", false) == true) }
      val prefs = remember { ctx.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE) }
      var apiKey by remember { mutableStateOf(prefs.getString("gemini_api_key", "") ?: "") }
      var openAIKey by remember { mutableStateOf(prefs.getString("openai_api_key", "") ?: "") }
      var useOpenAI by remember { mutableStateOf(prefs.getBoolean("use_openai_transcription", false)) }
      
      // All prompts come from DB (seeded first time) - declared early so auto-prompt can use it
      var customPrompts by remember { mutableStateOf<List<se.olle.rostbubbla.data.Prompt>>(emptyList()) }

      // Multiple permissions (mic + notifications). Overlay handled via Settings screen afterwards
      var overlayRequested by remember { mutableStateOf(false) }
      
      // Check if this is first time user
      LaunchedEffect(Unit) {
        val isFirstTime = !prefs.getBoolean("welcome_shown", false)
        if (isFirstTime) {
          showWelcome = true
          prefs.edit().putBoolean("welcome_shown", true).apply()
        }
      }

      // Quick Start Section - Main functionality
      Text("🎤 Quick Start", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
      
      Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(
          onClick = {
            if (!Settings.canDrawOverlays(ctx)) {
              ctx.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + ctx.packageName)))
            } else {
              try {
                ctx.startForegroundService(Intent(ctx, OverlayService::class.java))
              } catch (t: Throwable) {
                Toast.makeText(ctx, "Cannot start bubble: ${t.message}", Toast.LENGTH_SHORT).show()
              }
            }
          },
          modifier = Modifier.weight(1f)
        ) { Text("Start Bubble") }

        Button(
          onClick = { ctx.stopService(Intent(ctx, OverlayService::class.java)) },
          modifier = Modifier.weight(1f)
        ) { Text("Stop Bubble") }
      }

      Divider()

      // Advanced Settings - Collapsible section
      var showAdvancedSettings by remember { mutableStateOf(false) }
      Row(
        Modifier.fillMaxWidth().clickable { showAdvancedSettings = !showAdvancedSettings },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
      ) {
        Text("⚙️ Advanced Settings", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        Text(if (showAdvancedSettings) "▼" else "▶", style = MaterialTheme.typography.titleMedium)
      }

      if (showAdvancedSettings) {
        // API Keys
        Text("API Keys", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        
        OutlinedTextField(
          value = apiKey,
          onValueChange = {
            apiKey = it
            prefs.edit().putString("gemini_api_key", apiKey).apply()
          },
          label = { Text("Gemini API Key") },
          singleLine = true,
          modifier = Modifier.fillMaxWidth(),
          visualTransformation = PasswordVisualTransformation(),
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
        )

        OutlinedTextField(
          value = openAIKey,
          onValueChange = {
            openAIKey = it
            prefs.edit().putString("openai_api_key", openAIKey).apply()
          },
          label = { Text("OpenAI API Key") },
          singleLine = true,
          modifier = Modifier.fillMaxWidth(),
          visualTransformation = PasswordVisualTransformation(),
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
        )

        // OpenAI transcription switch (uses top-level showOpenAIInfo state)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
          Text("Use OpenAI for transcription (instead of Google)", modifier = Modifier.weight(1f))
          IconButton(onClick = { showOpenAIInfo = true }) { Icon(Icons.Outlined.Info, contentDescription = "Info") }
          Switch(checked = useOpenAI, onCheckedChange = {
            useOpenAI = it
            prefs.edit().putBoolean("use_openai_transcription", useOpenAI).apply()
          })
        }

        Divider()

        // Audio section
        Text("Audio", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        // Mic gain
        var gainMode by remember { mutableStateOf(prefs.getString("gain_mode", "Off") ?: "Off") }
        var manualGainDb by remember { mutableStateOf(prefs.getInt("manual_gain_db", 0)) }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
          Text("Mic gain", modifier = Modifier.weight(1f))
          IconButton(onClick = { showGainInfo = true }) { Icon(Icons.Outlined.Info, contentDescription = "Info") }
          var expanded by remember { mutableStateOf(false) }
          Box {
            OutlinedButton(onClick = { expanded = true }) { Text(gainMode) }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
              listOf("Off", "Auto", "Manual").forEach { opt ->
                DropdownMenuItem(text = { Text(opt) }, onClick = {
                  gainMode = opt
                  prefs.edit().putString("gain_mode", opt).apply()
                  expanded = false
                })
              }
            }
          }
        }
        if (gainMode == "Manual") {
          Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Mic boost (dB)", modifier = Modifier.weight(1f))
            // Simple stepper instead of Slider to keep diff minimal
            OutlinedButton(onClick = { manualGainDb = (manualGainDb - 1).coerceIn(0, 12); prefs.edit().putInt("manual_gain_db", manualGainDb).apply() }) { Text("-") }
            Text("$manualGainDb dB")
            OutlinedButton(onClick = { manualGainDb = (manualGainDb + 1).coerceIn(0, 12); prefs.edit().putInt("manual_gain_db", manualGainDb).apply() }) { Text("+") }
          }
        }

        // Car mode (auto mic gain on BT headset)
        var carMode by remember { mutableStateOf(prefs.getBoolean("car_mode_bt_auto", false)) }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
          Text("Car mode (auto mic gain on BT)", modifier = Modifier.weight(1f))
          IconButton(onClick = { showCarModeInfo = true }) { Icon(Icons.Outlined.Info, contentDescription = "Info") }
          Switch(checked = carMode, onCheckedChange = { on -> carMode = on; prefs.edit().putBoolean("car_mode_bt_auto", carMode).apply() })
        }

        // Section divider for clarity
        Divider()

        // Auto-paste setting
        var autoPaste by remember { mutableStateOf(prefs.getBoolean("auto_paste", false)) }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
          Text("Auto-paste in active text field", modifier = Modifier.weight(1f))
          IconButton(onClick = { showAutoSteps = true }) { Icon(Icons.Outlined.Info, contentDescription = "Help") }
          Switch(checked = autoPaste, onCheckedChange = {
            autoPaste = it
            prefs.edit().putBoolean("auto_paste", autoPaste).apply()
          })
        }

        // Auto-prompt setting
        var autoPromptEnabled by remember { mutableStateOf(prefs.getBoolean("auto_prompt_enabled", false)) }
        var defaultPromptTitle by remember { mutableStateOf(prefs.getString("auto_prompt_title", "") ?: "") }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
          Text("Always use selected prompt", modifier = Modifier.weight(1f))
          IconButton(onClick = { showAutoPromptInfo = true }) { Icon(Icons.Outlined.Info, contentDescription = "Help") }
          Switch(checked = autoPromptEnabled, onCheckedChange = {
            autoPromptEnabled = it
            prefs.edit().putBoolean("auto_prompt_enabled", autoPromptEnabled).apply()
          })
        }
        if (autoPromptEnabled) {
          Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Default prompt: ${if (defaultPromptTitle.isBlank()) "(not set)" else defaultPromptTitle}", modifier = Modifier.weight(1f))
            Button(onClick = {
              val all = customPrompts.map { it.title }
              val cont = CompletableDeferred<String?>()
              pickState = PromptPickState(options = all) { choice -> cont.complete(choice) }
              scope.launch {
                val picked = cont.await()
                if (picked != null) {
                  defaultPromptTitle = picked
                  prefs.edit().putString("auto_prompt_title", picked).apply()
                }
              }
            }) { Text("Change") }
          }
        }
      }

      Divider()

      // Prompts Section - Always visible
      Text("📝 Prompts", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)

      var selectedPrompt by remember { mutableStateOf<String?>(null) }

      // Edit dialog state (must be declared before first use)
      val showEditId = remember { mutableStateOf<se.olle.rostbubbla.data.Prompt?>(null) }
      var menuFor by remember { mutableStateOf<String?>(null) }

      // Context menu state for long press on chips (must be declared before use below)
      data class PromptMenu(val title: String, val isBuiltIn: Boolean)
      var promptMenu by remember { mutableStateOf<PromptMenu?>(null) }
      LaunchedEffect(Unit) {
        val seeded = prefs.getBoolean("prompts_seeded_v1", false)
        if (!seeded) {
          vm.upsertPromptByTitle(
            "Write Email",
            "You will receive text below that has been spoken and transcribed. This means some words may have been incorrectly transcribed and periods and commas may sometimes be in the wrong places. If it says -Smiley- or similar, it's likely that I want a smiley there. Your task: Based on the text. Follow the tone and flow of the text as well as you can but format it as a finished text for an email with good formatting, periods etc. in the right places. You may make small changes to make it grammatically correct or to remove words that don't seem logical or don't seem to belong to the email. If the user doesn't provide a closing greeting, always end with Best regards Olle.\n\nRespond ONLY with the email text",
            null
          )
          vm.upsertPromptByTitle(
            "WhatsApp",
            "write an informal message from the transcribed text below. it should be sent on whatsapp to friends family. keep in mind that the text has been transcribed and some word may have been wrong. try to understand the user's intention from the context if so. add some smiley (people and thumbs up not lots of symbols, cars etc) if and when it fits. try to write as close to what the user says otherwise. only start with hi/hey etc IF the user actually started that way.\n\nrespond only with whatsapp chat message - nothing else!",
            null
          )
          prefs.edit().putBoolean("prompts_seeded_v1", true).apply()
        }
        customPrompts = vm.prompts()
      }
      FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        customPrompts.forEach { item ->
          Box {
            FilterChip(
              selected = selectedPrompt == item.title,
              onClick = { selectedPrompt = item.title },
              label = {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                  Text(item.title)
                  Text("⋯", modifier = Modifier.clickable { menuFor = item.title })
                }
              }
            )
            DropdownMenu(expanded = menuFor == item.title, onDismissRequest = { menuFor = null }) {
            DropdownMenuItem(text = { Text("Edit") }, onClick = {
                showEditId.value = item
                menuFor = null
              })
            DropdownMenuItem(text = { Text("Delete") }, onClick = {
                scope.launch {
                  vm.deletePrompt(item)
                  customPrompts = vm.prompts()
                  if (selectedPrompt == item.title) selectedPrompt = null
                  menuFor = null
                }
              })
            }
          }
        }
      }

      // (All displayed above)

      var showAdd by remember { mutableStateOf(false) }
      Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(onClick = { showAdd = true }) { Text("Add Prompt") }
      }
      if (showAdd) {
        var t by remember { mutableStateOf("") }
        var s by remember { mutableStateOf("") }
        var useSearch by remember { mutableStateOf(false) }
        var thinkingEnabled by remember { mutableStateOf(false) }
        var useOpenAI by remember { mutableStateOf(false) }
        AlertDialog(
          onDismissRequest = { showAdd = false },
          confirmButton = {
            TextButton(onClick = {
              scope.launch {
                vm.addPromptExtended(t, s, null, useSearch, null, thinkingEnabled, useOpenAI)
                customPrompts = vm.prompts()
                showAdd = false
              }
            }) { Text("Save") }
          },
          dismissButton = { TextButton(onClick = { showAdd = false }) { Text("Cancel") } },
          title = { Text("New Prompt") },
          text = {
            LazyColumn(
              modifier = Modifier.heightIn(max = 400.dp).imePadding(),
              verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
              item { OutlinedTextField(t, { t = it }, label = { Text("Title") }, modifier = Modifier.fillMaxWidth()) }
              item { OutlinedTextField(s, { s = it }, label = { Text("System Instruction") }, modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp)) }
              item {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                  Text("Grounding with Google Search", modifier = Modifier.weight(1f))
                  Switch(checked = useSearch, onCheckedChange = { useSearch = it })
                  IconButton(onClick = { showGroundingInfo = true }) { Icon(Icons.Outlined.Info, contentDescription = "Info") }
                }
              }
              item {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                  Text("Thinking mode", modifier = Modifier.weight(1f))
                  Switch(checked = thinkingEnabled, onCheckedChange = { thinkingEnabled = it })
                  IconButton(onClick = { showThinkingInfo = true }) { Icon(Icons.Outlined.Info, contentDescription = "Info") }
                }
              }
              item {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                  Text("Use OpenAI transcription for this prompt", modifier = Modifier.weight(1f))
                  Switch(checked = useOpenAI, onCheckedChange = { useOpenAI = it })
                }
              }
            }
          }
        )
      }

      // Edit prompt (custom)
      showEditId.value?.let { editP ->
        var t by remember { mutableStateOf(editP.title) }
        var s by remember { mutableStateOf(editP.systemText) }
        var useSearch by remember { mutableStateOf(editP.useGoogleSearch) }
        var thinkingEnabled by remember { mutableStateOf(editP.thinkingEnabled) }
        var useOpenAI by remember { mutableStateOf(editP.useOpenAI) }
        AlertDialog(
          onDismissRequest = { showEditId.value = null },
          confirmButton = {
            TextButton(onClick = {
        scope.launch {
                vm.updatePrompt(editP.copy(title = t, systemText = s, useGoogleSearch = useSearch, thinkingEnabled = thinkingEnabled, thinkingBudget = null, useOpenAI = useOpenAI))
                customPrompts = vm.prompts()
                showEditId.value = null
              }
            }) { Text("Save") }
          },
          dismissButton = { TextButton(onClick = { showEditId.value = null }) { Text("Cancel") } },
          title = { Text("Edit Prompt") },
          text = {
            LazyColumn(
              modifier = Modifier.heightIn(max = 400.dp).imePadding(),
              verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
              item { OutlinedTextField(t, { t = it }, label = { Text("Title") }, modifier = Modifier.fillMaxWidth()) }
              item { OutlinedTextField(s, { s = it }, label = { Text("System Instruction") }, modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp)) }
              item {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                  Text("Grounding with Google Search", modifier = Modifier.weight(1f))
                  Switch(checked = useSearch, onCheckedChange = { useSearch = it })
                  IconButton(onClick = { showGroundingInfo = true }) { Icon(Icons.Outlined.Info, contentDescription = "Info") }
                }
              }
              item {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                  Text("Thinking mode", modifier = Modifier.weight(1f))
                  Switch(checked = thinkingEnabled, onCheckedChange = { thinkingEnabled = it })
                  IconButton(onClick = { showThinkingInfo = true }) { Icon(Icons.Outlined.Info, contentDescription = "Info") }
                }
              }
              item {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                  Text("Use OpenAI transcription for this prompt", modifier = Modifier.weight(1f))
                  Switch(checked = useOpenAI, onCheckedChange = { useOpenAI = it })
                }
              }
            }
          }
        )
      }

      // (Inga inbyggda specialfall – allt lever i DB)

      // Context menu via AlertDialog removed – per-chip menu is used

      suspend fun runFlow() {
        // Stop the loop: run only one segment per tap
        vm.moreSegmentsDecider = { _, _ -> false }
        if (!micPerm.status.isGranted) {
          Toast.makeText(ctx, "Microphone permission missing", Toast.LENGTH_SHORT).show()
          return
        }
        if (!android.speech.SpeechRecognizer.isRecognitionAvailable(ctx)) {
          Toast.makeText(ctx, "Speech recognition not available on device", Toast.LENGTH_SHORT).show()
          return
        }
        if (apiKey.isBlank()) {
          Toast.makeText(ctx, "Please enter API key first", Toast.LENGTH_SHORT).show()
          return
        }
        try {
          vm.capture(1)
        } catch (t: Throwable) {
          Toast.makeText(ctx, "Recording error: ${t.message}", Toast.LENGTH_SHORT).show()
          return
        }
        if (vm.rawText.isBlank()) {
          Toast.makeText(ctx, "No voice captured", Toast.LENGTH_SHORT).show()
          return
        }
        val custom = customPrompts.firstOrNull { it.title == selectedPrompt }
        val promptText = custom?.systemText ?: "summarize in bullet points"
        val p = se.olle.rostbubbla.data.Prompt(title = selectedPrompt ?: "Selected", systemText = promptText, vehikel = custom?.vehikel)
        busy = true
        retryMessage = null
        result = vm.callGemini(p, apiKey) { att -> retryMessage = "Retrying… ($att)" }
        busy = false
        if (result.isBlank() || result.startsWith("Fel vid AI-anrop:")) {
          Toast.makeText(ctx, "AI response empty or error", Toast.LENGTH_SHORT).show()
        } else {
          val cm = ctx.getSystemService(android.content.ClipboardManager::class.java)
          cm.setPrimaryClip(android.content.ClipData.newPlainText("AI", result))
          // Let the system clipboard notification suffice
        }
      }

      // Prompt picker UI
      pickState?.let { st ->
        AlertDialog(
          onDismissRequest = { st.onPick(null); pickState = null },
          confirmButton = {},
          dismissButton = { TextButton(onClick = { st.onPick(null); pickState = null }) { Text("Cancel") } },
          title = { Text("Select Prompt") },
          text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
              st.options.forEach { opt ->
                Button(onClick = { st.onPick(opt); pickState = null }) { Text(opt) }
              }
            }
          }
        )
      }

      LaunchedEffect(act) {
        act?.pttFlow?.collect {
          // Bubble flow: 1) record 2) pick prompt (dialog) 3) Gemini
          vm.moreSegmentsDecider = { _, _ -> false }
          if (!micPerm.status.isGranted) {
            Toast.makeText(ctx, "Microphone permission missing", Toast.LENGTH_SHORT).show(); return@collect
          }
          if (!android.speech.SpeechRecognizer.isRecognitionAvailable(ctx)) {
            Toast.makeText(ctx, "Speech recognition not available on device", Toast.LENGTH_SHORT).show(); return@collect
          }
          if (apiKey.isBlank()) {
            Toast.makeText(ctx, "Please enter API key first", Toast.LENGTH_SHORT).show(); return@collect
          }
          try { vm.capture(1) } catch (t: Throwable) {
            Toast.makeText(ctx, "Recording error: ${t.message}", Toast.LENGTH_SHORT).show(); return@collect
          }
          if (vm.rawText.isBlank()) { Toast.makeText(ctx, "No voice captured", Toast.LENGTH_SHORT).show(); return@collect }

          val all = customPrompts.map { it.title }
          val cont = CompletableDeferred<String?>()
          pickState = PromptPickState(options = all) { choice -> cont.complete(choice) }
          val picked = cont.await()
          val custom = customPrompts.firstOrNull { it.title == picked }
          val promptText = custom?.systemText ?: "summarize in bullet points"
          val p = se.olle.rostbubbla.data.Prompt(title = picked ?: "Selected", systemText = promptText, vehikel = custom?.vehikel)
          result = vm.callGemini(p, apiKey)
          if (result.isBlank() || result.startsWith("Fel vid AI-anrop:")) {
            Toast.makeText(ctx, "AI response empty or error", Toast.LENGTH_SHORT).show()
          } else {
            val cm = ctx.getSystemService(android.content.ClipboardManager::class.java)
            cm.setPrimaryClip(android.content.ClipData.newPlainText("AI", result))
            // Let the system clipboard notification suffice
          }
          // Started from bubble? Stay in background, do not take focus
          if (startedHeadless) {
            act?.finish()
          }
        }
      }

      // Listen button removed – mic in bubble is used instead

      if (busy) {
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        CircularProgressIndicator(strokeWidth = 3.dp, modifier = Modifier.size(20.dp))
        Text(retryMessage ?: "Working…", style = MaterialTheme.typography.bodySmall)
      }
      }
      if (result.isNotBlank()) {
      OutlinedTextField(value = result, onValueChange = {}, modifier = Modifier.fillMaxWidth().height(160.dp), label = { Text("Result") })
      }

      // Copy/paste buttons removed – bubble flow handles this
      }
    }
  }
  if (showAbout) {
    val ctx = LocalContext.current
    AlertDialog(
      onDismissRequest = { showAbout = false },
      confirmButton = { TextButton(onClick = { showAbout = false }) { Text("OK") } },
      title = { Text("About TapScribe") },
      text = {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
          // Centrerad logga
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center
          ) {
            Image(
              painter = painterResource(id = R.drawable.aiolle_logo),
              contentDescription = "Aiolle Logo",
              modifier = Modifier.size(80.dp),
              contentScale = ContentScale.Fit
            )
          }
          
          LazyColumn(
            modifier = Modifier.heightIn(max = 400.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
          ) {
            item {
              Text("🎤 TapScribe – voice to AI assistant with floating mic bubble.", 
                   style = MaterialTheme.typography.bodyMedium)
            }
            
            item { Divider() }
            
            item {
              Text("👨‍💻 Created by", style = MaterialTheme.typography.titleSmall)
            }
            item { 
              Text("Olle Söderqvist", 
                   style = MaterialTheme.typography.bodyLarge,
                   color = MaterialTheme.colorScheme.primary)
            }
            
            item { Divider() }
            
            item {
              Text("📞 Contact", style = MaterialTheme.typography.titleSmall)
            }
            
            // LinkedIn länk
            item {
              val linkStyle = SpanStyle(color = MaterialTheme.colorScheme.primary, textDecoration = TextDecoration.Underline)
              val linkedInText = buildAnnotatedString {
                append("💼 LinkedIn: ")
                pushStringAnnotation(tag = "URL", annotation = "https://www.linkedin.com/in/olle-soderqvist/")
                withStyle(linkStyle) { append("linkedin.com/in/olle-soderqvist") }
                pop()
              }
              ClickableText(text = linkedInText, onClick = { off ->
                linkedInText.getStringAnnotations("URL", off, off).firstOrNull()?.let { ann ->
                  try { ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(ann.item))) } catch (_: Throwable) {}
                }
              })
            }

            // Website länk
            item {
              val linkStyle = SpanStyle(color = MaterialTheme.colorScheme.primary, textDecoration = TextDecoration.Underline)
              val siteText = buildAnnotatedString {
                append("🌐 Website: ")
                pushStringAnnotation(tag = "URL", annotation = "https://aiolle.se")
                withStyle(linkStyle) { append("aiolle.se") }
                pop()
              }
              ClickableText(text = siteText, onClick = { off ->
                siteText.getStringAnnotations("URL", off, off).firstOrNull()?.let { ann ->
                  try { ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(ann.item))) } catch (_: Throwable) {}
                }
              })
            }

            // Privacy policy länk
            item {
              val linkStyle = SpanStyle(color = MaterialTheme.colorScheme.primary, textDecoration = TextDecoration.Underline)
              val policy = buildAnnotatedString {
                val url = "https://raw.githubusercontent.com/Olleman82/TapScribe/main/PRIVACY_POLICY.md"
                pushStringAnnotation(tag = "URL", annotation = url)
                withStyle(linkStyle) { append("🔒 Privacy policy") }
                pop()
              }
              ClickableText(text = policy, onClick = { off ->
                policy.getStringAnnotations("URL", off, off).firstOrNull()?.let { ann ->
                  try { ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(ann.item))) } catch (_: Throwable) {}
                }
              })
            }
          }
        }
      }
    )
  }

  if (showAutoSteps) {
    val ctx = LocalContext.current
    AlertDialog(
      onDismissRequest = { showAutoSteps = false },
      confirmButton = {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          TextButton(onClick = {
            try { ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + ctx.packageName))) } catch (_: Throwable) {}
          }) { Text("Open App Settings") }
          TextButton(onClick = {
            try { ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) } catch (_: Throwable) {}
          }) { Text("Open Accessibility") }
          TextButton(onClick = { showAutoSteps = false }) { Text("Close") }
        }
      },
      title = { Text("Enable Auto-Paste") },
      text = {
        LazyColumn(
          modifier = Modifier.heightIn(max = 400.dp),
          verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
          item {
            Text("🔒 Important: Accessibility Service Information", 
                 style = MaterialTheme.typography.titleMedium,
                 color = MaterialTheme.colorScheme.primary)
          }
          
          item {
            Text("TapScribe uses Android Accessibility Services to automatically paste AI-generated text into the focused text field.")
          }
          
          item {
            Text("📋 What the service does:", style = MaterialTheme.typography.titleSmall)
          }
          item { Text("• Identifies the text field that has focus") }
          item { Text("• Pastes AI responses directly into the field") }
          item { Text("• Saves you from manual copy/paste operations") }
          item { Text("• Works with any app that has text input fields") }
          
          item {
            Text("🔍 Data usage:", style = MaterialTheme.typography.titleSmall)
          }
          item { Text("• Only text content that you dictate") }
          item { Text("• No personal data is collected or shared") }
          item { Text("• All data is processed locally and via Gemini API") }
          
          item {
            Text("⚙️ Activation steps for Android 13+:", style = MaterialTheme.typography.titleSmall)
          }
          item { Text("1) Open app settings (⋯ → Allow restricted settings)") }
          item { Text("2) Go to Accessibility → Installed services") }
          item { Text("3) Enable 'TapScribe: Paste in focused field'") }
          
          item {
            Text("By enabling this service, you consent to TapScribe using Accessibility Services to improve your user experience.", 
                 style = MaterialTheme.typography.bodySmall,
                 color = MaterialTheme.colorScheme.onSurfaceVariant)
          }
        }
      }
    )
  }

  if (showAutoPromptInfo) {
    AlertDialog(
      onDismissRequest = { showAutoPromptInfo = false },
      confirmButton = { TextButton(onClick = { showAutoPromptInfo = false }) { Text("OK") } },
      title = { Text("Auto-Prompt Feature") },
      text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          Text("🚀 What is auto-prompt:", style = MaterialTheme.typography.titleSmall)
          Text("• Normally, you choose a prompt each time you use the bubble")
          Text("• With auto-prompt enabled, one prompt is used automatically")
          Text("• Perfect for hands-free operation when you always use the same prompt")
          
          Text("⚙️ How to set it up:", style = MaterialTheme.typography.titleSmall)
          Text("• Enable 'Always use selected prompt'")
          Text("• Tap 'Change' to select your default prompt")
          Text("• The selected prompt will be used every time you tap the bubble")
          
          Text("💡 Use cases:", style = MaterialTheme.typography.titleSmall)
          Text("• Email writing: Always use your 'Write Email' prompt")
          Text("• Quick notes: Always use a 'Summarize' prompt")
          Text("• Social media: Always use your 'WhatsApp' prompt")
          
          Text("🔄 To change prompts:", style = MaterialTheme.typography.titleSmall)
          Text("• Disable auto-prompt to return to manual selection")
          Text("• Or change the default prompt using the 'Change' button")
        }
      }
    )
  }

  if (showGroundingInfo) {
    AlertDialog(
      onDismissRequest = { showGroundingInfo = false },
      confirmButton = { TextButton(onClick = { showGroundingInfo = false }) { Text("OK") } },
      title = { Text("Grounding with Google Search") },
      text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          Text("When enabled, the model may use Google Search to ground its answer with sources.")
          Text("This can improve factuality for tasks like research or drafting a LinkedIn post.")
          Text("Note: responses may take longer when grounding is enabled.", style = MaterialTheme.typography.bodySmall)
        }
      }
    )
  }

  if (showThinkingInfo) {
    AlertDialog(
      onDismissRequest = { showThinkingInfo = false },
      confirmButton = { TextButton(onClick = { showThinkingInfo = false }) { Text("OK") } },
      title = { Text("Thinking mode") },
      text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          Text("Let the model internally reason more before answering.")
          Text("When enabled without a budget, the model uses an automatic budget per request (if the model supports it).", style = MaterialTheme.typography.bodySmall)
          Text("Use for complex tasks where quality matters more than speed.", style = MaterialTheme.typography.bodySmall)
        }
      }
    )
  }

  if (showOpenAIInfo) {
    AlertDialog(
      onDismissRequest = { showOpenAIInfo = false },
      confirmButton = { TextButton(onClick = { showOpenAIInfo = false }) { Text("OK") } },
      title = { Text("OpenAI Realtime API") },
      text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          Text("🎯 What is OpenAI Realtime API:", style = MaterialTheme.typography.titleSmall)
          Text("• Replaces Google Speech Recognition with OpenAI's advanced speech-to-text")
          Text("• Better accuracy and lower latency for Swedish language")
          Text("• Real-time transcription with partial results")
          
          Text("💰 Costs:", style = MaterialTheme.typography.titleSmall)
          Text("• Approximately $1 per hour of spoken audio")
          Text("• Paid per usage through your OpenAI account")
          Text("• No monthly fees or binding contracts")
          
          Text("🔧 Technical advantages:", style = MaterialTheme.typography.titleSmall)
          Text("• GPT-4o-transcribe model for highest accuracy")
          Text("• WebRTC connection for low latency")
          Text("• Manual control over recording start/stop")
          Text("• Visual feedback with audio waves")
          
          Text("⚠️ Requirements:", style = MaterialTheme.typography.titleSmall)
          Text("• Valid OpenAI API key")
          Text("• Internet connection during recording")
          Text("• Microphone permission (same as Google Speech)")
        }
      }
    )
  }

  // Info bubbles for new audio settings
  if (showGainInfo) {
    AlertDialog(
      onDismissRequest = { showGainInfo = false },
      confirmButton = { TextButton(onClick = { showGainInfo = false }) { Text("OK") } },
      title = { Text("Mic gain") },
      text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          Text("Auto: Gentle automatic level up to +12 dB, avoids clipping.")
          Text("Manual: Fixed boost (0–12 dB). Use if Auto is too low or pumps.")
          Text("Off: No software gain. Good if your device AGC is already strong.", style = MaterialTheme.typography.bodySmall)
        }
      }
    )
  }

  if (showCarModeInfo) {
    AlertDialog(
      onDismissRequest = { showCarModeInfo = false },
      confirmButton = { TextButton(onClick = { showCarModeInfo = false }) { Text("OK") } },
      title = { Text("Car mode (BT auto)") },
      text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          Text("When a BT headset is connected, automatic mic gain is enabled.")
          Text("This boosts levels in noisy environments without changing the mic profile.")
        }
      }
    )
  }

  // Welcome dialog for first-time users
  if (showWelcome) {
    val ctx = LocalContext.current
    AlertDialog(
      onDismissRequest = { showWelcome = false },
      confirmButton = {},
      dismissButton = {},
      text = {
        // After runtime permissions resolve, trigger overlay permission (last)
        LaunchedEffect(requestedAll, multiPerms.permissions) {
          if (requestedAll) {
            if (!Settings.canDrawOverlays(ctx)) {
              try { ctx.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + ctx.packageName))) } catch (_: Throwable) {}
            }
          }
        }
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
          LazyColumn(
            modifier = Modifier.heightIn(max = 400.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
          ) {
            item {
              Text("🚀 Quick Setup Guide", 
                   style = MaterialTheme.typography.titleMedium,
                   color = MaterialTheme.colorScheme.primary)
            }
            
            item {
              Text("📝 Get your Gemini API key:", style = MaterialTheme.typography.titleSmall)
            }
            item { Text("• Go to https://aistudio.google.com") }
            item { Text("• Log in with your Google account") }
            item { Text("• Click 'Get API key' and create new key") }
            item { Text("• Copy and paste the key in the field above") }
            
            item {
              Text("🔐 Request permissions:", style = MaterialTheme.typography.titleSmall)
            }
            item { Text("• Tap 'Request All Permissions' button below") }
            item { Text("• Grant microphone and notification access") }
            item { Text("• Allow 'Display over other apps' permission") }
            item { Text("• This enables the floating bubble to work") }
            
            item {
              Text("♿ Optional - Enable Auto-Paste:", style = MaterialTheme.typography.titleSmall)
            }
            item { Text("• Tap 'Accessibility Settings' below") }
            item { Text("• Find 'TapScribe: Paste in focused field'") }
            item { Text("• Enable the service for automatic pasting") }
            
            item {
              Text("✨ You're all set!", style = MaterialTheme.typography.titleSmall)
            }
            item { Text("• Add and edit your own prompts in the Prompts section") }
            item { Text("• Start the floating bubble to begin using TapScribe") }
            item { Text("• Long-press the bubble for more options") }
            item { Divider() }
            item { Text("Privacy & Gemini:", style = MaterialTheme.typography.titleSmall) }
            item {
              Text("If you use a free Gemini key via AI Studio, your text may be used by Google to improve models under their terms. Avoid sharing sensitive information.", style = MaterialTheme.typography.bodySmall)
            }
            item {
              val linkStyle = SpanStyle(color = MaterialTheme.colorScheme.primary, textDecoration = TextDecoration.Underline)
              val policy = buildAnnotatedString {
                append("Learn more: ")
                pushStringAnnotation(tag = "URL", annotation = "https://raw.githubusercontent.com/Olleman82/TapScribe/main/PRIVACY_POLICY.md")
                withStyle(linkStyle) { append("Privacy policy") }
                pop()
              }
              val ctx = LocalContext.current
              ClickableText(text = policy, onClick = { off ->
                policy.getStringAnnotations("URL", off, off).firstOrNull()?.let { ann ->
                  try { ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(ann.item))) } catch (_: Throwable) {}
                }
              })
            }
          }
          
          // Separera knapparna från huvudinnehållet för bättre layout
          Divider()
          
          // Knappar i en egen sektion med bättre spacing
          Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
          ) {
            Button(
              onClick = {
                requestedAll = true
                multiPerms.launchMultiplePermissionRequest()
              },
              modifier = Modifier.fillMaxWidth()
            ) { 
              Text("Request All Permissions") 
            }
            
            OutlinedButton(
              onClick = {
                try { ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) } catch (_: Throwable) {}
              },
              modifier = Modifier.fillMaxWidth()
            ) { 
              Text("Accessibility Settings") 
            }
            
            // Centered "Got it!" button
            Button(
              onClick = { showWelcome = false },
              modifier = Modifier.fillMaxWidth(),
              colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
              )
            ) { 
              Text("Got it! ✨", style = MaterialTheme.typography.titleMedium)
            }
          }
        }
      },
      title = { Text("Welcome to TapScribe! 🎤") }
    )
  }
}
