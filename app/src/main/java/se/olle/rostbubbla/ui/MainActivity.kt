
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
import kotlinx.coroutines.CompletableDeferred
import com.google.accompanist.permissions.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import se.olle.rostbubbla.access.PasteAccessibilityService
import se.olle.rostbubbla.overlay.OverlayService

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
  // State-variabler för UI
  var result by remember { mutableStateOf("") }
  
  // Behörigheter
  val micPerm = rememberPermissionState(Manifest.permission.RECORD_AUDIO)
  val notifPerm = if (Build.VERSION.SDK_INT >= 33) rememberPermissionState(Manifest.permission.POST_NOTIFICATIONS) else null

  var showAbout by remember { mutableStateOf(false) }
  var showAutoSteps by remember { mutableStateOf(false) }
  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text("TapScribe") },
        actions = {
          var menu by remember { mutableStateOf(false) }
          IconButton(onClick = { menu = true }) { Text("⋯") }
          DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text("Om") }, onClick = { menu = false; showAbout = true })
          }
        }
      )
    }
  ) { pad ->
    Column(Modifier.padding(pad).fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      val ctx = LocalContext.current
      val act = ctx as? MainActivity
      val startedHeadless = remember(act) { (act?.intent?.getBooleanExtra("headless", false) == true) }
      val prefs = remember { ctx.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE) }
      var apiKey by remember { mutableStateOf(prefs.getString("gemini_api_key", "") ?: "") }
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

      Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(onClick = {
          if (!Settings.canDrawOverlays(ctx)) {
            ctx.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + ctx.packageName)))
          } else {
            try {
              ctx.startForegroundService(Intent(ctx, OverlayService::class.java))
            } catch (t: Throwable) {
              Toast.makeText(ctx, "Cannot start bubble: ${t.message}", Toast.LENGTH_SHORT).show()
            }
          }
        }) { Text("Start Bubble") }

        Button(onClick = { ctx.stopService(Intent(ctx, OverlayService::class.java)) }) { Text("Stop Bubble") }
      }

      Button(onClick = {
        if (!micPerm.status.isGranted) micPerm.launchPermissionRequest()
        notifPerm?.let { if (!it.status.isGranted) it.launchPermissionRequest() }
      }) { Text("Request Permissions") }

      // Setting: Auto-paste in focused text field via accessibility service
      var autoPaste by remember { mutableStateOf(prefs.getBoolean("auto_paste", false)) }
      Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Auto-paste in active text field", modifier = Modifier.weight(1f))
        IconButton(onClick = { showAutoSteps = true }) { Icon(Icons.Outlined.Info, contentDescription = "Help") }
        Switch(checked = autoPaste, onCheckedChange = {
          autoPaste = it
          prefs.edit().putBoolean("auto_paste", autoPaste).apply()
        })
      }
      Text("Pastes AI response directly into the text field that has focus.", style = MaterialTheme.typography.bodySmall)
      Divider()
      // help opens in a small dialog via i-button

      var selectedPrompt by remember { mutableStateOf<String?>(null) }

      // Edit dialog state (must be declared before first use)
      val showEditId = remember { mutableStateOf<se.olle.rostbubbla.data.Prompt?>(null) }
      var menuFor by remember { mutableStateOf<String?>(null) }

      // Context menu state for long press on chips (must be declared before use below)
      data class PromptMenu(val title: String, val isBuiltIn: Boolean)
      var promptMenu by remember { mutableStateOf<PromptMenu?>(null) }

      Text("Prompts", style = MaterialTheme.typography.titleMedium)
      // All prompts come from DB (seeded first time)
      var customPrompts by remember { mutableStateOf<List<se.olle.rostbubbla.data.Prompt>>(emptyList()) }
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
              DropdownMenuItem(text = { Text("Redigera") }, onClick = {
                showEditId.value = item
                menuFor = null
              })
              DropdownMenuItem(text = { Text("Ta bort") }, onClick = {
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

      // (Allt visas samlat ovan)

      var showAdd by remember { mutableStateOf(false) }
      Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(onClick = { showAdd = true }) { Text("Lägg till prompt") }
      }
      if (showAdd) {
        var t by remember { mutableStateOf("") }
        var s by remember { mutableStateOf("") }
        AlertDialog(
          onDismissRequest = { showAdd = false },
          confirmButton = {
            TextButton(onClick = {
              scope.launch {
                vm.addPrompt(t, s, null)
                customPrompts = vm.prompts()
                showAdd = false
              }
            }) { Text("Spara") }
          },
          dismissButton = { TextButton(onClick = { showAdd = false }) { Text("Avbryt") } },
          title = { Text("Ny prompt") },
          text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
              OutlinedTextField(t, { t = it }, label = { Text("Titel") })
              OutlinedTextField(s, { s = it }, label = { Text("Systeminstruktion") })
            }
          }
        )
      }

      // Redigera prompt (egen)
      showEditId.value?.let { editP ->
        var t by remember { mutableStateOf(editP.title) }
        var s by remember { mutableStateOf(editP.systemText) }
        AlertDialog(
          onDismissRequest = { showEditId.value = null },
          confirmButton = {
            TextButton(onClick = {
        scope.launch {
                vm.updatePrompt(editP.copy(title = t, systemText = s))
                customPrompts = vm.prompts()
                showEditId.value = null
              }
            }) { Text("Spara") }
          },
          dismissButton = { TextButton(onClick = { showEditId.value = null }) { Text("Avbryt") } },
          title = { Text("Redigera prompt") },
          text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
              OutlinedTextField(t, { t = it }, label = { Text("Titel") })
              OutlinedTextField(s, { s = it }, label = { Text("Systeminstruktion") })
            }
          }
        )
      }

      // (Inga inbyggda specialfall – allt lever i DB)

      // (Kontextmeny via AlertDialog borttagen – per‑chip meny används)

      suspend fun runFlow() {
        // Stoppa loopen: kör bara ett segment per tryck
        vm.moreSegmentsDecider = { _, _ -> false }
        if (!micPerm.status.isGranted) {
          Toast.makeText(ctx, "Mikrofonbehörighet saknas", Toast.LENGTH_SHORT).show()
          return
        }
        if (!android.speech.SpeechRecognizer.isRecognitionAvailable(ctx)) {
          Toast.makeText(ctx, "Taletigenkänning saknas på enheten", Toast.LENGTH_SHORT).show()
          return
        }
        if (apiKey.isBlank()) {
          Toast.makeText(ctx, "Fyll i API-nyckel först", Toast.LENGTH_SHORT).show()
          return
        }
        try {
          vm.capture(1)
        } catch (t: Throwable) {
          Toast.makeText(ctx, "Fel vid inspelning: ${t.message}", Toast.LENGTH_SHORT).show()
          return
        }
        if (vm.rawText.isBlank()) {
          Toast.makeText(ctx, "Ingen röst fångades", Toast.LENGTH_SHORT).show()
          return
        }
        val custom = customPrompts.firstOrNull { it.title == selectedPrompt }
        val promptText = custom?.systemText ?: "sammanfatta i punktform"
        val p = se.olle.rostbubbla.data.Prompt(title = selectedPrompt ?: "Vald", systemText = promptText, vehikel = custom?.vehikel)
        result = vm.callGemini(p, apiKey)
        if (result.isBlank() || result.startsWith("Fel vid AI-anrop:")) {
          Toast.makeText(ctx, "AI-svar tomt eller fel", Toast.LENGTH_SHORT).show()
        } else {
          val cm = ctx.getSystemService(android.content.ClipboardManager::class.java)
          cm.setPrimaryClip(android.content.ClipData.newPlainText("AI", result))
          // Låt systemets clipboard‑notis räcka
        }
      }

      // Prompt-picker dialog state
      data class PromptPickState(val options: List<String>, val onPick: (String?) -> Unit)
      var pickState by remember { mutableStateOf<PromptPickState?>(null) }

      // UI för promptval
      pickState?.let { st ->
        AlertDialog(
          onDismissRequest = { st.onPick(null); pickState = null },
          confirmButton = {},
          dismissButton = { TextButton(onClick = { st.onPick(null); pickState = null }) { Text("Avbryt") } },
          title = { Text("Välj prompt") },
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
          // Bubbel-flöde: 1) spela in 2) välj prompt (dialog) 3) Gemini
          vm.moreSegmentsDecider = { _, _ -> false }
          if (!micPerm.status.isGranted) {
            Toast.makeText(ctx, "Mikrofonbehörighet saknas", Toast.LENGTH_SHORT).show(); return@collect
          }
          if (!android.speech.SpeechRecognizer.isRecognitionAvailable(ctx)) {
            Toast.makeText(ctx, "Taletigenkänning saknas på enheten", Toast.LENGTH_SHORT).show(); return@collect
          }
          if (apiKey.isBlank()) {
            Toast.makeText(ctx, "Fyll i API-nyckel först", Toast.LENGTH_SHORT).show(); return@collect
          }
          try { vm.capture(1) } catch (t: Throwable) {
            Toast.makeText(ctx, "Fel vid inspelning: ${t.message}", Toast.LENGTH_SHORT).show(); return@collect
          }
          if (vm.rawText.isBlank()) { Toast.makeText(ctx, "Ingen röst fångades", Toast.LENGTH_SHORT).show(); return@collect }

          val all = customPrompts.map { it.title }
          val cont = CompletableDeferred<String?>()
          pickState = PromptPickState(options = all) { choice -> cont.complete(choice) }
          val picked = cont.await()
          val custom = customPrompts.firstOrNull { it.title == picked }
          val promptText = custom?.systemText ?: "sammanfatta i punktform"
          val p = se.olle.rostbubbla.data.Prompt(title = picked ?: "Vald", systemText = promptText, vehikel = custom?.vehikel)
          result = vm.callGemini(p, apiKey)
          if (result.isBlank() || result.startsWith("Fel vid AI-anrop:")) {
            Toast.makeText(ctx, "AI-svar tomt eller fel", Toast.LENGTH_SHORT).show()
          } else {
            val cm = ctx.getSystemService(android.content.ClipboardManager::class.java)
            cm.setPrimaryClip(android.content.ClipData.newPlainText("AI", result))
            // Låt systemets clipboard‑notis räcka
          }
          // Startad från bubbla? Stanna kvar i bakgrunden, ta inte fokus
          if (startedHeadless) {
            act?.finish()
          }
        }
      }

      // Lyssna-knappen borttagen – micken i bubblan används istället

      if (result.isNotBlank()) {
      OutlinedTextField(value = result, onValueChange = {}, modifier = Modifier.fillMaxWidth().height(160.dp), label = { Text("Resultat") })
      }

      // Knappar för klistra/kopiera borttagna – bubbelflödet hanterar detta
    }
  }
  if (showAbout) {
    AlertDialog(
      onDismissRequest = { showAbout = false },
      confirmButton = { TextButton(onClick = { showAbout = false }) { Text("OK") } },
      title = { Text("About TapScribe") },
      text = {
        val ctx = LocalContext.current
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
          Text("TapScribe – voice to AI assistant with floating mic bubble.")
          Divider()
          Text("Created by", style = MaterialTheme.typography.titleSmall)
          Text("Olle Söderqvist")
          Divider()
          Text("Get your Gemini API key", style = MaterialTheme.typography.titleSmall)
          Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("1) Go to https://aistudio.google.com")
            Text("2) Log in with your Google account")
            Text("3) Click Get API key and create new key")
            Text("4) Copy and paste the key in the field at the top of the app")
          }
          Divider()
          Text("Contact", style = MaterialTheme.typography.titleSmall)
          val linkStyle = SpanStyle(color = MaterialTheme.colorScheme.primary, textDecoration = TextDecoration.Underline)
          val linkedInText = buildAnnotatedString {
            append("LinkedIn: ")
            pushStringAnnotation(tag = "URL", annotation = "https://www.linkedin.com/in/olle-soderqvist/")
            withStyle(linkStyle) { append("https://www.linkedin.com/in/olle-soderqvist/") }
            pop()
          }
          ClickableText(text = linkedInText, onClick = { off ->
            linkedInText.getStringAnnotations("URL", off, off).firstOrNull()?.let { ann ->
              try { ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(ann.item))) } catch (_: Throwable) {}
            }
          })

          val siteText = buildAnnotatedString {
            append("Hemsida: ")
            pushStringAnnotation(tag = "URL", annotation = "https://aiolle.se")
            withStyle(linkStyle) { append("https://aiolle.se") }
            pop()
          }
          ClickableText(text = siteText, onClick = { off ->
            siteText.getStringAnnotations("URL", off, off).firstOrNull()?.let { ann ->
              try { ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(ann.item))) } catch (_: Throwable) {}
            }
          })

          val policy = buildAnnotatedString {
            val url = "https://raw.githubusercontent.com/Olleman82/TapScribe/main/PRIVACY_POLICY.md"
            pushStringAnnotation(tag = "URL", annotation = url)
            withStyle(linkStyle) { append("Privacy policy") }
            pop()
          }
          ClickableText(text = policy, onClick = { off ->
            policy.getStringAnnotations("URL", off, off).firstOrNull()?.let { ann ->
              try { ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(ann.item))) } catch (_: Throwable) {}
            }
          })
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
}
