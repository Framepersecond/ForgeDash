# ForgeDash

<div align="center">

**Modern web-based admin dashboard for NeoForge Minecraft servers**

Manage, monitor, moderate, update, and maintain your NeoForge server from any device with a browser.

[![Release](https://img.shields.io/badge/release-v4.0-10b981?style=for-the-badge)](../../releases)
[![Minecraft](https://img.shields.io/badge/Minecraft-1.21%2B-62b47a?style=for-the-badge)](#supported-versions)
[![NeoForge](https://img.shields.io/badge/NeoForge-supported-f97316?style=for-the-badge)](#supported-versions)
[![Java](https://img.shields.io/badge/Java-21%2B-f89820?style=for-the-badge)](#supported-versions)
[![License](https://img.shields.io/badge/License-BSD%203--Clause-blue?style=for-the-badge)](LICENSE)

</div>

---

## Overview

ForgeDash is a powerful web administration panel for NeoForge Minecraft servers. It gives server owners and staff a clean, responsive interface for monitoring performance, managing players, running console commands, browsing files, maintaining mods, reviewing Guardian activity, and applying GitHub-backed updates without needing direct terminal access for every task.

ForgeDash is built for everyday server administration: quick setup, secure registration, audit-friendly actions, mobile-friendly layouts, local NeoForge maintenance, and a polished browser experience for desktop, tablet, and mobile operators.

## Current Release

- **Latest version:** `v4.0`
- **Release date:** `2026-07-06`
- **Release:** available from the [GitHub releases page](../../releases)
- **License:** BSD 3-Clause

## What's New in ForgeDash 4.0

ForgeDash 4.0 is the major NeoForge release after 3.1. It brings the new Dash motion system, a cleaner Guardian experience, stronger file interactions, local NeoForge mod maintenance, and clearer GitHub-backed update workflows.

### Dash Motion System

- Rebuilt the shared animation layer for smoother page transitions and content swaps.
- Added smoother card entrances, row reveals, hover lift, press feedback, status flips, and value flashes.
- Removed the bumpy feel when switching between pages.
- Preserved reduced-motion support for users who prefer less animation.
- Tuned transitions around transform and opacity patterns for a smoother browser experience.

### Guardian Improvements

- Shortened the Guardian activity area with a fixed-height, scrollable table surface.
- Added paging for Timeline, Blocks, and Containers.
- Added sticky activity headers for easier scanning.
- Spread Guardian tools across a full-width responsive grid instead of stacking everything in one right-side column.
- Kept Guardian cases, notes, rollback, incidents, scores, replay, restore, alert rules, retention, purge, and import tools available.

### Files and Mod Maintenance

- File and folder rows now open from the whole row bubble, not only from the name.
- Upload, edit, rename, download, and delete flows keep operators in context.
- NeoForge mod maintenance remains local to ForgeDash.
- Installed mod state, JAR upload, and Modrinth search are available from the dashboard.

### GitHub and Update Workflows

- GitHub-backed release checks remain targeted at `Framepersecond/ForgeDash`.
- Update scans are rate-limit aware.
- Manual update scans can retry when needed.
- Staged updates apply through the NeoForge mods workflow.

### NeoDash Bridge

- Restart from a NeoDash SSO session delegates to NeoDash's configured start path.
- Restart redirects into NeoDash startup-log visibility for NeoForge servers.
- Bridge values are stored consistently with Dash and FabricDash.

### Release Metadata

- Gradle `mod_version` updated to `4.0`.
- ForgeDash documentation no longer carries Fabric-specific wording.

## Features

### Real-Time Monitoring

- Live server statistics, including TPS, memory usage, and CPU usage.
- Historical performance graphs and analytics.
- Online player tracking with detailed player profiles.
- Live console log streaming with color-coded output.
- Setup-safe telemetry endpoints for panel integrations.
- Graph snapshots and performance views for server health checks.

### Guardian

- Guardian activity review with fixed-height, scrollable table surfaces.
- Timeline, Blocks, and Containers paging for larger datasets.
- Sticky headers for easier activity scanning.
- Full-width responsive Guardian tool grid.
- Cases, notes, rollback, incidents, scores, replay, restore, alert rules, retention, purge, and import tools.

### Player Management

- View all players with session history and playtime tracking.
- Kick, ban, freeze, or teleport players from the web panel.
- Add and manage admin notes for players.
- View and edit player inventories and ender chests.
- Manage the server whitelist.

### Server Configuration

- Modify common server settings such as MOTD, view distance, and simulation distance.
- Configure game rules across worlds.
- Upload and manage the server icon.
- Manage supported server-side settings directly from the dashboard.

### Files, Datapacks, and Mods

- Browse server files and directories directly in the browser.
- Open files and folders from the whole row bubble for faster navigation.
- Edit configuration files from the web interface.
- Upload, rename, download, and delete files while staying in context.
- Upload and manage datapacks.
- Maintain NeoForge mods locally from ForgeDash.
- View installed mod state.
- Upload mod JAR files.
- Search for mods through Modrinth support.

### Backup System

- Create manual backups instantly.
- Schedule automatic backups.
- Download backup archives.
- Configure backup retention with a maximum backup limit.

### Console and Moderation Tools

- Execute console commands remotely.
- Teleport players to coordinates or to other players.
- Broadcast messages as the server or as an admin.
- Use the player freeze system for moderation workflows.
- Register web admins through time-limited in-game registration codes.

### SSO and Approval Workflow

- NeoDash bridge SSO bootstrap flow.
- Waiting room for identities pending admin approval.
- Review pending bridge users directly from the Users page.
- Assign roles during bridge approval.
- Bridge-aware navigation and session handoff.
- NeoDash restart handoff for configured start paths and startup-log visibility.

### GitHub-Backed Updates

- Release checks targeted at `Framepersecond/ForgeDash`.
- Rate-limit-aware update scanning.
- Manual retry support for update scans.
- Staged updates through the NeoForge mods workflow.
- Clearer update workflow for server operators.

### Responsive Web UI

- Reworked global scrolling behavior.
- Mobile-first handling for Players, Tasks, Audit, and Guardian pages.
- Improved overflow and clipping behavior for action menus and card actions.
- Better mobile form layouts in Settings pages.
- Smooth Dash motion system with reduced-motion support.

## Installation

1. Download the latest ForgeDash release JAR from the [releases page](../../releases).
2. Place the JAR file in your NeoForge server's `mods` folder.
3. Restart your Minecraft server.
4. Open the generated ForgeDash configuration and adjust the web port if needed. The default port is `8080`.
5. Restart the server again to apply configuration changes.

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

ForgeDash creates a server-side configuration file on first start. Use it to adjust the dashboard port, database storage, backup retention, and related server settings.

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
| Server software | NeoForge servers |
| Java | `21` or higher |

## Security

ForgeDash includes several built-in security measures for safer server administration:

- Registration codes expire after 5 minutes.
- Session-based authentication with secure cookie handling.
- Explicit logout flow and session hardening updates.
- Admin credentials are hashed before storage.
- File uploads are validated.
- File paths are sanitized to reduce traversal risks.
- Administrative actions are logged with IP tracking.
- Bridge approvals keep SSO identities gated behind admin review.

## Recommended Production Setup

When exposing the dashboard publicly, use a secure deployment setup:

- Put ForgeDash behind HTTPS.
- Restrict the dashboard port with a firewall where possible.
- Use strong admin credentials.
- Only approve bridge users you trust.
- Keep ForgeDash updated through the latest GitHub release.
- Regularly download or rotate important backups.
- Review Guardian activity, incidents, alert rules, and retention settings regularly.

## Previous Release

### ForgeDash 3.1

ForgeDash 3.1 introduced the first premium motion pass, local NeoForge maintenance, Modrinth browser support, notifications, staff workflow, graph snapshots, mobile navigation fixes, build stabilization, and NeoDash restart handoff.

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