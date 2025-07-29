# DoubleLife

[![Java Version](https://img.shields.io/badge/Java-21-orange.svg)](https://www.oracle.com/java/)
[![Minecraft Version](https://img.shields.io/badge/Minecraft-1.21-green.svg)](https://papermc.io/)
[![Paper API](https://img.shields.io/badge/Paper-1.21.6-blue.svg)](https://papermc.io/)

A Minecraft Paper plugin that provides a **limited admin mode** system. Players can temporarily gain administrative permissions for a configured duration with comprehensive activity logging and Discord webhook integration.

## 🌟 Features

- **Temporary Admin Mode**: Grant players administrative permissions for a limited time
- **LuckPerms Integration**: Seamless permission management using LuckPerms API
- **Comprehensive Logging**: Track all player actions during admin sessions
- **Discord Webhooks**: Real-time notifications to Discord channels
- **Boss Bar Timer**: Visual countdown timer for active sessions
- **Player State Management**: Save and restore player inventory, gamemode, and location
- **Cooldown System**: Prevent permission abuse with configurable cooldowns
- **Internationalization**: Multi-language support with YAML language files
- **Modern GUI**: Beautiful inventory-based interfaces using Triumph GUI
- **Gradient Text Styling**: Branded color scheme with Adventure API components

## 📋 Requirements

- **Java 21** or higher
- **Paper 1.21** or compatible server
- **LuckPerms** plugin (required dependency)

## 🚀 Installation

1. Download the latest release from the [releases page](../../releases)
2. Place the `DoubleLife-x.x.x.jar` file in your server's `/plugins/` directory
3. Ensure **LuckPerms** is installed and running
4. Restart your server
5. Configure the plugin using `/plugins/DoubleLife/config.yml`

## ⚙️ Configuration

The plugin is configured through `config.yml`:

```yaml
# Session settings
session:
  duration: 3600  # Duration in seconds (1 hour)
  cooldown: 1800  # Cooldown in seconds (30 minutes)

# Permissions to grant during admin mode
permissions:
  - "*"           # Grant all permissions
  - "-some.perm"  # Deny specific permissions (use - prefix)

# Commands to execute when entering admin mode
commands:
  - "gamemode creative {player}"
  - "fly {player} on"

# Discord webhook settings
webhook:
  enabled: true
  url: "https://discord.com/api/webhooks/your/webhook/url"

# Language settings
language:
  default: "en" # available: ["en", "uk"]
```

## 🎮 Usage

### Commands

- `/doublelife` - Toggle admin mode on/off
- `/doublelife status` - View current session information
- `/doublelife reload` - Reload plugin configuration (admin only)

### Permissions

- `doublelife.use` - Allows toggling Double Life mode (default: op)
- `doublelife.bypass.limit` - Use without timer restriction (default: false)
- `doublelife.status` - View session details (default: op)
- `doublelife.admin` - Access to reload and admin functions (default: op)

### GUI Interface

Players can also access the admin mode through an intuitive GUI interface that displays:
- Current session status
- Remaining time with visual progress bar
- Quick toggle buttons
- Session history and statistics

## 🔧 Building from Source

This project uses Gradle for building:

```bash
# Build the plugin JAR
./gradlew build

# Build with dependencies included (recommended)
./gradlew shadowJar

# Run test server with plugin loaded
./gradlew runServer

# Clean build artifacts
./gradlew clean
```

The built JAR will be located in `build/libs/DoubleLife-x.x.x-all.jar`.

## 📝 Activity Logging

DoubleLife tracks comprehensive activity during admin sessions:

- **Command Execution**: All commands run by the player
- **Block Changes**: Blocks placed, broken, or modified
- **Item Interactions**: Items used, given, or modified
- **Player Interactions**: PvP, teleportation, and other player actions
- **Permission Usage**: Specific permissions used during the session

Logs are saved to individual files and can be exported in multiple formats.

## 🎨 Styling System

The plugin features a modern styling system with:

- **Brand Colors**: Consistent gold (#FFD700) to orange-red (#FF6B35) gradients
- **MiniMessage Support**: Rich text formatting with Adventure API
- **GUI Components**: Styled inventory interfaces with custom lore
- **Progress Indicators**: Visual timers and status bars
- **Internationalization**: Localized messages with placeholder support

## 🌐 Language Support

DoubleLife supports multiple languages through YAML files:

- Create language files in `plugins/DoubleLife/lang/`
- Use format: `en.yml`, `uk.yml`, etc.
- Supports placeholders: `{0}`, `{1}`, `{2}`, etc.
- Automatic fallback to default language if translation missing

## 🔗 API Integration

### LuckPerms Integration
```java
// Plugin automatically integrates with LuckPerms
// Permissions are granted/removed seamlessly
// Supports permission negation and hierarchies
```

## 🤝 Contributing

1. Fork the repository
2. Create a feature branch (`git checkout -b feature/amazing-feature`)
3. Commit your changes (`git commit -m 'Add amazing feature'`)
4. Push to the branch (`git push origin feature/amazing-feature`)
5. Open a Pull Request

## 📄 License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.

## 🐛 Bug Reports & Feature Requests

Please use the [GitHub Issues](../../issues) page to report bugs or request new features.

## 👨‍💻 Author

Created by **ikeepcalm** - [GitHub Profile](https://github.com/ikeepcalm)

---

⭐ **Star this repository if you find it useful!**