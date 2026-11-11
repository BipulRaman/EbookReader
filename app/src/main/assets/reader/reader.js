/*
 * Reader page script: injected into every chapter the reader shows (see BookServer.inject).
 *
 * Pagination: the body becomes a CSS multi-column box exactly one screen tall; each column (two in
 * landscape on wide screens) is one page and the body slides sideways by one screen per page
 * (a GPU transform, so turning pages is instant and smooth).
 *
 * Positions are character offsets into the chapter's text (all text nodes under <body>, in
 * order). They don't depend on font, size or screen, so reading positions, bookmarks and
 * highlights survive any layout change.
 *
 * Talks to the app through the RDNative bridge (ReaderBridge.kt); every message carries the
 * load's generation id so the app can ignore a page that's being replaced.
 */
(function () {
  'use strict';
  if (window.rd) return;

  var N = window.RDNative;
  var P = new URLSearchParams(location.search);
  var G = P.get('g') || '0';
  var hasPrev = P.get('prev') === '1';
  var hasNext = P.get('next') === '1';
  var animate = P.get('anim') !== '0';
  var DURATION = 260;
  var de = document.documentElement;

  var body = null;
  var W = 1, H = 1, M = 20, count = 1, cur = 0, ready = false, pinEnd = false;
  var nodes = [], starts = [], nodeIndex = new Map(), total = 0, pageStart = [];

  function send(name) {
    if (!N || typeof N[name] !== 'function') return undefined;
    var args = [G];
    for (var i = 1; i < arguments.length; i++) args.push(arguments[i]);
    try { return N[name].apply(N, args); } catch (e) { return undefined; }
  }

  // ---------------------------------------------------------------- text index

  function index() {
    nodes = []; starts = []; nodeIndex = new Map(); pageStart = [];
    var off = 0;
    var w = document.createTreeWalker(body, NodeFilter.SHOW_TEXT, null);
    for (var n = w.nextNode(); n; n = w.nextNode()) {
      var l = n.data.length;
      if (!l) continue;
      nodeIndex.set(n, nodes.length);
      nodes.push(n);
      starts.push(off);
      off += l;
    }
    total = off;
  }

  function nodeAt(off) {
    var lo = 0, hi = starts.length - 1;
    while (lo < hi) {
      var mid = (lo + hi + 1) >> 1;
      if (starts[mid] <= off) lo = mid; else hi = mid - 1;
    }
    return lo;
  }

  // ---------------------------------------------------------------- geometry

  function originX() { return body.getBoundingClientRect().left; }

  function pageX(x, ox) { return Math.max(0, Math.floor((x - ox + 0.5) / W)); }

  function charRect(n, i) {
    var r = document.createRange();
    r.setStart(n, i);
    r.setEnd(n, i + 1);
    var rs = r.getClientRects();
    for (var k = 0; k < rs.length; k++) if (rs[k].width > 0 || rs[k].height > 0) return rs[k];
    return null;
  }

  function nodeRects(n) {
    var r = document.createRange();
    r.selectNodeContents(n);
    return r.getClientRects();
  }

  /** Page of the first (or last) rendered character of text node k, or -1 if it isn't rendered. */
  function nodePage(k, last, ox) {
    var n = nodes[k];
    var rs = nodeRects(n);
    if (!rs.length) return -1;
    var L = n.data.length, i, rc;
    if (last) {
      for (i = L - 1; i >= 0 && i >= L - 48; i--) { rc = charRect(n, i); if (rc) return pageX(rc.left, ox); }
      return pageX(rs[rs.length - 1].left, ox);
    }
    for (i = 0; i < L && i < 48; i++) { rc = charRect(n, i); if (rc) return pageX(rc.left, ox); }
    return pageX(rs[0].left, ox);
  }

  /** Offset of the first character shown on page p. */
  function pageStartOffset(p) {
    if (p <= 0 || !nodes.length) return 0;
    if (p >= count) return total;
    if (pageStart[p] !== undefined) return pageStart[p];
    var ox = originX();
    var lo = 0, hi = nodes.length - 1, found = -1;
    while (lo <= hi) {
      var mid = (lo + hi) >> 1, m = mid, pg = -1;
      while (m <= hi && (pg = nodePage(m, true, ox)) < 0) m++;
      if (pg < 0) { hi = mid - 1; continue; }
      if (pg >= p) { found = m; hi = mid - 1; } else { lo = m + 1; }
    }
    var res = total;
    if (found >= 0) {
      var n = nodes[found], a = 0, b = n.data.length - 1, first = n.data.length - 1;
      while (a <= b) {
        var c = (a + b) >> 1, cc = c, rc = null;
        while (cc <= b && !(rc = charRect(n, cc))) cc++;
        if (!rc) { b = c - 1; continue; }
        if (pageX(rc.left, ox) >= p) { first = cc; b = c - 1; } else { a = cc + 1; }
      }
      res = starts[found] + first;
    }
    pageStart[p] = res;
    return res;
  }

  /** Page showing the character at offset off (or the next rendered one). */
  function pageOfOffset(off) {
    if (off <= 0 || !nodes.length) return 0;
    if (off >= total) return count - 1;
    var ox = originX();
    var k = nodeAt(off), i = off - starts[k], steps = 0;
    for (; k < nodes.length && steps < 5000; k++, i = 0) {
      var n = nodes[k];
      if (!nodeRects(n).length) continue;
      for (; i < n.data.length && steps < 5000; i++, steps++) {
        var rc = charRect(n, i);
        if (rc) return Math.min(count - 1, pageX(rc.left, ox));
      }
    }
    return count - 1;
  }

  function pageOfElement(el) {
    if (!el) return 0;
    var rs = el.getClientRects();
    var r = rs.length ? rs[0] : el.getBoundingClientRect();
    return Math.min(count - 1, pageX(r.left, originX()));
  }

  // ---------------------------------------------------------------- layout

  function layout() {
    W = Math.max(1, de.clientWidth);
    H = Math.max(1, de.clientHeight);
    var cs = getComputedStyle(de);
    M = parseFloat(cs.getPropertyValue('--rd-margin')) || 20;
    var V = parseFloat(cs.getPropertyValue('--rd-vmargin')) || 0;
    var cols = (cs.getPropertyValue('--rd-two').trim() === '1' && W > H && W >= 720) ? 2 : 1;
    if (M * 2 > W / cols / 3) M = Math.floor(W / cols / 6);
    var s = body.style;
    s.setProperty('width', W + 'px', 'important');
    s.setProperty('height', H + 'px', 'important');
    s.setProperty('padding', V + 'px ' + M + 'px', 'important');
    s.setProperty('column-width', (W / cols - 2 * M) + 'px', 'important');
    s.setProperty('column-gap', (2 * M) + 'px', 'important');
    s.setProperty('column-count', 'auto', 'important');
    s.setProperty('will-change', 'transform');
    pageStart = [];

    // The last page is the one holding the last rendered text or media.
    count = 1;
    var ox = originX(), last = 0;
    for (var k = nodes.length - 1; k >= 0; k--) {
      var p = nodePage(k, true, ox);
      if (p >= 0) { last = p; break; }
    }
    var media = body.querySelectorAll('img, svg, video, table, hr');
    for (var j = media.length - 1; j >= 0 && j >= media.length - 3; j--) {
      var r = media[j].getBoundingClientRect();
      if (r.width || r.height) last = Math.max(last, pageX(r.left, ox));
    }
    count = last + 1;
  }

  /** Marks elements the book centres or right-aligns, so the alignment setting leaves them be. */
  function markAlign() {
    var link = document.getElementById('rd-style');
    if (link) link.disabled = true;
    var rtl = getComputedStyle(body).direction === 'rtl';
    var els = body.querySelectorAll('p, div, li, blockquote');
    for (var i = 0; i < els.length; i++) {
      var ta = getComputedStyle(els[i]).textAlign;
      if (ta === 'center' || ta === '-webkit-center' || (ta === 'right' && !rtl) || (ta === 'left' && rtl) || ta === 'end') {
        els[i].classList.add('rd-keep');
      }
    }
    if (link) link.disabled = false;
  }

  // ---------------------------------------------------------------- paging

  function translate(px, animated) {
    body.style.transition = animated ? 'transform ' + DURATION + 'ms cubic-bezier(.22,.8,.3,1)' : 'none';
    body.style.transform = 'translate3d(' + px + 'px,0,0)';
  }

  function state() {
    var s = pageStartOffset(cur);
    var e = cur + 1 < count ? pageStartOffset(cur + 1) : total;
    return JSON.stringify({ page: cur, count: count, start: s, end: e, total: total });
  }

  // Logical reading position: the offset the reader navigated to. Re-layouts (late fonts,
  // images, resizes) return to it rather than to the start of whatever page is now shown,
  // so the position never drifts.
  var anchor = -1;

  function show(p, animated, off) {
    cur = Math.max(0, Math.min(count - 1, p));
    pinEnd = pinEnd && cur === count - 1;
    anchor = off >= 0 ? off : -1;
    translate(-cur * W, animated && animate);
    send('page', state());
  }

  function next() {
    clearFound();
    pinEnd = false;
    if (cur < count - 1) show(cur + 1, true);
    else { translate(-cur * W, false); send('edge', 1, hasNext ? 1 : 0); }
  }

  function prev() {
    clearFound();
    pinEnd = false;
    if (cur > 0) show(cur - 1, true);
    else { translate(-cur * W, false); send('edge', -1, hasPrev ? 1 : 0); }
  }

  function keepOffset() { return anchor >= 0 ? anchor : pageStartOffset(cur); }

  function relayout() {
    if (!ready) return;
    var keep = keepOffset();
    layout();
    show(pinEnd ? count - 1 : pageOfOffset(keep), false, keep);
  }

  // ---------------------------------------------------------------- highlights

  var SVG_NS = 'http://www.w3.org/2000/svg';

  function wrap(s, e, cls, id) {
    if (e <= s || !nodes.length) return null;
    var targets = [];
    for (var k = nodeAt(s); k < nodes.length && starts[k] < e; k++) {
      var n = nodes[k], ns = starts[k];
      var a = Math.max(s, ns) - ns, b = Math.min(e, ns + n.data.length) - ns;
      if (b > a && /\S/.test(n.data.slice(a, b)) && n.parentNode && n.parentNode.namespaceURI !== SVG_NS) targets.push([n, a, b]);
    }
    var first = null;
    targets.forEach(function (t) {
      var mid = t[0];
      if (t[1] > 0) mid = mid.splitText(t[1]);
      if (t[2] - t[1] < mid.data.length) mid.splitText(t[2] - t[1]);
      var span = document.createElement('span');
      span.className = cls;
      if (id) span.setAttribute('data-id', id);
      mid.parentNode.insertBefore(span, mid);
      span.appendChild(mid);
      if (!first) first = span;
    });
    index();
    return first;
  }

  function unwrap(selector) {
    var spans = body.querySelectorAll(selector);
    if (!spans.length) return;
    for (var i = 0; i < spans.length; i++) {
      var sp = spans[i], parent = sp.parentNode;
      while (sp.firstChild) parent.insertBefore(sp.firstChild, sp);
      parent.removeChild(sp);
      parent.normalize();
    }
    index();
  }

  function addHighlight(h) {
    wrap(h.s, h.e, 'rd-hl rd-hl-' + (h.c | 0) + (h.n ? ' rd-note' : ''), String(h.id));
  }

  function clearFound() { if (body && body.querySelector('.rd-found')) unwrap('.rd-found'); }

  // ---------------------------------------------------------------- selection

  function hasSelection() {
    var s = window.getSelection();
    return !!(s && !s.isCollapsed && s.toString().length);
  }

  function walkerAt(node) {
    var w = document.createTreeWalker(body, NodeFilter.SHOW_TEXT, null);
    w.currentNode = node;
    return w;
  }

  function textFrom(node) {
    if (node.nodeType === 3 && nodeIndex.has(node)) return starts[nodeIndex.get(node)];
    var w = walkerAt(node), n = w.nextNode();
    while (n && !nodeIndex.has(n)) n = w.nextNode();
    return n ? starts[nodeIndex.get(n)] : total;
  }

  function textAfter(c) {
    var last = c;
    while (last.lastChild) last = last.lastChild;
    if (last.nodeType === 3 && nodeIndex.has(last)) return starts[nodeIndex.get(last)] + last.data.length;
    var w = walkerAt(last), n = w.nextNode();
    while (n && !nodeIndex.has(n)) n = w.nextNode();
    return n ? starts[nodeIndex.get(n)] : total;
  }

  function toOffset(c, o) {
    if (c.nodeType === 3) return nodeIndex.has(c) ? starts[nodeIndex.get(c)] + o : textFrom(c);
    var child = c.childNodes[o];
    return child ? textFrom(child) : textAfter(c);
  }

  function selection() {
    var sel = window.getSelection();
    if (!sel || !sel.rangeCount || sel.isCollapsed) return '';
    var r = sel.getRangeAt(0);
    var s = toOffset(r.startContainer, r.startOffset), e = toOffset(r.endContainer, r.endOffset);
    if (e <= s) return '';
    return JSON.stringify({ s: s, e: e, text: sel.toString() });
  }

  function textAt(off, len) {
    if (!nodes.length) return '';
    var out = '', k = nodeAt(Math.max(0, off)), i = Math.max(0, off - starts[k]);
    for (; k < nodes.length && out.length < len * 2; k++, i = 0) out += nodes[k].data.slice(i);
    return out.replace(/[\s\u00A0]+/g, ' ').trim().slice(0, len);
  }

  // ---------------------------------------------------------------- search

  /** The n-th (0-based) case-insensitive match of q, with whitespace collapsed like the app's search. */
  function findNth(q, n) {
    var norm = function (str, map, base) {
      var out = [], space = false;
      for (var i = 0; i < str.length; i++) {
        var c = str[i];
        if (c === '\u00AD') continue;
        if (/[\s\u00A0]/.test(c)) {
          if (!space) { out.push(' '); if (map) map.push(base + i); }
          space = true;
        } else {
          var lc = c.toLowerCase();
          for (var j = 0; j < lc.length; j++) { out.push(lc[j]); if (map) map.push(base + i); }
          space = false;
        }
      }
      return out.join('');
    };
    var needle = norm(q, null, 0).trim();
    if (!needle) return null;
    var map = [], parts = [];
    for (var k = 0; k < nodes.length; k++) parts.push(norm(nodes[k].data, map, starts[k]));
    // Whitespace collapses across node boundaries too.
    var hay = '', hmap = [], prevSpace = false, m = 0;
    var joined = parts.join('');
    for (var x = 0; x < joined.length; x++, m++) {
      var ch = joined[x];
      if (ch === ' ') { if (prevSpace) continue; prevSpace = true; } else prevSpace = false;
      hay += ch;
      hmap.push(map[m]);
    }
    var from = 0, idx = -1;
    for (var count_ = 0; count_ <= n; count_++) {
      idx = hay.indexOf(needle, from);
      if (idx < 0) return null;
      from = idx + needle.length;
    }
    return { s: hmap[idx], e: hmap[idx + needle.length - 1] + 1 };
  }

  function showFound(q, n) {
    clearFound();
    var hit = findNth(q, n);
    if (!hit) return -1;
    var span = wrap(hit.s, hit.e, 'rd-found', 'found');
    return span ? pageOfElement(span) : pageOfOffset(hit.s);
  }

  // ---------------------------------------------------------------- links and footnotes

  var OPS_NS = 'http://www.idpf.org/2007/ops';

  function byId(id) {
    if (!id) return null;
    var el = document.getElementById(id);
    if (el) return el;
    try { return document.querySelector('[name="' + id.replace(/["\\]/g, '\\$&') + '"]'); } catch (e) { return null; }
  }

  function epubType(el) {
    return ((el.getAttributeNS && el.getAttributeNS(OPS_NS, 'type')) || el.getAttribute('epub:type') || '') + ' ' + (el.getAttribute('role') || '');
  }

  function isNoteRef(a, target) {
    if (/noteref/.test(epubType(a))) return true;
    if (/^h[1-6]$/i.test(target.localName)) return false;
    if (/(foot|end|rear)?note/.test(epubType(target)) || (target.closest && target.closest('aside'))) return true;
    var t = (a.textContent || '').trim();
    return t.length > 0 && t.length <= 4 && (/^[\d\*\u2020\u2021\u00A7\[\]\(\)ivx]+$/i.test(t) || !!a.closest('sup'));
  }

  function noteText(target) {
    var el = target;
    if (/^(a|span|sup)$/i.test(el.localName) || !(el.textContent || '').trim()) {
      el = el.closest('aside, li, p, dd, div, section') || el;
    }
    var t = (el.textContent || '').replace(/[\s\u00A0]+/g, ' ').trim();
    return t ? t.slice(0, 4000) : null;
  }

  document.addEventListener('click', function (e) {
    var a = e.target && e.target.closest ? e.target.closest('a') : null;
    if (!a) return;
    var raw = a.getAttribute('href') || a.getAttributeNS('http://www.w3.org/1999/xlink', 'href');
    if (!raw) return;
    e.preventDefault();
    var u;
    try { u = new URL(raw, location.href); } catch (err) { return; }
    var internal = u.origin === location.origin;
    var anchor = u.hash ? decodeURIComponent(u.hash.slice(1)) : null;
    var note = null;
    if (internal && u.pathname === location.pathname && anchor) {
      var target = byId(anchor);
      if (target && isNoteRef(a, target)) note = noteText(target);
    }
    send('link', JSON.stringify({
      href: u.href,
      internal: internal,
      path: internal ? decodeURIComponent(u.pathname) : null,
      anchor: anchor,
      note: note,
    }));
  }, true);

  // ---------------------------------------------------------------- touch

  var t0 = null, dragging = false, bright = false, moved = false, dx = 0, lastTap = 0, tapTimer = null;

  function imageSrc(el) {
    if (el.localName === 'img') return el.currentSrc || el.src;
    var img = el.localName === 'image' ? el : el.querySelector('image');
    if (!img) return null;
    var href = img.getAttributeNS('http://www.w3.org/1999/xlink', 'href') || img.getAttribute('href');
    return href ? new URL(href, location.href).href : null;
  }

  function zoneTap(x, y) {
    if (x > W * 0.84 && y < H * 0.09) send('tap', 'corner');
    else if (x < W * 0.28) prev();
    else if (x > W * 0.72) next();
    else send('tap', 'menu');
  }

  function tap(t, target, hadSelection) {
    if (hadSelection || hasSelection()) { window.getSelection().removeAllRanges(); return; }
    var el = target && target.nodeType === 1 ? target : (target && target.parentElement);
    if (el && el.closest && el.closest('a')) return; // the click handler takes it
    var hl = el && el.closest && el.closest('.rd-hl');
    if (hl) { send('highlight', hl.getAttribute('data-id')); return; }
    var x = t.clientX, y = t.clientY;
    var pic = el && el.closest && el.closest('img, svg');
    if (pic) {
      var now = Date.now();
      if (tapTimer && now - lastTap < 320) {
        clearTimeout(tapTimer);
        tapTimer = null;
        var src = imageSrc(pic);
        if (src) send('image', src);
        return;
      }
      lastTap = now;
      tapTimer = setTimeout(function () { tapTimer = null; zoneTap(x, y); }, 320);
      return;
    }
    zoneTap(x, y);
  }

  document.addEventListener('touchstart', function (e) {
    if (!ready || e.touches.length !== 1) { t0 = null; return; }
    var t = e.touches[0];
    t0 = { x: t.clientX, y: t.clientY, t: Date.now(), sel: hasSelection() };
    dragging = bright = moved = false;
    dx = 0;
  }, { passive: true });

  document.addEventListener('touchmove', function (e) {
    if (!t0) return;
    if (e.touches.length !== 1) { if (dragging) translate(-cur * W, true); t0 = null; dragging = bright = false; return; }
    var t = e.touches[0], mx = t.clientX - t0.x, my = t.clientY - t0.y;
    if (!dragging && !bright) {
      if (Math.abs(mx) < 10 && Math.abs(my) < 10) return;
      moved = true;
      // A long press selects text; dragging after it extends the selection, not the page.
      if (t0.sel || hasSelection() || Date.now() - t0.t > 450) { t0 = null; return; }
      if (Math.abs(mx) > Math.abs(my)) {
        dragging = true;
      } else if (t0.x < W * 0.14) {
        bright = true;
        send('brightness', 0, 0);
      } else { t0 = null; return; }
    }
    if (e.cancelable) e.preventDefault();
    if (dragging) {
      dx = mx;
      if ((cur === 0 && dx > 0 && !hasPrev) || (cur === count - 1 && dx < 0 && !hasNext)) dx *= 0.25;
      else if ((cur === 0 && dx > 0) || (cur === count - 1 && dx < 0)) dx *= 0.5;
      translate(-cur * W + dx, false);
    } else if (bright) {
      send('brightness', 1, -my / H);
    }
  }, { passive: false });

  document.addEventListener('touchend', function (e) {
    if (!t0) return;
    var st = t0;
    t0 = null;
    var t = e.changedTouches[0];
    if (dragging) {
      dragging = false;
      var raw = t.clientX - st.x, v = raw / Math.max(1, Date.now() - st.t);
      if (raw < -W * 0.2 || (raw < -24 && v < -0.3)) next();
      else if (raw > W * 0.2 || (raw > 24 && v > 0.3)) prev();
      else translate(-cur * W, true);
      return;
    }
    if (bright) { bright = false; send('brightness', 2, 0); return; }
    if (moved || Date.now() - st.t > 450) return;
    tap(t, e.target, st.sel);
  }, { passive: true });

  document.addEventListener('touchcancel', function () {
    if (dragging) translate(-cur * W, true);
    if (bright) send('brightness', 2, 0);
    t0 = null;
    dragging = bright = false;
  }, { passive: true });

  document.addEventListener('selectionchange', function () {
    if (dragging && hasSelection()) { dragging = false; t0 = null; translate(-cur * W, true); }
  });

  // The page never scrolls; selection handles or find-in-page may try to.
  window.addEventListener('scroll', function () { if (window.scrollX || window.scrollY) window.scrollTo(0, 0); });
  document.addEventListener('scroll', function (e) {
    var t = e.target;
    if (t && t !== document && t.scrollLeft) t.scrollLeft = 0;
  }, true);

  var resizeTimer = null;
  window.addEventListener('resize', function () {
    clearTimeout(resizeTimer);
    resizeTimer = setTimeout(relayout, 120);
  });

  // ---------------------------------------------------------------- start

  var started = false;

  function start() {
    if (started) return;
    body = document.body;
    if (!body) return;
    started = true;
    markAlign();
    index();
    try {
      var hl = JSON.parse(send('highlights') || '[]');
      for (var i = 0; i < hl.length; i++) addHighlight(hl[i]);
    } catch (e) { /* no highlights */ }
    layout();

    var at = P.get('at') || '', q = P.get('q'), target = 0;
    if (q) {
      target = showFound(q, parseInt(P.get('n') || '0', 10));
      if (target < 0) target = 0;
    } else if (at === 'end') {
      target = count - 1;
      // Arriving backwards from the next chapter: stay on the last page if fonts re-flow it.
      pinEnd = true;
    } else if (at.charAt(0) === 'o') {
      anchor = parseInt(at.slice(1), 10) || 0;
      target = pageOfOffset(anchor);
    } else if (at.charAt(0) === 'f') {
      target = Math.floor((parseFloat(at.slice(1)) || 0) * count);
    } else if (location.hash) {
      target = pageOfElement(byId(decodeURIComponent(location.hash.slice(1))));
    }
    cur = Math.max(0, Math.min(count - 1, target));
    translate(-cur * W, false);
    de.classList.add('rd-ready');
    ready = true;
    // Report once the first frame with the right page is on screen.
    requestAnimationFrame(function () { requestAnimationFrame(function () { send('ready', state()); }); });
  }

  function whenLoaded() {
    var go = function () {
      // fonts.ready only covers fonts already requested, and nothing is requested before the
      // first layout; force one so the reader's fonts start loading, then wait for them.
      if (document.body) void document.body.offsetHeight;
      if (document.fonts && document.fonts.ready) document.fonts.ready.then(start, start); else start();
    };
    if (document.readyState === 'complete') go(); else window.addEventListener('load', go);
    // Very large images can hold up "load"; don't keep the reader waiting on them.
    setTimeout(start, 4000);
  }
  if (document.fonts) document.fonts.addEventListener('loadingdone', function () { if (ready) relayout(); });
  window.addEventListener('load', function () { if (ready) relayout(); });

  whenLoaded();

  // ---------------------------------------------------------------- API for the app

  window.rd = {
    next: next,
    prev: prev,
    goPage: function (p) { show(p, false); },
    goOffset: function (o) { show(pageOfOffset(o), false, o); },
    goFraction: function (f) { show(Math.floor(f * count), false); },
    goAnchor: function (id) { var el = byId(id); if (el) show(pageOfElement(el), false); return !!el; },
    goFound: function (q, n) { var p = showFound(q, n); show(p < 0 ? cur : p, false); return p >= 0; },
    setStyle: function (version) {
      var old = document.getElementById('rd-style');
      if (!old || !ready) return;
      var keep = keepOffset();
      var link = old.cloneNode(false);
      old.id = 'rd-style-old';
      link.id = 'rd-style';
      link.setAttribute('href', '/reader/reader.css?v=' + version);
      var done = false;
      var apply = function () {
        if (done) return;
        done = true;
        if (old.parentNode) old.parentNode.removeChild(old);
        var fin = function () { layout(); show(pinEnd ? count - 1 : pageOfOffset(keep), false, keep); };
        if (document.fonts && document.fonts.ready) document.fonts.ready.then(fin, fin); else fin();
      };
      link.addEventListener('load', apply);
      setTimeout(apply, 1500);
      old.parentNode.insertBefore(link, old.nextSibling);
    },
    addHighlight: function (json) {
      addHighlight(JSON.parse(json));
      var s = window.getSelection();
      if (s) s.removeAllRanges();
    },
    removeHighlight: function (id) { unwrap('.rd-hl[data-id="' + id + '"]'); },
    updateHighlight: function (json) {
      var h = JSON.parse(json);
      var spans = body.querySelectorAll('.rd-hl[data-id="' + h.id + '"]');
      for (var i = 0; i < spans.length; i++) spans[i].className = 'rd-hl rd-hl-' + (h.c | 0) + (h.n ? ' rd-note' : '');
    },
    selection: selection,
    setAnimate: function (b) { animate = !!b; },
    clearSelection: function () { var s = window.getSelection(); if (s) s.removeAllRanges(); },
    textAt: textAt,
    state: state,
  };
})();
