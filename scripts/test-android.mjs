#!/usr/bin/env node
// End-to-end test of the APK on a real Android runtime (an emulator in CI), driven over adb.
// Where scripts/test-shell.mjs runs the shell in desktop Chromium against a stand-in for the
// Java bridge, this runs the real thing: MainActivity, ShellBridge, FileStore, MediaStore,
// DocumentsUI, the print spooler and Android System WebView.
//
// Usage: NODE_PATH="$(npm root -g)" node scripts/test-android.mjs <apk> [out dir]
// Needs adb with one device online, and the playwright package (no browser download: Playwright
// attaches to the app's own WebView, which the userdebug system images used in CI expose).
import { createRequire } from 'node:module';
import { spawn, spawnSync } from 'node:child_process';
import crypto from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';

const require = createRequire(import.meta.url);
const { _android: android } = require('playwright');

const PKG = 'com.appm.pdfcraft';
const MAIN = `${PKG}/.MainActivity`;
const LAUNCHER = `${PKG}/.LauncherActivity`;
const DL = '/sdcard/Download';
const apk = process.argv[2];
const out = path.resolve(process.argv[3] || 'build/emulator');
if (!apk || !fs.existsSync(apk)) {
  console.error('usage: test-android.mjs <apk> [out dir]');
  process.exit(2);
}
fs.mkdirSync(out, { recursive: true });
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

// ---- adb ----

function adb(args, { input, binary = false, timeout = 120000 } = {}) {
  const r = spawnSync('adb', args, { input, timeout, maxBuffer: 256 << 20, encoding: binary ? 'buffer' : 'utf8' });
  if (r.error) throw r.error;
  return { code: r.status, out: r.stdout, err: binary ? String(r.stderr) : r.stderr };
}
/** A shell command on the device; its output (stdout and stderr). */
function sh(cmd, opts) {
  const r = adb(['shell', cmd], opts);
  return `${r.out || ''}${r.err || ''}`;
}
function shOk(cmd) {
  const r = adb(['shell', cmd]);
  if (r.code !== 0) throw new Error(`adb shell ${cmd}: exit ${r.code}: ${r.out}${r.err}`);
  return r.out;
}
const pidOf = () => sh(`pidof ${PKG}`).trim();
const top = () => (sh('dumpsys activity activities').match(/topResumedActivity=.*|mResumedActivity=.*/) || [''])[0];
async function waitTop(re, ms = 30000) {
  const end = Date.now() + ms;
  for (;;) {
    const t = top();
    if (re.test(t)) return t;
    if (Date.now() > end) return null;
    await sleep(500);
  }
}
async function until(fn, ms, step = 500) {
  const end = Date.now() + ms;
  for (;;) {
    const v = await fn();
    if (v) return v;
    if (Date.now() > end) return v;
    await sleep(step);
  }
}
function screenshot(name) {
  const r = adb(['exec-out', 'screencap', '-p'], { binary: true });
  if (r.code === 0 && r.out.length) fs.writeFileSync(path.join(out, name), r.out);
}
const sha = (file) => (sh(`sha256sum ${file} 2>/dev/null`).split(/\s+/)[0] || '');
const head = (file) => sh(`head -c 5 ${file} 2>/dev/null`);

/** The MediaStore id of a file pushed to Download/, once MediaProvider has seen it. */
async function mediaId(name) {
  const where = `_data='/storage/emulated/0/Download/${name}'`;
  return until(() => {
    const m = sh(`content query --uri content://media/external/downloads --projection _id --where "${where}"`).match(/_id=(\d+)/);
    if (m) return m[1];
    sh(`content call --uri content://media --method scan_file --arg /storage/emulated/0/Download/${name}`);
    return null;
  }, 20000);
}

// ---- logcat: everything to a file; checks read PdfCraft's lines from it ----

const logFile = path.join(out, 'logcat.txt');
let logcat;
function startLogcat() {
  logcat = spawn('adb', ['logcat', '-v', 'threadtime'], { stdio: ['ignore', fs.openSync(logFile, 'a'), 'ignore'] });
}
const logMark = () => (fs.existsSync(logFile) ? fs.statSync(logFile).size : 0);
const logSince = (mark) => (fs.existsSync(logFile) ? fs.readFileSync(logFile).subarray(mark).toString('utf8') : '');
/** PdfCraft's own log lines since `mark`. */
const ours = (mark) => logSince(mark).split('\n').filter((l) => / PdfCraft\s*:/.test(l));
async function waitLog(re, mark, ms = 30000) {
  return until(() => ours(mark).find((l) => re.test(l)), ms);
}

// ---- checks ----

const results = [];
function check(name, ok, detail = '') {
  results.push({ name, ok: !!ok });
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? `  (${detail})` : ''}`);
}

/** A small one-page PDF with `text` on it. */
function makePdf(text) {
  const objs = [
    '<< /Type /Catalog /Pages 2 0 R >>',
    '<< /Type /Pages /Kids [3 0 R] /Count 1 >>',
    '<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents 4 0 R /Resources << /Font << /F1 5 0 R >> >> >>',
    null,
    '<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>',
  ];
  const stream = `BT /F1 36 Tf 72 680 Td (${text}) Tj ET`;
  objs[3] = `<< /Length ${stream.length} >>\nstream\n${stream}\nendstream`;
  let pdf = '%PDF-1.4\n';
  const offsets = [];
  objs.forEach((o, i) => {
    offsets.push(pdf.length);
    pdf += `${i + 1} 0 obj\n${o}\nendobj\n`;
  });
  const xref = pdf.length;
  pdf += `xref\n0 ${objs.length + 1}\n0000000000 65535 f \n`;
  for (const o of offsets) pdf += `${String(o).padStart(10, '0')} 00000 n \n`;
  pdf += `trailer\n<< /Size ${objs.length + 1} /Root 1 0 R >>\nstartxref\n${xref}\n%%EOF\n`;
  return Buffer.from(pdf, 'latin1');
}

/** Push a PDF to Download/ and return its MediaStore URI. */
async function pushPdf(name, text) {
  const local = path.join(out, name);
  fs.writeFileSync(local, makePdf(text));
  adb(['push', local, `${DL}/${name}`]);
  const id = await mediaId(name);
  if (!id) throw new Error(`MediaStore never listed ${name}`);
  return `content://media/external/downloads/${id}`;
}

// ---- the WebView ----

let device;
let page;
let app;

/** Attach to the WebView of PdfCraft's current process; returns the shell page. */
async function attach() {
  const pid = await until(() => pidOf(), 30000);
  if (!pid) throw new Error('PdfCraft is not running');
  const webView = await device.webView({ socketName: `webview_devtools_remote_${pid}` }, { timeout: 60000 });
  const first = await webView.page();
  page = first.context().pages().find((p) => p.url().includes('/app/shell/')) || first;
  page.setDefaultTimeout(60000);
  return page;
}

/** Wait for the shell to report PdfCraft ready; returns null, or the error screen's text. */
async function waitReady(ms = 300000) {
  // The shell shows a "still starting" notice after 45 s, with a Wait button; that is no error.
  await page.waitForFunction(() => document.getElementById('splash').hidden
    && (document.getElementById('error').hidden || document.getElementById('wait').hidden), null, { timeout: ms });
  const error = await page.evaluate(() => (document.getElementById('error').hidden ? null : document.getElementById('error-text').textContent));
  app = page.frames().find((f) => f.url().includes('/app/pdfcraft/index.html'));
  return error;
}

/** Record what passes between the shell and PdfCraft from now on (window.__seen, __opened). */
async function listen() {
  await page.evaluate(() => {
    window.__seen = [];
    window.__dirty = null;
    window.addEventListener('message', (e) => {
      if (!e.data || typeof e.data.type !== 'string') return;
      window.__seen.push(e.data.type);
      if (e.data.type === 'pdfcraft:dirty') window.__dirty = !!e.data.dirty;
    });
    window.__marker = window.__marker || Math.random().toString(36).slice(2);
  });
  await app.evaluate(() => {
    window.__opened = [];
    window.addEventListener('message', (e) => {
      if (e.data && e.data.type === 'pdfcraft:open') window.__opened.push({ name: e.data.name, id: e.data.id });
    });
  });
}

async function focus() {
  const box = await page.frameLocator('#app').locator('canvas').boundingBox();
  await page.mouse.click(box.x + box.width / 2, box.y + box.height * 0.6);
}

async function palette(command) {
  await focus();
  await page.keyboard.press('Control+k');
  // The palette's search field brings up the on-screen keyboard, which resizes the page.
  await sleep(2000);
  await page.keyboard.type(command, { delay: 50 });
  await sleep(1500);
  await page.keyboard.press('Enter');
  await sleep(2000);
}

/** Make an edit PdfCraft reports as unsaved work, trying again if a keystroke went astray. */
async function edit() {
  for (let attempt = 0; attempt < 3; attempt++) {
    await page.evaluate(() => { window.__dirty = null; });
    await palette('Rotate pages clockwise');
    if (await until(() => page.evaluate(() => window.__dirty === true), 10000)) return true;
    await page.keyboard.press('Escape');
    await sleep(1000);
  }
  return false;
}

async function opened(name, ms = 30000) {
  return until(() => app.evaluate((n) => window.__opened.find((o) => o.name === n) || null, name), ms);
}

const marker = () => page.evaluate(() => window.__marker).catch(() => null);

// ---- uiautomator, for the system's own screens (pickers, print dialog) ----

function uiNodes() {
  // A failed dump writes nothing: remove the last one first, so it can't be read as this one.
  sh('rm -f /sdcard/ui.xml; uiautomator dump /sdcard/ui.xml');
  const xml = adb(['exec-out', 'cat', '/sdcard/ui.xml']).out || '';
  return [...xml.matchAll(/<node [^>]*>/g)].map(([n]) => {
    const attr = (k) => (n.match(new RegExp(` ${k}="([^"]*)"`)) || [])[1] || '';
    const b = attr('bounds').match(/\[(\d+),(\d+)\]\[(\d+),(\d+)\]/);
    return {
      text: attr('text'), id: attr('resource-id'), desc: attr('content-desc'), pkg: attr('package'),
      enabled: attr('enabled') === 'true',
      x: b ? (+b[1] + +b[3]) / 2 : 0, y: b ? (+b[2] + +b[4]) / 2 : 0,
    };
  });
}
async function tapNode(match, ms = 15000) {
  const node = await until(() => uiNodes().find(match), ms, 1000);
  if (!node) return null;
  sh(`input tap ${Math.round(node.x)} ${Math.round(node.y)}`);
  return node;
}

// ---- the run ----

async function main() {
  // The device: awake, unlocked, upright.
  shOk('svc power stayon true');
  sh('input keyevent KEYCODE_WAKEUP');
  sh('wm dismiss-keyguard');
  sh('settings put system accelerometer_rotation 0');
  sh('settings put system user_rotation 0');
  sh(`rm -rf ${DL}/PdfCraft ${DL}/ci-*.pdf`);
  const info = {
    build: sh('getprop ro.build.fingerprint').trim(),
    type: sh('getprop ro.build.type').trim(),
    sdk: sh('getprop ro.build.version.sdk').trim(),
    webview: (sh('dumpsys webviewupdate').match(/Current WebView package \(name, version\): \((.*)\)/) || [])[1] || '?',
  };
  console.log(`device: ${JSON.stringify(info)}`);
  fs.writeFileSync(path.join(out, 'device.json'), JSON.stringify(info, null, 2));

  // 1. Install, and start from the launcher icon.
  adb(['uninstall', PKG]);
  const install = adb(['install', '-r', '-g', apk], { timeout: 300000 });
  check('the APK installs', /Success/.test(install.out), (install.out + install.err).trim().split('\n').pop());
  startLogcat();
  let mark = logMark();
  sh(`am start -W -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -f 0x10200000 -n ${LAUNCHER}`);
  check('the launcher icon opens PdfCraft', await waitTop(new RegExp(MAIN.replace('.', '\\.'))), top());

  device = (await android.devices())[0];
  if (!device) throw new Error('Playwright sees no device');
  device.setDefaultTimeout(60000);
  await attach();
  const t0 = Date.now();
  const startError = await waitReady();
  const gl = await page.evaluate(() => {
    const c = document.createElement('canvas').getContext('webgl2');
    const ext = c && c.getExtension('WEBGL_debug_renderer_info');
    return c ? (ext ? c.getParameter(ext.UNMASKED_RENDERER_WEBGL) : 'webgl2') : 'no WebGL 2';
  });
  check('PdfCraft starts in Android System WebView', !startError && app,
    startError ? `error screen: ${startError}` : `${((Date.now() - t0) / 1000).toFixed(0)} s, ${gl}`);
  check('the shell reports PdfCraft ready to Android', await waitLog(/PdfCraft ready/, mark, 10000));
  if (startError || !app) {
    screenshot('0-error.png');
    return;
  }
  await listen();
  await sleep(1500);
  screenshot('1-started.png');

  // 2. "Open with" from another app, with write access: Save writes the file in place.
  const rwUri = await pushPdf('ci-rw.pdf', 'Opened with PdfCraft');
  const rwBefore = sha(`${DL}/ci-rw.pdf`);
  mark = logMark();
  sh(`am start -W -a android.intent.action.VIEW -t application/pdf -d ${rwUri} --grant-read-uri-permission --grant-write-uri-permission -n ${MAIN}`);
  const rw = await opened('ci-rw.pdf');
  check('a PDF opened with PdfCraft from another app opens in it', rw && /^f\d+$/.test(rw.id), JSON.stringify(rw));
  await sleep(1500);
  screenshot('2-opened.png');
  check('an edit is reported as unsaved work', await edit());
  mark = logMark();
  await focus();
  await page.keyboard.press('Control+s');
  const savedRw = await waitLog(/toast: Saved ci-rw\.pdf/, mark, 30000);
  const rwAfter = sha(`${DL}/ci-rw.pdf`);
  check('Save writes the edited PDF back over the original', savedRw && rwAfter !== rwBefore && head(`${DL}/ci-rw.pdf`) === '%PDF-',
    savedRw ? `${rwBefore.slice(0, 8)} -> ${rwAfter.slice(0, 8)}` : ours(mark).slice(-3).join(' | '));
  check('... with no copy left behind', !sh(`ls ${DL}/PdfCraft 2>/dev/null`).includes('ci-rw'));

  // 3. "Open with" read-only: Save keeps a copy in Download/PdfCraft and leaves the original.
  const roUri = await pushPdf('ci-ro.pdf', 'Read only');
  const roBefore = sha(`${DL}/ci-ro.pdf`);
  sh(`am start -W -a android.intent.action.VIEW -t application/pdf -d ${roUri} --grant-read-uri-permission -n ${MAIN}`);
  check('a read-only PDF opens too', await opened('ci-ro.pdf'));
  await sleep(1500);
  check('... and can be edited', await edit());
  mark = logMark();
  await focus();
  await page.keyboard.press('Control+s');
  const savedRo = await waitLog(/toast: Saved to Download\/PdfCraft\/ci-ro\.pdf/, mark, 30000);
  check('Save of a read-only file goes to Download/PdfCraft', savedRo && head(`${DL}/PdfCraft/ci-ro.pdf`) === '%PDF-',
    savedRo || ours(mark).slice(-3).join(' | '));
  check('... and leaves the original as it was', sha(`${DL}/ci-ro.pdf`) === roBefore);

  // 4. Save As: Android's "save to" picker; the launcher icon brings it back rather than closing
  //    it; saving there writes the file.
  mark = logMark();
  await focus();
  await page.keyboard.press('Control+Shift+s');
  const picker = await waitTop(/documentsui/i, 30000);
  check('Save As opens Android\'s "save to" picker', picker, top());
  if (picker) {
    await sleep(1500);
    screenshot('3-save-as-picker.png');
    sh('input keyevent KEYCODE_HOME');
    await sleep(1500);
    sh(`am start -W -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -f 0x10200000 -n ${LAUNCHER}`);
    await sleep(2000);
    check('the launcher icon brings back an open picker instead of closing it', /documentsui/i.test(top()), top());
    let saved = null;
    if (/documentsui/i.test(top())) {
      const isSave = (n) => /documentsui/.test(n.pkg) && n.enabled && (/:id\/button1$/.test(n.id) || /^save$/i.test(n.text));
      await tapNode(isSave);
      saved = await waitLog(/toast: Saved (?!to )/, mark, 15000);
      if (!saved && /documentsui/i.test(top())) {
        // No folder chosen yet: pick Downloads from the roots and save there.
        await tapNode((n) => /show roots/i.test(n.desc));
        await sleep(1000);
        await tapNode((n) => /documentsui/.test(n.pkg) && /^downloads$/i.test(n.text));
        await sleep(1500);
        await tapNode(isSave);
        saved = await waitLog(/toast: Saved (?!to )/, mark, 15000);
      }
    }
    check('saving in the picker writes the file there', saved, saved || `${top()} | ${ours(mark).slice(-3).join(' | ')}`);
    if (!/pdfcraft/.test(top())) sh('input keyevent KEYCODE_BACK');
    await waitTop(/pdfcraft/, 10000);
  }

  // 5. Printing: sheets (PNGs in a print frame, as PdfCraft's Print makes them) reach Android's
  //    print dialog through the shell, ShellBridge and Printer.
  mark = logMark();
  await app.evaluate(async () => {
    const c = document.createElement('canvas');
    c.width = 850; c.height = 1100;
    const g = c.getContext('2d');
    g.fillStyle = '#fff'; g.fillRect(0, 0, 850, 1100);
    g.fillStyle = '#12a58a'; g.fillRect(100, 100, 650, 200);
    const png = await new Promise((r) => c.toBlob(r, 'image/png'));
    const url = URL.createObjectURL(png);
    const frame = document.createElement('iframe');
    frame.srcdoc = '<!doctype html><style>@page{size:612pt 792pt;margin:0}div{width:612pt;height:792pt}</style>'
      + `<body><div><img src="${url}"></div><div class="turned"><img src="${url}"></div></body>`;
    frame.addEventListener('load', () => setTimeout(() => frame.contentWindow.print(), 50));
    document.body.appendChild(frame);
  });
  const dialog = await waitTop(/printspooler/i, 60000);
  check('printing opens Android\'s print dialog', dialog, dialog || ours(mark).slice(-3).join(' | '));
  if (dialog) {
    // The preview renders the PDF Printer wrote: a page, or the print spooler's error message.
    const shown = (await until(() => {
      const nodes = uiNodes();
      return nodes.some((n) => /printspooler:id\/(page_content|message)$/.test(n.id)) ? nodes : null;
    }, 30000, 1000)) || [];
    screenshot('4-print-dialog.png');
    const error = shown.find((n) => /printspooler:id\/message$/.test(n.id));
    check('... and it can render what PdfCraft sent', shown.some((n) => /printspooler:id\/page_content$/.test(n.id)) && !error,
      error ? error.text : shown.map((n) => n.text).filter(Boolean).join(' | ').slice(0, 200));
    sh('input keyevent KEYCODE_BACK');
    await waitTop(/pdfcraft/, 15000);
  }

  // 6. Configuration changes keep PdfCraft and its documents (no recreated activity).
  // Each change must reach the activity (it logs "configuration changed") and leave the same
  // page and process.
  const pid = pidOf();
  const before = await marker();
  check('the page is still there before the configuration changes', before);
  const kept = async (change) => {
    const seen = await waitLog(/configuration changed: /, mark, 15000);
    await sleep(1500);
    return seen && change.test(seen) && before && (await marker()) === before && pidOf() === pid;
  };
  mark = logMark();
  sh('settings put system user_rotation 1');
  check('turning the phone keeps PdfCraft and its documents', await kept(/ land /));
  screenshot('5-landscape.png');
  sh('settings put system user_rotation 0');
  await sleep(2000);
  mark = logMark();
  sh('cmd uimode night yes');
  check('switching to Dark theme keeps PdfCraft and its documents', await kept(/ night /));
  screenshot('6-dark-theme.png');
  sh('cmd uimode night no');
  await sleep(2000);
  mark = logMark();
  sh('settings put secure theme_customization_overlay_packages \'{"android.theme.customization.system_palette":"B3261E","android.theme.customization.color_source":"preset"}\'');
  await sleep(5000);
  const themed = ours(mark).some((l) => /configuration changed: /.test(l));
  check('a wallpaper or theme colour change keeps PdfCraft and its documents', before && (await marker()) === before && pidOf() === pid,
    themed ? 'the change reached the activity in place' : 'no configuration change reached the activity');
  sh('settings delete secure theme_customization_overlay_packages');
  await sleep(2000);

  // 7. Android ends PdfCraft in the background: it starts again cleanly, and the file that started
  //    it is not opened a second time.
  await device.close();
  sh(`am force-stop ${PKG}`);
  const coldUri = await pushPdf('ci-cold.pdf', 'Cold start');
  mark = logMark();
  sh(`am start -W -a android.intent.action.VIEW -t application/pdf -d ${coldUri} --grant-read-uri-permission -n ${MAIN}`);
  device = (await android.devices())[0];
  await attach();
  const coldError = await waitReady();
  // The shell drains Android's queue once PdfCraft is ready: nothing should still be waiting.
  const leftover = coldError ? null : await page.evaluate(() => AndroidShell.pending()).catch(() => null);
  await sleep(3000); // Time for the file's fetch to fail, if it is going to.
  const coldLines = ours(mark).filter((l) => /incoming f\d+: |Couldn't open/.test(l));
  check('"Open with" starts PdfCraft when it isn\'t running and opens the file',
    !coldError && leftover === '[]' && coldLines.some((l) => /incoming f\d+: /.test(l))
      && !coldLines.some((l) => /Couldn't open/.test(l)),
    coldError ? `error screen: ${coldError}` : `pending ${leftover}; ${coldLines.join(' | ')}`);
  sh('input keyevent KEYCODE_HOME');
  await sleep(2000);
  sh(`am kill ${PKG}`);
  const killed = await until(() => !pidOf(), 15000);
  check('Android can end PdfCraft in the background', killed);
  await device.close();
  mark = logMark();
  sh(`am start -W -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -f 0x10200000 -n ${LAUNCHER}`);
  device = (await android.devices())[0];
  await attach();
  const again = await waitReady();
  check('it starts again from the launcher', !again && (await waitLog(/PdfCraft ready/, mark, 10000)), again || '');
  await sleep(3000);
  check('... without opening the file that first started it again', !ours(mark).some((l) => /incoming f\d+: /.test(l)),
    ours(mark).filter((l) => /incoming/.test(l)).join(' | '));
  await listen();

  // 8. The page's process dies (as when Android reclaims its memory): PdfCraft starts again in
  //    the same activity.
  {
    const appPid = pidOf();
    mark = logMark();
    const cdp = await page.context().newCDPSession(page);
    await cdp.send('Page.crash').catch(() => {}); // The connection goes with the page.
    const gone = await waitLog(/renderer gone/, mark, 20000);
    const back = await waitLog(/PdfCraft ready/, mark, 300000);
    check('when the page\'s process dies, PdfCraft starts again in the same app', gone && back && pidOf() === appPid,
      `${gone ? 'restarted' : 'no restart'}, pid ${appPid} -> ${pidOf()}`);
    await device.close();
    device = (await android.devices())[0];
    await attach();
    check('... and works again', !(await waitReady()));
    screenshot('7-after-restart.png');
  }

  // 9. Nothing crashed along the way, and no save left its safety copy behind.
  await device.close();
  device = null;
  if (/already running as root|restarting adbd as root/.test(`${adb(['root']).out}`)) {
    adb(['wait-for-device']);
    await sleep(2000);
    const left = sh(`ls /data/data/${PKG}/no_backup/writing 2>/dev/null`);
    check('saves leave no backup copy or note behind', !/\.(bak|json)/.test(left), left.trim());
  }
  const crashes = sh('logcat -d -b crash');
  check('PdfCraft never crashed', !crashes.includes(PKG) && !/FATAL EXCEPTION[\s\S]{0,400}com\.appm\.pdfcraft/.test(logSince(0)),
    crashes.split('\n').filter((l) => l.includes(PKG)).slice(0, 3).join(' | '));
}

try {
  await main();
} catch (e) {
  check('the run finished', false, String(e && e.stack || e).split('\n').slice(0, 4).join(' | '));
  screenshot('error.png');
} finally {
  if (device) await device.close().catch(() => {});
  if (logcat) logcat.kill();
}
const failed = results.filter((r) => !r.ok).length;
console.log(`\n${results.length} checks, ${failed} failed; screenshots and logcat in ${out}`);
process.exit(failed ? 1 : 0);
