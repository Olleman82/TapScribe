
# Röstdbubbla – Android starter

Detta är ett minimalt Kotlin/Jetpack Compose-projekt som replikerar ditt Tasker-flöde:
- Overlay-bubbla (foreground service)
- Röst → text i segment
- Prompt-val (enkla inbyggda; lägg till Room för egna)
- Anrop till Gemini (Retrofit)
- Klistra in via Clipboard eller (valfritt) Accessibility SET_TEXT

## Bygga
1. Öppna mappen i Android Studio eller Cursor.
2. Kör appen på en enhet (minSdk 26).
3. Ge behörigheter: Mikrofon, Notiser, Visa över andra appar. Slå på Accessibility-tjänsten om du vill auto-klistra.

## Viktigt
- Fyll i **Gemini API-nyckel** i UI:t.
- Auto-klistra kräver att du aktiverar appens **Tillgänglighetstjänst** (inställningar).
- Overlay-bubblan startas/stoppas från startsidan.

## Integritetspolicy

Se `PRIVACY_POLICY.md` i repo. Vid publicering på Google Play kan du ange den publika GitHub‑URL:en till denna fil.
