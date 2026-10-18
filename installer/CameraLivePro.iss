; Instalador do Camera Live Pro Receiver (Inno Setup 6). Compilado no GitHub Actions (runner Windows).
; Pré-requisito: `dotnet publish ... -o publish\receiver` (self-contained: não exige instalar o .NET).
#define AppName "Camera Live Pro Receiver"
#ifndef AppVersion
  #define AppVersion "0.1.0"
#endif
#define AppExe "CameraLiveProReceiver.exe"

[Setup]
AppId={{B3F1C0A2-6D7E-4C55-9A4E-5C1D0E7A2F10}
AppName={#AppName}
AppVersion={#AppVersion}
AppPublisher=Camera Live Pro
DefaultDirName={autopf}\CameraLivePro
DefaultGroupName=Camera Live Pro
DisableProgramGroupPage=yes
DisableDirPage=no
OutputDir=..\dist
OutputBaseFilename=CameraLivePro-Setup-x64
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
MinVersion=10.0
PrivilegesRequired=admin
Compression=lzma2
SolidCompression=yes
WizardStyle=modern
UninstallDisplayIcon={app}\{#AppExe}
UninstallDisplayName={#AppName}

[Languages]
Name: "brazilianportuguese"; MessagesFile: "compiler:Languages\BrazilianPortuguese.isl"

[Tasks]
Name: "desktopicon"; Description: "Criar atalho na área de trabalho"; Flags: unchecked
Name: "firewall"; Description: "Permitir o receptor no Firewall do Windows (somente redes privadas) - recomendado"

[Files]
Source: "..\publish\receiver\*"; DestDir: "{app}"; Flags: recursesubdirs ignoreversion

[Icons]
Name: "{autoprograms}\Camera Live Pro\Camera Live Pro Receiver"; Filename: "{app}\{#AppExe}"
Name: "{autoprograms}\Camera Live Pro\Desinstalar"; Filename: "{uninstallexe}"
Name: "{autodesktop}\Camera Live Pro Receiver"; Filename: "{app}\{#AppExe}"; Tasks: desktopicon

[Run]
Filename: "{sys}\netsh.exe"; Parameters: "advfirewall firewall add rule name=""Camera Live Pro Receiver (TCP)"" dir=in action=allow program=""{app}\{#AppExe}"" protocol=TCP localport=47800 profile=private"; Flags: runhidden; Tasks: firewall
Filename: "{sys}\netsh.exe"; Parameters: "advfirewall firewall add rule name=""Camera Live Pro Receiver (UDP)"" dir=in action=allow program=""{app}\{#AppExe}"" protocol=UDP localport=47801 profile=private"; Flags: runhidden; Tasks: firewall
Filename: "{app}\{#AppExe}"; Description: "Abrir o Camera Live Pro Receiver"; Flags: nowait postinstall skipifsilent

[UninstallRun]
Filename: "{sys}\netsh.exe"; Parameters: "advfirewall firewall delete rule name=""Camera Live Pro Receiver (TCP)"""; Flags: runhidden; RunOnceId: "FwTcp"
Filename: "{sys}\netsh.exe"; Parameters: "advfirewall firewall delete rule name=""Camera Live Pro Receiver (UDP)"""; Flags: runhidden; RunOnceId: "FwUdp"
