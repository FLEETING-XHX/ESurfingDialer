# ESurfingDialer Windows icons

The icon family uses a white rounded-square background, a charcoal open door,
and three authentication signal bars. The door is fixed; only the signal color
changes with the authentication state.

- `default` and `disconnected`: gray, not authenticated or stopped
- `connecting`: amber, authentication in progress
- `connected`: green, authentication and heartbeat confirmed
- `error`: red, startup or runtime error

`ESurfingDialer-default.ico` is the application, installer, and desktop
shortcut icon. The tray controller switches among the state icons at runtime.

The SVG files are editable source assets. Each ICO contains 16, 24, 32, 48,
64, 128, and 256 pixel PNG entries for Windows shell, taskbar, and installer use.
