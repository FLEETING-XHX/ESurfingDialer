#define AppRelease "0.1.1-2082-test"
#ifndef StageDirectory
  #error StageDirectory is required
#endif
#ifndef PackageDirectory
  #error PackageDirectory is required
#endif

[Setup]
AppId={{8CE582D1-C173-4E03-80E6-F90C32C7C61D}
AppName=ESurfingDialer WindowsCpp
AppVersion={#AppRelease}
DefaultDirName={localappdata}\Programs\ESurfingDialerWindowsCpp
DefaultGroupName=ESurfingDialer WindowsCpp
UninstallDisplayIcon={app}\ESurfingDialerWindowsCpp.exe
PrivilegesRequired=lowest
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
OutputDir={#PackageDirectory}
OutputBaseFilename=ESurfingDialer-WindowsCpp-{#AppRelease}-x64-Setup
Compression=lzma2/max
SolidCompression=yes
WizardStyle=modern

[Files]
Source: "{#StageDirectory}\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs

[Icons]
Name: "{group}\ESurfingDialer WindowsCpp"; Filename: "{app}\ESurfingDialerWindowsCpp.exe"
