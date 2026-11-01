// File/folder picker through a Windows dialog, and the list of installed apps.
// A browser cannot find out the full path of a file, so the server opens the dialog.
const { execFile } = require('child_process');

function powershell(script, { sta = false, timeout = 0 } = {}) {
  const args = ['-NoLogo', '-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass'];
  if (sta) args.push('-STA');
  args.push('-EncodedCommand', Buffer.from('[Console]::OutputEncoding = [Text.Encoding]::UTF8\n' + script, 'utf16le').toString('base64'));
  return new Promise((resolve, reject) => {
    execFile('powershell.exe', args, { windowsHide: true, timeout, maxBuffer: 8 * 1024 * 1024 }, (err, out) => {
      if (err && !out) reject(err);
      else resolve(String(out || '').trim());
    });
  });
}

// The dialog is shown on top, so it does not end up behind the settings window.
const OWNER = `
Add-Type -AssemblyName System.Windows.Forms
$owner = New-Object System.Windows.Forms.Form
$owner.TopMost = $true
$owner.ShowInTaskbar = $false
$owner.StartPosition = 'CenterScreen'
$owner.Size = New-Object System.Drawing.Size(1, 1)
$owner.Show()
$owner.Activate()
`;

async function pick(mode) {
  const script = mode === 'folder'
    ? OWNER + `
$d = New-Object System.Windows.Forms.FolderBrowserDialog
$d.Description = 'Choose a folder for Lumen Desk'
if ($d.ShowDialog($owner) -eq 'OK') { $d.SelectedPath }
$owner.Close()`
    : OWNER + `
$d = New-Object System.Windows.Forms.OpenFileDialog
$d.Title = 'Choose a file or program for Lumen Desk'
$d.Filter = 'Programs and shortcuts (*.exe;*.lnk;*.bat;*.cmd;*.url)|*.exe;*.lnk;*.bat;*.cmd;*.url|All files (*.*)|*.*'
$d.FilterIndex = 2
$d.InitialDirectory = [Environment]::GetFolderPath('Desktop')
$d.DereferenceLinks = $false
if ($d.ShowDialog($owner) -eq 'OK') { $d.FileName }
$owner.Close()`;
  const path = await powershell(script, { sta: true });
  return path || null;
}

let appsCache = null;
let appsAt = 0;

// Apps from Start, including Microsoft Store apps (started through shell:AppsFolder).
async function apps() {
  if (appsCache && Date.now() - appsAt < 5 * 60 * 1000) return appsCache;
  const out = await powershell('Get-StartApps | Select-Object Name, AppID | ConvertTo-Json -Compress', { timeout: 20000 });
  let list = [];
  try { list = JSON.parse(out); } catch {}
  if (!Array.isArray(list)) list = list ? [list] : [];
  const seen = new Set();
  appsCache = list
    .filter((a) => a && a.Name && a.AppID && !/uninstall/i.test(a.Name))
    .map((a) => ({ name: a.Name, id: a.AppID }))
    .filter((a) => !seen.has(a.name) && seen.add(a.name))
    .sort((a, b) => a.name.localeCompare(b.name, 'sk'));
  appsAt = Date.now();
  return appsCache;
}

module.exports = { pick, apps };
