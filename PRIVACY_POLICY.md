# Privacy Policy for TapScribe (Mic Bubble)

Last updated: 2025-09-29

This app is a hobby project and is provided free of charge. It does not collect personal data for its own purposes, does not sell it to third parties, and does not show ads. The app runs locally on your device and only sends the text you dictate to the AI service you configure (Google Gemini) to obtain a response.

## What data is handled
- Voice content: Your speech is transcribed to text by Android’s built‑in speech recognition or Google’s speech UI. Audio is handled by the system’s voice service; this app does not store audio files.
- Transcribed text: The text you dictated may — at your choice — be sent to Google’s Gemini API to generate a response. The text is transmitted over the internet to Google’s servers, and the response is shown in the app. You can copy/paste it into other apps.
- Optional Search Grounding: If you enable prompts that use “Google Search grounding”, the model may query Google Search to ground answers with sources. Returned links may be shown to you.
- Custom prompts (local database): Your saved prompts are stored locally on the device in the app’s database. The app does not transmit them.
- Clipboard: When you copy or auto‑paste, the app uses Android’s clipboard to place text in the clipboard and — if you enable the accessibility service — attempts to paste into the focused text field.
- Local diagnostics (logs): While the app is running, HTTP request/response details to Gemini can be logged to Android Logcat for debugging (includes prompt and transcribed text). These logs stay on the device and are not sent by the app, but can be visible to other debugging tools on the device.

## Permissions
- Microphone: Needed to transcribe speech to text.
- Display over other apps: Needed so the floating microphone bubble can appear on top of other apps.
- Accessibility service (optional): If enabled, allows the app to try to paste responses automatically into the focused text field.
- Internet: Needed to call the AI service (Gemini) and optional network features.
- Notifications (Android 13+): Used to show foreground‑service status while the bubble is active.

## Sharing with third parties
- Google (Gemini API, speech recognition, optional Search Grounding): When you use AI features, your text is sent to Google under their terms. Please review Google’s privacy policies and terms for these services.

## Data retention
- The app does not store your voice recordings.
- Your prompts remain locally until you delete them or uninstall the app.
- Clipboard content is managed by Android’s normal behavior and is replaced when you copy something new.
- API key storage: Your Gemini API key is stored locally in Android SharedPreferences (not additionally encrypted by the app). You can remove it any time in the app settings or by uninstalling the app.

## Security
- Data sent to the AI service uses HTTPS. No additional end‑to‑end encryption is applied by the app. Handle your API key carefully and do not share it.

## Your choices
- Use the app without auto‑paste if you don’t want to enable the accessibility service.
- Disable prompts that use Search Grounding if you don’t want search queries involved.
- Do not use the AI feature if you do not want to send text to a third party.
- Delete prompts you no longer need and remove your API key if you stop using the app.

## Children
The app is not intended for users under 13 years of age.

## Contact
Questions? Open an issue in the GitHub repository or contact the developer via the website linked in the app.

---
This policy may be updated. Continued use of the app after changes means you accept the updated policy.