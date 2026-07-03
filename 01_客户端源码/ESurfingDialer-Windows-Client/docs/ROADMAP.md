# Roadmap

## Phase 1 - Desktop Shell

- WPF window
- Tray icon
- Basic config save/load
- Core process launcher

## Phase 2 - Stable Runtime

- Build and package `client.jar`
- Monitor Java process
- Read health state from core
- Auto reconnect button
- Log viewer

## Phase 3 - Windows Integration

- DPAPI-encrypted password storage
- Startup registry toggle
- Minimize-to-tray startup
- Single-instance guard

## Phase 4 - Installer

- Inno Setup installer
- Install directory selection
- Treat the install directory picker as a parent-folder picker: if the user chooses `D:\software`, install into `D:\software\ESurfingDialer Lite`; avoid scattering files directly into the selected parent folder and avoid ambiguous double-folder behavior.
- Desktop shortcut
- Start menu shortcut
- Uninstall cleanup choices


