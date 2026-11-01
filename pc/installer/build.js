// Builds the Windows installer: LumenDeskSetup.exe
//   1. bundles the server into one JS file (esbuild)
//   2. packs it with Node.js into LumenDesk.exe (Node single executable application)
//   3. builds the installer with Inno Setup (ISCC.exe)
// Run from pc/:  npm run installer
// Needs Inno Setup 6 (https://jrsoftware.org/isdl.php); set ISCC to its path if it is not found.
const { execFileSync } = require('child_process');
const fs = require('fs');
const path = require('path');

const PC = path.join(__dirname, '..');
const OUT = path.join(__dirname, 'out');
const APP = path.join(OUT, 'app');
const EXE = path.join(APP, 'LumenDesk.exe');
const VERSION = require('../package.json').version;

function step(msg) { console.log('\n> ' + msg); }

fs.rmSync(OUT, { recursive: true, force: true });
fs.mkdirSync(APP, { recursive: true });

step('Bundling the server');
require('esbuild').buildSync({
  entryPoints: [path.join(PC, 'main.js')],
  bundle: true,
  platform: 'node',
  target: 'node22',
  format: 'cjs',
  outfile: path.join(OUT, 'bundle.js'),
  external: ['bufferutil', 'utf-8-validate'], // optional speed-ups of "ws", not needed
  logLevel: 'warning'
});

step('Packing Node.js and the server into LumenDesk.exe');
fs.writeFileSync(path.join(OUT, 'sea-config.json'), JSON.stringify({
  main: path.join(OUT, 'bundle.js'),
  output: path.join(OUT, 'sea.blob'),
  disableExperimentalSEAWarning: true
}));
execFileSync(process.execPath, ['--experimental-sea-config', path.join(OUT, 'sea-config.json')], { stdio: 'inherit' });
fs.copyFileSync(process.execPath, EXE);

// Icon and file details (before the blob is injected).
const rcedit = require('rcedit');
const rceditDone = (rcedit.rcedit || rcedit)(EXE, {
  icon: path.join(PC, 'icon.ico'),
  'version-string': { ProductName: 'Lumen Desk', FileDescription: 'Lumen Desk', CompanyName: 'cyipex', LegalCopyright: 'MIT License' },
  'file-version': VERSION,
  'product-version': VERSION
});

Promise.resolve(rceditDone).then(() => {
  execFileSync(process.execPath, [require.resolve('postject/dist/cli.js'), EXE, 'NODE_SEA_BLOB', path.join(OUT, 'sea.blob'),
    '--sentinel-fuse', 'NODE_SEA_FUSE_fce680ab2cc467b6e072b8b5df1996b2'], { stdio: 'inherit' });

  // Windows GUI app instead of a console app: no black window when it starts.
  const buf = fs.readFileSync(EXE);
  const pe = buf.readUInt32LE(0x3c);
  if (buf.toString('ascii', pe, pe + 4) !== 'PE\0\0') throw new Error('not a PE file');
  buf.writeUInt16LE(2, pe + 24 + 68); // OptionalHeader.Subsystem = IMAGE_SUBSYSTEM_WINDOWS_GUI
  fs.writeFileSync(EXE, buf);

  step('Copying the app files');
  fs.cpSync(path.join(PC, 'ui'), path.join(APP, 'ui'), { recursive: true });
  fs.copyFileSync(path.join(PC, '..', 'shared', 'icons.json'), path.join(APP, 'icons.json'));
  fs.copyFileSync(path.join(PC, 'icon.ico'), path.join(APP, 'icon.ico'));

  step('Building the installer (Inno Setup)');
  const iscc = [process.env.ISCC,
    path.join(process.env.LOCALAPPDATA || '', 'Programs', 'Inno Setup 6', 'ISCC.exe'),
    path.join(process.env['ProgramFiles(x86)'] || '', 'Inno Setup 6', 'ISCC.exe'),
    path.join(process.env.ProgramFiles || '', 'Inno Setup 6', 'ISCC.exe')].find((p) => p && fs.existsSync(p));
  if (!iscc) throw new Error('Inno Setup 6 not found. Install it from https://jrsoftware.org/isdl.php or set ISCC.');
  execFileSync(iscc, ['/Q', `/DAppVersion=${VERSION}`, path.join(__dirname, 'LumenDesk.iss')], { stdio: 'inherit' });
  console.log('\nDone: ' + path.join(OUT, 'LumenDeskSetup.exe'));
}).catch((e) => { console.error(e); process.exit(1); });
