// PC volume, mute and audio output selection (Windows Core Audio).
// One PowerShell process runs in the background; every command returns the full state as one JSON line.
const { spawn } = require('child_process');
const { EventEmitter } = require('events');
const input = require('./input');

const HELPER = `
$ErrorActionPreference = 'Stop'
Add-Type @"
using System;
using System.Text;
using System.Runtime.InteropServices;
namespace LcAudio {
  [ComImport, Guid("BCDE0395-E52F-467C-8E3D-C4579291692E")] class MMDeviceEnumerator {}
  [ComImport, Guid("870AF99C-171D-4F9E-AF0D-E63DF40C2BC9")] class PolicyConfigClient {}

  [Guid("A95664D2-9614-4F35-A746-DE8DB63617E6"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
  interface IMMDeviceEnumerator {
    int EnumAudioEndpoints(int flow, int mask, out IMMDeviceCollection list);
    int GetDefaultAudioEndpoint(int flow, int role, out IMMDevice dev);
    int GetDevice([MarshalAs(UnmanagedType.LPWStr)] string id, out IMMDevice dev);
  }
  [Guid("0BD7A1BE-7A1A-44DB-8397-CC5392387B5E"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
  interface IMMDeviceCollection { int GetCount(out int n); int Item(int i, out IMMDevice dev); }
  [Guid("D666063F-1587-4E43-81F1-B948E807363F"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
  interface IMMDevice {
    int Activate(ref Guid iid, int ctx, IntPtr p, [MarshalAs(UnmanagedType.IUnknown)] out object o);
    int OpenPropertyStore(int access, out IPropertyStore store);
    int GetId([MarshalAs(UnmanagedType.LPWStr)] out string id);
  }
  [Guid("886D8EEB-8CF2-4446-8D02-CDBA1DBDCF99"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
  interface IPropertyStore { int GetCount(out int n); int GetAt(int i, out PropertyKey k); int GetValue(ref PropertyKey k, out PropVariant v); }
  [StructLayout(LayoutKind.Sequential)] struct PropertyKey { public Guid fmt; public int pid; }
  [StructLayout(LayoutKind.Explicit, Size = 24)] struct PropVariant { [FieldOffset(0)] public short vt; [FieldOffset(8)] public IntPtr p; }
  [Guid("5CDF2C82-841E-4546-9722-0CF74078229A"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
  interface IAudioEndpointVolume {
    int RegisterControlChangeNotify(IntPtr p);
    int UnregisterControlChangeNotify(IntPtr p);
    int GetChannelCount(out uint n);
    int SetMasterVolumeLevel(float db, ref Guid ctx);
    int SetMasterVolumeLevelScalar(float v, ref Guid ctx);
    int GetMasterVolumeLevel(out float db);
    int GetMasterVolumeLevelScalar(out float v);
    int SetChannelVolumeLevel(uint ch, float db, ref Guid ctx);
    int SetChannelVolumeLevelScalar(uint ch, float v, ref Guid ctx);
    int GetChannelVolumeLevel(uint ch, out float db);
    int GetChannelVolumeLevelScalar(uint ch, out float v);
    int SetMute([MarshalAs(UnmanagedType.Bool)] bool mute, ref Guid ctx);
    int GetMute([MarshalAs(UnmanagedType.Bool)] out bool mute);
  }
  // Undocumented Windows 10/11 interface for changing the default output (the system uses it too).
  [Guid("F8679F50-850A-41CF-9C72-430F290290C8"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
  interface IPolicyConfig {
    int A1(); int A2(); int A3(); int A4(); int A5(); int A6(); int A7(); int A8(); int A9(); int A10();
    int SetDefaultEndpoint([MarshalAs(UnmanagedType.LPWStr)] string id, int role);
  }

  // Per-app audio (volume mixer).
  [Guid("77AA99A0-1BD6-484F-8BC7-2C654C9A9B6F"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
  interface IAudioSessionManager2 {
    int GetAudioSessionControl(IntPtr a, int b, out IntPtr c);
    int GetSimpleAudioVolume(IntPtr a, int b, out IntPtr c);
    int GetSessionEnumerator(out IAudioSessionEnumerator e);
  }
  [Guid("E2F5BB11-0570-40CA-ACDD-3AA01277DEE8"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
  interface IAudioSessionEnumerator { int GetCount(out int n); int GetSession(int i, out IAudioSessionControl2 s); }
  [Guid("BFB7FF88-7239-4FC9-8FA2-07C950BE9C6D"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
  interface IAudioSessionControl2 {
    int GetState(out int s); int GetDisplayName(out IntPtr n); int SetDisplayName(IntPtr a, IntPtr b);
    int GetIconPath(out IntPtr p); int SetIconPath(IntPtr a, IntPtr b); int GetGroupingParam(out Guid g); int SetGroupingParam(IntPtr a, IntPtr b);
    int RegisterAudioSessionNotification(IntPtr a); int UnregisterAudioSessionNotification(IntPtr a);
    int GetSessionIdentifier(out IntPtr s); int GetSessionInstanceIdentifier(out IntPtr s);
    int GetProcessId(out uint pid); [PreserveSig] int IsSystemSoundsSession();
  }
  // Session volume per channel (ISimpleAudioVolume cannot be obtained from other processes' sessions).
  [Guid("1C158861-B533-4B30-B1CF-E853E51C59B8"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
  interface IChannelAudioVolume {
    int GetChannelCount(out uint n);
    int SetChannelVolume(uint ch, float v, ref Guid ctx);
    int GetChannelVolume(uint ch, out float v);
  }

  public static class Audio {
    class Sess {
      public string key; public string label; public IChannelAudioVolume vol;
      public float Get() { uint n; vol.GetChannelCount(out n); float m = 0; for (uint i = 0; i < n; i++) { float v; vol.GetChannelVolume(i, out v); if (v > m) m = v; } return m; }
      public void Set(float v) { Guid g = Guid.Empty; uint n; vol.GetChannelCount(out n); for (uint i = 0; i < n; i++) vol.SetChannelVolume(i, v, ref g); }
    }

    // Audio sessions of the default output; key = process name (e.g. merges all Chrome tabs).
    static System.Collections.Generic.List<Sess> Sessions() {
      System.Collections.Generic.List<Sess> res = new System.Collections.Generic.List<Sess>();
      Guid iid = typeof(IAudioSessionManager2).GUID; object o;
      Marshal.ThrowExceptionForHR(Default().Activate(ref iid, 23, IntPtr.Zero, out o));
      IAudioSessionEnumerator en; ((IAudioSessionManager2)o).GetSessionEnumerator(out en);
      int n; en.GetCount(out n);
      for (int i = 0; i < n; i++) {
        IAudioSessionControl2 c; en.GetSession(i, out c);
        int st; c.GetState(out st);
        if (st == 2) continue;
        Sess s = new Sess(); s.vol = (IChannelAudioVolume)c;
        if (c.IsSystemSoundsSession() == 0) { s.key = "system"; s.label = "system"; }
        else {
          uint pid; c.GetProcessId(out pid);
          try {
            System.Diagnostics.Process pr = System.Diagnostics.Process.GetProcessById((int)pid);
            s.key = pr.ProcessName; s.label = pr.ProcessName;
            try { string d = pr.MainModule.FileVersionInfo.FileDescription; if (!string.IsNullOrEmpty(d)) s.label = d; } catch {}
          } catch { continue; }
        }
        res.Add(s);
      }
      return res;
    }

    // APPS | APPVOL key 0..100  ->  {"apps":[{key,name,volume}]}  (mute is handled by Node: volume 0)
    public static string Apps(string line) {
      string[] p = line.Split(new char[] { ' ' }, 3);
      System.Collections.Generic.List<Sess> list = Sessions();
      if (p[0] == "APPVOL") {
        foreach (Sess s in list) if (s.key == p[1]) s.Set(Math.Max(0, Math.Min(100, int.Parse(p[2]))) / 100f);
      }
      System.Collections.Generic.Dictionary<string, bool> seen = new System.Collections.Generic.Dictionary<string, bool>();
      StringBuilder o = new StringBuilder("{\\"apps\\":[");
      foreach (Sess s in list) {
        if (seen.ContainsKey(s.key)) continue;
        seen[s.key] = true;
        float v = s.Get();
        if (seen.Count > 1) o.Append(",");
        o.Append("{\\"key\\":\\"").Append(Esc(s.key)).Append("\\",\\"name\\":\\"").Append(Esc(s.label));
        o.Append("\\",\\"volume\\":").Append((int)Math.Round(v * 100)).Append("}");
      }
      return o.Append("]}").ToString();
    }

    static IMMDeviceEnumerator En() { return (IMMDeviceEnumerator)new MMDeviceEnumerator(); }
    static IAudioEndpointVolume Vol(IMMDevice d) {
      Guid iid = typeof(IAudioEndpointVolume).GUID; object o;
      Marshal.ThrowExceptionForHR(d.Activate(ref iid, 23, IntPtr.Zero, out o));
      return (IAudioEndpointVolume)o;
    }
    static IMMDevice Default() { IMMDevice d; Marshal.ThrowExceptionForHR(En().GetDefaultAudioEndpoint(0, 1, out d)); return d; }
    static string Name(IMMDevice d) {
      IPropertyStore s; if (d.OpenPropertyStore(0, out s) != 0) return "";
      PropertyKey k = new PropertyKey { fmt = new Guid("A45C254E-DF1C-4EFD-8020-67D146A850E0"), pid = 14 };
      PropVariant v; if (s.GetValue(ref k, out v) != 0 || v.vt != 31) return "";
      return Marshal.PtrToStringUni(v.p);
    }
    static string Esc(string s) {
      StringBuilder b = new StringBuilder();
      foreach (char c in s ?? "") { if (c == '"' || c == '\\\\') b.Append('\\\\'); if (c >= ' ') b.Append(c); }
      return b.ToString();
    }
    public static string Run(string line) {
      if (line.StartsWith("APP")) return Apps(line);
      string[] p = line.Split(new char[] { ' ' }, 2);
      Guid g = Guid.Empty;
      if (p[0] == "VOL") Vol(Default()).SetMasterVolumeLevelScalar(Math.Max(0, Math.Min(100, int.Parse(p[1]))) / 100f, ref g);
      else if (p[0] == "MUTE") Vol(Default()).SetMute(p[1] == "1", ref g);
      else if (p[0] == "DEV") {
        IPolicyConfig pc = (IPolicyConfig)new PolicyConfigClient();
        for (int r = 0; r < 3; r++) pc.SetDefaultEndpoint(p[1], r);
      }
      IMMDevice def = Default();
      IAudioEndpointVolume vol = Vol(def);
      float lv; bool mute; string defId;
      vol.GetMasterVolumeLevelScalar(out lv); vol.GetMute(out mute); def.GetId(out defId);
      StringBuilder o = new StringBuilder();
      o.Append("{\\"volume\\":").Append((int)Math.Round(lv * 100)).Append(",\\"muted\\":").Append(mute ? "true" : "false");
      o.Append(",\\"device\\":\\"").Append(Esc(defId)).Append("\\",\\"devices\\":[");
      IMMDeviceCollection list; int n;
      En().EnumAudioEndpoints(0, 1, out list); list.GetCount(out n);
      for (int i = 0; i < n; i++) {
        IMMDevice d; list.Item(i, out d); string id; d.GetId(out id);
        if (i > 0) o.Append(",");
        o.Append("{\\"id\\":\\"").Append(Esc(id)).Append("\\",\\"name\\":\\"").Append(Esc(Name(d))).Append("\\"}");
      }
      return o.Append("]}").ToString();
    }
  }
}
"@
[Console]::OutputEncoding = [Text.Encoding]::UTF8
while ($true) {
  $line = [Console]::In.ReadLine()
  if ($line -eq $null) { break }
  try { $out = [LcAudio.Audio]::Run($line.Trim()) } catch { $out = '{"error":"' + ($_.Exception.Message -replace '["\\\\]', ' ') + '"}' }
  [Console]::Out.WriteLine($out)
}
`;

const events = new EventEmitter();
let proc = null;
let buf = '';
const waiting = [];
let state = null;
let apps = [];
let mixer = false; // also read per-app volume (only when the tablet has a mixer page)
let pollTimer = null;
let pollTick = 0;

function helper() {
  if (proc && proc.exitCode === null) return proc;
  const encoded = Buffer.from(HELPER, 'utf16le').toString('base64');
  proc = spawn('powershell.exe', ['-NoLogo', '-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass', '-EncodedCommand', encoded], {
    stdio: ['pipe', 'pipe', 'ignore'],
    windowsHide: true
  });
  buf = '';
  proc.stdout.setEncoding('utf8');
  proc.stdout.on('data', (d) => {
    buf += d;
    let i;
    while ((i = buf.indexOf('\n')) >= 0) {
      const line = buf.slice(0, i).trim();
      buf = buf.slice(i + 1);
      if (!line.startsWith('{')) continue;
      const w = waiting.shift();
      if (w) w(line);
    }
  });
  proc.on('exit', () => {
    proc = null;
    while (waiting.length) waiting.shift()('{"error":"helper stopped"}');
  });
  return proc;
}

function run(cmd) {
  return new Promise((resolve) => {
    waiting.push((line) => {
      let s;
      try { s = JSON.parse(line); } catch { s = { error: 'bad response' }; }
      if (s.apps) {
        const list = s.apps.map((a) => ({
          ...a,
          name: a.key === 'system' ? 'System sounds' : String(a.name).replace(/.Root$/, ''),
          muted: appMuted.has(a.key) && a.volume === 0,
          volume: appMuted.has(a.key) && a.volume === 0 ? appMuted.get(a.key) : a.volume
        }));
        const changed = JSON.stringify(list) !== JSON.stringify(apps);
        apps = list;
        if (changed) events.emit('apps', apps);
      } else if (!s.error) {
        const changed = JSON.stringify(s) !== JSON.stringify(state);
        state = s;
        if (changed) events.emit('change', s);
      }
      resolve(s);
    });
    helper().stdin.write(cmd + '\r\n');
  });
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

// Windows shows the volume popup only for a media key, not for a change through the API.
// Up + down opens the popup; setVolume then sets the exact value.
function showPopup() {
  input.media('volup');
  input.media('voldown');
}

// Dragging the slider sends many values; only the latest one goes to PowerShell.
let volBusy = false;
let volNext = null;
async function setVolume(v) {
  volNext = Math.max(0, Math.min(100, Math.round(Number(v) || 0)));
  if (volBusy) return;
  volBusy = true;
  showPopup();
  let last = null;
  while (volNext !== null) {
    const n = volNext;
    volNext = null;
    last = n;
    await run('VOL ' + n);
    if (state && state.muted && n > 0) await run('MUTE 0');
  }
  // The popup keys may have moved the volume by 2 %, restore the exact value.
  await sleep(150);
  if (volNext === null) await run('VOL ' + last);
  volBusy = false;
  if (volNext !== null) setVolume(volNext);
}

const setMuted = (m) => run('MUTE ' + (m ? 1 : 0));

// The Mute media key toggles sound and shows the popup at the same time.
async function toggleMute() {
  input.media('mute');
  await sleep(150);
  return run('GET');
}

function setDevice(id) {
  if (!state || !state.devices.some((d) => d.id === id)) throw new Error('Audio output not found');
  return run('DEV ' + id);
}

// Switches to the next output in order (headphones -> speakers -> ...).
function nextDevice() {
  if (!state || state.devices.length < 2) throw new Error('This PC has only one audio output');
  const i = state.devices.findIndex((d) => d.id === state.device);
  return setDevice(state.devices[(i + 1) % state.devices.length].id);
}

// Changes made on the PC (keyboard, Windows) are picked up by polling, only while a tablet is connected.
function setActive(on) {
  if (on && !pollTimer) {
    run('GET');
    pollTimer = setInterval(() => {
      if (volBusy || appBusy || waiting.length) return;
      // Output every second, apps every two seconds (only when the mixer is on the tablet).
      if (mixer && pollTick++ % 2 === 1) run('APPS');
      else run('GET');
    }, 1000);
  } else if (!on && pollTimer) {
    clearInterval(pollTimer);
    pollTimer = null;
  }
}

// ---------- mixer: per-app volume ----------

let appBusy = false;
const appNext = new Map(); // key -> volume, only the latest value while dragging

async function setAppVolume(key, v) {
  key = String(key || '');
  if (!/^[\w .()+-]+$/.test(key)) throw new Error('Unknown app');
  appNext.set(key, Math.max(0, Math.min(100, Math.round(Number(v) || 0))));
  appMuted.delete(key);
  if (appBusy) return;
  appBusy = true;
  while (appNext.size) {
    const [k, n] = appNext.entries().next().value;
    appNext.delete(k);
    await run(`APPVOL ${k} ${n}`);
  }
  appBusy = false;
}

// Muting an app: volume to 0, the previous value is remembered and restored on unmute.
const appMuted = new Map(); // key -> volume before muting

function setAppMuted(key, m) {
  key = String(key || '');
  if (!/^[\w .()+-]+$/.test(key)) throw new Error('Unknown app');
  const app = apps.find((a) => a.key === key);
  if (m) {
    if (!appMuted.has(key)) appMuted.set(key, app ? app.volume : 100);
    return run(`APPVOL ${key} 0`);
  }
  const back = appMuted.has(key) ? appMuted.get(key) : (app ? app.volume : 100);
  appMuted.delete(key);
  return run(`APPVOL ${key} ${back || 50}`);
}

function setMixer(on) {
  mixer = !!on;
}

function current() {
  if (!state) return null;
  const dev = state.devices.find((d) => d.id === state.device);
  return { volume: state.volume, muted: state.muted, output: dev ? dev.name : '', outputs: state.devices.length };
}

module.exports = { setAppVolume, setAppMuted, setMixer, apps: () => apps, warmUp: helper, setVolume, setMuted, toggleMute, setDevice, nextDevice, setActive, current, list: () => (state ? state.devices : []), events };
