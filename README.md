# Beeper MCP Server

An Android MCP (Model Context Protocol) server that provides access to Beeper chat data for AI assistants. This server exposes Beeper's chats, contacts, and messaging capabilities through an MCP interface.

## Prerequisites

- **Android device** with Beeper Android app installed
- **Same device requirement**: This MCP server must be installed on the same Android device as Beeper
- Android 8.0 (API level 26) or higher
- Android Studio or build tools for compilation

## Build and Installation

### 1. Clone the Repository
```bash
git clone https://github.com/your-repo/beeper-mcp.git
cd beeper-mcp
```

### 2. Connect Your Device
1. Connect your Android device via USB
2. Enable **Developer Options** and **USB Debugging**

### 3. Build and Install

#### Option A: Command Line
```bash
./gradlew installDebug
```

#### Option B: Android Studio
1. Open the project in Android Studio
2. Sync project with Gradle files
3. Run the app: **Run → Run 'app'**

## Setup and Usage

### 1. Grant Permissions
After installation, open the Beeper MCP Server app and:
- Grant **Beeper permissions** (read/send access)
- **Disable battery optimization** for the app (recommended)
- The service will start automatically once permissions are granted

### 2. Connect to Claude
Once the server is running, add it to Claude Code:

```bash
claude mcp add --transport sse beeper-android http://[DEVICE_IP]:8081
```

Replace `[DEVICE_IP]` with your Android device's IP address (shown in the app).

## Available MCP Tools

- **get_chats** - Retrieve chats with filtering options
- **get_contacts** - Retrieve contacts with filtering options  
- **get_messages** - Retrieve messages with search capabilities
- **send_message** - Send messages to specific chats

## Important Notes

- **Device Requirement**: The MCP server must run on the same Android device as Beeper to access the content provider
- **Network Access**: Ensure your device and Claude Code client are on the same network
- **Permissions**: Beeper permissions are required for the server to function
- **Battery**: Consider disabling battery optimization to prevent the service from being killed

