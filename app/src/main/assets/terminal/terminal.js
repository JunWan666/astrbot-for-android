/* Offline renderer. Each tab has its own terminal, transcript cursor and PTY identity. */
(() => {
  'use strict';
  const post = value => window.UbuntuTerminal.postMessage(JSON.stringify(value));
  const container = document.getElementById('terminal');
  const entries = new Map();
  let current = null, active = true, fontSize = 10, selecting = false, range = null;
  let magnifierFrame = 0, magnifierPoint = null, magnifierShown = false;
  let longPress = null, touchOrigin = null, dragging = null, ignoreClickUntil = 0;
  let scrollTouch = null, inertia = 0, dragFrame = 0, scrollPixels = 0, scrollView = null;
  let labels = {latest:'Latest'}, reportedSelection = false, reportedSession = null;
  const latest = document.createElement('button');
  latest.type = 'button'; latest.className = 'scroll-latest';
  latest.addEventListener('click', () => { stopScroll(); current?.term.scrollToBottom(); updateLatest(); });
  document.body.appendChild(latest);
  const handles = ['start','end'].map(edge => {
    const element = document.createElement('div');
    element.className = 'selection-handle ' + edge;
    element.dataset.edge = edge;
    element.addEventListener('touchstart', e => {
      if (!range || !current) return;
      e.preventDefault(); e.stopPropagation();
      const cols = current.term.cols;
      const start = range.start.y * cols + range.start.x, end = range.end.y * cols + range.end.x - 1;
      const index = edge === 'start' ? start : end;
      const rect = current.element.querySelector('.xterm-screen').getBoundingClientRect();
      const point = {clientX:rect.left + (index % cols + 0.5) * rect.width / cols,
        clientY:rect.top + (Math.floor(index / cols) - current.term.buffer.active.viewportY + 0.5) * rect.height / current.term.rows};
      const touch = e.touches[0];
      if (!touch) return;
      dragging = {anchor:edge === 'start' ? end : start, dx:touch.clientX - point.clientX, dy:touch.clientY - point.clientY};
      showMagnifier(point);
    }, {passive:false});
    element.addEventListener('touchmove', e => {
      if (!dragging || !e.touches[0]) return;
      e.preventDefault(); e.stopPropagation();
      const point = {clientX:e.touches[0].clientX - dragging.dx, clientY:e.touches[0].clientY - dragging.dy};
      extendSelection(point, dragging.anchor); showMagnifier(point);
    }, {passive:false});
    for (const type of ['touchend','touchcancel']) element.addEventListener(type, e => {
      e.preventDefault(); e.stopPropagation(); dragging = null; hideMagnifier();
    }, {passive:false});
    document.body.appendChild(element); return element;
  });
  function updateLabels() {
    latest.textContent = '↓ ' + labels.latest;
  }
  function showMagnifier(touch) {
    if (!current || !selecting) return;
    const point = position(touch), term = current.term;
    const rect = current.element.querySelector('.xterm-screen').getBoundingClientRect();
    magnifierPoint = {type:'magnifier',id:current.id,show:true,
      x:Math.max(0,Math.min(innerWidth,rect.left + (point.x + 0.5) * rect.width / term.cols)),
      y:Math.max(0,Math.min(innerHeight,rect.top + (point.y - term.buffer.active.viewportY + 0.5) * rect.height / term.rows)),
      width:innerWidth,height:innerHeight};
    if (!magnifierFrame) magnifierFrame = requestAnimationFrame(() => {
      magnifierFrame = 0;
      if (magnifierPoint && selecting && active) { magnifierShown = true; post(magnifierPoint); }
    });
  }
  function hideMagnifier() {
    cancelAnimationFrame(magnifierFrame); magnifierFrame = 0; magnifierPoint = null;
    if (magnifierShown) { magnifierShown = false; post({type:'magnifier',id:current?.id,show:false}); }
  }
  function updateLatest() {
    const buffer = current?.term.buffer.active;
    latest.style.display = !selecting && buffer && buffer.viewportY < buffer.baseY ? 'block' : 'none';
  }
  function stopScroll() {
    cancelAnimationFrame(inertia); cancelAnimationFrame(dragFrame);
    inertia = 0; dragFrame = 0; scrollTouch = null; scrollPixels = 0;
    if (scrollView) {
      scrollView.screen.style.transform = '';
      scrollView.screen.style.willChange = '';
      scrollView = null;
    }
    if (current) {
      current.scrolling = false;
      if (current.pending && !selecting) { const batch = current.pending; current.pending = null; receive(batch); }
    }
    poll();
  }
  function offsetScreen() {
    if (scrollView) scrollView.screen.style.transform = `translate3d(0,${scrollView.rendered * scrollView.height - scrollView.position}px,0)`;
  }
  function scrollBy(pixels) {
    if (!current) return false;
    const term = current.term;
    if (!scrollView) {
      const screen = current.element.querySelector('.xterm-screen');
      const height = screen.getBoundingClientRect().height / term.rows;
      if (!height) return false;
      // Measure once per gesture. Fractional rows move on the compositor while
      // xterm redraws complete rows; no repeated layout read on the animation path.
      scrollView = {screen,height,position:term.buffer.active.viewportY * height,rendered:term.buffer.active.viewportY};
      screen.style.willChange = 'transform';
    }
    const previous = scrollView.position;
    scrollView.position = Math.max(0, Math.min(term.buffer.active.baseY * scrollView.height, previous + pixels));
    term.scrollToLine(Math.round(scrollView.position / scrollView.height));
    offsetScreen(); updateLatest();
    return previous !== scrollView.position;
  }
  function flushDrag() {
    cancelAnimationFrame(dragFrame); dragFrame = 0;
    if (scrollPixels) { const delta = scrollPixels; scrollPixels = 0; scrollBy(delta); }
  }
  function finishScroll(velocity) {
    flushDrag();
    scrollTouch = null;
    let previous = performance.now();
    const frame = now => {
      const elapsed = Math.min(32, now - previous); previous = now;
      const decay = Math.exp(-elapsed / 325);
      const moved = scrollBy(velocity * 325 * (1 - decay));
      velocity *= decay;
      if (moved && Math.abs(velocity) > 0.025) inertia = requestAnimationFrame(frame);
      else stopScroll();
    };
    inertia = requestAnimationFrame(frame);
  }
  function selectionUi() {
    const term = current?.term;
    range = term?.getSelectionPosition() || null;
    selecting = !!range && term.hasSelection();
    updateLatest();
    // Selection actions live in the native tab bar, outside the output viewport.
    if (reportedSelection !== selecting || reportedSession !== current?.id) {
      reportedSelection = selecting; reportedSession = current?.id;
      post({type:'selection',id:current?.id,selected:selecting});
    }
    if (!selecting) { hideMagnifier(); handles.forEach(h => h.style.display = 'none'); return; }
    const rect = current.element.querySelector('.xterm-screen').getBoundingClientRect();
    const width = rect.width / term.cols, height = rect.height / term.rows;
    [range.start, range.end].forEach((point,index) => {
      const handle = handles[index], row = point.y - term.buffer.active.viewportY;
      handle.style.display = row >= 0 && row < term.rows ? 'block' : 'none';
      handle.style.left = Math.max(12, Math.min(innerWidth - 12, rect.left + point.x * width)) + 'px';
      handle.style.top = (rect.top + (row + 1) * height) + 'px';
    });
  }
  function clearSelection() {
    const entry = current;
    selecting = false; range = null; dragging = null;
    hideMagnifier();
    entry?.term.clearSelection(); selectionUi();
    if (entry?.pending) { const batch = entry.pending; entry.pending = null; receive(batch); }
    poll();
  }
  function position(touch) {
    const term = current.term;
    const rect = current.element.querySelector('.xterm-screen').getBoundingClientRect();
    const x = Math.max(0, Math.min(term.cols - 1, Math.floor((touch.clientX - rect.left) / (rect.width / term.cols))));
    const y = Math.max(0, Math.min(term.rows - 1, Math.floor((touch.clientY - rect.top) / (rect.height / term.rows))));
    return {x, y:term.buffer.active.viewportY + y};
  }
  function extendSelection(touch, anchor) {
    if (!current) return;
    const term = current.term, point = position(touch), end = point.y * term.cols + point.x;
    const start = Math.min(anchor,end), last = Math.max(anchor,end);
    term.select(start % term.cols, Math.floor(start / term.cols), last - start + 1);
    selectionUi();
  }
  function selectWord(touch) {
    if (!current) return;
    const term = current.term, point = position(touch), line = term.buffer.active.getLine(point.y);
    const chars = x => line?.getCell(x)?.getChars() || ' ';
    const category = text => /\s/.test(text) ? 0 : /[\w:/?&=.%@~+\-#]/u.test(text) ? 1 : 2;
    const group = category(chars(point.x));
    let first = point.x, last = point.x;
    while (first > 0 && category(chars(first - 1)) === group) first--;
    while (last + 1 < term.cols && category(chars(last + 1)) === group) last++;
    if (group === 0) first = last = point.x;
    term.select(first, point.y, last - first + 1);
    current.element.querySelector('textarea')?.blur();
    selectionUi(); showMagnifier(touch);
  }
  container.addEventListener('touchstart', e => {
    stopScroll();
    clearTimeout(longPress);
    if (e.touches.length !== 1 || !current) return;
    if (selecting) { clearSelection(); return; }
    const touch = e.touches[0];
    touchOrigin = {clientX:touch.clientX,clientY:touch.clientY};
    scrollTouch = {y:touch.clientY,time:performance.now(),velocity:0,moved:false};
    longPress = setTimeout(() => { longPress = null; selectWord(touchOrigin); ignoreClickUntil = Date.now() + 600; }, 500);
  }, {capture:true,passive:true});
  container.addEventListener('touchmove', e => {
    const touch = e.touches[0];
    if (!touch || !touchOrigin) return;
    if (selecting) {
      e.preventDefault(); e.stopImmediatePropagation();
      const anchor = range.start.y * current.term.cols + range.start.x;
      extendSelection(touch, anchor); showMagnifier(touch); return;
    }
    if (Math.hypot(touch.clientX - touchOrigin.clientX,touch.clientY - touchOrigin.clientY) > 8) {
      clearTimeout(longPress);
      if (scrollTouch) scrollTouch.moved = true;
    }
    if (scrollTouch?.moved) {
      e.preventDefault(); e.stopImmediatePropagation();
      const now = performance.now(), delta = scrollTouch.y - touch.clientY;
      const velocity = Math.max(-10, Math.min(10,delta / Math.max(1,now - scrollTouch.time)));
      scrollTouch.velocity = scrollTouch.velocity * 0.35 + velocity * 0.65;
      scrollTouch.y = touch.clientY; scrollTouch.time = now;
      current.scrolling = true;
      scrollPixels += delta;
      if (!dragFrame) dragFrame = requestAnimationFrame(flushDrag);
    }
  }, {capture:true,passive:false});
  container.addEventListener('touchend', e => {
    clearTimeout(longPress); touchOrigin = null; hideMagnifier();
    if (selecting) { e.preventDefault(); e.stopImmediatePropagation(); ignoreClickUntil = Date.now() + 600; }
    else if (scrollTouch?.moved) {
      e.preventDefault(); e.stopImmediatePropagation(); ignoreClickUntil = Date.now() + 400;
      finishScroll(performance.now() - scrollTouch.time < 100 ? scrollTouch.velocity : 0);
    } else stopScroll();
  }, {capture:true,passive:false});
  container.addEventListener('touchcancel', () => { clearTimeout(longPress); touchOrigin = null; hideMagnifier(); stopScroll(); });
  container.addEventListener('click', e => {
    if (selecting || Date.now() < ignoreClickUntil) { e.preventDefault(); e.stopImmediatePropagation(); }
  }, true);
  container.addEventListener('contextmenu', e => e.preventDefault());
  function create(id) {
    const element = document.createElement('div'); element.className = 'terminal-session';
    container.appendChild(element);
    const term = new Terminal({
      fontFamily:'monospace',fontSize,scrollback:2000,cursorBlink:true,
      allowProposedApi:false,allowTransparency:false,convertEol:false,
      theme:{background:'#121826',foreground:'#e5eaf3',cursor:'#7ba3ff',selectionBackground:'#35558c'},
      linkHandler:{activate:() => {}}
    });
    const fit = new FitAddon.FitAddon(); term.loadAddon(fit); term.open(element);
    const entry = {id,element,term,fit,generation:-1,sequence:0,awaiting:false,pending:null,ctrl:false,running:true,scrolling:false};
    term.onData(data => {
      if (current !== entry || !entry.running || selecting) return;
      if (entry.ctrl && /^[a-z@\[\]\\^_]$/i.test(data)) data = String.fromCharCode(data.toUpperCase().charCodeAt(0)&31);
      if (entry.ctrl) { entry.ctrl=false; post({type:'ctrlReleased',id}); }
      post({type:'input',id,data});
    });
    term.onSelectionChange(() => { if (entry === current) selectionUi(); });
    term.onScroll(() => { if (entry === current) { updateLatest(); if (selecting) selectionUi(); } });
    term.onRender(() => {
      if (entry === current && scrollView) { scrollView.rendered = term.buffer.active.viewportY; offsetScreen(); }
    });
    entries.set(id,entry); return entry;
  }
  function resize() {
    hideMagnifier();
    stopScroll();
    container.style.width = Math.max(20,innerWidth - 12) + 'px';
    container.style.height = Math.max(20,innerHeight - 16) + 'px';
    if (!current) return;
    current.fit.fit();
    post({type:'resize',id:current.id,columns:current.term.cols,rows:current.term.rows}); selectionUi();
  }
  new ResizeObserver(() => requestAnimationFrame(resize)).observe(container);
  window.addEventListener('resize', resize);
  function poll() {
    const entry = current;
    if (entry && !entry.awaiting && !selecting && !entry.scrolling && active && !document.hidden) {
      entry.awaiting = true;
      post({type:'poll',id:entry.id,generation:entry.generation,sequence:entry.sequence});
    }
  }
  function receive(batch) {
    const entry = entries.get(batch.id);
    if (!entry) return;
    if (entry === current && (selecting || entry.scrolling)) { entry.pending = batch; return; }
    if (batch.reset || batch.generation !== entry.generation) entry.term.reset();
    entry.generation = batch.generation; entry.sequence = batch.sequence;
    const raw = atob(batch.data), bytes = Uint8Array.from(raw,c => c.charCodeAt(0));
    const viewport = entry.term.buffer.active.viewportY, stay = viewport < entry.term.buffer.active.baseY;
    const done = () => {
      if (stay) entry.term.scrollToLine(viewport);
      entry.awaiting = false; updateLatest(); setTimeout(poll,bytes.length ? 40 : 160);
    };
    if (bytes.length) entry.term.write(bytes,done); else done();
  }
  function copy(selectionOnly = false) {
    if (!current) return;
    let text = current.term.getSelection();
    if (!text && !selectionOnly) {
      const buffer = current.term.buffer.active, lines = [];
      for(let i = 0;i < buffer.length;i++) lines.push(buffer.getLine(i)?.translateToString(true) || '');
      text = lines.join('\n').trimEnd();
    }
    if (text) post({type:'copy',id:current.id,data:text.slice(-524288)});
  }
  window.TerminalApp = {
    sessions(rows,id) {
      const ids = new Set(rows.map(row => row.id));
      if (current?.id !== id) { stopScroll(); clearSelection(); if (current) { current.ctrl = false; current.element.style.display = 'none'; current.term.options.disableStdin = true; } }
      for (const [key,entry] of entries) if (!ids.has(key)) { entry.term.dispose(); entry.element.remove(); entries.delete(key); }
      current = ids.has(id) ? entries.get(id) || create(id) : null;
      for (const row of rows) { const entry = entries.get(row.id); if (entry) entry.running = row.running; }
      if (current) { current.element.style.display = 'block'; current.term.options.disableStdin = !current.running; }
      requestAnimationFrame(resize); poll();
    },
    receive, copy,
    selection(action) {
      if (action === 'copy') { copy(true); clearSelection(); }
      else if (action === 'all') current?.term.selectAll();
      else clearSelection();
    },
    labels(value) { labels = value; updateLabels(); },
    focus() { clearSelection(); if (current?.running) current.term.focus(); },
    active(value) { active = value; if(value) poll(); else hideMagnifier(); },
    paste(text) { if (current?.running) { clearSelection(); current.term.paste(text); current.term.focus(); } },
    ctrl(enabled) { if (current) { current.ctrl = enabled; current.term.focus(); } },
    key(data) { if (current?.running) { post({type:'input',id:current.id,data}); current.term.focus(); } },
    font(size) { fontSize = size; for (const entry of entries.values()) entry.term.options.fontSize = size; requestAnimationFrame(resize); }
  };
  document.addEventListener('visibilitychange', () => { if (!document.hidden) poll(); else hideMagnifier(); });
  updateLabels(); post({type:'ready'});
})();
