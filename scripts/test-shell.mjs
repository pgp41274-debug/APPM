#!/usr/bin/env node
// End-to-end test of the Android shell around PdfCraft's wasm build, run in Chromium (the
// engine inside Android System WebView) on an emulated Pixel 7, with a stand-in for the Java
// bridge (window.AndroidShell). Serves the APK's assets the way MainActivity does.
//
// Usage: NODE_PATH="$(npm root -g)" node scripts/test-shell.mjs [screenshot dir]
// Needs build/web-assets (scripts/build-web.sh) and the playwright package with Chromium.
import { createRequire } from 'node:module';
import fs from 'node:fs';
import http from 'node:http';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import zlib from 'node:zlib';

const require = createRequire(import.meta.url);
const { chromium, devices } = require('playwright');

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const shots = path.resolve(process.argv[2] || path.join(root, 'build/test-shots'));
fs.mkdirSync(shots, { recursive: true });
const assetDirs = [path.join(root, 'android/app/src/main/assets'), path.join(root, 'build/web-assets')];
const MIME = {
  '.html': 'text/html; charset=utf-8', '.js': 'text/javascript', '.wasm': 'application/wasm',
  '.png': 'image/png', '.css': 'text/css', '.svg': 'image/svg+xml', '.json': 'application/json',
};

// ---- a server shaped like MainActivity's WebViewAssetLoader ----
const incoming = new Map();
const served = [];
const server = http.createServer((req, res) => {
  const url = new URL(req.url, 'http://x');
  if (url.pathname.startsWith('/incoming/')) {
    const id = decodeURIComponent(url.pathname.slice('/incoming/'.length));
    const bytes = incoming.get(id);
    incoming.delete(id);
    served.push(id);
    if (!bytes) return res.writeHead(404).end('no such file');
    return res.writeHead(200, { 'Content-Type': 'application/pdf' }).end(bytes);
  }
  const rel = path.normalize(decodeURIComponent(url.pathname)).replace(/^([/\\])+/, '');
  if (rel.includes('..')) return res.writeHead(404).end();
  for (const dir of assetDirs) {
    const file = path.join(dir, rel);
    if (fs.existsSync(file) && fs.statSync(file).isFile()) {
      res.writeHead(200, { 'Content-Type': MIME[path.extname(file)] || 'application/octet-stream' });
      return fs.createReadStream(file).pipe(res);
    }
  }
  res.writeHead(404).end();
});
await new Promise((r) => server.listen(0, '127.0.0.1', r));
const base = `http://127.0.0.1:${server.address().port}`;

// ---- the stand-in for ShellBridge.java ----
const mockBridge = () => {
  if (window.top !== window) {
    // What PdfCraft's frame hears from the shell: the pdfcraft:open messages.
    window.addEventListener('message', (e) => {
      if (e.data && e.data.type === 'pdfcraft:open' && window.top.__opened) window.top.__opened.push({ name: e.data.name, id: e.data.id });
    });
    return;
  }
  window.__calls = [];
  window.__pending = [{ id: 'f1', name: 'showcase.pdf' }];
  window.__opened = [];
  window.__saves = [];
  window.__ended = [];
  window.__print = null;
  window.AndroidShell = {
    pending() { const p = window.__pending; window.__pending = []; window.__calls.push(['pending', p.length]); return JSON.stringify(p); },
    appReady() { window.__calls.push(['appReady']); },
    beginSave(name, mime, saveAs, kind, id, doc) {
      window.__saves.push({ name, mime, saveAs, kind, id, doc, data: '', done: false });
      return String(window.__saves.length - 1);
    },
    appendChunk(id, b64) { window.__saves[+id].data += b64; return true; },
    endSave(id) { window.__saves[+id].done = true; window.__ended.push(+id); window.__calls.push(['endSave', window.__saves[+id].name]); return true; },
    abortSave(id) { window.__calls.push(['abortSave', id]); },
    closed(id, doc) { window.__calls.push(['closed', id, doc]); },
    setDirty(d) { window.__calls.push(['setDirty', d]); },
    openExternal(u) { window.__calls.push(['openExternal', u]); },
    beginPrint(name) { window.__print = { name, pages: [], done: false }; return 'p1'; },
    printPage(id, w, h, turned, data) { window.__print.pages.push({ w, h, turned, png: data.startsWith('iVBORw0KGgo') }); return true; },
    abortPrint(id) { window.__calls.push(['abortPrint', id]); },
    endPrint(id) { window.__print.done = true; },
    toast(m) { window.__calls.push(['toast', m]); },
    info() { return JSON.stringify({ app: 'test', android: '-', device: 'Playwright', webview: navigator.userAgent }); },
    updateWebView() {},
  };
};

const results = [];
function check(name, ok, detail = '') {
  results.push({ name, ok: !!ok });
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? `  (${detail})` : ''}`);
}
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

// ---- reading screenshots (8-bit RGB or RGBA PNGs, as Chromium writes them) ----
function decodePng(png) {
  let width = 0;
  let height = 0;
  let channels = 0;
  const idat = [];
  for (let pos = 8; pos < png.length; pos += 12 + png.readUInt32BE(pos)) {
    const type = png.toString('latin1', pos + 4, pos + 8);
    const data = png.subarray(pos + 8, pos + 8 + png.readUInt32BE(pos));
    if (type === 'IHDR') {
      [width, height, channels] = [data.readUInt32BE(0), data.readUInt32BE(4), { 2: 3, 6: 4 }[data[9]]];
      if (data[8] !== 8 || !channels || data[12] !== 0) throw new Error('not an 8-bit RGB(A) PNG');
    } else if (type === 'IDAT') idat.push(data);
  }
  const raw = zlib.inflateSync(Buffer.concat(idat));
  const stride = width * channels;
  const rgb = Buffer.alloc(width * height * 3);
  let prev = Buffer.alloc(stride);
  for (let y = 0; y < height; y++) {
    const filter = raw[y * (stride + 1)];
    const row = Buffer.from(raw.subarray(y * (stride + 1) + 1, (y + 1) * (stride + 1)));
    for (let i = 0; i < stride; i++) {
      const a = i < channels ? 0 : row[i - channels];
      const b = prev[i];
      const c = i < channels ? 0 : prev[i - channels];
      if (filter === 1) row[i] += a;
      else if (filter === 2) row[i] += b;
      else if (filter === 3) row[i] += (a + b) >> 1;
      else if (filter === 4) {
        const pa = Math.abs(b - c);
        const pb = Math.abs(a - c);
        const pc = Math.abs(a + b - 2 * c);
        row[i] += pa <= pb && pa <= pc ? a : pb <= pc ? b : c;
      }
    }
    for (let x = 0; x < width; x++) row.copy(rgb, (y * width + x) * 3, x * channels, x * channels + 3);
    prev = row;
  }
  return { width, height, rgb };
}

// The bounding box, in screenshot pixels, of the largest 4-connected patch of pixels that
// `match(r, g, b)` accepts at or below row `top`, of those that `keep(box)` accepts.
function largestPatch({ width, height, rgb }, top, match, keep = () => true) {
  const seen = new Uint8Array(width * height);
  const take = (x, y) => {
    const i = y * width + x;
    if (x < 0 || x >= width || y < top || y >= height || seen[i] || !match(rgb[i * 3], rgb[i * 3 + 1], rgb[i * 3 + 2])) return false;
    seen[i] = 1;
    return true;
  };
  let best = null;
  for (let y = top; y < height; y++) {
    for (let x = 0; x < width; x++) {
      if (!take(x, y)) continue;
      const patch = { n: 0, x0: x, x1: x, y0: y, y1: y };
      for (const stack = [[x, y]]; stack.length;) {
        const [px, py] = stack.pop();
        patch.n++;
        [patch.x0, patch.x1, patch.y0, patch.y1] = [Math.min(patch.x0, px), Math.max(patch.x1, px), Math.min(patch.y0, py), Math.max(patch.y1, py)];
        for (const [nx, ny] of [[px + 1, py], [px - 1, py], [px, py + 1], [px, py - 1]]) if (take(nx, ny)) stack.push([nx, ny]);
      }
      if ((!best || patch.n > best.n) && keep(patch)) best = patch;
    }
  }
  return best;
}

// A phone-sized window at a Pixel 7's real device scale factor. Playwright's emulated
// deviceScaleFactor is not enough: under it Chromium reports devicePixelContentBoxSize in CSS
// pixels, so eframe would size its canvas at 1x and draw a blurry, oversized interface that no
// phone shows.
const PHONE = { width: 412, height: 915, scale: 2.625 };
const launch = (width, height) => chromium.launch({
  args: ['--use-angle=swiftshader', '--enable-unsafe-swiftshader', '--ignore-gpu-blocklist',
    `--force-device-scale-factor=${PHONE.scale}`, `--window-size=${width},${height}`],
});
const browser = await launch(PHONE.width, PHONE.height);
try {
  // A real multi-page PDF to open: PdfCraft's own demo page, printed by Chromium.
  const maker = await browser.newPage();
  await maker.goto('file://' + path.join(root, 'build/src/pdfcraft/assets/demo/showcase.html'));
  const showcase = await maker.pdf({ format: 'A4', printBackground: true });
  incoming.set('f1', showcase);
  await maker.close();
  incoming.set('f2', fs.readFileSync(path.join(root, 'build/src/pdfcraft/crates/ui-egui/tests/data/form.pdf')));

  const context = await browser.newContext({ viewport: null, hasTouch: true, userAgent: devices['Pixel 7'].userAgent });
  await context.addInitScript(mockBridge);
  const page = await context.newPage();
  const errors = [];
  page.on('pageerror', (e) => errors.push(String(e)));
  page.on('console', (m) => { if (m.type() === 'error') errors.push(m.text()); });

  // 1. Start-up: PdfCraft reports ready and the loading screen goes away.
  const t0 = Date.now();
  await page.goto(`${base}/app/shell/index.html`);
  await page.waitForFunction(() => document.getElementById('splash').hidden, null, { timeout: 240000 });
  check('PdfCraft starts and says pdfcraft:ready', await page.evaluate(() => document.getElementById('error').hidden),
    `${((Date.now() - t0) / 1000).toFixed(1)} s`);
  const app = page.frames().find((f) => f.url().includes('/app/pdfcraft/index.html'));
  check('PdfCraft runs in the frame with ?host=parent', app && app.url().includes('host=parent'));
  const seeded = await page.evaluate(() => JSON.parse(localStorage.getItem('pdfcraft') || '{}').docking_left_open);
  check('a phone starts with the Tools panel closed', seeded === false, String(seeded));

  // 2. "Open with": the queued file is fetched from /incoming/ and opened.
  await page.waitForFunction(() => window.__calls.some((c) => c[0] === 'pending'));
  await sleep(4000);
  check('a file queued by Android is fetched from /incoming/', served.includes('f1'));
  const opened = await page.evaluate(() => window.__opened);
  check('the open message carries Android\'s id for the file', opened.some((o) => o.name === 'showcase.pdf' && o.id === 'f1'),
    JSON.stringify(opened));
  check('a (re)started PdfCraft is reported to Android', await page.evaluate(() => window.__calls.some((c) => c[0] === 'appReady')));
  await page.screenshot({ path: path.join(shots, '1-opened-portrait.png') });

  const canvas = page.frameLocator('#app').locator('canvas');
  const box = await canvas.boundingBox();
  const focus = () => page.mouse.click(box.x + box.width / 2, box.y + box.height * 0.6);

  // 3. An edit (Ctrl+K palette ▸ "Rotate pages clockwise") marks the document unsaved.
  await focus();
  await page.keyboard.press('Control+k');
  await sleep(600);
  await page.keyboard.type('Rotate pages clockwise', { delay: 20 });
  await sleep(600);
  await page.screenshot({ path: path.join(shots, '2-command-palette.png') });
  await page.keyboard.press('Enter');
  await sleep(1500);
  const dirty = await page.evaluate(() => window.__calls.filter((c) => c[0] === 'setDirty').map((c) => c[1]));
  check('unsaved work reaches Android (setDirty)', dirty.includes(true), JSON.stringify(dirty));

  // 4. Save (Ctrl+S) hands the PDF bytes to Android, in slices.
  await focus();
  await page.keyboard.press('Control+s');
  await page.waitForFunction(() => window.__saves.some((s) => s.done && s.kind === 'save'), null, { timeout: 20000 }).catch(() => {});
  const save = await page.evaluate(() => {
    const s = window.__saves.find((x) => x.done && x.kind === 'save');
    return s && { name: s.name, saveAs: s.saveAs, id: s.id, doc: s.doc, size: atob(s.data).length, head: atob(s.data.slice(0, 12)) };
  });
  check('Save sends the document to Android as a PDF', save && save.head.startsWith('%PDF-') && save.name === 'showcase.pdf' && !save.saveAs,
    JSON.stringify(save));
  check('Save of the "Open with" file sends its id back, with PdfCraft\'s document number',
    save && save.id === 'f1' && /^\d+$/.test(save.doc), JSON.stringify(save && { id: save.id, doc: save.doc }));
  await sleep(800);
  const clean = await page.evaluate(() => window.__calls.filter((c) => c[0] === 'setDirty').map((c) => c[1]));
  check('after saving, the document is clean again', clean[clean.length - 1] === false, JSON.stringify(clean));

  // 5. Save As (Ctrl+Shift+S) is flagged so Android asks where.
  await focus();
  await page.keyboard.press('Control+Shift+s');
  await page.waitForFunction(() => window.__saves.some((s) => s.done && s.saveAs), null, { timeout: 20000 }).catch(() => {});
  check('Save As is marked saveAs', await page.evaluate(() => window.__saves.some((s) => s.done && s.saveAs && s.kind === 'save')));

  // 5b. A document PdfCraft made itself (palette ▸ "New blank PDF") has no id from Android: its
  // Save names it by PdfCraft's document number instead, so it never takes another file's place.
  await focus();
  await page.keyboard.press('Control+k');
  await sleep(600);
  await page.keyboard.type('New blank PDF', { delay: 20 });
  await sleep(600);
  await page.keyboard.press('Enter');
  await sleep(1500);
  await focus();
  await page.keyboard.press('Control+s');
  await page.waitForFunction(() => window.__saves.some((s) => s.done && s.name === 'Untitled.pdf'), null, { timeout: 20000 }).catch(() => {});
  const fresh = await page.evaluate(() => {
    const s = window.__saves.find((x) => x.done && x.name === 'Untitled.pdf');
    return s && { id: s.id, doc: s.doc, saveAs: s.saveAs };
  });
  check('Save of a document with no id sends only PdfCraft\'s document number',
    fresh && fresh.id === '' && /^\d+$/.test(fresh.doc) && fresh.doc !== (save && save.doc) && !fresh.saveAs, JSON.stringify(fresh));
  // Close it again ("Close file"), so showcase.pdf is the document in front. Android hears of
  // the close by the number the save carried, and lets go of the file that save went to.
  await focus();
  await page.keyboard.press('Control+k');
  await sleep(600);
  await page.keyboard.type('Close file', { delay: 20 });
  await sleep(600);
  await page.keyboard.press('Enter');
  await page.waitForFunction(() => window.__calls.some((c) => c[0] === 'closed'), null, { timeout: 5000 }).catch(() => {});
  const closedTab = await page.evaluate(() => window.__calls.filter((c) => c[0] === 'closed'));
  check('closing a tab reports pdfcraft:closed with its document number',
    fresh && closedTab.length === 1 && closedTab[0][1] === '' && closedTab[0][2] === fresh.doc, JSON.stringify(closedTab));

  // 5c. Two quick saves reach Android in the order PdfCraft made them, even when the older one
  // is far bigger and takes longer to read.
  await app.evaluate(() => {
    for (const size of [24 << 20, 1024]) {
      const bytes = new Uint8Array(size);
      bytes.set([37, 80, 68, 70, 45]); // %PDF-
      parent.postMessage({ type: 'pdfcraft:save', name: 'order.pdf', saveAs: false, bytes: bytes.buffer, id: 'f1', doc: 1 },
        location.origin, [bytes.buffer]);
    }
  });
  await page.waitForFunction(() => window.__saves.filter((s) => s.done && s.name === 'order.pdf').length === 2, null, { timeout: 30000 })
    .catch(() => {});
  const order = await page.evaluate(() =>
    window.__ended.map((i) => window.__saves[i]).filter((s) => s.name === 'order.pdf').map((s) => s.data.length));
  check('two quick saves reach Android in order', order.length === 2 && order[0] > order[1], JSON.stringify(order));

  // 5d. A close reaches Android only after the saves PdfCraft made of the document before it,
  // even one that takes long to read: Android must not let go of the file that save goes to.
  await app.evaluate(() => {
    const bytes = new Uint8Array(24 << 20);
    bytes.set([37, 80, 68, 70, 45]); // %PDF-
    parent.postMessage({ type: 'pdfcraft:save', name: 'closing.pdf', saveAs: false, bytes: bytes.buffer, id: 'f7', doc: 7 },
      location.origin, [bytes.buffer]);
    parent.postMessage({ type: 'pdfcraft:closed', id: 'f7', doc: 7 }, location.origin);
  });
  await page.waitForFunction(() => window.__calls.some((c) => c[0] === 'closed' && c[1] === 'f7'), null, { timeout: 30000 })
    .catch(() => {});
  const closing = await page.evaluate(() =>
    window.__calls.filter((c) => (c[0] === 'endSave' && c[1] === 'closing.pdf') || (c[0] === 'closed' && c[1] === 'f7')));
  check('a close reaches Android after the save made before it',
    closing.length === 2 && closing[0][0] === 'endSave' && closing[1][2] === '7', JSON.stringify(closing));

  // 6. Other downloads (PdfCraft's download(): <a download> on a revoked blob: URL) go to Android.
  await app.evaluate(() => {
    const blob = new Blob(['a,b\n1,2\n'], { type: 'application/pdf' });
    const a = document.createElement('a');
    a.href = URL.createObjectURL(blob);
    a.download = 'measurements.csv';
    a.click();
    URL.revokeObjectURL(a.href);
  });
  await sleep(500);
  const exp = await page.evaluate(() => {
    const s = window.__saves.find((x) => x.kind === 'export');
    return s && { name: s.name, done: s.done, text: atob(s.data) };
  });
  check('blob: downloads are handed to Android as exports', exp && exp.done && exp.name === 'measurements.csv' && exp.text === 'a,b\n1,2\n',
    JSON.stringify(exp));

  // 7. Links open in the browser, never inside the app's frame.
  await app.evaluate(() => {
    window.open('https://github.com/storytold/pdfcraft/releases', '_blank');
    window.open('https://github.com/storytold/pdfcraft', '_self');
  });
  const links = await page.evaluate(() => window.__calls.filter((c) => c[0] === 'openExternal').map((c) => c[1]));
  check('window.open links go to Android (new tab and _self)', links.length === 2, JSON.stringify(links));
  check('PdfCraft stays loaded after a _self link', app.url().includes('/app/pdfcraft/index.html'));

  // 8. Print: PdfCraft's own Print button sends its print sheets to Android's print framework
  // instead of a no-op print(). Nothing stands in for PdfCraft: without its sheets this fails.
  await focus();
  await page.keyboard.press('Control+p');
  await sleep(1500);
  const screen = await page.evaluate(() => ({ width: innerWidth, height: innerHeight }));
  const dialog = decodePng(await page.screenshot({ path: path.join(shots, '3-print-dialog.png') }));
  const px = dialog.width / screen.width;
  const pt = (b) => b && { x: (b.x0 + b.x1 + 1) / 2 / px, y: (b.y0 + b.y1 + 1) / 2 / px, w: (b.x1 - b.x0 + 1) / px, h: (b.y1 - b.y0 + 1) / px };
  // The card is all the pure white left: the backdrop dims the rest of the screen. Print is
  // taller than half a phone's screen, so a card that is not cut short (say to egui's default
  // 400-point area) spans more than that.
  const card = pt(largestPatch(dialog, 0, (r, g, b) => r > 250 && g > 250 && b > 250));
  check('the Print dialog spans the phone\'s width and more than half its height',
    card && card.w > screen.width - 24 && card.h > screen.height / 2,
    card && `card ${card.w.toFixed(0)} x ${card.h.toFixed(0)} pt on a ${screen.width} x ${screen.height} pt screen`);
  // The dialog scrolls on a phone: wheel to its end, where its buttons are.
  await page.mouse.move(box.x + box.width / 2, box.y + box.height / 2);
  for (let i = 0; i < 6; i++) { await page.mouse.wheel(600, 600); await sleep(150); }
  await sleep(500);
  const scrolled = decodePng(await page.screenshot({ path: path.join(shots, '3b-print-dialog-scrolled.png') }));
  // PdfCraft's Print button is a pill (28 points tall) filled with the theme's accent (#1B63E0,
  // or #4B8BF5 when dark) behind white text: the largest patch of that blue in the lower half.
  const accent = (r, g, b) => [[0x1b, 0x63, 0xe0], [0x4b, 0x8b, 0xf5]]
    .some(([R, G, B]) => Math.abs(r - R) + Math.abs(g - G) + Math.abs(b - B) <= 24);
  const pill = (b) => b.y1 - b.y0 + 1 > 18 * px && b.y1 - b.y0 + 1 < 36 * px && b.x1 - b.x0 + 1 > 30 * px && b.x1 - b.x0 + 1 < 160 * px;
  const button = pt(largestPatch(scrolled, Math.round(scrolled.height / 2), accent, pill));
  check('PdfCraft\'s Print button shows on the scrolled dialog', button,
    button && `at ${button.x.toFixed(0)}, ${button.y.toFixed(0)}, ${button.w.toFixed(0)} x ${button.h.toFixed(0)} pt`);
  if (button) await page.mouse.click(button.x, button.y);
  await page.waitForFunction(() => window.__print && window.__print.done, null, { timeout: 30000 }).catch(() => {});
  await page.screenshot({ path: path.join(shots, '3c-after-print.png') });
  const printed = await page.evaluate(() => window.__print);
  // All pages at their size: a sheet of US Letter or A4 (the locale's paper) for every page.
  const pages = (showcase.toString('latin1').match(/\/Type\s*\/Page\b/g) || []).length;
  const paper = (p) => [[612, 792], [595.28, 841.89]].some(([w, h]) => Math.abs(Math.min(p.w, p.h) - w) < 1 && Math.abs(Math.max(p.w, p.h) - h) < 1);
  check('PdfCraft\'s own Print reaches Android: a PNG on paper for every page',
    pages > 0 && printed && printed.done && printed.pages.length === pages && printed.pages.every((p) => p.png && paper(p)),
    `${printed ? printed.pages.length : 0} sheets for ${pages} pages: ${JSON.stringify(printed && printed.pages.slice(0, 2))}`);
  if (!printed || !printed.done) {
    // Diagnostic only: does the shell pass on a print frame shaped like PdfCraft's at all?
    const relayed = await app.evaluate(async () => {
      const c = document.createElement('canvas');
      c.width = 85; c.height = 110;
      c.getContext('2d').fillRect(10, 10, 20, 20);
      const png = await new Promise((r) => c.toBlob(r, 'image/png'));
      const url = URL.createObjectURL(png);
      const frame = document.createElement('iframe');
      frame.srcdoc = `<!doctype html><style>@page{size:612pt 792pt;margin:0}div{width:612pt;height:792pt}</style>`
        + `<body><div><img src="${url}"></div><div class="turned"><img src="${url}"></div></body>`;
      frame.addEventListener('load', () => setTimeout(() => frame.contentWindow.print(), 50));
      document.body.appendChild(frame);
      for (let i = 0; i < 50 && !(window.top.__print && window.top.__print.done); i++) await new Promise((r) => setTimeout(r, 100));
      return !!(window.top.__print && window.top.__print.done);
    });
    console.log(`      (a stand-in print frame ${relayed ? 'does' : 'does not either'} reach Android)`);
  }

  // 9. A file shared while PdfCraft is open arrives in a new tab.
  await page.evaluate(() => {
    window.__pending.push({ id: 'f2', name: 'form.pdf' });
    window.shell.incoming();
  });
  await sleep(2500);
  check('a file shared later opens too', served.includes('f2'));
  await page.screenshot({ path: path.join(shots, '4-second-file.png') });

  // 9b. Close all: each tab from Android reports its id with its number.
  await page.waitForFunction(() => window.__opened.some((o) => o.id === 'f2'), null, { timeout: 10000 }).catch(() => {});
  await sleep(500);
  await focus();
  await page.keyboard.press('Control+k');
  await sleep(600);
  await page.keyboard.type('Close all', { delay: 20 });
  await sleep(600);
  await page.keyboard.press('Enter');
  await page.waitForFunction(() => window.__calls.filter((c) => c[0] === 'closed' && /^f[12]$/.test(c[1])).length === 2, null,
    { timeout: 5000 }).catch(() => {});
  const closedAll = await page.evaluate(() => window.__calls.filter((c) => c[0] === 'closed' && /^f[12]$/.test(c[1])));
  const showcaseDoc = save && save.doc;
  check('Close all reports each tab Android opened, by its id and number',
    closedAll.length === 2 && closedAll.some((c) => c[1] === 'f1' && c[2] === showcaseDoc)
      && closedAll.some((c) => c[1] === 'f2' && /^\d+$/.test(c[2]) && c[2] !== showcaseDoc),
    JSON.stringify(closedAll));

  // 9c. Files from Android that never become a document of their own report their id alone:
  // one PdfCraft can't open, and a picture PdfCraft makes a new PDF of (that PDF is not the
  // picture's file, so its saves must never go there).
  incoming.set('f3', Buffer.from('not a PDF'));
  incoming.set('f4', await page.screenshot({ clip: { x: 0, y: 0, width: 24, height: 24 } }));
  await page.evaluate(() => {
    window.__pending.push({ id: 'f3', name: 'broken.pdf' }, { id: 'f4', name: 'receipt.png' });
    window.shell.incoming();
  });
  await page.waitForFunction(() => ['f3', 'f4'].every((i) => window.__calls.some((c) => c[0] === 'closed' && c[1] === i)), null,
    { timeout: 10000 }).catch(() => {});
  const unopened = await page.evaluate(() => window.__calls.filter((c) => c[0] === 'closed' && /^f[34]$/.test(c[1])));
  check('files that never become their own document report pdfcraft:closed by id',
    unopened.length === 2 && unopened.every((c) => c[2] === ''), JSON.stringify(unopened));
  await page.screenshot({ path: path.join(shots, '4b-picture-made-a-pdf.png') });


  const toasts = await page.evaluate(() => window.__calls.filter((c) => c[0] === 'toast'));
  check('no error toasts', toasts.length === 0, JSON.stringify(toasts));
  const serious = errors.filter((e) => !/favicon|GPU stall|WebGL|swiftshader/i.test(e));
  check('no page errors', serious.length === 0, serious.slice(0, 5).join(' | '));
  const dpr = await app.evaluate(() => ({ dpr: devicePixelRatio, canvas: document.querySelector('canvas').width, css: innerWidth }));
  check('the canvas is drawn at the device resolution', Math.abs(dpr.canvas - dpr.css * dpr.dpr) < 2, JSON.stringify(dpr));
} finally {
  await browser.close();
}

// 10. Landscape, in a landscape window of its own.
const wide = await launch(PHONE.height, PHONE.width);
try {
  const context = await wide.newContext({ viewport: null, hasTouch: true, userAgent: devices['Pixel 7'].userAgent });
  await context.addInitScript(mockBridge);
  incoming.set('f1', fs.readFileSync(path.join(root, 'build/src/pdfcraft/crates/ui-egui/tests/data/form.pdf')));
  const page = await context.newPage();
  await page.goto(`${base}/app/shell/index.html`);
  await page.waitForFunction(() => document.getElementById('splash').hidden, null, { timeout: 240000 });
  await sleep(3000);
  await page.screenshot({ path: path.join(shots, '5-landscape.png') });
  const box = await page.frameLocator('#app').locator('canvas').boundingBox();
  await page.mouse.click(box.x + box.width / 2, box.y + box.height * 0.6);
  await page.keyboard.press('Control+p');
  await sleep(2000);
  await page.screenshot({ path: path.join(shots, '6-landscape-print-dialog.png') });
} finally {
  await wide.close();
  server.close();
}

const failed = results.filter((r) => !r.ok);
console.log(`\n${results.length - failed.length}/${results.length} checks passed; screenshots in ${shots}`);
process.exit(failed.length ? 1 : 0);
