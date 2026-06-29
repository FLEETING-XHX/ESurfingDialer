#define MyAppName "ESurfingDialer Lite"
#define MyAppVersion "1.00"
#define MyAppPublisher "ESurfingDialer Lite"
#define MyAppExeName "ESurfingDialerLite.exe"
#define SourceDir "..\dist\ESurfingDialerLite"

[Setup]
AppId={{F59B6C7D-C53B-4DD8-B51F-745A74223C9D}
AppName={#MyAppName}
AppVersion={#MyAppVersion}
AppPublisher={#MyAppPublisher}
DefaultDirName={autopf}\ESurfingDialer Lite
DefaultGroupName={#MyAppName}
AllowNoIcons=yes
OutputDir=..\dist
OutputBaseFilename=ESurfingDialer-Lite-v1.00-Setup
Compression=lzma
SolidCompression=yes
WizardStyle=modern
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible

[Tasks]
Name: "desktopicon"; Description: "Create a desktop shortcut"; GroupDescription: "Additional tasks:"; Flags: unchecked

[Files]
Source: "{#SourceDir}\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs

[Icons]
Name: "{group}\{#MyAppName}"; Filename: "{app}\{#MyAppExeName}"
Name: "{autodesktop}\{#MyAppName}"; Filename: "{app}\{#MyAppExeName}"; Tasks: desktopicon

[Run]
Filename: "{app}\{#MyAppExeName}"; Description: "Launch {#MyAppName}"; Flags: nowait postinstall skipifsilent
