# ForgeDash

<div align="center">

**Modern web-based admin dashboard for Minecraft servers**

Manage, monitor, and moderate your Minecraft server from any device with a browser.

[![Release](https://img.shields.io/badge/release-v4.0-10b981?style=for-the-badge)](../../releases)
[![Minecraft](https://img.shields.io/badge/Minecraft-1.21%2B-62b47a?style=for-the-badge)](#supported-versions)
[![Java](https://img.shields.io/badge/Java-21%2B-f89820?style=for-the-badge)](#supported-versions)
[![License](https://img.shields.io/badge/License-BSD%203--Clause-blue?style=for-the-badge)](LICENSE)

</div>

---

## Overview

ForgeDash is a powerful web administration panel for Paper, Spigot, and compatible Minecraft servers. It gives server owners and staff a clean, responsive interface for monitoring performance, managing players, running console commands, editing files, configuring server settings, and handling backups without needing direct terminal access.

ForgeDash is built for everyday administration: quick setup, secure registration, audit-friendly actions, mobile-friendly layouts, and enough control to manage a live server from a desktop, tablet, or phone.

## Current Release

- **Latest version:** `v4.0`
- **Release:** available from the [GitHub releases page](../../releases)
- **License:** BSD 3-Clause

## Features

### Real-Time Monitoring

- Live server statistics, including TPS, memory usage, and CPU usage
- Historical performance graphs and analytics
- Online player tracking with detailed player profiles
- Live console log streaming with color-coded output
- Setup-safe telemetry endpoints for panel integrations

### Player Management

- View all players with session history and playtime tracking
- Kick, ban, freeze, or teleport players from the web panel
- Add and manage admin notes for players
- View and edit player inventories and ender chests
- Manage the server whitelist

### Server Configuration

- Modify common server settings such as MOTD, view distance, and simulation distance
- Configure game rules across worlds
- Upload and manage the server icon
- Enable or disable plugins dynamically where supported

### Backup System

- Create manual backups instantly
- Schedule automatic backups
- Download backup archives
- Configure backup retention with a maximum backup limit

### File Management

- Browse server files and directories directly in the browser
- Edit configuration files from the web interface
- Upload files and plugins
- Upload and manage datapacks

### Console and Moderation Tools

- Execute console commands remotely
- Teleport players to coordinates or to other players
- Broadcast messages as the server or as an admin
- Use the player freeze system for moderation workflows
- Register web admins through time-limited in-game registration codes

### SSO and Approval Workflow

- NeoDash bridge SSO bootstrap flow
- Waiting room for identities pending admin approval
- Review pending bridge users directly from the Users page
- Assign roles during bridge approval
- Bridge-aware navigation and session handoff

### Responsive Web UI

- Reworked global scrolling behavior
- Mobile-first handling for Players, Tasks, and Audit pages
- Improved overflow and clipping behavior for action menus and card actions
- Better mobile form layouts in Settings pages

## Installation

1. Download the latest release JAR from the [releases page](../../releases).
2. Place the JAR file in your server's `plugins` folder.
3. Restart your Minecraft server.
4. Configure the web port in `plugins/Dash/config.yml` if needed. The default port is `8080`.
5. Restart the server again to apply the configuration.

## Initial Setup

1. Join your server as an operator.
2. Run `/dash register` in-game to generate a registration code.
3. Open the dashboard in your browser:

   ```text
   http://localhost:8080
   ```

   Replace `8080` with your configured port if you changed it.

4. Enter the registration code and create your admin account.
5. Log in and start managing your server through ForgeDash.

> For production use, run ForgeDash behind HTTPS through a reverse proxy or a secure hosting panel whenever the dashboard is exposed outside your local machine.

## Commands

| Command | Description | Permission |
| --- | --- | --- |
| `/dash register` | Generates a web registration code that expires after 5 minutes. | `dash.register` |

## Permissions

| Permission | Description | Default |
| --- | --- | --- |
| `dash.register` | Allows a player to generate web registration codes. | `op` |

## Configuration

ForgeDash stores its configuration in:

```text
plugins/Dash/config.yml
```

Example configuration:

```yaml
# Web server port for the admin dashboard
port: 8080

# Database settings for player data tracking
database:
  type: sqlite
  file: dash.db

# Backup settings
backups:
  directory: backups
  max-backups: 10
```

### Configuration Options

| Option | Description | Default |
| --- | --- | --- |
| `port` | Web server port used by the dashboard. | `8080` |
| `database.type` | Database backend for player data tracking. Currently supports SQLite. | `sqlite` |
| `database.file` | SQLite database file name. | `dash.db` |
| `backups.directory` | Directory used for backup archives. | `backups` |
| `backups.max-backups` | Maximum number of backups to retain. | `10` |

## Supported Versions

| Requirement | Supported |
| --- | --- |
| Minecraft | `1.21`, `1.21.1`, `1.21.2`, `1.21.3`, `1.21.4+` |
| Server software | Paper, Spigot, and compatible forks |
| Java | `21` or higher |

## Security

ForgeDash includes several built-in security measures for safer server administration:

- Registration codes expire after 5 minutes
- Session-based authentication with secure cookie handling
- Explicit logout flow and session hardening updates
- Admin credentials are hashed before storage
- File uploads are validated
- File paths are sanitized to reduce traversal risks
- Administrative actions are logged with IP tracking

## Recommended Production Setup

When exposing the dashboard publicly, use a secure deployment setup:

- Put ForgeDash behind HTTPS
- Restrict the dashboard port with a firewall where possible
- Use strong admin credentials
- Only approve bridge users you trust
- Keep ForgeDash updated through the latest GitHub release
- Regularly download or rotate important backups

## Author

Developed by **Frxme**.

## License

This project is licensed under the **BSD 3-Clause License**. See [LICENSE](LICENSE) for details.

---

<div align="center">

## Partner

<a href="https://emeraldhost.de/frxme">
  <img src="https://cdn.emeraldhost.de/branding/icon/icon.png" width="80" alt="Emerald Host Logo">
</a>

### Powered by EmeraldHost

*DDoS-Protection, NVMe Performance und 99.9% Uptime.*  
*Der Host meines Vertrauens für alle Development-Server.*

<a href="https://emeraldhost.de/frxme">
  <img src="https://img.shields.io/badge/Code-Frxme10-10b981?style=for-the-badge&logo=gift&logoColor=white&labelColor=0f172a" alt="Use code Frxme10 for 10% off">
</a>

</div>

---

*For issues, feature requests, or contributions, please visit the [GitHub repository](../../issues).*