// Keyboard and media keys. One PowerShell process keeps running in the background,
// so a shortcut takes milliseconds (not a second like a new process).
const { spawn } = require('child_process');

const HELPER = `
$ErrorActionPreference = 'SilentlyContinue'
Add-Type @"
using System;
using System.Runtime.InteropServices;
public static class LcKeys {
  [StructLayout(LayoutKind.Sequential)] struct KEYBDINPUT { public ushort vk; public ushort scan; public uint flags; public uint time; public IntPtr extra; }
  [StructLayout(LayoutKind.Explicit, Size = 40)] struct INPUT { [FieldOffset(0)] public uint type; [FieldOffset(8)] public KEYBDINPUT ki; }
  [StructLayout(LayoutKind.Sequential)] struct MOUSEINPUT { public int dx; public int dy; public int data; public uint flags; public uint time; public IntPtr extra; }
  [StructLayout(LayoutKind.Explicit, Size = 40)] struct MINPUT { [FieldOffset(0)] public uint type; [FieldOffset(8)] public MOUSEINPUT mi; }
  [DllImport("user32.dll")] static extern uint SendInput(uint n, INPUT[] inputs, int size);
  // The mouse works in real pixels even when Windows uses scaling (e.g. 125 %).
  [DllImport("user32.dll")] public static extern bool SetProcessDpiAwarenessContext(IntPtr v);
  delegate bool EnumProc(IntPtr h, IntPtr l);
  [DllImport("user32.dll")] static extern bool EnumWindows(EnumProc f, IntPtr l);
  [DllImport("user32.dll")] static extern bool IsWindowVisible(IntPtr h);
  [DllImport("user32.dll")] static extern int GetWindowTextLength(IntPtr h);
  [DllImport("user32.dll")] static extern IntPtr GetWindow(IntPtr h, uint cmd);
  [DllImport("user32.dll")] static extern uint GetWindowThreadProcessId(IntPtr h, out uint pid);
  [DllImport("user32.dll")] static extern bool SetForegroundWindow(IntPtr h);
  [DllImport("user32.dll")] static extern bool ShowWindow(IntPtr h, int cmd);
  [DllImport("user32.dll")] static extern bool IsIconic(IntPtr h);
  // Brings the topmost visible window of a program to the front. Pressing Alt unlocks the Windows foreground lock.
  public static void Focus(string exe) {
    IntPtr found = IntPtr.Zero;
    EnumWindows(delegate (IntPtr h, IntPtr l) {
      if (!IsWindowVisible(h) || GetWindowTextLength(h) == 0 || GetWindow(h, 4) != IntPtr.Zero) return true;
      uint pid; GetWindowThreadProcessId(h, out pid);
      try { if (System.Diagnostics.Process.GetProcessById((int)pid).ProcessName.Equals(exe, StringComparison.OrdinalIgnoreCase)) { found = h; return false; } } catch {}
      return true;
    }, IntPtr.Zero);
    if (found == IntPtr.Zero) return;
    Down(0x12, false); Up(0x12, false);
    if (IsIconic(found)) ShowWindow(found, 9);
    SetForegroundWindow(found);
  }
  [DllImport("user32.dll", EntryPoint = "SendInput")] static extern uint SendMouse(uint n, MINPUT[] inputs, int size);
  public static void Mouse(int x, int y, uint flags, int data) {
    MINPUT[] i = new MINPUT[1];
    i[0].type = 0; i[0].mi.dx = x; i[0].mi.dy = y; i[0].mi.flags = flags; i[0].mi.data = data;
    SendMouse(1, i, Marshal.SizeOf(typeof(MINPUT)));
  }
  static void Send(ushort vk, ushort scan, uint flags) {
    INPUT[] i = new INPUT[1];
    i[0].type = 1; i[0].ki.vk = vk; i[0].ki.scan = scan; i[0].ki.flags = flags;
    SendInput(1, i, Marshal.SizeOf(typeof(INPUT)));
  }
  public static void Down(ushort vk, bool ext) { Send(vk, 0, ext ? 1u : 0u); }
  public static void Up(ushort vk, bool ext) { Send(vk, 0, (ext ? 1u : 0u) | 2u); }
  public static void Type(string s) { foreach (char c in s) { Send(0, c, 4u); Send(0, c, 6u); } }
}
"@
[void][LcKeys]::SetProcessDpiAwarenessContext([IntPtr]-4)
[Console]::Out.WriteLine('ready')
while ($true) {
  $line = [Console]::In.ReadLine()
  if ($line -eq $null) { break }
  $p = $line.Split(' ', 3)
  switch ($p[0]) {
    'D' { [LcKeys]::Down([uint16]$p[1], $p[2] -eq '1') }
    'U' { [LcKeys]::Up([uint16]$p[1], $p[2] -eq '1') }
    'T' { [LcKeys]::Type([Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($p[1]))) }
    'F' { [LcKeys]::Focus($p[1]) }
    'M' { $m = $p[1..2] -join ' ' -split ' '; [LcKeys]::Mouse([int]$m[0], [int]$m[1], [uint32]$m[2], [int]$m[3]) }
  }
}
`;

const VK = {
  ctrl: 0x11, control: 0x11, shift: 0x10, alt: 0x12, win: 0x5b, cmd: 0x5b,
  enter: 0x0d, esc: 0x1b, escape: 0x1b, tab: 0x09, space: 0x20, backspace: 0x08,
  delete: 0x2e, del: 0x2e, insert: 0x2d, home: 0x24, end: 0x23, pageup: 0x21, pagedown: 0x22,
  left: 0x25, up: 0x26, right: 0x27, down: 0x28, printscreen: 0x2c, capslock: 0x14,
  playpause: 0xb3, next: 0xb0, prev: 0xb1, stop: 0xb2, volup: 0xaf, voldown: 0xae, mute: 0xad,
  minus: 0xbd, '-': 0xbd, equal: 0xbb, '=': 0xbb, plus: 0xbb, comma: 0xbc, ',': 0xbc, period: 0xbe, '.': 0xbe,
  slash: 0xbf, '/': 0xbf, semicolon: 0xba, ';': 0xba, backtick: 0xc0, '`': 0xc0,
  '[': 0xdb, '\\': 0xdc, ']': 0xdd, "'": 0xde
};
const EXTENDED = new Set([0x21, 0x22, 0x23, 0x24, 0x25, 0x26, 0x27, 0x28, 0x2d, 0x2e, 0x5b, 0xb0, 0xb1, 0xb2, 0xb3, 0xad, 0xae, 0xaf]);

function keyCode(name) {
  const k = name.trim().toLowerCase();
  if (VK[k] !== undefined) return VK[k];
  if (/^[a-z0-9]$/.test(k)) return k.toUpperCase().charCodeAt(0);
  const f = /^f([1-9]|1\d|2[0-4])$/.exec(k);
  if (f) return 0x6f + Number(f[1]);
  return null;
}

let proc = null;

function helper() {
  if (proc && !proc.killed && proc.exitCode === null) return proc;
  // The script goes through -EncodedCommand, so stdin stays free for commands.
  const encoded = Buffer.from(HELPER, 'utf16le').toString('base64');
  proc = spawn('powershell.exe', ['-NoLogo', '-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass', '-EncodedCommand', encoded], {
    stdio: ['pipe', 'pipe', 'ignore'],
    windowsHide: true
  });
  proc.stdout.on('data', () => {});
  proc.on('exit', () => { proc = null; });
  return proc;
}

function send(line) {
  helper().stdin.write(line + '\r\n');
}

// "ctrl+shift+m" -> presses the modifiers and the key, then releases them in reverse order.
function combo(str) {
  const codes = String(str || '').split('+').map(keyCode);
  if (codes.length === 0 || codes.some((c) => c === null)) throw new Error('Unknown key in shortcut: ' + str);
  for (const c of codes) send(`D ${c} ${EXTENDED.has(c) ? 1 : 0}`);
  for (const c of codes.slice().reverse()) send(`U ${c} ${EXTENDED.has(c) ? 1 : 0}`);
}

function media(key) {
  const c = VK[key];
  if (c === undefined) throw new Error('Unknown media key: ' + key);
  send(`D ${c} 1`);
  send(`U ${c} 1`);
}

function type(text) {
  if (text) send('T ' + Buffer.from(String(text), 'utf8').toString('base64'));
}

// Mouse: x, y are 0..65535 across the whole virtual desktop (all monitors).
const MOUSE = { move: 0x1, ldown: 0x2, lup: 0x4, rdown: 0x8, rup: 0x10, mdown: 0x20, mup: 0x40, wheel: 0x800, hwheel: 0x1000, abs: 0x8000, desk: 0x4000 };

function mouse(x, y, flags, data = 0) {
  send(`M ${Math.round(x)} ${Math.round(y)} ${flags} ${Math.round(data)}`);
}

// ---------- touchpad and keyboard on the tablet ----------

const BUTTONS = { left: [MOUSE.ldown, MOUSE.lup], right: [MOUSE.rdown, MOUSE.rup], middle: [MOUSE.mdown, MOUSE.mup] };
const MODIFIERS = ['ctrl', 'shift', 'alt', 'win'];
// Mouse buttons and modifier keys the tablet holds down; released when it lets go or disconnects.
const heldButtons = new Set();
const heldKeys = new Set();

// Relative movement (pixels), buttons and scrolling (wheel: 120 = one notch, positive = up / right).
function pad(msg) {
  const n = (v, max) => Math.max(-max, Math.min(max, Math.round(Number(v) || 0)));
  const name = BUTTONS[msg.b] ? msg.b : 'left';
  const [down, up] = BUTTONS[name];
  switch (msg.a) {
    case 'move': return mouse(n(msg.dx, 4000), n(msg.dy, 4000), MOUSE.move);
    case 'click': mouse(0, 0, down); return mouse(0, 0, up);
    case 'down': heldButtons.add(name); return mouse(0, 0, down);
    case 'up': heldButtons.delete(name); return mouse(0, 0, up);
    case 'scroll':
      if (msg.dy) mouse(0, 0, MOUSE.wheel, n(msg.dy, 2400));
      if (msg.dx) mouse(0, 0, MOUSE.hwheel, n(msg.dx, 2400));
      return;
    case 'release': return releaseAll();
  }
}

// One key with the modifiers pressed just for it, e.g. { k: 't', mods: ['ctrl', 'shift'] }. Held modifiers are already down.
function key(msg) {
  const mods = (Array.isArray(msg.mods) ? msg.mods : []).filter((m) => MODIFIERS.includes(m) && !heldKeys.has(m));
  combo([...mods, String(msg.k)].join('+'));
}

// A modifier that stays down (locked on the tablet keyboard, or Ctrl while pinching to zoom).
function hold(name, down) {
  if (!MODIFIERS.includes(name)) return;
  const c = keyCode(name);
  const ext = EXTENDED.has(c) ? 1 : 0;
  if (down && !heldKeys.has(name)) { heldKeys.add(name); send(`D ${c} ${ext}`); }
  if (!down && heldKeys.has(name)) { heldKeys.delete(name); send(`U ${c} ${ext}`); }
}

function releaseAll() {
  for (const b of heldButtons) mouse(0, 0, BUTTONS[b][1]);
  heldButtons.clear();
  for (const k of [...heldKeys]) hold(k, false);
}

// Brings a program window to the front, e.g. "chrome".
function focus(exe) {
  if (/^[\w.-]+$/.test(exe)) send('F ' + exe);
}

function validCombo(str) {
  return String(str || '').split('+').every((k) => keyCode(k) !== null);
}

module.exports = { warmUp: helper, combo, media, type, validCombo, mouse, MOUSE, focus, pad, key, hold, releaseAll };
