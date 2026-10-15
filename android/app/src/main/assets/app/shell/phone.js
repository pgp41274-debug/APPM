// PdfCraft on a phone: the controls, laid out as phone PDF apps lay them out.
//
// PdfCraft runs with `&chrome=host` (shell.js) and draws only the page, its dialogs and the
// open panel; this page draws the controls around it, from the state PdfCraft reports
// (pdfcraft:state), and drives it with pdfcraft:command, :select, :panel, :activate and
// :gotoPage (apps/pdfcraft-web/src/main.rs in PdfCraft, patch 0007).
//
//   home:   what to do (open, create) and the open files
//   top:    ← home · Undo · Find · ⋮ (the file: view settings, panels, print, save, ...)
//   bottom: Edit PDF ▾ · Comment ▾ · Highlight ▾ · Draw · Fill & Sign ▾ · More tools
//           a ▾ item shows its tools in a strip, with ✓ to finish; More tools is a grid
//   page:   a tap hides the bars and another brings them back; the page number shows a while
//           after the page changes, and goes to a page when tapped
(() => {
  'use strict';

  const shell = window.shell;
  if (!shell || !shell.phone) return;
  const frame = document.getElementById('app');
  const origin = location.origin;
  const body = document.body;

  // ---- what the controls do ----

  // A tool (`cmd`) stays on until Done or another tool; `panel` opens PdfCraft's panel of that
  // name over the page. `pick`: the tool the item turns on as it opens its strip.
  const BOTTOM = [
    { id: 'edit', label: 'Edit PDF', icon: 'file-pen-line', more: true, tools: [
      { cmd: 'edit.edit_text', label: 'Edit text', icon: 'file-pen-line' },
      { cmd: 'edit.text', label: 'Add text', icon: 'type' },
      { cmd: 'edit.image', label: 'Add image', icon: 'image-plus' },
      { cmd: 'edit.link', label: 'Link', icon: 'link' },
      { cmd: 'page.crop', label: 'Crop', icon: 'crop' },
      { cmd: 'redact.mark', label: 'Redact', icon: 'square-dashed' },
      { cmd: 'edit.watermark', label: 'Watermark', icon: 'droplet' },
      { cmd: 'edit.header_footer', label: 'Header', icon: 'heading' },
    ] },
    { id: 'comment', label: 'Comment', icon: 'message-square-plus', more: true, tools: [
      { cmd: 'comment.note', label: 'Note', icon: 'sticky-note' },
      { cmd: 'comment.freetext', label: 'Text', icon: 'type' },
      { cmd: 'comment.square', label: 'Rectangle', icon: 'square' },
      { cmd: 'comment.circle', label: 'Oval', icon: 'circle' },
      { cmd: 'comment.line', label: 'Line', icon: 'minus' },
      { cmd: 'comment.arrow', label: 'Arrow', icon: 'arrow-up-right' },
      { cmd: 'comment.stamp', label: 'Stamp', icon: 'stamp' },
      { panel: 'Comments', label: 'All comments', icon: 'messages-square' },
    ] },
    { id: 'highlight', label: 'Highlight', icon: 'highlighter', more: true, pick: 'comment.highlight', tools: [
      { cmd: 'comment.highlight', label: 'Highlight', icon: 'highlighter' },
      { cmd: 'comment.underline', label: 'Underline', icon: 'underline' },
      { cmd: 'comment.strikeout', label: 'Strike out', icon: 'strikethrough' },
    ] },
    { id: 'draw', label: 'Draw', icon: 'pencil', pick: 'comment.ink', tools: [
      { cmd: 'comment.ink', label: 'Draw', icon: 'pencil' },
      { cmd: 'comment.eraser', label: 'Eraser', icon: 'eraser' },
    ] },
    { id: 'sign', label: 'Fill & Sign', icon: 'signature', more: true, tools: [
      { cmd: 'sign.fill.signature', label: 'Sign', icon: 'signature' },
      { cmd: 'sign.fill.text', label: 'Text', icon: 'type' },
      { cmd: 'sign.fill.check', label: 'Check', icon: 'check' },
      { cmd: 'sign.fill.cross', label: 'Cross', icon: 'x' },
      { cmd: 'sign.fill.dot', label: 'Dot', icon: 'circle-dot' },
      { cmd: 'sign.fill.date', label: 'Date', icon: 'clock' },
      { cmd: 'sign.fill.initials', label: 'Initials', icon: 'pen-tool' },
      { panel: 'Fields', label: 'Form fields', icon: 'text-cursor-input' },
    ] },
    { id: 'tools', label: 'More tools', icon: 'layout-grid', run: () => toolsSheet() },
  ];

  // More tools: a grid of tiles, each tinted with its own colour.
  const TILES = [
    { label: 'Organize pages', icon: 'files', cmd: 'page.organize', hue: 95 },
    { label: 'Export PDF', icon: 'file-output', run: () => exportSheet(), hue: 160 },
    { label: 'Combine files', icon: 'file-input', cmd: 'page.combine', hue: 235 },
    { label: 'Compress PDF', icon: 'file-minus', cmd: 'optimize.reduce', hue: 185 },
    { label: 'Set password', icon: 'lock', cmd: 'protect.password', hue: 255 },
    { label: 'Crop pages', icon: 'crop', cmd: 'page.crop', hue: 215 },
    { label: 'Rotate pages', icon: 'rotate-cw', cmd: 'page.rotate', hue: 40 },
    { label: 'Insert pages', icon: 'file-plus', cmd: 'page.insert', hue: 140 },
    { label: 'Extract pages', icon: 'file-output', cmd: 'page.extract', hue: 20 },
    { label: 'Split', icon: 'scissors', cmd: 'page.split', hue: 330 },
    { label: 'Delete pages', icon: 'trash-2', cmd: 'page.delete', hue: 0 },
    { label: 'Number pages', icon: 'hash', cmd: 'page.number', hue: 60 },
    { label: 'Create PDF', icon: 'image-plus', cmd: 'create.images', hue: 350 },
    { label: 'Redact', icon: 'square-dashed', cmd: 'redact.mark', hue: 300 },
    { label: 'Watermark', icon: 'droplet', cmd: 'edit.watermark', hue: 200 },
    { label: 'All commands', icon: 'search', cmd: 'view.palette', hue: 270 },
  ];

  const EXPORTS = [
    { label: 'Word document', icon: 'file-text', cmd: 'export.docx' },
    { label: 'PowerPoint', icon: 'file-text', cmd: 'export.pptx' },
    { label: 'Images', icon: 'image-plus', cmd: 'export.image' },
    { label: 'Plain text', icon: 'type', cmd: 'export.text' },
    { label: 'Web page (HTML)', icon: 'file-text', cmd: 'export.html' },
  ];

  const VIEW = [
    { label: 'Continuous scrolling', icon: 'rows-3', cmd: 'view.continuous', on: (s) => s.continuous },
    { label: 'Single page', icon: 'file-text', cmd: 'view.layout.single' },
    { label: 'Two pages side by side', icon: 'columns-2', cmd: 'view.layout.two_up' },
    { label: 'Fit width', icon: 'arrow-left-right', cmd: 'view.fit_width_scrolling' },
    { label: 'Fit page', icon: 'minimize-2', cmd: 'view.fit_one_page' },
    { label: 'Full screen', icon: 'maximize', cmd: 'view.full_screen' },
  ];

  const PANEL_NAMES = { Comments: 'Comments', Bookmarks: 'Bookmarks', Pages: 'Pages', Fields: 'Form fields',
    Layers: 'Layers', Attachments: 'Attachments', Signatures: 'Signatures' };

  let state = null;
  let strip = null; // the bottom item whose tools show in the strip
  let immersive = false;
  let home = true; // the home screen, over the open files
  let lastPage = 0;
  let pillTimer = 0;

  // ---- talking to PdfCraft ----

  function post(type, fields) {
    if (frame.contentWindow) frame.contentWindow.postMessage(Object.assign({ type }, fields), origin);
  }
  const command = (id) => post('pdfcraft:command', { id });
  const panel = (name) => post('pdfcraft:panel', { name: name || '' });

  window.addEventListener('message', (event) => {
    if (event.origin !== origin || event.source !== frame.contentWindow || !event.data) return;
    if (event.data.type === 'pdfcraft:state') {
      try {
        render(JSON.parse(event.data.state));
      } catch (e) {
        // A state this page can't read leaves the controls as they are.
      }
    }
  });
  // A PdfCraft starting over (Try again, a crashed page) reports afresh.
  frame.addEventListener('load', () => {
    state = null;
    closeStrip(false);
    closeSheet();
    setImmersive(false);
    watchTaps();
  });

  // ---- building the screen ----

  const el = (tag, attrs, ...children) => {
    const node = document.createElement(tag);
    for (const [k, v] of Object.entries(attrs || {})) {
      if (k === 'class') node.className = v;
      else if (k === 'style') node.style.cssText = v;
      else if (k.startsWith('on')) node.addEventListener(k.slice(2), v);
      else if (v !== undefined && v !== null && v !== false) node.setAttribute(k, v === true ? '' : v);
    }
    for (const c of children) if (c !== null && c !== undefined && c !== false) node.append(c);
    return node;
  };
  const icon = (name) => {
    const i = el('i', { class: 'ic', 'aria-hidden': 'true' });
    i.style.setProperty('--icon', `url("../icons/${name}.svg")`);
    return i;
  };

  const undoBtn = el('button', { class: 'icon-btn', id: 'undo', 'aria-label': 'Undo', onclick: () => command('edit.undo') }, icon('undo-2'));
  const topbar = el('header', { id: 'topbar', class: 'pbar' },
    el('button', { class: 'icon-btn', id: 'back', 'aria-label': 'Home', onclick: () => showHome(true) }, icon('chevron-left')),
    el('div', { class: 'spacer' }),
    undoBtn,
    el('button', { class: 'icon-btn', id: 'find', 'aria-label': 'Find', onclick: () => command('edit.find') }, icon('search')),
    el('button', { class: 'icon-btn', id: 'more', 'aria-label': 'More', onclick: () => fileSheet() }, icon('ellipsis-vertical')));

  const bottombar = el('nav', { id: 'bottombar', class: 'pbar' },
    ...BOTTOM.map((b) => el('button', { class: `cat${b.more ? ' more' : ''}`, 'data-item': b.id, onclick: () => pressBottom(b) },
      icon(b.icon), el('span', {}, b.label))));

  const stripTools = el('div', { id: 'strip-tools' });
  const toolstrip = el('div', { id: 'toolstrip', class: 'pbar', hidden: true },
    el('button', { id: 'strip-done', 'aria-label': 'Done', onclick: () => closeStrip() }, icon('check')),
    stripTools);

  const panelName = el('span', {});
  const panelbar = el('div', { id: 'panelbar', class: 'pbar', hidden: true },
    panelName,
    el('button', { class: 'icon-btn', 'aria-label': 'Close', onclick: () => panel(null) }, icon('chevron-down')));

  const pill = el('button', { id: 'pagepill', hidden: true, onclick: () => gotoSheet() });

  // Home: what to do, then the open files.
  const homeFiles = el('div', { id: 'home-files' });
  const homeScreen = el('div', { id: 'home' },
    el('header', { class: 'home-head' },
      el('img', { src: 'icon.png', alt: '' }),
      el('span', { class: 'brand' }, 'PdfCraft'),
      el('div', { class: 'spacer' }),
      el('button', { class: 'icon-btn', 'aria-label': 'Settings', onclick: () => command('app.preferences') }, icon('settings'))),
    el('h2', {}, 'What would you like to do?'),
    el('div', { class: 'actions' },
      action('Open file', 'folder-open', 45, () => command('file.open')),
      action('Images to PDF', 'image-plus', 350, () => command('create.images')),
      action('Blank PDF', 'file-plus', 140, () => command('create.blank')),
      action('Combine files', 'file-input', 235, () => command('page.combine'))),
    el('h2', {}, 'Open files'),
    homeFiles);

  function action(label, iconName, hue, onclick) {
    return el('button', { class: 'action', onclick }, el('span', { class: 'tile', style: `--hue:${hue}` }, icon(iconName)), el('span', {}, label));
  }

  const scrim = el('div', { id: 'scrim', hidden: true, onclick: () => closeSheet() });
  const sheet = el('div', { id: 'sheet', role: 'dialog', 'aria-modal': 'true', hidden: true });

  document.body.append(topbar, bottombar, toolstrip, panelbar, pill, homeScreen, scrim, sheet);
  body.classList.add('phone', 'home');

  // ---- drawing PdfCraft's state ----

  function render(s) {
    const firstDoc = (!state || !state.active) && s.active;
    const switched = state && state.active !== s.active;
    state = s;
    const doc = s.docs.find((d) => d.doc === s.active);
    // A file that opens (from Android, the picker, a new PDF) is shown at once.
    if (firstDoc || (switched && s.active)) home = false;
    if (!doc) home = true;
    body.classList.toggle('home', home);
    body.classList.toggle('full-screen', !!s.fullScreen);
    body.classList.toggle('busy', !!s.busy);
    undoBtn.disabled = !s.undo;
    renderHome(s);
    if (home) {
      closeStrip(false);
      setImmersive(false);
    }
    const open = doc && s.panel ? PANEL_NAMES[s.panel] || s.panel : '';
    panelName.textContent = open;
    panelbar.hidden = home || !open;
    bottombar.hidden = home || !!open || !!strip;
    toolstrip.hidden = home || !!open || !strip;
    if (strip) markTools();
    for (const b of bottombar.children) {
      const item = BOTTOM.find((x) => x.id === b.dataset.item);
      b.classList.toggle('on', !!item && !!item.tools && item.tools.some((t) => t.cmd && t.cmd === s.tool));
    }
    if (doc && s.page !== lastPage) {
      lastPage = s.page;
      showPill();
    }
    if (!doc) pill.hidden = true;
    if (sheet.dataset.kind === 'file') fileSheet(true);
  }

  function renderHome(s) {
    homeFiles.replaceChildren(...(s.docs.length ? s.docs.map((d) => el('div', { class: 'file' },
      el('button', { class: 'file-open', onclick: () => {
        post('pdfcraft:activate', { doc: d.doc });
        showHome(false);
      } }, el('span', { class: 'file-icon' }, icon('file-text')),
      el('span', { class: 'file-text' }, el('span', { class: 'file-name' }, d.name),
        el('span', { class: 'file-sub' }, d.dirty ? 'PDF · Unsaved changes' : 'PDF'))),
      el('button', { class: 'icon-btn', 'aria-label': `Close ${d.name}`, onclick: () => {
        post('pdfcraft:activate', { doc: d.doc });
        command('file.close');
      } }, icon('x'))))
      : [el('p', { class: 'empty' }, 'No files open. Open a PDF to read, comment, fill and sign, or edit it.')]));
  }

  /** The home screen (true), or the open file (false). */
  function showHome(on) {
    if (!on && !(state && state.active)) return;
    home = on;
    closeSheet();
    closeStrip();
    body.classList.toggle('home', home);
    bottombar.hidden = home || !!(state && state.panel);
    panelbar.hidden = home || !(state && state.panel);
    if (state) render(state);
  }

  function showPill() {
    if (!state || !state.pages) return;
    pill.textContent = `${state.page} / ${state.pages}`;
    pill.hidden = false;
    clearTimeout(pillTimer);
    pillTimer = setTimeout(() => { pill.hidden = true; }, 2000);
  }

  // ---- the bottom bar and its strips ----

  function pressBottom(item) {
    if (item.run) return item.run();
    if (strip === item) return closeStrip();
    if (item.pick) command(item.pick);
    openStrip(item);
  }

  function openStrip(item) {
    strip = item;
    stripTools.replaceChildren(...item.tools.map((t) => el('button', {
      class: 'tool', 'data-cmd': t.cmd || '', 'data-panel': t.panel || '',
      onclick: () => (t.panel ? panel(t.panel) : command(t.cmd)),
    }, icon(t.icon), el('span', {}, t.label))));
    stripTools.scrollLeft = 0;
    bottombar.hidden = true;
    toolstrip.hidden = false;
    markTools();
  }

  /** Back to the bottom bar, and to the Select tool so a finger scrolls the page again. */
  function closeStrip(select = true) {
    if (!strip) return false;
    strip = null;
    toolstrip.hidden = true;
    bottombar.hidden = home || !state || !state.active || !!state.panel;
    if (select) post('pdfcraft:select');
    return true;
  }

  function markTools() {
    for (const b of stripTools.children) b.classList.toggle('on', !!state && b.dataset.cmd !== '' && b.dataset.cmd === state.tool);
  }

  // ---- sheets ----

  function openSheet(kind, content) {
    sheet.dataset.kind = kind;
    sheet.replaceChildren(el('div', { class: 'grabber' }), ...content);
    if (sheet.hidden) {
      scrim.hidden = false;
      sheet.hidden = false;
      requestAnimationFrame(() => {
        scrim.classList.add('open');
        sheet.classList.add('open');
      });
    }
  }

  function closeSheet() {
    if (sheet.hidden) return false;
    delete sheet.dataset.kind;
    scrim.classList.remove('open');
    sheet.classList.remove('open');
    scrim.hidden = true;
    sheet.hidden = true;
    return true;
  }

  const row = (label, iconName, onclick, extra = {}) => el('button', {
    class: 'row', disabled: extra.disabled, onclick,
  }, icon(iconName), el('span', { class: 'label' }, label),
  extra.tick ? el('span', { class: 'tick' }, icon('check')) : null,
  extra.next ? el('span', { class: 'next' }, icon('chevron-right')) : null);

  /** Run a command from a sheet: the sheet goes first. */
  const then = (fn) => () => { closeSheet(); fn(); };

  function fileSheet(refresh = false) {
    if (refresh && sheet.dataset.kind !== 'file') return;
    const s = state;
    const doc = s && s.docs.find((d) => d.doc === s.active);
    if (!doc) return;
    openSheet('file', [
      el('div', { class: 'sheet-head' }, el('div', { class: 'sheet-title' }, doc.name),
        el('div', { class: 'sheet-sub' }, `PDF · ${s.pages} page${s.pages === 1 ? '' : 's'}${doc.dirty ? ' · Unsaved changes' : ''}`)),
      el('div', { class: 'sep' }),
      el('div', { class: 'list' },
        row('View settings', 'file-text', () => viewSheet(), { next: true }),
        row('Pages', 'layout-grid', then(() => panel('Pages')), { next: true }),
        row('Bookmarks', 'bookmark', then(() => panel('Bookmarks')), { next: true }),
        row('Comment list', 'messages-square', then(() => panel('Comments')), { next: true }),
        row('Attachments', 'paperclip', then(() => panel('Attachments')), { next: true }),
        row('Go to page', 'hash', () => gotoSheet()),
        row('Add bookmark', 'bookmark', then(() => command('bookmark.add'))),
        row('Save', 'save', then(() => command('file.save')), { disabled: !s.save }),
        row('Save a copy', 'file-down', then(() => command('file.save_as'))),
        row('Print', 'printer', then(() => command('print.dialog'))),
        row('Document properties', 'info', then(() => command('file.properties'))),
        row('Close file', 'file-x', then(() => command('file.close')))),
    ]);
  }

  function viewSheet() {
    const s = state || {};
    openSheet('view', [
      el('div', { class: 'sheet-head' }, el('button', { class: 'icon-btn back', 'aria-label': 'Back', onclick: () => fileSheet() }, icon('chevron-left')),
        el('div', { class: 'sheet-title' }, 'View settings')),
      el('div', { class: 'list' }, ...VIEW.map((v) => row(v.label, v.icon, then(() => command(v.cmd)), { tick: v.on && v.on(s) }))),
    ]);
  }

  function toolsSheet() {
    openSheet('tools', [
      el('div', { class: 'sheet-head' }, el('div', { class: 'sheet-title big' }, 'More tools')),
      el('div', { class: 'grid' }, ...TILES.map((t) => el('button', { class: 'action', onclick: t.run ? () => t.run() : then(() => command(t.cmd)) },
        el('span', { class: 'tile', style: `--hue:${t.hue}` }, icon(t.icon)), el('span', {}, t.label)))),
    ]);
  }

  function exportSheet() {
    openSheet('export', [
      el('div', { class: 'sheet-head' }, el('button', { class: 'icon-btn back', 'aria-label': 'Back', onclick: () => toolsSheet() }, icon('chevron-left')),
        el('div', { class: 'sheet-title' }, 'Export PDF as')),
      el('div', { class: 'list' }, ...EXPORTS.map((x) => row(x.label, x.icon, then(() => command(x.cmd))))),
    ]);
  }

  function gotoSheet() {
    if (!state || !state.pages) return;
    const input = el('input', { type: 'number', inputmode: 'numeric', min: '1', max: String(state.pages), value: String(state.page),
      'aria-label': `Page number, 1 to ${state.pages}` });
    const go = () => {
      const n = Math.round(Number(input.value));
      if (n >= 1 && n <= state.pages) {
        closeSheet();
        post('pdfcraft:gotoPage', { page: n });
      } else {
        input.select();
      }
    };
    input.addEventListener('keydown', (e) => { if (e.key === 'Enter') go(); });
    openSheet('goto', [
      el('div', { class: 'sheet-head' }, el('div', { class: 'sheet-title' }, `Go to page (1 to ${state.pages})`)),
      el('div', { class: 'goto' }, input, el('button', { onclick: go }, 'Go')),
    ]);
    setTimeout(() => { input.focus(); input.select(); }, 200);
  }

  // ---- a tap on the page hides the bars, another brings them back ----

  function setImmersive(on) {
    if (on === immersive) return;
    immersive = on;
    body.classList.toggle('immersive', on);
    if (!on) showPill();
  }

  function watchTaps() {
    let doc;
    try {
      doc = frame.contentDocument;
    } catch (e) {
      return;
    }
    if (!doc || doc.__phoneTaps) return;
    doc.__phoneTaps = true;
    let down = null;
    let pending = 0;
    let fingers = 0;
    doc.addEventListener('pointerdown', (e) => {
      fingers++;
      clearTimeout(pending); // a second tap: a double tap selects a word instead
      // A finger's tap only: a mouse (a keyboard and mouse on a big screen) never hides the bars.
      down = fingers === 1 && e.pointerType === 'touch' ? { x: e.clientX, y: e.clientY, t: e.timeStamp } : null;
    }, true);
    const up = (e) => {
      fingers = Math.max(0, fingers - 1);
      const d = down;
      down = null;
      if (!d || e.type !== 'pointerup' || home || !state || !state.active || state.tool !== 'select' || state.busy || strip
        || !sheet.hidden || state.panel || state.fullScreen) return;
      if (Math.hypot(e.clientX - d.x, e.clientY - d.y) > 12 || e.timeStamp - d.t > 350) return;
      pending = setTimeout(() => setImmersive(!immersive), 320);
    };
    doc.addEventListener('pointerup', up, true);
    doc.addEventListener('pointercancel', up, true);
  }
  watchTaps();

  // ---- Android's Back ----

  /** Close what is open, innermost first; false when there is nothing (Android goes back). */
  shell.back = () => {
    if (closeSheet()) return true;
    if (state && state.fullScreen) {
      shell.leaveFullScreen();
      return true;
    }
    if (state && state.panel) {
      panel(null);
      return true;
    }
    if (closeStrip()) return true;
    if (immersive) {
      setImmersive(false);
      return true;
    }
    if (!home && state && state.active) {
      showHome(true);
      return true;
    }
    return false;
  };
})();
