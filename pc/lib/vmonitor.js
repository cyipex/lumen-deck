// The virtual monitor (Virtual Display Driver) that the tablet shows as a second monitor.
// While the tablet is not showing it, Windows still has an extra screen the mouse can wander onto,
// so Lumen Desk can detach it and attach it again. This only changes the display layout (like
// Windows Settings → Display), so it needs no admin rights and the driver stays installed.
const { execFile } = require('child_process');

const PS = `
$ErrorActionPreference = 'Stop'
Add-Type -TypeDefinition @'
using System; using System.Runtime.InteropServices;
public class LumenVM {
  [StructLayout(LayoutKind.Sequential, CharSet=CharSet.Unicode)] public struct DD { public int cb;
    [MarshalAs(UnmanagedType.ByValTStr, SizeConst=32)] public string Name; [MarshalAs(UnmanagedType.ByValTStr, SizeConst=128)] public string Text;
    public int Flags; [MarshalAs(UnmanagedType.ByValTStr, SizeConst=128)] public string Id; [MarshalAs(UnmanagedType.ByValTStr, SizeConst=128)] public string Key; }
  [StructLayout(LayoutKind.Sequential, CharSet=CharSet.Unicode)] public struct DM {
    [MarshalAs(UnmanagedType.ByValTStr, SizeConst=32)] public string dmDeviceName; public short dmSpecVersion, dmDriverVersion, dmSize, dmDriverExtra; public int dmFields;
    public int dmPositionX, dmPositionY, dmDisplayOrientation, dmDisplayFixedOutput; public short dmColor, dmDuplex, dmYResolution, dmTTOption, dmCollate;
    [MarshalAs(UnmanagedType.ByValTStr, SizeConst=32)] public string dmFormName; public short dmLogPixels; public int dmBitsPerPel, dmPelsWidth, dmPelsHeight,
    dmDisplayFlags, dmDisplayFrequency, dmICMMethod, dmICMIntent, dmMediaType, dmDitherType, dmReserved1, dmReserved2, dmPanningWidth, dmPanningHeight; }
  [DllImport("user32.dll", CharSet=CharSet.Unicode)] static extern bool EnumDisplayDevices(string dev, int i, ref DD d, int flags);
  [DllImport("user32.dll", CharSet=CharSet.Unicode)] static extern bool EnumDisplaySettings(string name, int mode, ref DM dm);
  [DllImport("user32.dll", CharSet=CharSet.Unicode)] static extern int ChangeDisplaySettingsEx(string name, ref DM dm, IntPtr w, int flags, IntPtr l);
  [DllImport("user32.dll", CharSet=CharSet.Unicode)] static extern int ChangeDisplaySettingsEx(string name, IntPtr dm, IntPtr w, int flags, IntPtr l);
  [DllImport("user32.dll")] static extern bool SetProcessDpiAwarenessContext(IntPtr v);

  public static string Run(string action, int w, int h, int x, int y) {
    SetProcessDpiAwarenessContext((IntPtr)(-4));
    DD vm = new DD(); bool found = false; int pw = 1920;
    for (int i = 0; i < 32; i++) {
      DD d = new DD(); d.cb = Marshal.SizeOf(d);
      if (!EnumDisplayDevices(null, i, ref d, 0)) break;
      bool virt = (d.Text ?? "").IndexOf("Virtual Display", StringComparison.OrdinalIgnoreCase) >= 0
        || (d.Id ?? "").IndexOf("MttVDD", StringComparison.OrdinalIgnoreCase) >= 0
        || (d.Text ?? "").IndexOf("IddSample", StringComparison.OrdinalIgnoreCase) >= 0;
      if (virt && !found) { vm = d; found = true; }
      if ((d.Flags & 4) != 0) { DM p = new DM(); p.dmSize = (short)Marshal.SizeOf(p); if (EnumDisplaySettings(d.Name, -1, ref p)) pw = p.dmPelsWidth; }
    }
    if (!found) return "{\\"found\\":false}";
    int code = 0;
    if (action == "off" || action == "on") {
      DM m = new DM(); m.dmSize = (short)Marshal.SizeOf(m); m.dmFields = 0x20 | 0x80000 | 0x100000;
      if (action == "on") { m.dmPelsWidth = w > 0 ? w : 1280; m.dmPelsHeight = h > 0 ? h : 800; m.dmPositionX = w > 0 ? x : pw; m.dmPositionY = w > 0 ? y : 0; }
      code = ChangeDisplaySettingsEx(vm.Name, ref m, IntPtr.Zero, 1 | 0x10000000, IntPtr.Zero);
      if (code == 0) code = ChangeDisplaySettingsEx(null, IntPtr.Zero, IntPtr.Zero, 0, IntPtr.Zero);
    }
    DD now = new DD(); now.cb = Marshal.SizeOf(now); bool attached = false;
    for (int i = 0; i < 32; i++) { DD d = new DD(); d.cb = Marshal.SizeOf(d); if (!EnumDisplayDevices(null, i, ref d, 0)) break; if (d.Name == vm.Name) { attached = (d.Flags & 1) != 0; break; } }
    DM c = new DM(); c.dmSize = (short)Marshal.SizeOf(c);
    string mode = attached && EnumDisplaySettings(vm.Name, -1, ref c) ? (",\\"w\\":" + c.dmPelsWidth + ",\\"h\\":" + c.dmPelsHeight + ",\\"x\\":" + c.dmPositionX + ",\\"y\\":" + c.dmPositionY) : "";
    return "{\\"found\\":true,\\"attached\\":" + (attached ? "true" : "false") + ",\\"code\\":" + code + mode + "}";
  }
}
'@
[LumenVM]::Run($env:LUMEN_VM_ACTION, [int]$env:LUMEN_VM_W, [int]$env:LUMEN_VM_H, [int]$env:LUMEN_VM_X, [int]$env:LUMEN_VM_Y)
`;
const ENCODED = Buffer.from(PS, 'utf16le').toString('base64');

function run(action, pos) {
  const p = pos && pos.w > 0 ? pos : { w: 0, h: 0, x: 0, y: 0 };
  return new Promise((resolve, reject) => {
    execFile('powershell.exe', ['-NoProfile', '-NonInteractive', '-EncodedCommand', ENCODED], {
      windowsHide: true, timeout: 20000,
      env: { ...process.env, LUMEN_VM_ACTION: action, LUMEN_VM_W: String(p.w), LUMEN_VM_H: String(p.h), LUMEN_VM_X: String(p.x), LUMEN_VM_Y: String(p.y) }
    }, (err, out) => {
      if (err) return reject(err);
      try { resolve(JSON.parse(out.trim().split('\n').pop())); } catch { reject(new Error('Cannot read the displays')); }
    });
  });
}

// One change at a time: Windows rearranges the screens after each one.
let chain = Promise.resolve();
const queue = (fn) => (chain = chain.then(fn, fn));

let last = null; // the last known state, for the settings window
let savePos = () => {};
let loadPos = () => null;

function status() {
  return queue(async () => (last = await run('status')));
}

// Detaches the virtual monitor; remembers where it was so it comes back in the same place.
function off() {
  return queue(async () => {
    const s = await run('status');
    if (!s.found || !s.attached) return (last = s);
    if (s.w > 0) savePos({ w: s.w, h: s.h, x: s.x, y: s.y });
    return (last = await run('off'));
  });
}

function on() {
  return queue(async () => {
    const s = await run('status');
    if (!s.found || s.attached) return (last = s);
    last = await run('on', loadPos());
    // Windows needs a moment before screen capture sees the new monitor.
    await new Promise((r) => setTimeout(r, 1500));
    return last;
  });
}

// pos: get/set functions for the remembered position (kept in the config).
function init(opts) {
  if (opts.savePos) savePos = opts.savePos;
  if (opts.loadPos) loadPos = opts.loadPos;
}

module.exports = { init, status, on, off, last: () => last };
