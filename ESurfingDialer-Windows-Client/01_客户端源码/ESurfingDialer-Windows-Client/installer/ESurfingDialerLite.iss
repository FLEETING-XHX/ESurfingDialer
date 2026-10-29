#define MyAppName "ESurfingDialer Lite"
#ifndef MyAppVersion
  #define MyAppVersion "2.4.1"
#endif
#ifndef MyAppFileVersion
  #define MyAppFileVersion "2.4.1.0"
#endif
#define MyAppPublisher "ESurfingDialer Lite"
#define MyAppExeName "ESurfingDialerLite.exe"
#ifndef SourceDir
  #define SourceDir "..\dist\v2.4.1\ESurfingDialerLite"
#endif

[Setup]
AppId={{F59B6C7D-C53B-4DD8-B51F-745A74223C9D}
AppName={#MyAppName}
AppVersion={#MyAppVersion}
VersionInfoVersion={#MyAppFileVersion}
VersionInfoProductTextVersion={#MyAppVersion}
AppPublisher={#MyAppPublisher}
DefaultDirName={autopf}\ESurfingDialer Lite
DefaultGroupName={#MyAppName}
AllowNoIcons=yes
OutputDir=..\dist\v{#MyAppVersion}
OutputBaseFilename=ESurfingDialer-Windows-v{#MyAppVersion}-Setup
Compression=lzma
SolidCompression=yes
WizardStyle=modern
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
SetupIconFile=..\resources\icons\ESurfingDialer-default.ico

[Tasks]
Name: "desktopicon"; Description: "创建桌面快捷方式"; GroupDescription: "附加选项："; Flags: unchecked

[Files]
Source: "{#SourceDir}\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs

[Icons]
Name: "{group}\{#MyAppName}"; Filename: "{app}\{#MyAppExeName}"
Name: "{autodesktop}\{#MyAppName}"; Filename: "{app}\{#MyAppExeName}"; IconFilename: "{app}\{#MyAppExeName}"; Tasks: desktopicon

[Run]
Filename: "{app}\{#MyAppExeName}"; Description: "启动 {#MyAppName}"; Flags: nowait postinstall skipifsilent
