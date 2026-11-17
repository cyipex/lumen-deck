; Inno Setup script for the Lumen Deck installer. Built by installer/build.js (npm run installer).
#ifndef AppVersion
  #define AppVersion "1.0.0"
#endif

[Setup]
AppId={{B5C1E0A4-2F7D-4C8E-9A61-3E5F7D2C9B10}
AppName=Lumen Deck
AppVersion={#AppVersion}
AppPublisher=cyipex
AppPublisherURL=https://github.com/cyipex/lumen-deck
AppSupportURL=https://github.com/cyipex/lumen-deck/issues
DefaultDirName={autopf}\Lumen Desk
DefaultGroupName=Lumen Deck
DisableProgramGroupPage=yes
; The Start menu folder was "Lumen Desk" before the rename: do not reuse it.
UsePreviousGroup=no
; Admin rights only to allow Lumen Deck through the Windows firewall, so the tablet can connect.
PrivilegesRequired=admin
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
OutputDir=out
OutputBaseFilename=LumenDeckSetup
SetupIconFile=..\icon.ico
UninstallDisplayIcon={app}\LumenDesk.exe
UninstallDisplayName=Lumen Deck
WizardStyle=modern
Compression=lzma2/ultra64
SolidCompression=yes
CloseApplications=no

[Tasks]
Name: "desktopicon"; Description: "Create a desktop icon"; GroupDescription: "Shortcuts:"
Name: "autostart"; Description: "Start Lumen Deck with Windows (recommended – the tablet needs it running)"; GroupDescription: "Startup:"

[Files]
Source: "out\app\*"; DestDir: "{app}"; Flags: recursesubdirs ignoreversion

; Shortcuts from before the rename to Lumen Deck.
[InstallDelete]
Type: files; Name: "{autoprograms}\Lumen Desk\Lumen Desk.lnk"
Type: files; Name: "{autoprograms}\Lumen Desk\Lumen Deck.lnk"
Type: dirifempty; Name: "{autoprograms}\Lumen Desk"
Type: files; Name: "{autoprograms}\Lumen Desk.lnk"
Type: files; Name: "{autodesktop}\Lumen Desk.lnk"
Type: files; Name: "{userstartup}\Lumen Desk.lnk"

[Icons]
Name: "{group}\Lumen Deck"; Filename: "{app}\LumenDesk.exe"; IconFilename: "{app}\icon.ico"
Name: "{autodesktop}\Lumen Deck"; Filename: "{app}\LumenDesk.exe"; IconFilename: "{app}\icon.ico"; Tasks: desktopicon
Name: "{userstartup}\Lumen Deck"; Filename: "{app}\LumenDesk.exe"; Parameters: "--background"; IconFilename: "{app}\icon.ico"; Tasks: autostart

[Run]
; Let the tablet reach the server (port 47801). Tablets still have to be approved once in the settings.
Filename: "{sys}\netsh.exe"; Parameters: "advfirewall firewall delete rule name=""Lumen Desk"""; Flags: runhidden
Filename: "{sys}\netsh.exe"; Parameters: "advfirewall firewall add rule name=""Lumen Desk"" dir=in action=allow program=""{app}\LumenDesk.exe"" enable=yes"; Flags: runhidden
Filename: "{app}\LumenDesk.exe"; Description: "Open Lumen Deck"; Flags: postinstall nowait skipifsilent

[UninstallRun]
Filename: "{sys}\taskkill.exe"; Parameters: "/F /IM LumenDesk.exe"; Flags: runhidden; RunOnceId: "StopApp"
Filename: "{sys}\netsh.exe"; Parameters: "advfirewall firewall delete rule name=""Lumen Desk"""; Flags: runhidden; RunOnceId: "Firewall"

[Code]
// A running Lumen Deck would lock its files during an update, so stop it first.
function PrepareToInstall(var NeedsRestart: Boolean): String;
var
  Code: Integer;
begin
  Exec(ExpandConstant('{sys}\taskkill.exe'), '/F /IM LumenDesk.exe', '', SW_HIDE, ewWaitUntilTerminated, Code);
  Result := '';
end;
