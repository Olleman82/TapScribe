
package se.olle.rostbubbla.ui

import android.Manifest
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.foundation.background
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
import androidx.core.content.FileProvider
import se.olle.rostbubbla.debug.DebugLogger
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Info
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.annotation.StringRes
import kotlinx.coroutines.flow.MutableSharedFlow
import retrofit2.HttpException
import kotlinx.coroutines.CompletableDeferred
import com.google.accompanist.permissions.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import se.olle.rostbubbla.access.PasteAccessibilityService
import se.olle.rostbubbla.overlay.OverlayService
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Image
import androidx.compose.ui.layout.ContentScale
import se.olle.rostbubbla.R
import se.olle.rostbubbla.AiModelConfig
import se.olle.rostbubbla.AppLanguage
import se.olle.rostbubbla.PREF_APP_LANGUAGE
import se.olle.rostbubbla.applyAppLanguage

class MainActivity : AppCompatActivity() {
  val pttFlow = MutableSharedFlow<Unit>(extraBufferCapacity = 1)


  override fun onCreate(savedInstanceState: Bundle?) {
    // Apply saved app language before rendering UI
    runCatching {
      val prefVal = getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)
        .getString(PREF_APP_LANGUAGE, AppLanguage.SYSTEM.prefValue)
      applyAppLanguage(AppLanguage.fromPref(prefVal))
    }
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

@StringRes
private fun gainModeLabelRes(value: String): Int = when (value) {
  "Manual" -> R.string.gain_mode_manual
  "Auto" -> R.string.gain_mode_auto
  else -> R.string.gain_mode_off
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
  var showAccessibilityConsent by remember { mutableStateOf(false) }
  var showWelcome by remember { mutableStateOf(false) }
  var showAutoPromptInfo by remember { mutableStateOf(false) }
  var showGroundingInfo by remember { mutableStateOf(false) }
  var showThinkingInfo by remember { mutableStateOf(false) }
  var showOpenAIInfo by remember { mutableStateOf(false) }
  // Hoist info-bubble states for audio settings
  var showGainInfo by remember { mutableStateOf(false) }
  var showCarModeInfo by remember { mutableStateOf(false) }
  var showMailInfo by remember { mutableStateOf(false) }
  var showMemoryInfo by remember { mutableStateOf(false) }
  var showAddMemory by remember { mutableStateOf(false) }
  var showEditMemory by remember { mutableStateOf<se.olle.rostbubbla.data.MemoryItem?>(null) }
  // Prompt-picker dialog state (declared early so other UI can use it)
  data class PromptPickState(val options: List<String>, val onPick: (String?) -> Unit)
  var pickState by remember { mutableStateOf<PromptPickState?>(null) }
  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text(stringResource(R.string.app_name)) },
        actions = {
          var menu by remember { mutableStateOf(false) }
          IconButton(onClick = { menu = true }) { Text("⋯") }
          DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(
              text = { Text(stringResource(R.string.menu_getting_started)) },
              onClick = { menu = false; showWelcome = true }
            )
            DropdownMenuItem(
              text = { Text(stringResource(R.string.menu_about)) },
              onClick = { menu = false; showAbout = true }
            )
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
      var selectedLanguage by remember {
        mutableStateOf(AppLanguage.fromPref(prefs.getString(PREF_APP_LANGUAGE, AppLanguage.SYSTEM.prefValue)))
      }
      LaunchedEffect(selectedLanguage) {
        applyAppLanguage(selectedLanguage)
      }
      var apiKey by remember { mutableStateOf(prefs.getString("gemini_api_key", "") ?: "") }
      var openrouterKey by remember { mutableStateOf(prefs.getString("openrouter_api_key", "") ?: "") }
      var apiProvider by remember {
        mutableStateOf(
          prefs.getString(AiModelConfig.PREF_API_PROVIDER, AiModelConfig.API_PROVIDER_GOOGLE) ?: AiModelConfig.API_PROVIDER_GOOGLE
        )
      }
      var openAIKey by remember { mutableStateOf(prefs.getString("openai_api_key", "") ?: "") }
      var useOpenAI by remember { mutableStateOf(prefs.getBoolean("use_openai_transcription", false)) }
      var geminiModel by remember {
        mutableStateOf(
          AiModelConfig.normalizeGeminiModel(
            prefs.getString(AiModelConfig.PREF_GEMINI_MODEL, AiModelConfig.GEMINI_2_5_FLASH)
          )
        )
      }
      
      // All prompts come from DB (seeded first time) - declared early so auto-prompt can use it
      var customPrompts by remember { mutableStateOf<List<se.olle.rostbubbla.data.Prompt>>(emptyList()) }

      // Memory items from DB
      var memoryItems by remember { mutableStateOf<List<se.olle.rostbubbla.data.MemoryItem>>(emptyList()) }

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
      Text(
        stringResource(R.string.section_quick_start),
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary
      )
      
      Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(
          onClick = {
            if (!Settings.canDrawOverlays(ctx)) {
              ctx.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + ctx.packageName)))
            } else {
              try {
                ctx.startForegroundService(Intent(ctx, OverlayService::class.java))
              } catch (t: Throwable) {
                val message = t.message ?: t::class.java.simpleName
                Toast.makeText(
                  ctx,
                  ctx.getString(R.string.toast_cannot_start_bubble, message),
                  Toast.LENGTH_SHORT
                ).show()
              }
            }
          },
          modifier = Modifier.weight(1f)
        ) { Text(stringResource(R.string.button_start_bubble)) }

        Button(
          onClick = { ctx.stopService(Intent(ctx, OverlayService::class.java)) },
          modifier = Modifier.weight(1f)
        ) { Text(stringResource(R.string.button_stop_bubble)) }
      }

      Divider()

      // Memory section
      Text(
        stringResource(R.string.memory_section_title),
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary
      )

      var showMemoryManagement by remember { mutableStateOf(false) }
      
      Button(
          onClick = { showMemoryManagement = true },
          modifier = Modifier.fillMaxWidth()
      ) { 
          Text(stringResource(R.string.memory_button_manage)) 
      }
      
      if (showMemoryManagement) {
        var memoryItemsList by remember { mutableStateOf<List<se.olle.rostbubbla.data.MemoryItem>>(emptyList()) }
        var showMemoryList by remember { mutableStateOf(false) }
        var memoryMenuFor by remember { mutableStateOf<Long?>(null) }
        
        LaunchedEffect(showMemoryManagement, showMemoryList) {
          if (showMemoryManagement) {
            memoryItemsList = vm.memoryItems()
          }
        }
        
        AlertDialog(
          onDismissRequest = { showMemoryManagement = false },
          confirmButton = {
             TextButton(onClick = { showMemoryManagement = false }) { Text(stringResource(R.string.button_close)) }
          },
          title = { Text(stringResource(R.string.memory_dialog_manage_title)) },
          text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                if (!showMemoryList) {
                    // Initial menu: Add or View list
                    Button(
                        onClick = { showAddMemory = true; showMemoryManagement = false },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                        Text(stringResource(R.string.memory_button_add))
                    }
                    
                    Button(
                        onClick = { showMemoryList = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.List, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                        Text(stringResource(R.string.memory_button_view_list))
                    }
                } else {
                    // Memory list view
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            stringResource(R.string.memory_list_title),
                            style = MaterialTheme.typography.titleMedium
                        )
                        TextButton(onClick = { showMemoryList = false }) {
                            Text(stringResource(R.string.memory_button_back))
                        }
                    }
                    
                    Divider()
                    
                    LazyColumn(
                        modifier = Modifier.heightIn(max = 400.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(memoryItemsList) { item ->
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            item.title,
                                            style = MaterialTheme.typography.titleSmall,
                                            modifier = Modifier.weight(1f)
                                        )
                                        Box {
                                            IconButton(onClick = { memoryMenuFor = item.id }) {
                                                Icon(Icons.Default.MoreVert, contentDescription = "Menu")
                                            }
                                            DropdownMenu(
                                                expanded = memoryMenuFor == item.id,
                                                onDismissRequest = { memoryMenuFor = null }
                                            ) {
                                                DropdownMenuItem(
                                                    text = { Text(stringResource(R.string.menu_prompt_edit)) },
                                                    onClick = {
                                                        showEditMemory = item
                                                        showMemoryManagement = false
                                                        memoryMenuFor = null
                                                    }
                                                )
                                                DropdownMenuItem(
                                                    text = { Text(stringResource(R.string.menu_prompt_delete)) },
                                                    onClick = {
                                                        scope.launch {
                                                            vm.deleteMemoryItem(item)
                                                            memoryItemsList = vm.memoryItems()
                                                            memoryMenuFor = null
                                                        }
                                                    }
                                                )
                                            }
                                        }
                                    }
                                    Text(
                                        item.content,
                                        style = MaterialTheme.typography.bodySmall,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }
          }
        )
      }

      if (showAddMemory) {
        var title by remember { mutableStateOf("") }
        var content by remember { mutableStateOf("") }
        AlertDialog(
          onDismissRequest = { showAddMemory = false },
          confirmButton = {
            TextButton(onClick = {
              scope.launch {
                vm.addMemoryItem(title, content)
                memoryItems = vm.memoryItems()
                showAddMemory = false
              }
            }) { Text(stringResource(R.string.button_save)) }
          },
          dismissButton = { TextButton(onClick = { showAddMemory = false }) { Text(stringResource(R.string.button_cancel)) } },
          title = { Text(stringResource(R.string.memory_dialog_add_title)) },
          text = {
            LazyColumn(
              modifier = Modifier.heightIn(max = 400.dp).imePadding(),
              verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
              item {
                OutlinedTextField(
                  title,
                  { title = it },
                  label = { Text(stringResource(R.string.memory_field_title)) },
                  modifier = Modifier.fillMaxWidth()
                )
              }
              item {
                OutlinedTextField(
                  content,
                  { content = it },
                  label = { Text(stringResource(R.string.memory_field_content)) },
                  modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp)
                )
              }
            }
          }
        )
      }

      Divider()

      // Debug Section
      val debugMode = prefs.getBoolean("debug_mode", false)
      if (debugMode) {
          Text(
              text = stringResource(R.string.section_debug),
              style = MaterialTheme.typography.titleMedium,
              color = MaterialTheme.colorScheme.primary,
              modifier = Modifier.padding(top = 16.dp, bottom = 8.dp)
          )
        
          Text(
              text = stringResource(R.string.debug_logs_info),
              style = MaterialTheme.typography.bodySmall,
              modifier = Modifier.padding(bottom = 8.dp)
          )

          val logsTitle = stringResource(R.string.debug_share_logs)

          Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
              Button(onClick = {
                  val file = DebugLogger.getLogFile(ctx)
                  if (file != null) {
                      val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.provider", file)
                      val intent = Intent(Intent.ACTION_SEND).apply {
                          type = "text/plain"
                          putExtra(Intent.EXTRA_STREAM, uri as android.os.Parcelable)
                          addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                      }
                      ctx.startActivity(Intent.createChooser(intent, logsTitle))
                  } else {
                      Toast.makeText(ctx, "No logs found", Toast.LENGTH_SHORT).show()
                  }
              }) {
                  Text(stringResource(R.string.debug_share_logs))
              }
              OutlinedButton(onClick = {
                  DebugLogger.clearLogs(ctx)
                  Toast.makeText(ctx, ctx.getString(R.string.debug_logs_cleared), Toast.LENGTH_SHORT).show()
              }) {
                  Text(stringResource(R.string.debug_clear_logs))
              }

          }
          Divider()
      }

      // Advanced Settings - Collapsible section
      var showAdvancedSettings by remember { mutableStateOf(false) }
      Row(
        Modifier.fillMaxWidth().clickable { showAdvancedSettings = !showAdvancedSettings },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
      ) {
        Text(
          stringResource(R.string.section_advanced_settings),
          style = MaterialTheme.typography.titleMedium,
          modifier = Modifier.weight(1f)
        )
        Text(if (showAdvancedSettings) "▼" else "▶", style = MaterialTheme.typography.titleMedium)
      }

      if (showAdvancedSettings) {
        Text(
          stringResource(R.string.section_localization),
          style = MaterialTheme.typography.titleSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        var languageMenuExpanded by remember { mutableStateOf(false) }
        Row(
          Modifier.fillMaxWidth(),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
          Text(stringResource(R.string.label_app_language), modifier = Modifier.weight(1f))
          OutlinedButton(onClick = { languageMenuExpanded = true }) {
            Text(stringResource(selectedLanguage.labelRes))
          }
          DropdownMenu(expanded = languageMenuExpanded, onDismissRequest = { languageMenuExpanded = false }) {
            AppLanguage.entries.forEach { option ->
              DropdownMenuItem(
                text = { Text(stringResource(option.labelRes)) },
                onClick = {
                  languageMenuExpanded = false
                  selectedLanguage = option
                  prefs.edit().putString(PREF_APP_LANGUAGE, option.prefValue).apply()
                  applyAppLanguage(option)
                  (ctx as? MainActivity)?.recreate()
                }
              )
            }
          }
        }

        Text(
          stringResource(R.string.language_setting_description),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Divider()

        // API Keys
        Text(
          stringResource(R.string.section_api_keys),
          style = MaterialTheme.typography.titleSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        OutlinedTextField(
          value = apiKey,
          onValueChange = {
            apiKey = it
            prefs.edit().putString("gemini_api_key", apiKey).apply()
          },
          label = { Text(stringResource(R.string.label_gemini_api_key)) },
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
          label = { Text(stringResource(R.string.label_openai_api_key)) },
          singleLine = true,
          modifier = Modifier.fillMaxWidth(),
          visualTransformation = PasswordVisualTransformation(),
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
        )

        OutlinedTextField(
          value = openrouterKey,
          onValueChange = {
            openrouterKey = it
            prefs.edit().putString("openrouter_api_key", openrouterKey).apply()
          },
          label = { Text(stringResource(R.string.label_openrouter_api_key)) },
          singleLine = true,
          modifier = Modifier.fillMaxWidth(),
          visualTransformation = PasswordVisualTransformation(),
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
        )

        var apiProviderMenuExpanded by remember { mutableStateOf(false) }
        Row(
          Modifier.fillMaxWidth(),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
          Text(stringResource(R.string.label_api_provider), modifier = Modifier.weight(1f))
          Box {
            OutlinedButton(onClick = { apiProviderMenuExpanded = true }) {
              Text(
                if (apiProvider == AiModelConfig.API_PROVIDER_OPENROUTER)
                  stringResource(R.string.api_provider_openrouter)
                else
                  stringResource(R.string.api_provider_google)
              )
            }
            DropdownMenu(
              expanded = apiProviderMenuExpanded,
              onDismissRequest = { apiProviderMenuExpanded = false }
            ) {
              DropdownMenuItem(
                text = { Text(stringResource(R.string.api_provider_google)) },
                onClick = {
                  apiProvider = AiModelConfig.API_PROVIDER_GOOGLE
                  prefs.edit().putString(AiModelConfig.PREF_API_PROVIDER, AiModelConfig.API_PROVIDER_GOOGLE).apply()
                  apiProviderMenuExpanded = false
                }
              )
              DropdownMenuItem(
                text = { Text(stringResource(R.string.api_provider_openrouter)) },
                onClick = {
                  apiProvider = AiModelConfig.API_PROVIDER_OPENROUTER
                  prefs.edit().putString(AiModelConfig.PREF_API_PROVIDER, AiModelConfig.API_PROVIDER_OPENROUTER).apply()
                  apiProviderMenuExpanded = false
                }
              )
            }
          }
        }

        var geminiModelMenuExpanded by remember { mutableStateOf(false) }
        Row(
          Modifier.fillMaxWidth(),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
          Text(stringResource(R.string.label_gemini_model), modifier = Modifier.weight(1f))
          Box {
            OutlinedButton(onClick = { geminiModelMenuExpanded = true }) {
              Text(AiModelConfig.geminiModelLabel(geminiModel))
            }
            DropdownMenu(
              expanded = geminiModelMenuExpanded,
              onDismissRequest = { geminiModelMenuExpanded = false }
            ) {
              AiModelConfig.geminiModels.forEach { option ->
                DropdownMenuItem(
                  text = { Text(AiModelConfig.geminiModelLabel(option)) },
                  onClick = {
                    geminiModel = option
                    prefs.edit().putString(AiModelConfig.PREF_GEMINI_MODEL, option).apply()
                    geminiModelMenuExpanded = false
                  }
                )
              }
            }
          }
        }

        // OpenAI transcription switch (uses top-level showOpenAIInfo state)
        Row(
          Modifier.fillMaxWidth(),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
          Text(stringResource(R.string.label_use_openai_transcription), modifier = Modifier.weight(1f))
          IconButton(onClick = { showOpenAIInfo = true }) {
            Icon(Icons.Outlined.Info, contentDescription = stringResource(R.string.cd_info))
          }
          Switch(
            checked = useOpenAI,
            onCheckedChange = {
              useOpenAI = it
              prefs.edit().putBoolean("use_openai_transcription", useOpenAI).apply()
            }
          )
        }

        Divider()

        // Audio section (only show if OpenAI transcription is enabled)
        if (useOpenAI) {
          Text(
            stringResource(R.string.section_audio),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
          )
          // Mic gain
          var gainMode by remember { mutableStateOf(prefs.getString("gain_mode", "Off") ?: "Off") }
          var manualGainDb by remember { mutableStateOf(prefs.getInt("manual_gain_db", 0)) }
          Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.label_mic_gain), modifier = Modifier.weight(1f))
            IconButton(onClick = { showGainInfo = true }) {
              Icon(Icons.Outlined.Info, contentDescription = stringResource(R.string.cd_info))
            }
            var expanded by remember { mutableStateOf(false) }
            Box {
              OutlinedButton(onClick = { expanded = true }) {
                Text(stringResource(gainModeLabelRes(gainMode)))
              }
              DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                listOf("Off", "Auto", "Manual").forEach { opt ->
                  DropdownMenuItem(text = { Text(stringResource(gainModeLabelRes(opt))) }, onClick = {
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
              Text(stringResource(R.string.label_mic_boost), modifier = Modifier.weight(1f))
              // Simple stepper instead of Slider to keep diff minimal
              OutlinedButton(onClick = { manualGainDb = (manualGainDb - 1).coerceIn(0, 12); prefs.edit().putInt("manual_gain_db", manualGainDb).apply() }) { Text("-") }
              Text(stringResource(R.string.label_mic_boost_value, manualGainDb))
              OutlinedButton(onClick = { manualGainDb = (manualGainDb + 1).coerceIn(0, 12); prefs.edit().putInt("manual_gain_db", manualGainDb).apply() }) { Text("+") }
            }
          }

          // Car mode (auto mic gain on BT headset)
          var carMode by remember { mutableStateOf(prefs.getBoolean("car_mode_bt_auto", false)) }
          Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.label_car_mode), modifier = Modifier.weight(1f))
            IconButton(onClick = { showCarModeInfo = true }) {
              Icon(Icons.Outlined.Info, contentDescription = stringResource(R.string.cd_info))
            }
            Switch(checked = carMode, onCheckedChange = { on -> carMode = on; prefs.edit().putBoolean("car_mode_bt_auto", carMode).apply() })
          }
        }

        // Section divider for clarity
        Divider()

        // Auto-paste setting
        var autoPaste by remember { mutableStateOf(prefs.getBoolean("auto_paste", false)) }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
          Text(stringResource(R.string.label_auto_paste), modifier = Modifier.weight(1f))
          IconButton(onClick = { showAutoSteps = true }) {
            Icon(Icons.Outlined.Info, contentDescription = stringResource(R.string.cd_help))
          }
          Switch(checked = autoPaste, onCheckedChange = {
            autoPaste = it
            prefs.edit().putBoolean("auto_paste", autoPaste).apply()
          })
        }

        // Auto-prompt setting
        var autoPromptEnabled by remember { mutableStateOf(prefs.getBoolean("auto_prompt_enabled", false)) }
        var defaultPromptTitle by remember { mutableStateOf(prefs.getString("auto_prompt_title", "") ?: "") }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
          Text(stringResource(R.string.label_always_use_prompt), modifier = Modifier.weight(1f))
          IconButton(onClick = { showAutoPromptInfo = true }) {
            Icon(Icons.Outlined.Info, contentDescription = stringResource(R.string.cd_help))
          }
          Switch(checked = autoPromptEnabled, onCheckedChange = {
            autoPromptEnabled = it
            prefs.edit().putBoolean("auto_prompt_enabled", autoPromptEnabled).apply()
          })
        }
        if (autoPromptEnabled) {
          Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            val promptName = if (defaultPromptTitle.isBlank()) {
              stringResource(R.string.label_default_prompt_not_set)
            } else defaultPromptTitle
            Text(
              stringResource(R.string.label_default_prompt, promptName),
              modifier = Modifier.weight(1f)
            )
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
            }) { Text(stringResource(R.string.button_change)) }
          }
        }
      }

      Divider()

      // Prompts Section - Always visible
      Text(
        stringResource(R.string.section_prompts),
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary
      )

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
            ctx.getString(R.string.default_prompt_email_title),
            ctx.getString(R.string.default_prompt_email_instruction),
            null
          )
          vm.upsertPromptByTitle(
            ctx.getString(R.string.default_prompt_whatsapp_title),
            ctx.getString(R.string.default_prompt_whatsapp_instruction),
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
              DropdownMenuItem(text = { Text(stringResource(R.string.menu_prompt_edit)) }, onClick = {
                showEditId.value = item
                menuFor = null
              })
              DropdownMenuItem(text = { Text(stringResource(R.string.menu_prompt_delete)) }, onClick = {
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
        Button(onClick = { showAdd = true }) { Text(stringResource(R.string.button_add_prompt)) }
      }
      if (showAdd) {
        var t by remember { mutableStateOf("") }
        var s by remember { mutableStateOf("") }
        var useSearch by remember { mutableStateOf(false) }
        var thinkingEnabled by remember { mutableStateOf(false) }
        var isMailPrompt by remember { mutableStateOf(false) }
        var useMemoryList by remember { mutableStateOf(false) }
        var sendWebhook by remember { mutableStateOf(false) }
        var webhookToken by remember { mutableStateOf("") }
        var webhookUrl by remember { mutableStateOf("") }
        var webhookRawOnly by remember { mutableStateOf(false) }
        AlertDialog(
          onDismissRequest = { showAdd = false },
          confirmButton = {
            TextButton(onClick = {
              scope.launch {
                vm.addPromptExtended(
                  t,
                  s,
                  null,
                  useSearch,
                  null,
                  thinkingEnabled,
                  false,
                  isMailPrompt,
                  useMemoryList,
                  sendWebhook,
                  webhookToken.ifBlank { null },
                  webhookUrl.ifBlank { null },
                  webhookRawOnly
                )
                customPrompts = vm.prompts()
                showAdd = false
              }
            }) { Text(stringResource(R.string.button_save)) }
          },
          dismissButton = { TextButton(onClick = { showAdd = false }) { Text(stringResource(R.string.button_cancel)) } },
          title = { Text(stringResource(R.string.dialog_new_prompt_title)) },
          text = {
            LazyColumn(
              modifier = Modifier.heightIn(max = 400.dp).imePadding(),
              verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
              item {
                OutlinedTextField(
                  t,
                  { t = it },
                  label = { Text(stringResource(R.string.field_prompt_title)) },
                  modifier = Modifier.fillMaxWidth()
                )
              }
              item {
                OutlinedTextField(
                  s,
                  { s = it },
                  label = { Text(stringResource(R.string.field_prompt_system_instruction)) },
                  modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp)
                )
              }
              item {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                  Text(stringResource(R.string.label_grounding), modifier = Modifier.weight(1f))
                  Switch(checked = useSearch, onCheckedChange = { useSearch = it })
                  IconButton(onClick = { showGroundingInfo = true }) {
                    Icon(Icons.Outlined.Info, contentDescription = stringResource(R.string.cd_info))
                  }
                }
              }
              item {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                  Text(stringResource(R.string.label_thinking_mode), modifier = Modifier.weight(1f))
                  Switch(checked = thinkingEnabled, onCheckedChange = { thinkingEnabled = it })
                  IconButton(onClick = { showThinkingInfo = true }) {
                    Icon(Icons.Outlined.Info, contentDescription = stringResource(R.string.cd_info))
                  }
                }
              }
              item {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                  Text(stringResource(R.string.label_mail_prompt), modifier = Modifier.weight(1f))
                  Switch(
                    checked = isMailPrompt, 
                    onCheckedChange = { 
                      isMailPrompt = it
                      if (it) {
                        // Auto-aktivera grounding och thinking för mail-prompt
                        useSearch = true
                        thinkingEnabled = true
                      }
                    }
                  )
                  IconButton(onClick = { showMailInfo = true }) {
                    Icon(Icons.Outlined.Info, contentDescription = stringResource(R.string.cd_info))
                  }
                }
              }
              item {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                  Text(stringResource(R.string.memory_prompt_use_memory), modifier = Modifier.weight(1f))
                  Switch(checked = useMemoryList, onCheckedChange = { useMemoryList = it })
                  IconButton(onClick = { showMemoryInfo = true }) {
                    Icon(Icons.Outlined.Info, contentDescription = stringResource(R.string.cd_info))
                  }
                }
              }
              item {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                  Text(stringResource(R.string.label_send_webhook), modifier = Modifier.weight(1f))
                  Switch(checked = sendWebhook, onCheckedChange = {
                    sendWebhook = it
                    if (!it) webhookRawOnly = false
                  })
                }
              }
              if (sendWebhook) {
                item {
                  OutlinedTextField(
                    value = webhookUrl,
                    onValueChange = { webhookUrl = it },
                    label = { Text(stringResource(R.string.label_webhook_url)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
                  )
                }
                item {
                  Text(
                    stringResource(R.string.webhook_url_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                  )
                }
                item {
                  OutlinedTextField(
                    value = webhookToken,
                    onValueChange = { webhookToken = it },
                    label = { Text(stringResource(R.string.label_webhook_token)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
                  )
                }
                item {
                  Text(
                    stringResource(R.string.webhook_token_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                  )
                }
                item {
                  Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.label_webhook_raw_only), modifier = Modifier.weight(1f))
                    Switch(checked = webhookRawOnly, onCheckedChange = { webhookRawOnly = it })
                  }
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
        var isMailPrompt by remember { mutableStateOf(editP.isMailPrompt) }
        var useMemoryList by remember { mutableStateOf(editP.useMemoryList) }
        var sendWebhook by remember { mutableStateOf(editP.sendWebhook) }
        var webhookToken by remember { mutableStateOf(editP.webhookToken.orEmpty()) }
        var webhookUrl by remember { mutableStateOf(editP.webhookUrl.orEmpty()) }
        var webhookRawOnly by remember { mutableStateOf(editP.webhookRawOnly) }
        AlertDialog(
          onDismissRequest = { showEditId.value = null },
          confirmButton = {
            TextButton(onClick = {
        scope.launch {
                vm.updatePrompt(
                  editP.copy(
                    title = t,
                    systemText = s,
                    useGoogleSearch = useSearch,
                    thinkingEnabled = thinkingEnabled,
                    thinkingBudget = null,
                    useOpenAI = false,
                    isMailPrompt = isMailPrompt,
                    useMemoryList = useMemoryList,
                    sendWebhook = sendWebhook,
                    webhookToken = webhookToken.ifBlank { null },
                    webhookUrl = webhookUrl.ifBlank { null },
                    webhookRawOnly = webhookRawOnly
                  )
                )
                customPrompts = vm.prompts()
                showEditId.value = null
              }
            }) { Text(stringResource(R.string.button_save)) }
          },
          dismissButton = { TextButton(onClick = { showEditId.value = null }) { Text(stringResource(R.string.button_cancel)) } },
          title = { Text(stringResource(R.string.dialog_edit_prompt_title)) },
          text = {
            LazyColumn(
              modifier = Modifier.heightIn(max = 400.dp).imePadding(),
              verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
              item {
                OutlinedTextField(
                  t,
                  { t = it },
                  label = { Text(stringResource(R.string.field_prompt_title)) },
                  modifier = Modifier.fillMaxWidth()
                )
              }
              item {
                OutlinedTextField(
                  s,
                  { s = it },
                  label = { Text(stringResource(R.string.field_prompt_system_instruction)) },
                  modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp)
                )
              }
              item {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                  Text(stringResource(R.string.label_grounding), modifier = Modifier.weight(1f))
                  Switch(checked = useSearch, onCheckedChange = { useSearch = it })
                  IconButton(onClick = { showGroundingInfo = true }) {
                    Icon(Icons.Outlined.Info, contentDescription = stringResource(R.string.cd_info))
                  }
                }
              }
              item {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                  Text(stringResource(R.string.label_thinking_mode), modifier = Modifier.weight(1f))
                  Switch(checked = thinkingEnabled, onCheckedChange = { thinkingEnabled = it })
                  IconButton(onClick = { showThinkingInfo = true }) {
                    Icon(Icons.Outlined.Info, contentDescription = stringResource(R.string.cd_info))
                  }
                }
              }
              item {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                  Text(stringResource(R.string.label_mail_prompt), modifier = Modifier.weight(1f))
                  Switch(
                    checked = isMailPrompt, 
                    onCheckedChange = { 
                      isMailPrompt = it
                      if (it) {
                        // Auto-aktivera grounding och thinking för mail-prompt
                        useSearch = true
                        thinkingEnabled = true
                      }
                    }
                  )
                  IconButton(onClick = { showMailInfo = true }) {
                    Icon(Icons.Outlined.Info, contentDescription = stringResource(R.string.cd_info))
                  }
                }
              }
              item {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                  Text(stringResource(R.string.memory_prompt_use_memory), modifier = Modifier.weight(1f))
                  Switch(checked = useMemoryList, onCheckedChange = { useMemoryList = it })
                  IconButton(onClick = { showMemoryInfo = true }) {
                    Icon(Icons.Outlined.Info, contentDescription = stringResource(R.string.cd_info))
                  }
                }
              }
              item {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                  Text(stringResource(R.string.label_send_webhook), modifier = Modifier.weight(1f))
                  Switch(checked = sendWebhook, onCheckedChange = {
                    sendWebhook = it
                    if (!it) webhookRawOnly = false
                  })
                }
              }
              if (sendWebhook) {
                item {
                  OutlinedTextField(
                    value = webhookUrl,
                    onValueChange = { webhookUrl = it },
                    label = { Text(stringResource(R.string.label_webhook_url)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
                  )
                }
                item {
                  Text(
                    stringResource(R.string.webhook_url_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                  )
                }
                item {
                  OutlinedTextField(
                    value = webhookToken,
                    onValueChange = { webhookToken = it },
                    label = { Text(stringResource(R.string.label_webhook_token)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
                  )
                }
                item {
                  Text(
                    stringResource(R.string.webhook_token_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                  )
                }
                item {
                  Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.label_webhook_raw_only), modifier = Modifier.weight(1f))
                    Switch(checked = webhookRawOnly, onCheckedChange = { webhookRawOnly = it })
                  }
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
          Toast.makeText(ctx, ctx.getString(R.string.toast_microphone_permission_missing), Toast.LENGTH_SHORT).show()
          return
        }
        if (!android.speech.SpeechRecognizer.isRecognitionAvailable(ctx)) {
          Toast.makeText(ctx, ctx.getString(R.string.toast_speech_not_available), Toast.LENGTH_SHORT).show()
          return
        }
        val activeApiKey = if (apiProvider == AiModelConfig.API_PROVIDER_OPENROUTER) openrouterKey else apiKey
        if (activeApiKey.isBlank()) {
          val errMsg = if (apiProvider == AiModelConfig.API_PROVIDER_OPENROUTER)
            R.string.toast_enter_openrouter_key_first
          else
            R.string.toast_enter_api_key_first
          Toast.makeText(ctx, ctx.getString(errMsg), Toast.LENGTH_SHORT).show()
          return
        }
        try {
          vm.capture(1)
        } catch (t: Throwable) {
          val message = t.message ?: t::class.java.simpleName
          Toast.makeText(ctx, ctx.getString(R.string.toast_recording_error, message), Toast.LENGTH_SHORT).show()
          return
        }
        if (vm.rawText.isBlank()) {
          Toast.makeText(ctx, ctx.getString(R.string.toast_no_voice_captured), Toast.LENGTH_SHORT).show()
          return
        }
        val custom = customPrompts.firstOrNull { it.title == selectedPrompt }
        val promptText = custom?.systemText ?: ctx.getString(R.string.default_prompt_summary_instruction)
        val promptForCall = custom ?: se.olle.rostbubbla.data.Prompt(
          title = selectedPrompt ?: ctx.getString(R.string.prompt_selected_placeholder),
          systemText = promptText,
          vehikel = custom?.vehikel
        )
        if (custom?.sendWebhook == true && custom.webhookRawOnly) {
          busy = true
          retryMessage = null
          result = vm.rawText
          scope.launch {
            val hook = vm.sendWebhookIfEnabled(custom, vm.rawText)
            hook?.let { res ->
              val summary = res.summary.ifBlank { ctx.getString(R.string.overlay_status_done) }
              val msg = if (res.ok) {
                ctx.getString(R.string.webhook_toast_success, summary)
              } else {
                ctx.getString(R.string.webhook_toast_error, summary)
              }
              withContext(Dispatchers.Main) {
                Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
              }
            }
          }
          busy = false
          return
        }
        busy = true
        retryMessage = null
        result = vm.callGemini(promptForCall, activeApiKey) { att ->
          retryMessage = ctx.getString(R.string.status_retrying, att)
        }
        busy = false
        val aiErrorPrefix = ctx.getString(R.string.ai_request_error_prefix)
        if (result.isBlank() || result.startsWith(aiErrorPrefix)) {
          Toast.makeText(ctx, ctx.getString(R.string.toast_ai_response_empty), Toast.LENGTH_SHORT).show()
        } else {
          val cm = ctx.getSystemService(android.content.ClipboardManager::class.java)
          cm.setPrimaryClip(android.content.ClipData.newPlainText(ctx.getString(R.string.clip_label_ai), result))
          // Let the system clipboard notification suffice
          custom?.let { prompt ->
            scope.launch {
              val hook = vm.sendWebhookIfEnabled(prompt, result)
              hook?.let { res ->
                val summary = res.summary.ifBlank { ctx.getString(R.string.overlay_status_done) }
                val msg = if (res.ok) {
                  ctx.getString(R.string.webhook_toast_success, summary)
                } else {
                  ctx.getString(R.string.webhook_toast_error, summary)
                }
                withContext(Dispatchers.Main) {
                  Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
                }
              }
            }
          }
        }
      }

      // Prompt picker UI
      pickState?.let { st ->
        AlertDialog(
          onDismissRequest = { st.onPick(null); pickState = null },
          confirmButton = {},
          dismissButton = {
            TextButton(onClick = { st.onPick(null); pickState = null }) {
              Text(stringResource(R.string.button_cancel))
            }
          },
          title = { Text(stringResource(R.string.dialog_select_prompt_title)) },
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
            Toast.makeText(ctx, ctx.getString(R.string.toast_microphone_permission_missing), Toast.LENGTH_SHORT).show(); return@collect
          }
          if (!android.speech.SpeechRecognizer.isRecognitionAvailable(ctx)) {
            Toast.makeText(ctx, ctx.getString(R.string.toast_speech_not_available), Toast.LENGTH_SHORT).show(); return@collect
          }
          val activeApiKey = if (apiProvider == AiModelConfig.API_PROVIDER_OPENROUTER) openrouterKey else apiKey
          if (activeApiKey.isBlank()) {
            val errMsg = if (apiProvider == AiModelConfig.API_PROVIDER_OPENROUTER)
              R.string.toast_enter_openrouter_key_first
            else
              R.string.toast_enter_api_key_first
            Toast.makeText(ctx, ctx.getString(errMsg), Toast.LENGTH_SHORT).show(); return@collect
          }
          try { vm.capture(1) } catch (t: Throwable) {
            val message = t.message ?: t::class.java.simpleName
            Toast.makeText(ctx, ctx.getString(R.string.toast_recording_error, message), Toast.LENGTH_SHORT).show(); return@collect
          }
          if (vm.rawText.isBlank()) { Toast.makeText(ctx, ctx.getString(R.string.toast_no_voice_captured), Toast.LENGTH_SHORT).show(); return@collect }

          val all = customPrompts.map { it.title }
          val cont = CompletableDeferred<String?>()
          pickState = PromptPickState(options = all) { choice -> cont.complete(choice) }
          val picked = cont.await()
          val custom = customPrompts.firstOrNull { it.title == picked }
          val promptText = custom?.systemText ?: ctx.getString(R.string.default_prompt_summary_instruction)
          val promptForCall = custom ?: se.olle.rostbubbla.data.Prompt(
            title = picked ?: ctx.getString(R.string.prompt_selected_placeholder),
            systemText = promptText,
            vehikel = custom?.vehikel
          )
          if (custom?.sendWebhook == true && custom.webhookRawOnly) {
            result = vm.rawText
            custom.let { prompt ->
              scope.launch {
                val hook = vm.sendWebhookIfEnabled(prompt, vm.rawText)
                hook?.let { res ->
                  val summary = res.summary.ifBlank { ctx.getString(R.string.overlay_status_done) }
                  val msg = if (res.ok) {
                    ctx.getString(R.string.webhook_toast_success, summary)
                  } else {
                    ctx.getString(R.string.webhook_toast_error, summary)
                  }
                  withContext(Dispatchers.Main) {
                    Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
                  }
                }
              }
            }
            // Skip AI call entirely
            if (startedHeadless) {
              act?.finish()
            }
            return@collect
          }
          result = vm.callGemini(promptForCall, activeApiKey)
          val aiErrorPrefix = ctx.getString(R.string.ai_request_error_prefix)
          if (result.isBlank() || result.startsWith(aiErrorPrefix)) {
            Toast.makeText(ctx, ctx.getString(R.string.toast_ai_response_empty), Toast.LENGTH_SHORT).show()
          } else {
            val cm = ctx.getSystemService(android.content.ClipboardManager::class.java)
            cm.setPrimaryClip(android.content.ClipData.newPlainText(ctx.getString(R.string.clip_label_ai), result))
            // Let the system clipboard notification suffice
            custom?.let { prompt ->
              scope.launch {
                val hook = vm.sendWebhookIfEnabled(prompt, result)
                hook?.let { res ->
                  val summary = res.summary.ifBlank { ctx.getString(R.string.overlay_status_done) }
                  val msg = if (res.ok) {
                    ctx.getString(R.string.webhook_toast_success, summary)
                  } else {
                    ctx.getString(R.string.webhook_toast_error, summary)
                  }
                  withContext(Dispatchers.Main) {
                    Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
                  }
                }
              }
            }
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
        Text(retryMessage ?: stringResource(R.string.status_working), style = MaterialTheme.typography.bodySmall)
      }
      }
      if (result.isNotBlank()) {
      OutlinedTextField(
        value = result,
        onValueChange = {},
        modifier = Modifier.fillMaxWidth().height(160.dp),
        label = { Text(stringResource(R.string.label_result)) }
      )
      }

      // Copy/paste buttons removed – bubble flow handles this
      }
    }
  }
  if (showAbout) {
    val ctx = LocalContext.current
    AlertDialog(
      onDismissRequest = { showAbout = false },
      confirmButton = { TextButton(onClick = { showAbout = false }) { Text(stringResource(R.string.button_ok)) } },
      title = { Text(stringResource(R.string.dialog_about_title)) },
      text = {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center
          ) {
            Image(
              painter = painterResource(id = R.drawable.aiolle_logo),
              contentDescription = stringResource(R.string.cd_aiolle_logo),
              modifier = Modifier.size(80.dp),
              contentScale = ContentScale.Fit
            )
          }

          LazyColumn(
            modifier = Modifier.fillMaxHeight(0.6f),
            verticalArrangement = Arrangement.spacedBy(8.dp)
          ) {
            item {
              Text(
                stringResource(R.string.about_tagline),
                style = MaterialTheme.typography.bodyMedium
              )
            }

            item { Divider() }

            item {
              Text(stringResource(R.string.about_created_by), style = MaterialTheme.typography.titleSmall)
            }
            item {
              Text(
                stringResource(R.string.about_author_name),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.primary
              )
            }

            item {
              val context = LocalContext.current
              val versionName = try {
                  context.packageManager.getPackageInfo(context.packageName, 0).versionName
              } catch (e: Exception) {
                  "0.6.0"
              }
              var versionClicks by remember { mutableStateOf(0) }
              Text(
                text = "Version $versionName",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.clickable {
                    versionClicks++
                    if (versionClicks == 7) {
                        val prefs = context.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)
                        val current = prefs.getBoolean("debug_mode", false)
                        prefs.edit().putBoolean("debug_mode", !current).apply()
                        Toast.makeText(context, context.getString(R.string.toast_debug_enabled) + ": ${!current}", Toast.LENGTH_SHORT).show()
                        versionClicks = 0
                    }
                }
              )
            }
            item { Divider() }

            item {
              Text(stringResource(R.string.about_contact), style = MaterialTheme.typography.titleSmall)
            }

            item {
              val linkStyle = SpanStyle(color = MaterialTheme.colorScheme.primary, textDecoration = TextDecoration.Underline)
              val linkedInPrefix = stringResource(R.string.about_linkedin_prefix)
              val linkedInDisplay = stringResource(R.string.about_linkedin_text)
              val linkedInUrl = stringResource(R.string.about_linkedin_url)
              val linkedInText = buildAnnotatedString {
                append(linkedInPrefix)
                pushStringAnnotation(tag = "URL", annotation = linkedInUrl)
                withStyle(linkStyle) { append(linkedInDisplay) }
                pop()
              }
              ClickableText(text = linkedInText, onClick = { off ->
                linkedInText.getStringAnnotations("URL", off, off).firstOrNull()?.let { ann ->
                  try { ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(ann.item))) } catch (_: Throwable) {}
                }
              })
            }

            item {
              val linkStyle = SpanStyle(color = MaterialTheme.colorScheme.primary, textDecoration = TextDecoration.Underline)
              val websitePrefix = stringResource(R.string.about_website_prefix)
              val websiteDisplay = stringResource(R.string.about_website_text)
              val siteText = buildAnnotatedString {
                append(websitePrefix)
                pushStringAnnotation(tag = "URL", annotation = "https://aiolle.se")
                withStyle(linkStyle) { append(websiteDisplay) }
                pop()
              }
              ClickableText(text = siteText, onClick = { off ->
                siteText.getStringAnnotations("URL", off, off).firstOrNull()?.let { ann ->
                  try { ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(ann.item))) } catch (_: Throwable) {}
                }
              })
            }

            item {
              val linkStyle = SpanStyle(color = MaterialTheme.colorScheme.primary, textDecoration = TextDecoration.Underline)
              val policy = buildAnnotatedString {
                val url = "https://raw.githubusercontent.com/Olleman82/TapScribe/main/PRIVACY_POLICY.md"
                pushStringAnnotation(tag = "URL", annotation = url)
                withStyle(linkStyle) { append(stringResource(R.string.about_privacy_policy)) }
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
    val featureLines = stringArrayResource(R.array.auto_paste_features)
    val dataUsageLines = stringArrayResource(R.array.auto_paste_data_usage)
    val activationSteps = stringArrayResource(R.array.auto_paste_activation_steps)
  AlertDialog(
      onDismissRequest = { showAutoSteps = false },
      confirmButton = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
          Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = {
              try { ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + ctx.packageName))) } catch (_: Throwable) {}
            }) { Text(stringResource(R.string.button_open_app_settings)) }
            TextButton(onClick = {
              showAutoSteps = false
              showAccessibilityConsent = true
            }) { Text(stringResource(R.string.button_open_accessibility)) }
          }
          TextButton(onClick = { showAutoSteps = false }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.button_ok)) }
        }
      },
      title = { Text(stringResource(R.string.dialog_auto_paste_title)) },
      text = {
        LazyColumn(
          modifier = Modifier.heightIn(max = 400.dp),
          verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
          item {
            Text(
              stringResource(R.string.auto_paste_info_header),
              style = MaterialTheme.typography.titleMedium,
              color = MaterialTheme.colorScheme.primary
            )
          }
          item { Text(stringResource(R.string.auto_paste_info_body)) }
          item {
            Text(
              stringResource(R.string.auto_paste_section_features),
              style = MaterialTheme.typography.titleSmall
            )
          }
          items(featureLines) { Text(it) }
          item {
            Text(
              stringResource(R.string.auto_paste_section_data_usage),
              style = MaterialTheme.typography.titleSmall
            )
          }
          items(dataUsageLines) { Text(it) }
          item {
            Text(
              stringResource(R.string.auto_paste_section_activation),
              style = MaterialTheme.typography.titleSmall
            )
          }
          items(activationSteps) { Text(it) }
          item {
            Text(
              stringResource(R.string.auto_paste_consent),
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant
            )
          }
        }
      }
    )
  }

  if (showAutoPromptInfo) {
    AlertDialog(
      onDismissRequest = { showAutoPromptInfo = false },
      confirmButton = { TextButton(onClick = { showAutoPromptInfo = false }) { Text(stringResource(R.string.button_ok)) } },
      title = { Text(stringResource(R.string.dialog_auto_prompt_title)) },
      text = {
        val introPoints = stringArrayResource(R.array.auto_prompt_intro_points)
        val setupPoints = stringArrayResource(R.array.auto_prompt_setup_steps)
        val useCases = stringArrayResource(R.array.auto_prompt_use_cases)
        val changeSteps = stringArrayResource(R.array.auto_prompt_change_steps)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          Text(stringResource(R.string.auto_prompt_section_intro), style = MaterialTheme.typography.titleSmall)
          introPoints.forEach { Text(it) }
          
          Text(stringResource(R.string.auto_prompt_section_setup), style = MaterialTheme.typography.titleSmall)
          setupPoints.forEach { Text(it) }
          
          Text(stringResource(R.string.auto_prompt_section_use_cases), style = MaterialTheme.typography.titleSmall)
          useCases.forEach { Text(it) }
          
          Text(stringResource(R.string.auto_prompt_section_change), style = MaterialTheme.typography.titleSmall)
          changeSteps.forEach { Text(it) }
        }
      }
    )
  }

  if (showGroundingInfo) {
    AlertDialog(
      onDismissRequest = { showGroundingInfo = false },
      confirmButton = { TextButton(onClick = { showGroundingInfo = false }) { Text(stringResource(R.string.button_ok)) } },
      title = { Text(stringResource(R.string.dialog_grounding_title)) },
      text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          Text(stringResource(R.string.grounding_body_primary))
          Text(stringResource(R.string.grounding_body_secondary))
          Text(
            stringResource(R.string.grounding_body_note),
            style = MaterialTheme.typography.bodySmall
          )
        }
      }
    )
  }

  if (showThinkingInfo) {
    AlertDialog(
      onDismissRequest = { showThinkingInfo = false },
      confirmButton = { TextButton(onClick = { showThinkingInfo = false }) { Text(stringResource(R.string.button_ok)) } },
      title = { Text(stringResource(R.string.dialog_thinking_title)) },
      text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          Text(stringResource(R.string.thinking_body_primary))
          Text(
            stringResource(R.string.thinking_body_secondary),
            style = MaterialTheme.typography.bodySmall
          )
          Text(
            stringResource(R.string.thinking_body_note),
            style = MaterialTheme.typography.bodySmall
          )
        }
      }
    )
  }

  if (showOpenAIInfo) {
    AlertDialog(
      onDismissRequest = { showOpenAIInfo = false },
      confirmButton = { TextButton(onClick = { showOpenAIInfo = false }) { Text(stringResource(R.string.button_ok)) } },
      title = { Text(stringResource(R.string.dialog_openai_title)) },
      text = {
        val overview = stringArrayResource(R.array.openai_overview_points)
        val costs = stringArrayResource(R.array.openai_cost_points)
        val advantages = stringArrayResource(R.array.openai_advantage_points)
        val requirements = stringArrayResource(R.array.openai_requirement_points)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          Text(stringResource(R.string.openai_section_overview), style = MaterialTheme.typography.titleSmall)
          overview.forEach { Text(it) }
          
          Text(stringResource(R.string.openai_section_costs), style = MaterialTheme.typography.titleSmall)
          costs.forEach { Text(it) }
          
          Text(stringResource(R.string.openai_section_advantages), style = MaterialTheme.typography.titleSmall)
          advantages.forEach { Text(it) }
          
          Text(stringResource(R.string.openai_section_requirements), style = MaterialTheme.typography.titleSmall)
          requirements.forEach { Text(it) }
        }
      }
    )
  }

  // Info bubbles for new audio settings
  if (showGainInfo) {
    AlertDialog(
      onDismissRequest = { showGainInfo = false },
      confirmButton = { TextButton(onClick = { showGainInfo = false }) { Text(stringResource(R.string.button_ok)) } },
      title = { Text(stringResource(R.string.label_mic_gain)) },
      text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          Text(stringResource(R.string.mic_gain_body_auto))
          Text(stringResource(R.string.mic_gain_body_manual))
          Text(
            stringResource(R.string.mic_gain_body_off),
            style = MaterialTheme.typography.bodySmall
          )
        }
      }
    )
  }

  if (showCarModeInfo) {
    AlertDialog(
      onDismissRequest = { showCarModeInfo = false },
      confirmButton = { TextButton(onClick = { showCarModeInfo = false }) { Text(stringResource(R.string.button_ok)) } },
      title = { Text(stringResource(R.string.dialog_car_mode_title)) },
      text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          Text(stringResource(R.string.car_mode_body_primary))
          Text(stringResource(R.string.car_mode_body_secondary))
        }
      }
    )
  }

  if (showMailInfo) {
    AlertDialog(
      onDismissRequest = { showMailInfo = false },
      confirmButton = { TextButton(onClick = { showMailInfo = false }) { Text(stringResource(R.string.button_ok)) } },
      title = { Text(stringResource(R.string.dialog_mail_prompt_title)) },
      text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          Text(stringResource(R.string.mail_prompt_info_body))
          Text(
            stringResource(R.string.mail_prompt_info_customization),
            style = MaterialTheme.typography.titleSmall
          )
          Text(
            stringResource(R.string.mail_prompt_info_requirements),
            style = MaterialTheme.typography.titleSmall
          )
          Text(
            stringResource(R.string.mail_prompt_info_example),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
          )
        }
      }
    )
  }

  // Memory info dialog
  if (showMemoryInfo) {
    AlertDialog(
      onDismissRequest = { showMemoryInfo = false },
      confirmButton = { TextButton(onClick = { showMemoryInfo = false }) { Text(stringResource(R.string.button_ok)) } },
      title = { Text(stringResource(R.string.memory_prompt_info_title)) },
      text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          Text(stringResource(R.string.memory_prompt_info_body))
        }
      }
    )
  }

  // Add memory dialog
  if (showAddMemory) {
    var title by remember { mutableStateOf("") }
    var content by remember { mutableStateOf("") }
    val ctx = LocalContext.current
    val cm = ctx.getSystemService(ClipboardManager::class.java)
    
    LaunchedEffect(showAddMemory) {
      if (showAddMemory) {
        // Auto-fill from clipboard if available
        if (cm.hasPrimaryClip()) {
          content = cm.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
        }
      }
    }

    AlertDialog(
      onDismissRequest = { showAddMemory = false },
      confirmButton = {
        TextButton(onClick = {
          if (title.isNotBlank() && content.isNotBlank()) {
            scope.launch {
              vm.addMemoryItem(title, content)
              showAddMemory = false
            }
          }
        }) { Text(stringResource(R.string.button_save)) }
      },
      dismissButton = { TextButton(onClick = { showAddMemory = false }) { Text(stringResource(R.string.button_cancel)) } },
      title = { Text(stringResource(R.string.memory_dialog_add_title)) },
      text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          OutlinedTextField(
            value = title,
            onValueChange = { title = it },
            label = { Text(stringResource(R.string.memory_field_title)) },
            modifier = Modifier.fillMaxWidth()
          )
          OutlinedTextField(
            value = content,
            onValueChange = { content = it },
            label = { Text(stringResource(R.string.memory_field_content)) },
            modifier = Modifier.fillMaxWidth().heightIn(min = 100.dp)
          )
        }
      }
    )
  }

  // Edit memory dialog
  showEditMemory?.let { item ->
    var title by remember(item) { mutableStateOf(item.title) }
    var content by remember(item) { mutableStateOf(item.content) }

    AlertDialog(
      onDismissRequest = { showEditMemory = null },
      confirmButton = {
        TextButton(onClick = {
          if (title.isNotBlank() && content.isNotBlank()) {
            scope.launch {
              vm.updateMemoryItem(item.copy(title = title, content = content))
              showEditMemory = null
            }
          }
        }) { Text(stringResource(R.string.button_save)) }
      },
      dismissButton = { TextButton(onClick = { showEditMemory = null }) { Text(stringResource(R.string.button_cancel)) } },
      title = { Text(stringResource(R.string.memory_dialog_manage_title)) },
      text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          OutlinedTextField(
            value = title,
            onValueChange = { title = it },
            label = { Text(stringResource(R.string.memory_field_title)) },
            modifier = Modifier.fillMaxWidth()
          )
          OutlinedTextField(
            value = content,
            onValueChange = { content = it },
            label = { Text(stringResource(R.string.memory_field_content)) },
            modifier = Modifier.fillMaxWidth().heightIn(min = 100.dp)
          )
        }
      }
    )
  }

  // Welcome dialog for first-time users
  if (showWelcome) {
    val ctx = LocalContext.current
    val geminiSteps = stringArrayResource(R.array.welcome_gemini_steps)
    val permissionSteps = stringArrayResource(R.array.welcome_permissions_steps)
    val autoPasteSteps = stringArrayResource(R.array.welcome_auto_paste_steps)
    val readySteps = stringArrayResource(R.array.welcome_ready_steps)
    AlertDialog(
      onDismissRequest = { showWelcome = false },
      confirmButton = {},
      dismissButton = {},
      title = { Text(stringResource(R.string.dialog_welcome_title)) },
      text = {
        // After runtime permissions resolve, trigger overlay permission (last)
        LaunchedEffect(requestedAll, multiPerms.permissions) {
          if (requestedAll) {
            if (!Settings.canDrawOverlays(ctx)) {
              try { ctx.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + ctx.packageName))) } catch (_: Throwable) {}
            }
          }
        }
        Column(verticalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxHeight(0.85f)) {
          LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp)
          ) {
            item {
              Text(
                stringResource(R.string.welcome_quick_setup_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
              )
            }
            item { Text(stringResource(R.string.welcome_gemini_title), style = MaterialTheme.typography.titleSmall) }
            items(geminiSteps) { Text(it) }

            item { Text(stringResource(R.string.welcome_permissions_title), style = MaterialTheme.typography.titleSmall) }
            items(permissionSteps) { Text(it) }

            item { Text(stringResource(R.string.welcome_auto_paste_title), style = MaterialTheme.typography.titleSmall) }
            items(autoPasteSteps) { Text(it) }

            // item { Text(stringResource(R.string.ts_welcome_ready_title), style = MaterialTheme.typography.titleSmall) }
            items(readySteps) { Text(it) }
            item { Divider() }
            item { Text(stringResource(R.string.welcome_privacy_title), style = MaterialTheme.typography.titleSmall) }
            item {
              Text(
                stringResource(R.string.welcome_privacy_body),
                style = MaterialTheme.typography.bodySmall
              )
            }
            item {
              val linkStyle = SpanStyle(color = MaterialTheme.colorScheme.primary, textDecoration = TextDecoration.Underline)
              val policy = buildAnnotatedString {
                append(stringResource(R.string.welcome_learn_more_prefix))
                pushStringAnnotation(tag = "URL", annotation = "https://raw.githubusercontent.com/Olleman82/TapScribe/main/PRIVACY_POLICY.md")
                withStyle(linkStyle) { append(stringResource(R.string.about_privacy_policy)) }
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
              Text(stringResource(R.string.button_request_permissions)) 
            }
            
            OutlinedButton(
              onClick = {
                showWelcome = false
                showAccessibilityConsent = true
              },
              modifier = Modifier.fillMaxWidth()
            ) { 
              Text(stringResource(R.string.button_accessibility_settings)) 
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
              Text(stringResource(R.string.button_got_it_celebrate), style = MaterialTheme.typography.titleMedium)
            }
          }
        }
      },
    )
  }

  // Accessibility consent dialog - must be shown before requesting accessibility permission
  if (showAccessibilityConsent) {
    val ctx = LocalContext.current
    AlertDialog(
      onDismissRequest = { /* Cannot dismiss - must make explicit choice */ },
      title = { Text(stringResource(R.string.dialog_accessibility_consent_title)) },
      text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
          Text(stringResource(R.string.accessibility_consent_body))
          Text(
            stringResource(R.string.accessibility_consent_data_usage),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
          )
        }
      },
      confirmButton = {
        Button(
          onClick = {
            showAccessibilityConsent = false
            try { 
              ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) 
            } catch (_: Throwable) {}
          },
          colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary
          )
        ) {
          Text(stringResource(R.string.button_accept_accessibility))
        }
      },
      dismissButton = {
        TextButton(
          onClick = { showAccessibilityConsent = false }
        ) {
          Text(stringResource(R.string.button_decline_accessibility))
        }
      }
    )
  }
}
