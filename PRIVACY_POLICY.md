# Privacy Policy for TapScribe (Mic Bubble)

Last updated: 2024-12-29

This app is a hobby project and is provided free of charge. It does not collect personal data for its own purposes, does not sell it to third parties, and does not display advertisements. The app works locally on your device and only sends the text you dictate to the AI service you configure (Google Gemini) to get a response. Please read this policy before using the app.

## What data is handled
- **Voice content**: Your speech is transcribed to text by Android's built-in speech recognition (or Google UI). The audio itself is handled by the system's voice service; this app does not save audio files.
- **Transcribed text**: The text you just spoke may – at your choice – be sent to Google's Gemini API to generate a response. The text is then sent over the internet to Google's servers. Responses from the API are displayed to you in the app and can be copied/pasted into other apps.
- **Custom prompts (local database)**: Your saved prompts are stored locally in the app's local database on the device. They are not sent anywhere by the app.
- **Clipboard**: When you copy or auto-paste, the app uses Android's clipboard function to place text in the clipboard and – if you have enabled the accessibility service – attempt to paste into the focused text field.

## Permissions
- **Microphone**: Required to transcribe speech to text.
- **Display over other apps**: Required for the floating microphone bubble to appear on top of other apps.
- **Accessibility service (optional)**: If you enable it, the app can attempt to automatically paste the response into the text field that has focus.
- **Internet**: Required for calls to the AI service (Gemini) and for any network functions.

## Sharing with third parties
- **Google (Gemini API, speech recognition)**: When you use the AI function, your text is sent to Google according to their terms. Please read Google's privacy policy and terms for the respective service.

## Data retention
The app does not store your voice recordings. Your prompts remain stored locally until you delete them yourself or uninstall the app. Clipboard content is cleared according to Android's normal behavior or when you copy something new.

## Security
Data is sent to the AI service via HTTPS. No additional encryption is applied by the app. Handle your API key carefully and do not share it.

## Your choices
- Use the app without auto-paste if you don't want to enable the accessibility service.
- Don't use the AI function if you don't want to send text to a third party.
- Delete prompts you no longer want to keep.

## Children
The app is not intended for users under 13 years of age.

## Contact
Questions? Create an issue in the GitHub repository or contact the developer via the website specified in the app.

---
This policy may be updated. Continued use of the app after changes means you accept the updated policy.