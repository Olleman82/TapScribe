
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
        label = { Text("Gemini API-nyckel") },
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
              Toast.makeText(ctx, "Kan inte starta bubbla: ${t.message}", Toast.LENGTH_SHORT).show()
            }
          }
        }) { Text("Starta bubbla") }

        Button(onClick = { ctx.stopService(Intent(ctx, OverlayService::class.java)) }) { Text("Stoppa bubbla") }
      }

      Button(onClick = {
        if (!micPerm.status.isGranted) micPerm.launchPermissionRequest()
        notifPerm?.let { if (!it.status.isGranted) it.launchPermissionRequest() }
      }) { Text("Begär behörigheter") }

      // Inställning: Klistra automatiskt i fokuserat textfält via tillgänglighetstjänst
      var autoPaste by remember { mutableStateOf(prefs.getBoolean("auto_paste", false)) }
      Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Klistra automatiskt i aktivt textfält", modifier = Modifier.weight(1f))
        IconButton(onClick = { showAutoSteps = true }) { Icon(Icons.Outlined.Info, contentDescription = "Hjälp") }
        Switch(checked = autoPaste, onCheckedChange = {
          autoPaste = it
          prefs.edit().putBoolean("auto_paste", autoPaste).apply()
        })
      }
      Text("Klistrar in AI‑svaret direkt i textfältet som har fokus.", style = MaterialTheme.typography.bodySmall)
      Divider()
      // hjälp öppnas i en liten dialog via i-knappen

      var selectedPrompt by remember { mutableStateOf<String?>(null) }

      // Redigeringsdialog state (måste deklareras innan första användning)
      val showEditId = remember { mutableStateOf<se.olle.rostbubbla.data.Prompt?>(null) }
      var menuFor by remember { mutableStateOf<String?>(null) }

      // Kontextmeny state för långtryck på chips (måste deklareras före användning nedan)
      data class PromptMenu(val title: String, val isBuiltIn: Boolean)
      var promptMenu by remember { mutableStateOf<PromptMenu?>(null) }

      Text("Prompter", style = MaterialTheme.typography.titleMedium)
      // Alla prompter kommer från DB (seedas första gången)
      var customPrompts by remember { mutableStateOf<List<se.olle.rostbubbla.data.Prompt>>(emptyList()) }
      LaunchedEffect(Unit) {
        val seeded = prefs.getBoolean("prompts_seeded_v1", false)
        if (!seeded) {
          vm.upsertPromptByTitle(
            "Skriv mejl",
            "Du får text nedan som är talat in via transkribering. Det betyder att en del ord antagligen blivit feltranskriberade och att punkter och kommatecken ibland kan hamna på fel ställen. Om det står -Smiley- eller liknande är det troligt att jag vill att det ska vara en smiley där.Din uppgift: Utifrån texten. Följ tonaliteten och följden i texten så väl du kan men formatera det som en färdig text för ett mail med bra formatering, punkter etc på rätt ställen. Du får göra små förändringar för att det ska bli grammatiskt korrekt eller för att ta bort ord som inte verkar logiska eller inte verkar höra till mailen. Om användaren inte ger en hälsningsfras på slutet så avslutar du alltid med Mvh Olle.\n\nSvara ENDAST med mailtexten",
            null
          )
          vm.upsertPromptByTitle(
            "WhatsApp",
            "skriv ett informellt meddelande av nedan transkriberade text. det ska skickas på whatsapp till vänner familj. tänk på att texten transkriberats och att något ord kan ha blivit fel. försök förstå användarens avsikt utifrån kontexten isåfall. lägg till någon smiley (gubbar samt tummen upp inte massa symboler, bilar etc) om och när det passar. försök att skriva så nära det som användaren säger i övrigt. inled enbart med hej/tjena etc OM användaren faktiskt inlett så.\n\nsvara endast med whatsapp chattmeddelandet - inget annat!",
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
      title = { Text("Om TapScribe") },
      text = {
        val ctx = LocalContext.current
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
          Text("TapScribe – röst till AI‑assistent med flytande mic‑bubbla.")
          Divider()
          Text("Skapad av", style = MaterialTheme.typography.titleSmall)
          Text("Olle Söderqvist")
          Divider()
          Text("Hämta din Gemini API‑nyckel", style = MaterialTheme.typography.titleSmall)
          Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("1) Gå till https://aistudio.google.com")
            Text("2) Logga in med ditt Google‑konto")
            Text("3) Klicka på Get API key och skapa ny nyckel")
            Text("4) Kopiera och klistra in nyckeln i fältet högst upp i appen")
          }
          Divider()
          Text("Kontakt", style = MaterialTheme.typography.titleSmall)
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
          }) { Text("Öppna appinställningar") }
          TextButton(onClick = {
            try { ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) } catch (_: Throwable) {}
          }) { Text("Öppna tillgänglighet") }
          TextButton(onClick = { showAutoSteps = false }) { Text("Stäng") }
        }
      },
      title = { Text("Aktivera automatisk inklistring") },
      text = {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
          Text("För Android 13+: Tillåt först 'Begränsad inställning' för appen:")
          Text("1) Öppna appinställningar (⋯ → Tillåt begränsad inställning)")
          Text("2) Gå till Tillgänglighet → Installerade tjänster")
          Text("3) Aktivera TapScribe: Klistra i fokuserat fält")
          Text("När detta är på, försöker appen klistra in AI‑svaret direkt i det textfält som har fokus.")
        }
      }
    )
  }
}
