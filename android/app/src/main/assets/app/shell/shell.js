// PdfCraft for Android: the page around PdfCraft's own browser build.
//
// PdfCraft runs in the iframe with `?host=parent`, so it talks to this page by postMessage
// (apps/pdfcraft-web/src/main.rs, `mod host` in PdfCraft): it posts pdfcraft:ready,
// pdfcraft:save, pdfcraft:closed, pdfcraft:dirty and pdfcraft:fullScreen, and takes pdfcraft:open
// and pdfcraft:leaveFullScreen. This page relays
// those to Android (`window.AndroidShell`, ShellBridge.java), and takes over the things a
// browser does that a WebView doesn't: downloading blob: URLs, window.open() and window.print().
(() => {
  'use strict';

  const android = window.AndroidShell;
  const frame = document.getElementById('app');
  const splash = document.getElementById('splash');
  const errorBox = document.getElementById('error');
  const origin = location.origin;
  // Phones and tablets alike draw PdfCraft's controls themselves (phone.js), as PDF apps for
  // them lay them out; PdfCraft draws the page. A tablet (600 dp or wider) has room to dock a
  // panel beside the page instead of raising it from the bottom, and for roomier bars and sheets.
  const TABLET = Math.min(screen.width, screen.height) >= 600;
  // Android's touch-and-hold delay and double-tap time (Accessibility settings change them),
  // for holding a word to select it and telling a double tap from two taps.
  const TOUCH = touchTimings();
  // A pen (an S Pen) used here before: PdfCraft leaves the marking to it from the start, and a
  // finger only scrolls (phone.js notes the first pen it sees).
  const PEN_KEY = 'shell.pen';
  const penBefore = () => {
    try {
      return localStorage.getItem(PEN_KEY) === '1';
    } catch (e) {
      return false;
    }
  };
  const appUrl = () => `../pdfcraft/index.html?host=parent&chrome=host${TABLET ? '&panel=side' : ''}&longpress=${TOUCH.longPress}${penBefore() ? '&pen=1' : ''}`;
  // A slow phone compiles the 40 MB module for a while; only then say something is wrong.
  const STARTUP_TIMEOUT_MS = 45000;
  // Bytes per bridge call, a multiple of 3 so every slice's base64 decodes on its own. A file
  // is read and encoded a slice at a time: one big base64 string can cost the page its memory.
  const SLICE = 384 * 1024;
  // Pauses between WebGL 2 checks at start-up, about 6 s in all before PdfCraft gives up.
  const WEBGL_RETRY_MS = [250, 500, 750, 1000, 1500, 2000];

  let ready = false;
  let lastError = '';
  let watchdog = 0;
  let starts = 0;
  let opening = Promise.resolve();
  let handing = Promise.resolve();

  // PdfCraft's default workspace (its saved "docking" setting, the same from 0.7.1 to 0.13.0): Tools beside the page.
  const DEFAULT_DOCKING = {
    hidden: [['inspector', { anchor: 'canvas', floating: null, placement: { Split: 'Right' }, sibling: ['canvas'],
      size: { FixedSecond: 330.0 }, stack: null }]],
    layout: { floating: [], root: { Split: { axis: 'Horizontal', first: { Tabs: { active: 0, panels: ['tools'] } },
      second: { Tabs: { active: 0, panels: ['canvas'] } }, size: { FixedFirst: 272.0 } } } },
  };

  // ---- start-up ----

  /**
   * First launch on a phone: start with the Tools panel closed, so the page gets the screen's
   * width (the panel button at the top left brings it back). PdfCraft restores each saved
   * setting on its own and ignores a workspace it can't use, and it overwrites this the first
   * time it saves its own settings.
   */
  function seedPhoneDefaults() {
    try {
      if (localStorage.getItem('pdfcraft') !== null || localStorage.getItem('printcraft') !== null) return;
      if (Math.min(screen.width, screen.height) >= 600) return; // A tablet keeps PdfCraft's layout.
      localStorage.setItem('pdfcraft', JSON.stringify({ docking: DEFAULT_DOCKING, docking_left_open: false }));
    } catch (e) {
      // No storage: PdfCraft starts with its own defaults.
    }
  }

  function start() {
    const attempt = ++starts;
    ready = false;
    // A PdfCraft starting over is not in Full screen mode.
    if (android) android.setFullScreen(false);
    lastError = '';
    errorBox.hidden = true;
    splash.hidden = false;
    clearTimeout(watchdog);
    probeWebGL2(attempt, 0);
  }

  /**
   * WebGL can be missing for a moment in a WebView that has only just started (seen once on
   * the emulator, a second after Android started PdfCraft again): ask again for a few seconds
   * before saying this WebView can't run PdfCraft.
   */
  function probeWebGL2(attempt, tries) {
    if (attempt !== starts) return; // Try again was pressed meanwhile.
    const problem = webGL2Problem();
    if (problem === null) {
      load();
    } else if (tries < WEBGL_RETRY_MS.length) {
      console.warn(`WebGL 2 is not available yet (${problem}); asking again`);
      setTimeout(() => probeWebGL2(attempt, tries + 1), WEBGL_RETRY_MS[tries]);
    } else {
      fail("This phone's WebView can't draw with WebGL 2, which PdfCraft needs. Updating Android System WebView usually fixes it.",
        false, problem);
    }
  }

  function load() {
    frame.src = 'about:blank';
    frame.src = appUrl();
    watchdog = setTimeout(() => {
      if (!ready) fail(lastError || 'PdfCraft is taking unusually long to start.', true);
    }, STARTUP_TIMEOUT_MS);
  }

  /** Returns null when a WebGL 2 context can be made, or why it can't. */
  function webGL2Problem() {
    const canvas = document.createElement('canvas');
    let reason = '';
    canvas.addEventListener('webglcontextcreationerror', (e) => { reason = e.statusMessage || ''; });
    try {
      const gl = canvas.getContext('webgl2');
      if (!gl) return reason || 'no context';
      const lose = gl.getExtension('WEBGL_lose_context');
      if (lose) lose.loseContext();
      return null;
    } catch (e) {
      return String((e && e.message) || e);
    }
  }

  function fail(message, stillLoading, detail) {
    document.getElementById('error-text').textContent = message;
    let info = navigator.userAgent;
    if (android) {
      try {
        const i = JSON.parse(android.info());
        info = `PdfCraft ${i.app}\nAndroid ${i.android}\n${i.device}\nWebView ${i.webview}`;
      } catch (e) {
        // Keep the user agent.
      }
    }
    if (detail) {
      info += `\n${detail}`;
      console.error(`${message} (${detail})`);
    }
    document.getElementById('error-info').textContent = info;
    document.getElementById('wait').hidden = !stillLoading;
    document.getElementById('update').hidden = !android;
    splash.hidden = true;
    errorBox.hidden = false;
  }

  document.getElementById('retry').addEventListener('click', () => start());
  document.getElementById('wait').addEventListener('click', () => {
    errorBox.hidden = true;
    splash.hidden = ready;
  });
  document.getElementById('update').addEventListener('click', () => android && android.updateWebView());

  // ---- PdfCraft's host bridge ----

  window.addEventListener('message', (event) => {
    if (event.origin !== origin || event.source !== frame.contentWindow) return;
    const msg = event.data;
    if (!msg || typeof msg.type !== 'string') return;
    switch (msg.type) {
      case 'pdfcraft:ready':
        ready = true;
        clearTimeout(watchdog);
        splash.hidden = true;
        errorBox.hidden = true;
        // A new PdfCraft numbers its documents afresh.
        if (android) android.appReady();
        incoming();
        break;
      case 'pdfcraft:save':
        // `id`: the id Android opened the document with, if it did; `doc`: PdfCraft's number for
        // it. Android saves by these, so two documents with the same name stay apart.
        hand(msg.name, new Blob([msg.bytes], { type: 'application/pdf' }), !!msg.saveAs, 'save', {
          id: typeof msg.id === 'string' ? msg.id : '',
          doc: Number.isInteger(msg.doc) ? String(msg.doc) : '',
        });
        break;
      case 'pdfcraft:share':
        // Share (phone.js): the document as Save would write it, for Android's share sheet.
        hand(msg.name, new Blob([msg.bytes], { type: 'application/pdf' }), false, 'share');
        break;
      case 'pdfcraft:closed':
        // A tab closed (or a file Android sent never became a document): Android lets go of
        // the file it saved to, once the saves sent before this are written.
        closed(typeof msg.id === 'string' ? msg.id : '', Number.isInteger(msg.doc) ? String(msg.doc) : '');
        break;
      case 'pdfcraft:dirty':
        if (android) android.setDirty(!!msg.dirty);
        break;
      case 'pdfcraft:fullScreen':
        if (android) android.setFullScreen(!!msg.on);
        break;
      case 'pdfcraft:failed':
        clearTimeout(watchdog);
        fail(String(msg.error || 'PdfCraft reported an error while starting.'));
        break;
    }
  });

  /** Open the files Android queued ("Open with", share sheet) in PdfCraft. */
  function incoming() {
    if (!ready || !android) return;
    opening = opening.then(async () => {
      let files = [];
      try {
        files = JSON.parse(android.pending());
      } catch (e) {
        return;
      }
      for (const file of files) {
        try {
          const res = await fetch('/incoming/' + encodeURIComponent(file.id));
          if (!res.ok) throw new Error((await res.text()) || `HTTP ${res.status}`);
          const bytes = await res.arrayBuffer();
          // PdfCraft hands the id back with every save of the document.
          frame.contentWindow.postMessage({ type: 'pdfcraft:open', name: file.name, id: file.id, bytes }, origin, [bytes]);
        } catch (e) {
          android.toast(`Couldn't open ${file.name}: ${e.message}`);
          closed(file.id, '');
        }
      }
    });
  }

  /**
   * Tell Android a document is closed: `id` and `doc` name it as in 'pdfcraft:save'. It waits
   * in line with the saves, so it never reaches Android before a save of that document that
   * PdfCraft made first.
   */
  function closed(id, doc) {
    if (!android) return;
    handing = handing
      .then(() => android.closed(id, doc))
      .catch(() => {});
  }

  /**
   * Give a file to Android: a document save, or an export (any other download). `which` names
   * the saved document ({ id, doc }, see 'pdfcraft:save'). Files go one at a time, in the order
   * PdfCraft made them: a bigger file takes longer to read, and an older save must never reach
   * Android after a newer one and overwrite it.
   */
  function hand(name, blob, saveAs, kind, which = { id: '', doc: '' }) {
    handing = handing
      .then(() => handNow(name, blob, saveAs, kind, which))
      .catch((e) => android && android.toast(`Couldn't save ${name}: ${e.message}`));
    return handing;
  }

  async function handNow(name, blob, saveAs, kind, which) {
    if (!android) {
      plainDownload(name, blob);
      return;
    }
    const id = android.beginSave(String(name || 'document.pdf'), blob.type || '', saveAs, kind, which.id, which.doc);
    if (!id) return;
    try {
      for (let at = 0; at < blob.size; at += SLICE) {
        if (!android.appendChunk(id, await base64(blob.slice(at, at + SLICE)))) return;
      }
      android.endSave(id);
    } catch (e) {
      android.abortSave(id);
      android.toast(`Couldn't save ${name}: ${e.message}`);
    }
  }

  function base64(blob) {
    return new Promise((resolve, reject) => {
      const reader = new FileReader();
      reader.onload = () => {
        const url = String(reader.result);
        resolve(url.slice(url.indexOf(',') + 1));
      };
      reader.onerror = () => reject(reader.error || new Error('could not read the data'));
      reader.readAsDataURL(blob);
    });
  }

  /** Outside Android (a desktop browser, for testing): download as a browser would. */
  function plainDownload(name, blob) {
    const a = document.createElement('a');
    a.href = URL.createObjectURL(blob);
    a.download = name;
    a.click();
    setTimeout(() => URL.revokeObjectURL(a.href), 10000);
  }

  function isExternal(url) {
    return /^(https?:|mailto:)/i.test(url) && !url.startsWith(origin + '/');
  }

  function openExternal(url) {
    if (android) android.openExternal(url);
    else window.open(url, '_blank', 'noopener');
  }

  // ---- what a WebView doesn't do for PdfCraft ----

  frame.addEventListener('load', () => {
    let w;
    try {
      w = frame.contentWindow;
      if (!w || w.location.href === 'about:blank') return;
    } catch (e) {
      return;
    }
    patch(w);
  });

  function patch(w) {
    if (w.__androidShell) return;
    w.__androidShell = true;

    w.addEventListener('error', (e) => {
      lastError = e.message || String(e.error || 'a script error');
    });
    w.addEventListener('unhandledrejection', (e) => {
      const r = e.reason;
      lastError = String((r && (r.message || r)) || 'start-up failed');
    });

    // The Blob behind each blob: URL. PdfCraft downloads by clicking an <a download> on a
    // blob: URL and revokes it at once; a WebView can't download those, so the click hands the
    // Blob to Android instead.
    const blobs = new Map();
    const createObjectURL = w.URL.createObjectURL;
    const revokeObjectURL = w.URL.revokeObjectURL;
    w.URL.createObjectURL = function (obj) {
      const url = createObjectURL.call(w.URL, obj);
      if (obj instanceof w.Blob) blobs.set(url, obj);
      return url;
    };
    w.URL.revokeObjectURL = function (url) {
      blobs.delete(url);
      return revokeObjectURL.call(w.URL, url);
    };

    const click = w.HTMLAnchorElement.prototype.click;
    w.HTMLAnchorElement.prototype.click = function () {
      const blob = blobs.get(this.href);
      if (blob && this.hasAttribute('download')) {
        hand(this.getAttribute('download') || 'download', blob, true, 'export');
        return;
      }
      if (isExternal(this.href)) {
        openExternal(this.href);
        return;
      }
      return click.call(this);
    };

    // Links (Help, credits, release notes) go to the browser; PdfCraft stays put.
    const open = w.open;
    w.open = function (url, target, features) {
      let abs = '';
      try {
        abs = url == null ? '' : new w.URL(String(url), w.location.href).href;
      } catch (e) {
        // Not a URL: let the WebView decide.
      }
      if (isExternal(abs)) {
        openExternal(abs);
        return null;
      }
      return open.call(w, url, target, features);
    };

    // Printing: PdfCraft draws each sheet as a PNG into a hidden srcdoc frame, takes that
    // frame's window (contentWindow) once it has loaded, and calls its print(), which a WebView
    // ignores. Hand out that window with a print() that sends the sheets to Android instead.
    const contentWindow = Object.getOwnPropertyDescriptor(w.HTMLIFrameElement.prototype, 'contentWindow');
    if (contentWindow && contentWindow.get) {
      Object.defineProperty(w.HTMLIFrameElement.prototype, 'contentWindow', {
        configurable: true,
        enumerable: contentWindow.enumerable,
        get() {
          const pw = contentWindow.get.call(this);
          if (pw && this.hasAttribute('srcdoc')) hookPrint(pw, blobs);
          return pw;
        },
      });
    }
  }

  function hookPrint(pw, blobs) {
    try {
      if (Object.prototype.hasOwnProperty.call(pw, '__androidPrint')) return;
      pw.__androidPrint = true;
      pw.print = () => {
        printSheets(pw, blobs).catch((e) => android && android.toast(`Couldn't print: ${e.message}`));
      };
    } catch (e) {
      // Not a window we can reach: leave it alone.
    }
  }

  async function printSheets(pw, blobs) {
    if (!android) return;
    const sheets = Array.from(pw.document.querySelectorAll('body > div'));
    try {
      if (!sheets.length) throw new Error('there is nothing to print');
      const id = android.beginPrint('PdfCraft');
      if (!id) return;
      try {
        for (const sheet of sheets) {
          const img = sheet.querySelector('img');
          if (!img) throw new Error('a sheet has no picture');
          const style = pw.getComputedStyle(sheet);
          // CSS pixels to points.
          const width = parseFloat(style.width) * 0.75;
          const height = parseFloat(style.height) * 0.75;
          const blob = blobs.get(img.src) || (await (await fetch(img.src)).blob());
          if (!android.beginSheet(id, width, height, sheet.classList.contains('turned'))) return;
          for (let at = 0; at < blob.size; at += SLICE) {
            if (!android.appendSheet(id, await base64(blob.slice(at, at + SLICE)))) return;
          }
          if (!android.endSheet(id)) return;
        }
        android.endPrint(id);
      } catch (e) {
        android.abortPrint(id);
        throw e;
      }
    } finally {
      // PdfCraft frees the sheets when the print dialog closes.
      pw.dispatchEvent(new pw.Event('afterprint'));
    }
  }

  /** Android's Back in Full screen mode: leave it, as Esc does. */
  function leaveFullScreen() {
    if (frame.contentWindow) frame.contentWindow.postMessage({ type: 'pdfcraft:leaveFullScreen' }, origin);
  }

  /** Android's touch timings (ms), or its defaults outside the app. */
  function touchTimings() {
    const timings = { longPress: 400, doubleTap: 300 };
    try {
      const t = android && android.touchTimings ? JSON.parse(android.touchTimings()) : {};
      for (const k of Object.keys(timings)) if (Number.isFinite(t[k]) && t[k] > 0) timings[k] = t[k];
    } catch (e) {
      // Android's defaults, then.
    }
    return timings;
  }

  /** A long press's buzz, where Android gives one. */
  function haptic() {
    if (android && android.haptic) android.haptic();
  }

  /** Put text on the clipboard: Android's, or the browser's outside the app. */
  function copyText(text) {
    if (android && android.copyText) android.copyText(String(text));
    else if (navigator.clipboard) navigator.clipboard.writeText(String(text)).catch(() => {});
  }

  function rememberPen() {
    try {
      localStorage.setItem(PEN_KEY, '1');
    } catch (e) {
      // No storage: the next start waits for the pen again.
    }
  }

  window.shell = { incoming, leaveFullScreen, copyText, haptic, rememberPen, touch: TOUCH, controls: true, tablet: TABLET };
  seedPhoneDefaults();
  start();
})();
