' Starts the Lumen Desk server in the background (if not running yet) and opens the settings as an app window.
' The /server argument starts only the server (for starting with Windows).
Set sh = CreateObject("WScript.Shell")
Set fs = CreateObject("Scripting.FileSystemObject")
dir = fs.GetParentFolderName(WScript.ScriptFullName)
sh.CurrentDirectory = dir
' run.js is the supervisor: it starts the server and restarts it after a crash.
sh.Run "node """ & dir & "\run.js""", 0, False

If WScript.Arguments.Count = 0 Then
  WScript.Sleep 900
  edge = sh.ExpandEnvironmentStrings("%ProgramFiles(x86)%") & "\Microsoft\Edge\Application\msedge.exe"
  If Not fs.FileExists(edge) Then edge = sh.ExpandEnvironmentStrings("%ProgramFiles%") & "\Microsoft\Edge\Application\msedge.exe"
  If fs.FileExists(edge) Then
    sh.Run """" & edge & """ --app=http://127.0.0.1:47803/ --window-size=1500,920", 1, False
  Else
    sh.Run "http://127.0.0.1:47803/", 1, False
  End If
End If
