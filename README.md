
# Röstdbubbla – AI Voice Assistant

[![License: PolyForm Noncommercial](https://img.shields.io/badge/License-PolyForm%20NC%201.0-red.svg)](https://polyformproject.org/licenses/noncommercial/1.0.0)
[![Android](https://img.shields.io/badge/Android-8.0%2B-green.svg)](https://developer.android.com)
[![Kotlin](https://img.shields.io/badge/Kotlin-1.9-blue.svg)](https://kotlinlang.org)
[![Google Play](https://img.shields.io/badge/Google_Play-Download-blue.svg)](https://play.google.com/store/apps/details?id=se.olle.rostbubbla&hl=en)

An Android app that converts voice to text and uses AI to process the content:

- **🎤 Overlay bubble** (foreground service) for quick access from any app
- **🗣️ Voice → text** with Google Speech-to-Text or OpenAI Whisper
- **🤖 AI integration** with Gemini API (Google Search + Thinking mode)
- **📧 Mail prompts** - AI searches for email addresses and composes emails automatically
- **📋 Smart output** via Clipboard or Accessibility SET_TEXT
- **🌍 Multi-language** support (Swedish & English)
- **⚙️ Customizable prompts** with Room database

## Features

### Voice-to-Text
- **Google Speech-to-Text** (default)
- **OpenAI Whisper** (alternative, requires API key)

### AI Integration
- **Gemini API** with Google Search grounding
- **Thinking mode** for complex tasks
- **Customizable prompts** with custom system text
- **Mail prompt** - AI searches for email addresses and composes emails automatically

### Output
- **Clipboard** (default)
- **Accessibility SET_TEXT** (automatic input)
- **Mail client** (for mail prompts)

## 🚀 Quick Start

### Download
- **Google Play Store**: [Download Röstdbubbla](https://play.google.com/store/apps/details?id=se.olle.rostbubbla&hl=en)
- **Source Code**: Clone this repository for development

### Setup
1. **Install** the app on your Android device (8.0+)
2. **Grant permissions**: Microphone, Notifications, Display over other apps
3. **Add API key**: Enter your Gemini API key in settings
4. **Enable overlay**: Start the floating bubble from the main screen
5. **Optional**: Enable Accessibility service for auto-paste

### Development Setup
1. Clone the repository: `git clone https://github.com/Olleman82/TapScribe.git`
2. Open in Android Studio or Cursor
3. Add your Gemini API key for testing
4. Build and run on device/emulator

## 📱 Usage

### Basic Flow
1. **Tap** the floating bubble (appears over other apps)
2. **Speak** your message clearly
3. **Choose** a prompt or use the default
4. **Wait** for AI to process and respond
5. **Result** is copied to clipboard or pasted automatically

### Mail Prompt Example
1. Create a prompt and enable "Mail prompt"
2. Speak: *"Find Daniel at company 123 in Uddevalla and write an email that I'll be five minutes late"*
3. AI searches for the email address and composes the email
4. Mail client opens with recipient, subject, and body pre-filled

### Advanced Features
- **Custom prompts**: Create your own AI instructions
- **Google Search**: Enable grounding for real-time information
- **Thinking mode**: Better reasoning for complex tasks
- **Multi-segment**: Speak in multiple parts for longer messages

## 🛠️ Technical Information

### Requirements
- **Android**: 8.0+ (API level 26)
- **RAM**: 2GB minimum
- **Storage**: 50MB
- **Internet**: Required for AI features

### Architecture
- **UI**: Jetpack Compose with Material Design
- **Architecture**: MVVM pattern
- **Database**: Room with automatic migration
- **Networking**: Retrofit with OkHttp
- **AI Integration**: Gemini API, OpenAI Whisper

### Permissions
- `RECORD_AUDIO` - Voice input
- `SYSTEM_ALERT_WINDOW` - Overlay bubble
- `POST_NOTIFICATIONS` - Foreground service
- `INTERNET` - AI API calls

## 🔒 Privacy & Security

### Data Handling
- **Voice**: Processed locally, not stored
- **Text**: Sent to AI services you configure
- **API Keys**: Stored locally in SharedPreferences
- **Prompts**: Stored locally in Room database

### Third-Party Services
- **Google Gemini**: AI processing and search
- **OpenAI Whisper**: Alternative voice transcription
- **Google Speech-to-Text**: Default voice recognition

See [PRIVACY_POLICY.md](PRIVACY_POLICY.md) for detailed information.

## 🤝 Contributing

We welcome contributions! Please see [CONTRIBUTING.md](CONTRIBUTING.md) for guidelines.

### Quick Start for Contributors
1. Fork the repository
2. Create a feature branch
3. Make your changes
4. Test thoroughly
5. Submit a pull request

## 📄 License

This project is licensed under the **PolyForm Noncommercial License 1.0.0** - see the [LICENSE.md](LICENSE.md) file for details. Commercial use is not permitted.

## 🆘 Support

- **Issues**: [GitHub Issues](https://github.com/Olleman82/TapScribe/issues)
- **Discussions**: [GitHub Discussions](https://github.com/Olleman82/TapScribe/discussions)
- **Email**: Contact via the app's website

---

**Made with ❤️ for the Android community**
