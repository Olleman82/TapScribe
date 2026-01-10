# Contributing to Röstdbubbla

Thank you for your interest in contributing to Röstdbubbla! This document provides guidelines for contributing to this open-source Android voice assistant app.

## Getting Started

### Prerequisites
- Android Studio or Cursor IDE
- Android SDK (API level 26+)
- Git
- A Gemini API key for testing AI features

### Setup
1. Fork the repository
2. Clone your fork: `git clone https://github.com/Olleman82/TapScribe.git`
3. Open the project in Android Studio
4. Add your Gemini API key in the app settings
5. Build and run the app on a device or emulator

## Development Guidelines

### Code Style
- Follow Kotlin coding conventions
- Use meaningful variable and function names
- Add comments for complex logic
- Keep functions focused and small

### Architecture
- Follow MVVM pattern with Jetpack Compose
- Use Room for local data persistence
- Implement proper error handling
- Use coroutines for async operations

### UI Guidelines
- Follow Material Design principles
- Ensure accessibility compliance
- Support both Swedish and English languages
- Test on different screen sizes

## Areas for Contribution

### High Priority
- **Bug fixes** - Report and fix issues
- **Performance improvements** - Optimize AI response times
- **Accessibility** - Improve screen reader support
- **Documentation** - Improve code comments and README

### Medium Priority
- **New languages** - Add support for additional languages
- **UI improvements** - Enhance user experience
- **Testing** - Add unit and integration tests
- **CI/CD** - Set up automated builds

### Low Priority
- **New AI providers** - Add support for other AI services
- **Advanced features** - Complex prompt templates
- **Themes** - Dark mode and custom themes

## Submitting Changes

### Pull Request Process
1. Create a feature branch: `git checkout -b feature/your-feature-name`
2. Make your changes
3. Test thoroughly on a real device
4. Update documentation if needed
5. Commit with clear messages: `git commit -m "Add feature: brief description"`
6. Push to your fork: `git push origin feature/your-feature-name`
7. Create a Pull Request with:
   - Clear title and description
   - Screenshots for UI changes
   - Testing instructions

### Commit Message Format
```
type(scope): brief description

Detailed description of changes

Fixes #issue-number
```

Types: `feat`, `fix`, `docs`, `style`, `refactor`, `test`, `chore`

## Reporting Issues

### Bug Reports
When reporting bugs, please include:
- Android version and device model
- App version
- Steps to reproduce
- Expected vs actual behavior
- Screenshots if applicable

### Feature Requests
For new features, please:
- Check existing issues first
- Describe the use case
- Explain why it would be valuable
- Consider implementation complexity

## Code of Conduct

### Our Pledge
We are committed to providing a welcoming and inclusive environment for all contributors.

### Expected Behavior
- Be respectful and constructive
- Focus on what's best for the community
- Accept constructive criticism gracefully
- Show empathy towards other community members

### Unacceptable Behavior
- Harassment or discrimination
- Trolling or inflammatory comments
- Personal attacks or political discussions
- Spam or off-topic discussions

## Development Setup

### Required Permissions
The app requires these permissions:
- `RECORD_AUDIO` - For voice input
- `SYSTEM_ALERT_WINDOW` - For overlay bubble
- `POST_NOTIFICATIONS` - For foreground service
- `INTERNET` - For AI API calls

### Testing
- Test on physical devices (Android 8.0+)
- Verify both Swedish and English localization
- Test overlay functionality
- Test AI integration with real API keys

### Building
```bash
# Debug build
./gradlew assembleDebug

# Release build
./gradlew assembleRelease

# Run tests
./gradlew test
```

## API Keys and Privacy

### Important Notes
- Never commit API keys to the repository
- API keys are stored locally in SharedPreferences
- The app only sends data to configured AI services
- Review the Privacy Policy for data handling details

### Testing with APIs
- Use your own API keys for development
- Be mindful of API rate limits
- Don't share API keys in issues or PRs

## Release Process

### Versioning
- Follow semantic versioning (MAJOR.MINOR.PATCH)
- Update version in `app/build.gradle.kts`
- Update CHANGELOG.md with new features

### Release Checklist
- [ ] All tests pass
- [ ] Documentation updated
- [ ] Version numbers incremented
- [ ] Release notes prepared
- [ ] APK/AAB built and tested

## Getting Help

### Resources
- GitHub Issues for bug reports and feature requests
- GitHub Discussions for questions and ideas
- README.md for setup instructions
- PRIVACY_POLICY.md for privacy information

### Contact
- Open an issue for technical questions
- Use GitHub Discussions for general questions
- Check existing issues before creating new ones

## Recognition

Contributors will be recognized in:
- README.md contributors section
- Release notes for significant contributions
- GitHub contributor statistics

Thank you for contributing to Röstdbubbla! 🎉


