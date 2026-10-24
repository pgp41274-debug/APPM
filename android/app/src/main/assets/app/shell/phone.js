// PdfCraft on a phone: the controls, laid out as phone PDF apps lay them out.
//
// PdfCraft runs with `&chrome=host` (shell.js) and draws only the page, its dialogs and the
// open panel; this page draws the controls around it, from the state PdfCraft reports
// (pdfcraft:state), and drives it with the host messages of apps/pdfcraft-web/src/main.rs in
// PdfCraft (patches 0007 and 0008).
//
//   home:    what to do (open, create) and the open files
//   reading: top    ← home · Undo (Redo) · Search · Share · ⋮ (the file: view settings, panels, ...)
//            bottom Edit PDF · Comment · Highlight · Draw · Fill & Sign · More tools
//            as in Acrobat's phone app: a tap on the page hides the bars; a double tap zooms in on
//            the text there, and another zooms back out; in single-page view a sideways swipe or
//            a tap at the screen's edge turns the page; a finger held on a word selects it (with
//            Android's touch-and-hold delay and buzz), with handles to widen it and a card of
//            what to do with it; the scrubber at the right edge drags through the pages
//   a mode:  Edit PDF, Comment, Highlight, Draw or Fill & Sign: ✓ (done) at the top left, the
//            mode's tools along the bottom with its colour (and thickness), a hint on the page
//   search:  the top bar turns into the search box
(() => {
  'use strict';

  const shell = window.shell;
  if (!shell || !shell.controls) return;
  const frame = document.getElementById('app');
  const origin = location.origin;
  const body = document.body;

  // ---- what the controls do ----

  // A mode's tools. `cmd` picks a tool (it stays on until another tool or Done), `run` does
  // something once, `toggle` switches a view on and off.
  const SHAPES = ['comment.square', 'comment.circle', 'comment.line', 'comment.arrow', 'comment.ink'];
  const MODES = {
    edit: { label: 'Edit PDF', enter: 'edit.edit_text', named: true, tools: [
      { toggle: 'page.organize', on: (s) => s.organize, label: 'Organize pages', icon: 'files' },
      { cmd: 'edit.text', label: 'Add text', icon: 'type' },
      { run: 'edit.image', label: 'Add image', icon: 'image-plus' },
    ] },
    comment: { label: 'Comment', enter: 'comment.note', tools: [
      { cmd: 'comment.note', label: 'Sticky note', icon: 'message-square' },
      { cmd: 'comment.freetext', label: 'Text box', icon: 'type' },
      { cmd: 'comment.square', label: 'Rectangle', icon: 'square' },
      { cmd: 'comment.circle', label: 'Oval', icon: 'circle' },
      { cmd: 'comment.line', label: 'Line', icon: 'minus' },
      { cmd: 'comment.arrow', label: 'Arrow', icon: 'arrow-up-right' },
      { cmd: 'comment.stamp', label: 'Stamp', icon: 'stamp' },
    ] },
    highlight: { label: 'Highlight', enter: 'comment.highlight', tools: [
      { cmd: 'comment.highlight', label: 'Highlight', icon: 'highlighter' },
      { cmd: 'comment.underline', label: 'Underline', icon: 'underline' },
      { cmd: 'comment.strikeout', label: 'Strike out', icon: 'strikethrough' },
    ] },
    draw: { label: 'Draw', enter: 'comment.ink', tools: [
      { cmd: 'comment.ink', label: 'Pen', icon: 'pencil' },
      { cmd: 'comment.eraser', label: 'Eraser', icon: 'eraser' },
    ] },
    sign: { label: 'Fill & Sign', enter: 'sign.fill.text', tools: [
      { cmd: 'sign.fill.text', label: 'Text', icon: 'type' },
      { cmd: 'sign.fill.cross', label: 'Cross', icon: 'x' },
      { cmd: 'sign.fill.check', label: 'Check', icon: 'check' },
      { cmd: 'sign.fill.dot', label: 'Dot', icon: 'circle-dot' },
      { cmd: 'sign.fill.line', label: 'Line', icon: 'minus' },
      { cmd: 'sign.fill.date', label: 'Date', icon: 'clock' },
      { cmd: 'sign.fill.signature', label: 'Sign', icon: 'signature', gap: true },
      { cmd: 'sign.fill.initials', label: 'Initials', icon: 'pen-tool' },
    ] },
  };

  const BOTTOM = [
    { mode: 'edit', icon: 'file-pen-line' },
    { mode: 'comment', icon: 'message-square-plus' },
    { mode: 'highlight', icon: 'highlighter' },
    { mode: 'draw', icon: 'pencil' },
    { mode: 'sign', icon: 'signature' },
    { label: 'More tools', icon: 'layout-grid', run: () => toolsSheet() },
  ];

  // Shown on the page as a tool comes on.
  // With a pen in use (an S Pen), as in Samsung Notes and Xodo: the pen marks the page and
  // selects text at once, one finger scrolls, and the pen's side button held erases a drawing.
  const PEN_HINT = 'Mark with the pen. One finger scrolls';
  const PEN_HINTS = {
    'comment.highlight': 'Run the pen over text to highlight. One finger scrolls',
    'comment.underline': 'Run the pen over text to underline. One finger scrolls',
    'comment.strikeout': 'Run the pen over text to strike it out. One finger scrolls',
    'comment.ink': 'Draw with the pen, hold its button to erase. One finger scrolls',
    'comment.eraser': 'Rub a drawing out with the pen. One finger scrolls',
  };
  const HINTS = {
    'comment.highlight': 'Drag over text to highlight',
    'comment.underline': 'Drag over text to underline',
    'comment.strikeout': 'Drag over text to strike it out',
    'comment.ink': 'Drag to draw and use two fingers to scroll',
    'comment.eraser': 'Drag over a drawing to erase it',
    'comment.note': 'Tap to pin your comment',
    'comment.freetext': 'Tap where the text goes',
    'comment.square': 'Drag to draw a rectangle',
    'comment.circle': 'Drag to draw an oval',
    'comment.line': 'Drag to draw a line',
    'comment.arrow': 'Drag to draw an arrow',
    'comment.stamp': 'Tap to place the stamp',
    'sign.fill.text': 'Tap where the text goes',
    'sign.fill.cross': 'Tap to place a cross',
    'sign.fill.check': 'Tap to place a check mark',
    'sign.fill.dot': 'Tap to place a dot',
    'sign.fill.line': 'Drag to draw a line',
    'sign.fill.date': 'Tap to add the date',
    'sign.fill.signature': 'Tap where you sign',
    'sign.fill.initials': 'Tap where your initials go',
    'edit.edit_text': 'Tap text or an image to edit it',
    'edit.text': 'Tap where the new text goes',
  };

  const SWATCHES = ['#000000', '#e53935', '#fb8c00', '#ffd400', '#43a047', '#1e88e5', '#8e24aa', '#ec407a'];

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
    { label: 'Add a link', icon: 'link', cmd: 'edit.link', hue: 200 },
    { label: 'Watermark', icon: 'droplet', cmd: 'edit.watermark', hue: 175 },
    { label: 'All commands', icon: 'search', cmd: 'view.palette', hue: 270 },
  ];

  const EXPORTS = [
    { label: 'Word document', icon: 'file-text', cmd: 'export.docx' },
    { label: 'PowerPoint', icon: 'file-text', cmd: 'export.pptx' },
    { label: 'Images', icon: 'image-plus', cmd: 'export.image' },
    { label: 'Plain text', icon: 'type', cmd: 'export.text' },
    { label: 'Web page (HTML)', icon: 'file-text', cmd: 'export.html' },
  ];

  const PANEL_NAMES = { Comments: 'Comments', Bookmarks: 'Bookmarks', Pages: 'Pages', Fields: 'Form fields',
    Layers: 'Layers', Attachments: 'Attachments', Signatures: 'Signatures' };

  const COMPOSER_HINTS = { note: 'Add a comment', textbox: 'Type text', callout: 'Type text', insert: 'Text to insert',
    replace: 'Replacement text', edit: 'Edit comment' };

  let state = null;
  let mode = null; // the mode whose tools show along the bottom
  let modeTool = null; // the tool last picked in it
  let searching = false;
  let immersive = false;
  let home = true; // the home screen, over the open files
  let lastPage = 0;
  let lastTool = 'select';
  let pillTimer = 0;
  let hintTimer = 0;
  let repickAt = 0;
  let dragging = null; // the selection handle being dragged
  let fingersDown = 0; // fingers on the page
  let scrubbing = false; // a finger is on the scrubber
  let scrubTimer = 0;
  let toldUneditable = false; // Edit PDF said this file's text can't be edited

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
    endMode(false);
    closeSearch(false);
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
  const iconButton = (id, label, name, onclick) => el('button', { class: 'icon-btn', id, 'aria-label': label, title: label, onclick }, icon(name));

  // The file's name, where a tablet's top bar has room for it (CSS hides it on a phone).
  const docTitle = el('span', { id: 'doc-title' });

  // Reading: ← home, then what applies to the whole file.
  const undoBtn = iconButton('undo', 'Undo', 'undo-2', () => command('edit.undo'));
  const redoBtn = iconButton('redo', 'Redo', 'redo-2', () => command('edit.redo'));
  const topbar = el('header', { id: 'topbar', class: 'pbar topbar' },
    iconButton('back', 'Home', 'chevron-left', () => showHome(true)),
    docTitle,
    el('div', { class: 'spacer' }),
    undoBtn, redoBtn,
    iconButton('find', 'Search', 'search', () => openSearch()),
    iconButton('share', 'Share', 'share-2', () => post('pdfcraft:share')),
    iconButton('more', 'More', 'ellipsis-vertical', () => fileSheet()));

  // A mode: ✓ ends it; Undo and Redo stay at hand.
  const modeTitle = el('span', { class: 'mode-title' });
  const modeUndo = iconButton('mode-undo', 'Undo', 'undo-2', () => command('edit.undo'));
  const modeRedo = iconButton('mode-redo', 'Redo', 'redo-2', () => command('edit.redo'));
  const modebar = el('header', { id: 'modebar', class: 'pbar topbar', hidden: true },
    iconButton('done', 'Done', 'check', () => endMode()),
    modeTitle, el('div', { class: 'spacer' }), modeUndo, modeRedo);

  // Search: the box, how many matches, and the way between them.
  const findInput = el('input', { id: 'find-input', type: 'search', placeholder: 'Find in document', 'aria-label': 'Find in document',
    enterkeyhint: 'search', autocomplete: 'off', spellcheck: 'false' });
  const findCount = el('span', { id: 'find-count', 'aria-live': 'polite' });
  const searchbar = el('header', { id: 'searchbar', class: 'pbar topbar', hidden: true },
    iconButton('find-close', 'Close search', 'chevron-left', () => closeSearch()),
    findInput, findCount,
    iconButton('find-clear', 'Clear', 'x', () => { findInput.value = ''; post('pdfcraft:find', { query: '' }); findInput.focus(); }),
    iconButton('find-prev', 'Previous match', 'chevron-up', () => post('pdfcraft:findStep', { forward: false })),
    iconButton('find-next', 'Next match', 'chevron-down', () => post('pdfcraft:findStep', { forward: true })));
  let findTimer = 0;
  findInput.addEventListener('input', () => {
    clearTimeout(findTimer);
    findTimer = setTimeout(() => post('pdfcraft:find', { query: findInput.value }), 250);
  });
  findInput.addEventListener('keydown', (e) => {
    if (e.key !== 'Enter') return;
    e.preventDefault();
    clearTimeout(findTimer);
    if (state && state.find && state.find.query === findInput.value) post('pdfcraft:findStep', { forward: !e.shiftKey });
    else post('pdfcraft:find', { query: findInput.value });
  });

  const bottombar = el('nav', { id: 'bottombar', class: 'pbar' },
    ...BOTTOM.map((b) => {
      const m = b.mode && MODES[b.mode];
      return el('button', { class: 'cat', 'data-item': b.mode || 'tools', onclick: () => (m ? startMode(b.mode) : b.run()) },
        icon(b.icon), el('span', {}, m ? m.label : b.label));
    }));

  // A mode's tools, then its colour and thickness.
  const modeTools = el('div', { id: 'mode-tools' });
  const widthBtn = el('button', { class: 'tool-btn', id: 'width', 'aria-label': 'Thickness', title: 'Thickness', onclick: () => styleSheet() },
    el('span', { class: 'width-lines' }, el('i'), el('i'), el('i')));
  const colorBtn = el('button', { class: 'tool-btn', id: 'color', 'aria-label': 'Colour', title: 'Colour', onclick: () => styleSheet() },
    el('span', { class: 'dot' }));
  const styleTools = el('div', { id: 'style-tools' }, el('span', { class: 'divider' }), widthBtn, colorBtn);
  const toolbar = el('nav', { id: 'toolbar', class: 'pbar', hidden: true }, modeTools, styleTools);

  const panelName = el('span', {});
  const panelbar = el('div', { id: 'panelbar', class: 'pbar', hidden: true },
    panelName,
    el('button', { class: 'icon-btn', 'aria-label': 'Close', onclick: () => panel(null) }, icon('chevron-down')));

  const pill = el('button', { id: 'pagepill', hidden: true, onclick: () => gotoSheet() });
  // The scrubber: a tab at the right edge, as in Acrobat's phone app, shown while the pages go
  // by. Dragged, it goes through the document; tapped, it asks for a page.
  const scrubThumb = el('div', { class: 'thumb' }, icon('chevron-up'), icon('chevron-down'));
  const scrubber = el('div', { id: 'scrubber', hidden: true, 'aria-hidden': 'true' }, scrubThumb);
  const hint = el('div', { id: 'hint', role: 'status', hidden: true });

  // The comment being written: PdfCraft pinned it, this box writes it.
  const composerText = el('textarea', { id: 'composer-text', rows: '1', 'aria-label': 'Comment' });
  const composerSend = iconButton('composer-send', 'Post', 'send', () => finishComposer(true));
  const composer = el('div', { id: 'composer', hidden: true },
    iconButton('composer-cancel', 'Cancel', 'x', () => finishComposer(false)),
    composerText, composerSend);
  composerText.addEventListener('input', () => {
    post('pdfcraft:composerText', { text: composerText.value });
    composerSend.disabled = !composerText.value.trim() && composer.dataset.kind !== 'edit';
    composerText.style.height = 'auto';
    composerText.style.height = `${Math.min(composerText.scrollHeight, 140)}px`;
  });

  // A text selection: a handle at each end, and a card of what to do with it.
  const selCard = el('div', { id: 'selcard', role: 'menu', hidden: true },
    el('div', { class: 'sel-icons' },
      selButton('sel-note', 'Comment', 'message-square', 'note'),
      selButton('sel-highlight', 'Highlight', 'highlighter', 'highlight'),
      selButton('sel-underline', 'Underline', 'underline', 'underline'),
      selButton('sel-strikeout', 'Strike out', 'strikethrough', 'strikeout')),
    el('button', { class: 'sel-row', id: 'sel-edit', role: 'menuitem', onclick: () => {
      post('pdfcraft:selection', { action: 'clear' });
      startMode('edit');
    } }, icon('file-pen-line'), el('span', {}, 'Edit text')),
    el('button', { class: 'sel-row', id: 'sel-copy', role: 'menuitem', onclick: () => {
      if (state && state.selection) shell.copyText(state.selection.text);
      post('pdfcraft:selection', { action: 'clear' });
      showHint('Copied');
    } }, icon('copy'), el('span', {}, 'Copy text')));
  const handles = [el('div', { class: 'handle start', id: 'handle-start', hidden: true }), el('div', { class: 'handle end', id: 'handle-end', hidden: true })];

  function selButton(id, label, name, act) {
    return el('button', { class: 'icon-btn', id, role: 'menuitem', 'aria-label': label, title: label, onclick: () => post('pdfcraft:selection', { action: act }) }, icon(name));
  }

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

  document.body.append(topbar, modebar, searchbar, bottombar, toolbar, panelbar, pill, scrubber, hint, composer, selCard, ...handles,
    homeScreen, scrim, sheet);
  // `phone`: the host draws the controls (phones and tablets); `tablet`: 600 dp or wider.
  body.classList.add('phone', 'home');
  if (shell.tablet) body.classList.add('tablet');

  // ---- drawing PdfCraft's state ----

  function render(s) {
    const firstDoc = (!state || !state.active) && s.active;
    const switched = state && state.active !== s.active;
    const before = state;
    state = s;
    const doc = s.docs.find((d) => d.doc === s.active);
    // A file that opens (from Android, the picker, a new PDF) is shown at once.
    if (firstDoc || (switched && s.active)) home = false;
    if (!doc) home = true;
    if (home || switched) {
      endMode(false);
      closeSearch(false);
      setImmersive(false);
    }
    body.classList.toggle('home', home);
    body.classList.toggle('full-screen', !!s.fullScreen);
    body.classList.toggle('busy', !!s.busy);
    undoBtn.disabled = modeUndo.disabled = !s.undo;
    redoBtn.hidden = modeRedo.hidden = !s.redo;
    renderHome(s);
    const open = doc && s.panel ? PANEL_NAMES[s.panel] || s.panel : '';
    panelName.textContent = open;
    docTitle.textContent = doc ? doc.name : '';
    // A phone's panel covers the bars (its own bar closes it); a tablet's sits beside the page,
    // with its own close button, and the bars stay.
    const covering = !!open && !shell.tablet;
    panelbar.hidden = home || !covering;
    bottombar.hidden = home || covering || !!mode;
    toolbar.hidden = home || covering || !mode;
    topbar.hidden = home || !!mode || searching;
    modebar.hidden = home || !mode;
    searchbar.hidden = home || !searching || !!mode;
    if (mode) renderMode(s);
    if (searching) renderFind(s);
    renderComposer(s, before);
    renderSelection(s);
    // A finger held on a word selected it: Android's long-press buzz, as everywhere else.
    if (s.selection && !(before && before.selection) && fingersDown > 0) shell.haptic && shell.haptic();
    if (doc && s.page !== lastPage) {
      lastPage = s.page;
      showPill();
    }
    if (!doc) pill.hidden = true;
    if (!doc || mode || covering) hideScrubber();
    // The first pen PdfCraft sees: from now on the pen marks and a finger scrolls, this time and
    // the next.
    if (s.pen && !(before && before.pen)) {
      if (shell.rememberPen) shell.rememberPen();
      if (mode) showHint(PEN_HINT);
    }
    if (s.tool !== lastTool) {
      lastTool = s.tool;
      if (mode && HINTS[s.tool]) showHint(s.pen && PEN_HINTS[s.tool] ? PEN_HINTS[s.tool] : HINTS[s.tool]);
    }
    if (sheet.dataset.kind === 'file') fileSheet(true);
    if (sheet.dataset.kind === 'view') viewSheet(true);
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
    endMode();
    closeSearch();
    body.classList.toggle('home', home);
    if (state) render(state);
  }

  function showPill(page) {
    if (!state || !state.pages) return;
    pill.textContent = `${page || state.page} / ${state.pages}`;
    pill.hidden = false;
    clearTimeout(pillTimer);
    if (!scrubbing) pillTimer = setTimeout(() => { pill.hidden = true; }, 2000);
    showScrubber();
  }

  // ---- the scrubber ----

  /** The track the scrubber's tab runs along: between the bars, or the screen's height without them. */
  function scrubTrack() {
    const bars = !immersive && !(state && state.fullScreen);
    const top = (bars ? topbar.getBoundingClientRect().bottom : 0) + 12;
    const foot = bars ? (bottombar.hidden ? toolbar : bottombar).getBoundingClientRect().top : window.innerHeight;
    return { top, height: Math.max(0, foot - 12 - top) };
  }

  /** Where page `page` (1-based) puts the tab, as a share of the track. */
  const pageShare = (page) => (state.pages > 1 ? (page - 1) / (state.pages - 1) : 0);

  function placeThumb(share) {
    const t = scrubTrack();
    scrubber.style.top = `${t.top}px`;
    scrubber.style.height = `${t.height}px`;
    const room = Math.max(0, t.height - (scrubThumb.offsetHeight || 48));
    scrubThumb.style.transform = `translateY(${Math.round(share * room)}px)`;
  }

  /** Long documents only (three pages or more, as in Acrobat), while reading. */
  function showScrubber() {
    const covering = !!(state && state.panel) && !shell.tablet;
    if (!state || state.pages < 3 || home || mode || searching || covering) return hideScrubber();
    scrubber.hidden = false;
    if (!scrubbing) placeThumb(pageShare(state.page));
    clearTimeout(scrubTimer);
    if (!scrubbing) scrubTimer = setTimeout(() => { scrubber.hidden = true; }, 2500);
  }

  function hideScrubber() {
    if (scrubbing) return;
    clearTimeout(scrubTimer);
    scrubber.hidden = true;
  }

  {
    let start = null;
    let sent = 0;
    let queued = 0;
    const pageAt = (y) => {
      const t = scrubTrack();
      const room = Math.max(1, t.height - scrubThumb.offsetHeight);
      const share = Math.max(0, Math.min(1, (y - start.grab - t.top) / room));
      return { share, page: 1 + Math.round(share * (state.pages - 1)) };
    };
    scrubThumb.addEventListener('pointerdown', (e) => {
      if (!state || !state.pages) return;
      e.preventDefault();
      scrubThumb.setPointerCapture(e.pointerId);
      scrubbing = true;
      clearTimeout(scrubTimer);
      clearTimeout(pillTimer);
      start = { y: e.clientY, grab: e.clientY - scrubThumb.getBoundingClientRect().top, moved: false };
      sent = state.page;
      scrubThumb.classList.add('held');
    });
    scrubThumb.addEventListener('pointermove', (e) => {
      if (!scrubbing || !start) return;
      if (!start.moved && Math.abs(e.clientY - start.y) < 6) return;
      start.moved = true;
      const at = pageAt(e.clientY);
      placeThumb(at.share);
      showPill(at.page);
      // One page change a frame at most: PdfCraft draws each page it is sent to.
      if (at.page !== sent && !queued) {
        queued = requestAnimationFrame(() => {
          queued = 0;
          const now = pageAt(e.clientY).page;
          if (now !== sent) {
            sent = now;
            post('pdfcraft:gotoPage', { page: now });
          }
        });
      }
    });
    const drop = (e) => {
      if (!scrubbing) return;
      const tapped = start && !start.moved && e.type === 'pointerup';
      if (start && start.moved && e.type === 'pointerup') {
        const last = pageAt(e.clientY).page;
        if (last !== sent) post('pdfcraft:gotoPage', { page: last });
      }
      scrubbing = false;
      start = null;
      scrubThumb.classList.remove('held');
      if (tapped) {
        // Opened on the tap's own click, which a finger's tap delivers to whatever is under it
        // by then: opened sooner, the sheet's scrim would take that click and close it again.
        clearTimeout(tapTimer);
        tapTimer = setTimeout(openGoto, 400);
      } else {
        showPill();
      }
    };
    const openGoto = () => {
      if (!tapTimer) return;
      clearTimeout(tapTimer);
      tapTimer = 0;
      hideScrubber();
      gotoSheet();
    };
    let tapTimer = 0;
    scrubThumb.addEventListener('pointerup', drop);
    scrubThumb.addEventListener('pointercancel', drop);
    scrubThumb.addEventListener('click', openGoto);
  }

  function showHint(text) {
    hint.textContent = text;
    hint.hidden = false;
    hint.classList.remove('out');
    clearTimeout(hintTimer);
    hintTimer = setTimeout(() => {
      hint.classList.add('out');
      hintTimer = setTimeout(() => { hint.hidden = true; }, 300);
    }, 2200);
  }

  // ---- modes ----

  function startMode(name) {
    const m = MODES[name];
    if (!m || !state || !state.active) return;
    closeSearch();
    closeSheet();
    setImmersive(false);
    mode = name;
    modeTool = m.enter;
    toldUneditable = false;
    body.classList.add('mode');
    modeTitle.textContent = m.named ? m.label : '';
    modeTools.classList.toggle('named', !!m.named);
    modeTools.replaceChildren(...m.tools.map((t) => {
      const b = el('button', { class: `tool-btn${t.gap ? ' gap' : ''}`, 'data-cmd': t.cmd || t.toggle || '', 'aria-label': t.label, title: t.label,
        onclick: () => pickTool(t) }, icon(t.icon), m.named ? el('span', {}, t.label) : null);
      return b;
    }));
    command(m.enter);
    if (state) render(state);
  }

  function pickTool(t) {
    if (t.toggle) return command(t.toggle);
    if (t.run) return command(t.run);
    // Edit PDF goes back to editing text once its text is placed; other modes keep the tool.
    if (!MODES[mode].named) modeTool = t.cmd;
    command(t.cmd);
  }

  /** Done: back to reading, and to the Select tool so a finger scrolls the page again. */
  function endMode(select = true) {
    if (!mode) return false;
    mode = null;
    modeTool = null;
    body.classList.remove('mode');
    clearTimeout(hintTimer);
    hint.hidden = true;
    if (select) {
      if (state && state.organize) command('page.organize');
      post('pdfcraft:select');
    }
    if (state) render(state);
    return true;
  }

  function renderMode(s) {
    const m = MODES[mode];
    for (const b of modeTools.children) {
      const t = m.tools.find((x) => (x.cmd || x.toggle || '') === b.dataset.cmd);
      b.classList.toggle('on', !!t && (t.toggle ? !!t.on(s) : t.cmd === s.tool));
    }
    // A comment tool that finished its comment hands back Select: the mode keeps its tool.
    if (s.tool === 'select' && modeTool && !s.composer && !s.busy && Date.now() - repickAt > 1000) {
      repickAt = Date.now();
      command(modeTool);
    }
    // Text PdfCraft can't edit (in many PDFs made by browsers and office apps): say so, once.
    if (mode === 'edit' && s.editable === 0 && !toldUneditable) {
      toldUneditable = true;
      showHint('The text on these pages can\'t be edited. You can still add text and images.');
    }
    const styled = !!s.style && s.tool !== 'comment.eraser';
    styleTools.hidden = !styled;
    widthBtn.hidden = !styled || !SHAPES.includes(s.tool);
    if (styled) {
      colorBtn.querySelector('.dot').style.background = s.style.color;
      colorBtn.querySelector('.dot').style.opacity = String(Math.max(0.35, s.style.opacity));
      widthBtn.style.setProperty('--w', `${Math.max(1, Math.min(6, s.style.width))}px`);
    }
  }

  // ---- search ----

  function openSearch() {
    if (!state || !state.active) return;
    endMode();
    closeSheet();
    searching = true;
    findInput.value = (state.find && state.find.query) || '';
    render(state);
    setTimeout(() => { findInput.focus(); findInput.select(); }, 50);
  }

  function closeSearch(tell = true) {
    if (!searching) return false;
    searching = false;
    clearTimeout(findTimer);
    findInput.blur();
    if (tell) post('pdfcraft:findClose');
    if (state) render(state);
    return true;
  }

  function renderFind(s) {
    const f = s.find;
    const n = f && f.query ? f.matches : 0;
    findCount.textContent = !f || !f.query ? '' : n ? `${f.current || 0}/${n}` : 'No results';
    document.getElementById('find-prev').disabled = document.getElementById('find-next').disabled = n < 2;
    document.getElementById('find-clear').hidden = !findInput.value;
  }

  // ---- writing a comment ----

  function renderComposer(s, before) {
    const c = s.composer;
    if (!c) {
      if (!composer.hidden) {
        composer.hidden = true;
        composerText.blur();
      }
      return;
    }
    if (composer.hidden || !before || !before.composer) {
      composer.dataset.kind = c.kind;
      composerText.placeholder = COMPOSER_HINTS[c.kind] || 'Add a comment';
      composerText.value = c.text || '';
      composerText.style.height = 'auto';
      composerSend.disabled = !composerText.value.trim() && c.kind !== 'edit';
      composer.hidden = false;
      setTimeout(() => composerText.focus(), 50);
    }
  }

  function finishComposer(postIt) {
    if (composer.hidden) return false;
    if (postIt) post('pdfcraft:composerText', { text: composerText.value });
    post('pdfcraft:composerDone', { post: postIt });
    composer.hidden = true;
    composerText.blur();
    return true;
  }

  // ---- a text selection ----

  function renderSelection(s) {
    const sel = s.selection;
    const f = frame.getBoundingClientRect();
    const shown = !!sel && !home && !mode && !s.busy && sheet.hidden && composer.hidden
      && sel.y + sel.h > 0 && sel.y < f.height && sel.x + sel.w > 0 && sel.x < f.width;
    for (const h of handles) h.hidden = !shown;
    selCard.hidden = !shown || !!dragging;
    if (!shown) return;
    const [sx, sy] = sel.start;
    const [ex, ey] = sel.end;
    handles[0].style.transform = `translate(${f.left + sx - 22}px, ${f.top + sy}px)`;
    handles[1].style.transform = `translate(${f.left + ex}px, ${f.top + ey}px)`;
    if (dragging) return;
    // Above the words when there is room under the top bar, otherwise below the handles.
    const cardW = Math.min(300, window.innerWidth - 24);
    selCard.style.width = `${cardW}px`;
    const cardH = selCard.offsetHeight || 220;
    const top = f.top + sel.y;
    const bottom = f.top + sel.y + sel.h;
    const topLimit = (topbar.hidden ? 0 : topbar.getBoundingClientRect().bottom) + 8;
    let y = top - cardH - 12;
    if (y < topLimit) y = Math.min(bottom + 34, window.innerHeight - cardH - 8);
    const x = Math.max(12, Math.min(window.innerWidth - cardW - 12, f.left + sel.x + sel.w / 2 - cardW / 2));
    selCard.style.transform = `translate(${x}px, ${y}px)`;
  }

  // A handle follows the finger: the text nearest the point above the finger, where the line is.
  for (const [i, h] of handles.entries()) {
    let frameRect = null;
    let queued = null;
    h.addEventListener('pointerdown', (e) => {
      e.preventDefault();
      h.setPointerCapture(e.pointerId);
      dragging = h;
      frameRect = frame.getBoundingClientRect();
      selCard.hidden = true;
    });
    h.addEventListener('pointermove', (e) => {
      if (dragging !== h) return;
      const at = { end: i === 1, x: e.clientX - frameRect.left, y: e.clientY - frameRect.top - 26 };
      if (!queued) requestAnimationFrame(() => { post('pdfcraft:selectionHandle', queued); queued = null; });
      queued = at;
    });
    const drop = () => {
      if (dragging !== h) return;
      dragging = null;
      if (state) renderSelection(state);
    };
    h.addEventListener('pointerup', drop);
    h.addEventListener('pointercancel', drop);
  }

  // ---- sheets ----

  function openSheet(kind, content) {
    sheet.dataset.kind = kind;
    sheet.replaceChildren(el('div', { class: 'grabber' }), ...content);
    selCard.hidden = true;
    for (const h of handles) h.hidden = true;
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
    if (state) renderSelection(state);
    return true;
  }

  const row = (label, iconName, onclick, extra = {}) => el('button', {
    class: 'row', disabled: extra.disabled, onclick, role: extra.toggle !== undefined ? 'switch' : null,
    'aria-checked': extra.toggle !== undefined ? String(!!extra.toggle) : null,
  }, icon(iconName), el('span', { class: 'label' }, label),
  extra.tick ? el('span', { class: 'tick' }, icon('check')) : null,
  extra.next ? el('span', { class: 'next' }, icon('chevron-right')) : null,
  extra.toggle !== undefined ? el('span', { class: `switch${extra.toggle ? ' on' : ''}` }) : null);

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
        row('Share', 'share-2', then(() => post('pdfcraft:share'))),
        row('Print', 'printer', then(() => command('print.dialog'))),
        row('Document properties', 'info', then(() => command('file.properties'))),
        row('Close file', 'file-x', then(() => command('file.close')))),
    ]);
  }

  function viewSheet(refresh = false) {
    if (refresh && sheet.dataset.kind !== 'view') return;
    const s = state || {};
    openSheet('view', [
      el('div', { class: 'sheet-head' }, el('button', { class: 'icon-btn back', 'aria-label': 'Back', onclick: () => fileSheet() }, icon('chevron-left')),
        el('div', { class: 'sheet-title' }, 'View settings')),
      el('div', { class: 'list' },
        row('Continuous', 'rows-3', () => { if (!s.continuous) command('view.continuous'); }, { tick: !!s.continuous }),
        row('Single page', 'file-text', () => { if (s.continuous) command('view.continuous'); command('view.layout.single'); }, { tick: !s.continuous }),
        row('Two pages side by side', 'columns-2', then(() => command('view.layout.two_up'))),
        el('div', { class: 'sep' }),
        row('Night mode', 'moon', () => post('pdfcraft:night', { on: !s.night }), { toggle: !!s.night }),
        el('div', { class: 'sep' }),
        row('Fit width', 'arrow-left-right', then(() => command('view.fit_width_scrolling'))),
        row('Fit page', 'minimize-2', then(() => command('view.fit_one_page'))),
        row('Full screen', 'maximize', then(() => command('view.full_screen')))),
    ]);
  }

  /** The current tool's colour, opacity and (for pens and shapes) thickness. */
  function styleSheet() {
    const s = state;
    if (!s || !s.style) return;
    const send = (fields) => post('pdfcraft:style', fields);
    const swatches = el('div', { class: 'swatches' }, ...SWATCHES.map((c) => {
      const b = el('button', { class: 'swatch', 'aria-label': `Colour ${c}`, style: `background:${c}`, onclick: () => {
        for (const x of swatches.children) x.classList.toggle('on', x === b);
        send({ color: c });
      } });
      b.classList.toggle('on', c === s.style.color);
      return b;
    }));
    const slider = (label, min, max, step, value, unit, onvalue) => {
      const out = el('span', { class: 'value' }, `${value}${unit}`);
      const input = el('input', { type: 'range', min: String(min), max: String(max), step: String(step), value: String(value), 'aria-label': label });
      input.addEventListener('input', () => {
        out.textContent = `${input.value}${unit}`;
        onvalue(Number(input.value));
      });
      return el('label', { class: 'slider' }, el('span', { class: 'label' }, label), input, out);
    };
    const content = [
      el('div', { class: 'sheet-head' }, el('div', { class: 'sheet-title' }, 'Colour')),
      swatches,
      slider('Opacity', 10, 100, 5, Math.round(s.style.opacity * 100), '%', (v) => send({ opacity: v / 100 })),
    ];
    if (SHAPES.includes(s.tool)) content.push(slider('Thickness', 0.5, 12, 0.5, s.style.width, ' pt', (v) => send({ width: v })));
    openSheet('style', content);
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
    else if (!scrubber.hidden) placeThumb(pageShare(state.page));
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
    let lastTap = null; // the tap a second one, soon and close by, makes a double tap
    // Two fingers on the page (a pinch or a two-finger scroll), and when the last of them lifted:
    // as in Acrobat, a touch in the next 500 ms is the end of that gesture, not a tap or a swipe.
    let several = false;
    let severalEnd = -Infinity;
    const touch = () => shell.touch || { longPress: 400, doubleTap: 300, slop: 8, doubleTapSlop: 100 };
    // A pen near the screen (touching or hovering, and a moment after): a finger's touch then is
    // the hand holding it, and PdfCraft drops it too.
    let penAt = -Infinity;
    let penDown = false;
    const penNear = (e) => penDown || e.timeStamp - penAt < 300;
    const palms = new Set();
    const notePen = (e) => {
      if (e.pointerType !== 'pen') return;
      penAt = e.timeStamp;
      if (e.type === 'pointerdown') penDown = true;
      else if (e.type === 'pointerup' || e.type === 'pointercancel') penDown = false;
    };
    doc.addEventListener('pointermove', notePen, true);
    // The press's recent path, for its sideways speed as it lifts.
    doc.addEventListener('pointermove', (e) => {
      if (!down) return;
      down.moves.push({ x: e.clientX, t: e.timeStamp });
      if (down.moves.length > 32) down.moves.splice(1, 1);
    }, true);
    doc.addEventListener('pointerdown', (e) => {
      const palm = e.pointerType === 'touch' && penNear(e);
      notePen(e);
      if (palm) {
        palms.add(e.pointerId);
        return;
      }
      if (e.pointerType === 'touch' && ++fingersDown > 1) several = true;
      // A second tap (of a double tap) waits for its own release.
      clearTimeout(pending);
      // A finger's tap only. With text selected, a tap lets it go (below); otherwise, while
      // reading, it hides the bars. A tap that places a comment or goes to a tool keeps them.
      const free = state && state.active && !home && !mode && !searching && !state.busy && !state.composer && sheet.hidden;
      const selecting = free && !!state.selection;
      const reading = free && state.tool === 'select' && !state.selection && !state.panel && !state.fullScreen;
      // A pen taps as a finger does; it drags to select text, so it never swipes.
      const pen = e.pointerType === 'pen';
      const settled = !several && e.timeStamp - severalEnd >= 500;
      down = settled && (pen ? fingersDown === 0 : fingersDown === 1 && e.pointerType === 'touch') && (reading || selecting)
        ? { x: e.clientX, y: e.clientY, t: e.timeStamp, selecting, pen, moves: [{ x: e.clientX, t: e.timeStamp }] } : null;
      if (!down) lastTap = null;
    }, true);
    const up = (e) => {
      notePen(e);
      if (palms.delete(e.pointerId)) return;
      if (e.pointerType === 'touch') fingersDown = Math.max(0, fingersDown - 1);
      if (several && fingersDown === 0) {
        several = false;
        severalEnd = e.timeStamp;
      }
      const d = down;
      down = null;
      if (!d || e.type !== 'pointerup') return;
      const dx = e.clientX - d.x;
      const dy = e.clientY - d.y;
      const t = touch();
      // Single-page view: a quick sideways swipe turns the page (PdfCraft keeps a page zoomed in
      // past the screen's width for scrolling). Quick is Acrobat's fling: 500 screen pixels a
      // second sideways as the finger lifts; a slow drag only moves the page.
      // Over the path's last 50 ms or more (moves can arrive bunched in one frame), from the
      // press itself when the path is shorter.
      const last = d.moves[d.moves.length - 1];
      const from = d.moves.findLast((m) => last.t - m.t >= 50) || d.moves[0];
      const speed = Math.abs(last.x - from.x) / Math.max(1, last.t - from.t) * 1000 * (window.devicePixelRatio || 1);
      if (!d.selecting && !d.pen && !state.continuous && Math.abs(dx) > t.slop && Math.abs(dx) > 1.5 * Math.abs(dy) && speed > 500) {
        lastTap = null;
        post('pdfcraft:turnPage', { forward: dx < 0, swipe: true });
        return;
      }
      // Android's tap: lifted within its touch slop, before a long press would begin.
      if (Math.hypot(dx, dy) > t.slop || e.timeStamp - d.t >= t.longPress) {
        lastTap = null;
        return;
      }
      if (d.selecting) {
        lastTap = null;
        // A tap anywhere but on the selected words lets the selection go, as in any PDF viewer
        // (Back does too). A tap on them leaves it; a long press is how another word is chosen.
        const sel = state && state.selection;
        const pad = 12;
        const inside = sel && d.x >= sel.x - pad && d.x <= sel.x + sel.w + pad && d.y >= sel.y - pad && d.y <= sel.y + sel.h + pad;
        if (sel && !inside) post('pdfcraft:selection', { action: 'clear' });
        return;
      }
      // A double tap zooms in on the text under it, or back out (Acrobat's smart zoom).
      const wait = t.doubleTap;
      if (lastTap && d.t - lastTap.t <= wait && Math.hypot(d.x - lastTap.x, d.y - lastTap.y) <= t.doubleTapSlop) {
        lastTap = null;
        post('pdfcraft:smartZoom', { x: e.clientX, y: e.clientY });
        return;
      }
      lastTap = { x: e.clientX, y: e.clientY, t: e.timeStamp };
      // A tap at the screen's left or right edge turns the page in single-page view; anywhere
      // else it hides or shows the bars. Either waits to be sure no second tap follows.
      const width = doc.documentElement.clientWidth || window.innerWidth;
      const edge = !state.continuous && (e.clientX < width * 0.15 || e.clientX > width * 0.85);
      const forward = e.clientX > width / 2;
      pending = setTimeout(() => {
        lastTap = null;
        if (edge) post('pdfcraft:turnPage', { forward, swipe: false });
        else setImmersive(!immersive);
      }, wait + 20);
    };
    doc.addEventListener('pointerup', up, true);
    doc.addEventListener('pointercancel', up, true);
  }
  watchTaps();

  // ---- Android's Back ----

  /** Close what is open, innermost first; false when there is nothing (Android goes back). */
  shell.back = () => {
    if (closeSheet()) return true;
    if (finishComposer(false)) return true;
    if (state && state.selection) {
      post('pdfcraft:selection', { action: 'clear' });
      return true;
    }
    if (closeSearch()) return true;
    if (state && state.fullScreen) {
      shell.leaveFullScreen();
      return true;
    }
    if (state && state.panel) {
      panel(null);
      return true;
    }
    if (endMode()) return true;
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
