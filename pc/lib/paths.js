// Where Lumen Deck keeps its files.
//   From the repository: everything inside pc/ (pc/data, pc/bin, ../shared/icons.json).
//   Installed (LumenDesk.exe): program files next to the exe, user data and downloaded tools
//   in %APPDATA%\Lumen Desk, so updates and uninstalling never touch the user's settings.
const path = require('path');

let packaged = false;
try {
  packaged = require('node:sea').isSea();
} catch {}

const PC = path.join(__dirname, '..');
const APP = packaged ? path.dirname(process.execPath) : PC;
const USER = packaged ? path.join(process.env.APPDATA || APP, 'Lumen Desk') : PC;

module.exports = {
  packaged,
  APP,
  UI: path.join(APP, 'ui'),
  ICONS: packaged ? path.join(APP, 'icons.json') : path.join(PC, '..', 'shared', 'icons.json'),
  // Clock fonts shared with the tablet (fonts.json + the font files), shown in the settings preview.
  FONTS: packaged ? path.join(APP, 'fonts') : path.join(PC, '..', 'shared', 'fonts'),
  // The tablet app offered to tablets as an update (lumen-desk.apk + version.json, made by the installer build).
  TABLET: process.env.LUMEN_TABLET_DIR || path.join(APP, 'tablet'),
  // LUMEN_DATA_DIR: a separate data folder for a test copy of the server (pc/test).
  DATA: process.env.LUMEN_DATA_DIR || path.join(USER, 'data'),
  BIN: path.join(USER, 'bin')
};
