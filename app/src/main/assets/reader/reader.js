/* BookShelf paginated reader. Lays a chapter out in CSS columns and pages horizontally. */
(function () {
  'use strict';
  var root = document.documentElement;
  root.classList.add('bs-loading');

  var A = window.BSAndroid || { onReady: function () {}, onPage: function () {}, onEdge: function () {}, onTap: function () {},
    onLink: function () {}, onFootnote: function () {}, onHighlightTap: function () {}, onImage: function () {}, log: function () {} };
  var BS = window.BS = {};
  var cfg = null, W = 0, pageCount = 1, page = 0, anim = null, ready = false;

  function el(tag, attrs) { var e = document.createElement(tag); for (var k in attrs) e.setAttribute(k, attrs[k]); return e; }

  // ---------- styling ----------
  function styleText(c) {
    var vw = window.innerWidth, vh = window.innerHeight;
    var spread = c.spread && vw > vh && vw >= 700;
    var M = Math.round(vw * [0.045, 0.07, 0.1][c.margin] / (spread ? 2 : 1)) + (spread ? 8 : 0);
    if (vw >= 900 && !spread) M = Math.max(M, Math.round((vw - 760) / 2)); // comfortable measure on tablets
    var top = c.top, bottom = c.bottom;
    var colW = spread ? (vw / 2 - 2 * M) : (vw - 2 * M);
    var f = c.font ? 'font-family:' + c.font + ' !important;' : '';
    var force = !c.publisher;
    var s = '';
    s += '@font-face{font-family:"BS Literata";src:url("/__bs/fonts/literata.ttf");font-weight:200 900;font-style:normal}';
    s += '@font-face{font-family:"BS Literata";src:url("/__bs/fonts/literata-italic.ttf");font-weight:200 900;font-style:italic}';
    s += '@font-face{font-family:"BS Inter";src:url("/__bs/fonts/inter.ttf");font-weight:100 900}';
    s += '@font-face{font-family:"BS Atkinson";src:url("/__bs/fonts/atkinson.ttf");font-weight:400}';
    s += '@font-face{font-family:"BS Atkinson";src:url("/__bs/fonts/atkinson-bold.ttf");font-weight:700}';
    s += 'html{height:' + vh + 'px !important;width:' + vw + 'px !important;max-width:none !important;margin:0 !important;' +
      'padding:' + top + 'px ' + M + 'px ' + bottom + 'px ' + M + 'px !important;box-sizing:border-box !important;' +
      'column-width:' + colW + 'px !important;column-gap:' + (2 * M) + 'px !important;column-fill:auto !important;' +
      'overflow:hidden !important;background:' + c.bg + ' !important;color:' + c.fg + ' !important;' +
      '-webkit-text-size-adjust:100% !important;-webkit-tap-highlight-color:transparent;}';
    s += 'body{margin:0 !important;padding:0 !important;max-width:none !important;width:auto !important;height:auto !important;' +
      'background:transparent !important;color:' + c.fg + ' !important;font-size:' + Math.round(c.scale * 100) + '% !important;' +
      'line-height:' + c.lineHeight + ' !important;' + f + 'overflow-wrap:break-word;}';
    if (force) {
      s += 'p,li,dd,dt,blockquote,div,span,td{line-height:inherit !important;' + (c.font ? 'font-family:inherit !important;' : '') + '}';
      s += 'p,li,dd,blockquote{color:inherit !important}';
      s += 'h1,h2,h3,h4,h5,h6{color:inherit !important;line-height:1.25 !important;' + (c.font ? 'font-family:inherit !important;' : '') + '}';
      s += '*{background-color:transparent !important}';
    }
    s += 'p{text-align:' + (c.justify ? 'justify' : 'start') + ' !important;' +
      (c.hyphenate ? '-webkit-hyphens:auto;hyphens:auto;' : '-webkit-hyphens:manual;hyphens:manual;') + 'orphans:2;widows:2}';
    s += 'img,svg,video,image{max-width:100% !important;max-height:' + (vh - top - bottom - 8) + 'px !important;' +
      'height:auto;object-fit:contain;break-inside:avoid;-webkit-column-break-inside:avoid}';
    s += 'svg{width:auto}';
    s += 'figure{break-inside:avoid;margin-left:0;margin-right:0}';
    s += 'h1,h2,h3{break-after:avoid;-webkit-column-break-after:avoid}';
    s += 'a{color:' + c.accent + ' !important;text-decoration:none}';
    s += 'table{max-width:100%;overflow:hidden}';
    s += 'pre{white-space:pre-wrap}';
    s += '::selection{background:' + c.accent + '55}';
    s += 'mark.bs-hl{color:inherit !important;border-radius:2px;padding:0 1px;cursor:pointer}';
    s += 'mark.bs-hl[data-c="gold"]{background:rgba(230,180,80,.38) !important}';
    s += 'mark.bs-hl[data-c="green"]{background:rgba(120,190,120,.35) !important}';
    s += 'mark.bs-hl[data-c="blue"]{background:rgba(100,160,230,.35) !important}';
    s += 'mark.bs-hl[data-c="rose"]{background:rgba(230,120,140,.35) !important}';
    s += 'mark.bs-hl[data-n="1"]{border-bottom:2px dotted ' + c.accent + '}';
    s += 'mark.bs-search{background:rgba(255,210,60,.65) !important;color:inherit !important}';
    s += '.bs-tts{background:' + c.accent + '30 !important;border-radius:3px;transition:background .2s}';
    return { css: s, spread: spread };
  }

  function applyStyle() {
    var st = document.getElementById('bs-style');
    if (!st) { st = el('style', { id: 'bs-style' }); (document.head || root).appendChild(st); }
    var r = styleText(cfg);
    // Drop book stylesheets when the reader wants full control
    var links = document.querySelectorAll('link[rel~="stylesheet"]:not([data-bs]), style:not(#bs-style)');
    for (var i = 0; i < links.length; i++) links[i].disabled = !cfg.publisher && cfg.dropStyles;
    st.textContent = r.css;
  }

  function measure() {
    W = window.innerWidth;
    root.style.setProperty('width', W + 'px', 'important');
    var sw = root.scrollWidth;
    pageCount = Math.max(1, Math.ceil((sw - 2) / W));
    // Make the scrollable width an exact multiple of the page so the last page lines up too
    root.style.setProperty('width', (pageCount * W) + 'px', 'important');
  }

  function scrollToX(x) { window.scrollTo(x, 0); }
  function stopAnim() { if (anim) { cancelAnimationFrame(anim); anim = null; } }

  function animateTo(p, fast) {
    stopAnim();
    p = Math.max(0, Math.min(pageCount - 1, p));
    var from = window.scrollX, to = p * W, t0 = null, dur = fast ? 160 : 280;
    page = p;
    if (Math.abs(to - from) < 1 || cfg.noAnim) { scrollToX(to); report(); return; }
    function step(ts) {
      if (t0 === null) t0 = ts;
      var k = Math.min(1, (ts - t0) / dur);
      var e = 1 - Math.pow(1 - k, 3);
      scrollToX(from + (to - from) * e);
      if (k < 1) anim = requestAnimationFrame(step); else { anim = null; report(); }
    }
    anim = requestAnimationFrame(step);
  }

  function goPage(p) { stopAnim(); page = Math.max(0, Math.min(pageCount - 1, p)); scrollToX(page * W); report(); }

  function report() { A.onPage(page, pageCount); }

  function pageOfRect(r) { return Math.floor((r.left + window.scrollX + 1) / W); }
  function pageOfNode(n) {
    if (!n) return 0;
    if (n.nodeType === 3) { var rg = document.createRange(); rg.selectNodeContents(n); var rs = rg.getClientRects(); if (rs.length) return pageOfRect(rs[0]); n = n.parentNode; }
    var rects = n.getClientRects ? n.getClientRects() : [];
    if (rects.length) return pageOfRect(rects[0]);
    return 0;
  }
  function pageOfRange(r) { var rs = r.getClientRects(); return rs.length ? pageOfRect(rs[0]) : pageOfNode(r.startContainer); }

  BS.next = function () { if (page < pageCount - 1) animateTo(page + 1); else A.onEdge(1); };
  BS.prev = function () { if (page > 0) animateTo(page - 1); else A.onEdge(-1); };
  BS.goFraction = function (f) { goPage(Math.round(f * pageCount)); };
  BS.page = function () { return page; };
  BS.goFragment = function (id) {
    var t = document.getElementById(id) || document.querySelector('[name="' + id + '"]');
    if (t) animateTo(pageOfNode(t));
  };
  BS.goOffset = function (o) { var r = rangeFromOffsets(o, o); if (r) goPage(pageOfRange(r)); };

  // ---------- text offsets (stable under highlight wrapping) ----------
  function textNodes() {
    var w = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT, null), out = [], n;
    while ((n = w.nextNode())) out.push(n);
    return out;
  }
  function offsetOf(container, off) {
    var r = document.createRange();
    r.setStart(document.body, 0);
    try { r.setEnd(container, off); } catch (e) { return 0; }
    return r.toString().length;
  }
  function rangeFromOffsets(s, e) {
    var nodes = textNodes(), pos = 0, r = document.createRange(), started = false;
    for (var i = 0; i < nodes.length; i++) {
      var len = nodes[i].data.length;
      if (!started && s <= pos + len) { r.setStart(nodes[i], Math.max(0, s - pos)); started = true; }
      if (started && e <= pos + len) { r.setEnd(nodes[i], Math.max(0, e - pos)); return r; }
      pos += len;
    }
    return started ? r : null;
  }
  function wrapRange(r, make) {
    var nodes = textNodes().filter(function (n) { return r.intersectsNode(n) && n.data.length; }), made = [];
    nodes.forEach(function (n) {
      var p = n.parentNode && n.parentNode.nodeName.toLowerCase();
      if (p === 'style' || p === 'script' || p === 'title') return;
      var s = n === r.startContainer ? r.startOffset : 0;
      var e = n === r.endContainer ? r.endOffset : n.data.length;
      if (e <= s) return;
      var mid = n;
      if (s > 0) mid = n.splitText(s);
      if (e - s < mid.data.length) mid.splitText(e - s);
      if (!mid.data.trim().length) return;
      var m = make(); mid.parentNode.insertBefore(m, mid); m.appendChild(mid); made.push(m);
    });
    return made;
  }
  function unwrap(sel) {
    var ms = document.querySelectorAll(sel);
    for (var i = 0; i < ms.length; i++) {
      var m = ms[i], p = m.parentNode;
      while (m.firstChild) p.insertBefore(m.firstChild, m);
      p.removeChild(m); p.normalize();
    }
  }

  BS.addHighlight = function (h) {
    var o; try { o = JSON.parse(h.range); } catch (e) { return; }
    var r = rangeFromOffsets(o.s, o.e); if (!r) return;
    wrapRange(r, function () { var m = el('mark', { 'class': 'bs-hl', 'data-id': h.id, 'data-c': h.color || 'gold', 'data-n': h.note ? '1' : '0' }); return m; });
  };
  BS.removeHighlight = function (id) { unwrap('mark.bs-hl[data-id="' + id + '"]'); };
  BS.setHighlights = function (list) { unwrap('mark.bs-hl'); (list || []).forEach(BS.addHighlight); };

  BS.selection = function () {
    var s = window.getSelection();
    if (!s || s.isCollapsed || !s.rangeCount) return null;
    var r = s.getRangeAt(0);
    var a = offsetOf(r.startContainer, r.startOffset), b = offsetOf(r.endContainer, r.endOffset);
    return JSON.stringify({ range: JSON.stringify({ s: a, e: b }), text: s.toString() });
  };
  BS.clearSelection = function () { var s = window.getSelection(); if (s) s.removeAllRanges(); };

  // ---------- search ----------
  BS.find = function (q, nth) {
    unwrap('mark.bs-search');
    if (!q) return false;
    var text = textNodes().map(function (n) { return n.data; }).join('');
    var lower = text.toLowerCase(), ql = q.toLowerCase(), i = -1, k = 0;
    while (k <= nth) { var j = lower.indexOf(ql, i + 1); if (j < 0) { if (i < 0) return false; break; } i = j; k++; }
    var r = rangeFromOffsets(i, i + q.length); if (!r) return false;
    var ms = wrapRange(r, function () { return el('mark', { 'class': 'bs-search' }); });
    goPage(ms.length ? pageOfNode(ms[0]) : pageOfRange(r));
    setTimeout(function () { unwrap('mark.bs-search'); }, 4000);
    return true;
  };

  // ---------- read aloud ----------
  var ttsEls = [];
  BS.ttsBlocks = function (fromStart) {
    var sel = 'p,h1,h2,h3,h4,h5,h6,li,blockquote,dd,dt,figcaption,pre,td';
    var all = Array.prototype.slice.call(document.body.querySelectorAll(sel)).filter(function (e) {
      return !e.querySelector(sel) && e.textContent.trim().length;
    });
    if (!all.length) all = Array.prototype.slice.call(document.body.querySelectorAll('div')).filter(function (e) { return !e.querySelector('div') && e.textContent.trim().length; });
    ttsEls = fromStart ? all : all.filter(function (e) { return pageOfNode(e) >= page; });
    return JSON.stringify(ttsEls.map(function (e, i) { return e.textContent.replace(/\s+/g, ' ').trim(); }));
  };
  BS.ttsMark = function (i) {
    var prev = document.querySelectorAll('.bs-tts'); for (var k = 0; k < prev.length; k++) prev[k].classList.remove('bs-tts');
    var e = ttsEls[i]; if (!e) return;
    e.classList.add('bs-tts');
    var p = pageOfNode(e);
    if (p !== page) animateTo(p);
  };
  BS.ttsClear = function () { var prev = document.querySelectorAll('.bs-tts'); for (var k = 0; k < prev.length; k++) prev[k].classList.remove('bs-tts'); };

  // ---------- anchor keeping on relayout ----------
  function anchor() {
    var cs = getComputedStyle(root);
    var x = (parseFloat(cs.paddingLeft) || 0) + 6, y = (parseFloat(cs.paddingTop) || 0) + 6;
    for (var k = 0; k < 6; k++) {
      var r = document.caretRangeFromPoint ? document.caretRangeFromPoint(x, y + k * 24) : null;
      if (r && r.startContainer.nodeType === 3 && document.body.contains(r.startContainer) && pageOfRange(r) === page)
        return offsetOf(r.startContainer, r.startOffset);
    }
    return null;
  }
  function restoreAnchor(a, frac) {
    if (a !== null) { var r = rangeFromOffsets(a, a); if (r) { var p = pageOfRange(r); goPage(p); return; } }
    goPage(Math.round(frac * pageCount));
  }

  BS.configure = function (c) {
    var a = ready ? anchor() : null, frac = pageCount ? page / pageCount : 0;
    cfg = c; applyStyle();
    requestAnimationFrame(function () { measure(); restoreAnchor(a, frac); });
  };

  function whenStable(fn) {
    var imgs = Array.prototype.slice.call(document.images).filter(function (i) { return !i.complete; });
    var waits = imgs.map(function (i) { return new Promise(function (res) { i.onload = i.onerror = res; }); });
    if (document.fonts && document.fonts.ready) waits.push(document.fonts.ready);
    var done = false;
    var finish = function () { if (!done) { done = true; requestAnimationFrame(function () { requestAnimationFrame(fn); }); } };
    Promise.all(waits).then(finish);
    setTimeout(finish, 1500);
  }

  BS.init = function (c, start, highlights) {
    cfg = c; applyStyle();
    BS.setHighlights(highlights);
    whenStable(function () {
      measure();
      if (start.type === 'last') goPage(pageCount - 1);
      else if (start.type === 'fraction') goPage(Math.min(pageCount - 1, Math.round(start.value * pageCount)));
      else if (start.type === 'fragment') {
        var t = document.getElementById(start.value) || document.querySelector('[name="' + start.value + '"]');
        goPage(t ? pageOfNode(t) : 0);
      } else if (start.type === 'offset') {
        var r = rangeFromOffsets(start.value, start.value); goPage(r ? pageOfRange(r) : 0);
      } else goPage(0);
      if (start.search) BS.find(start.search, start.nth || 0);
      root.classList.remove('bs-loading');
      ready = true;
      A.onReady(page, pageCount);
    });
  };

  var lastW = window.innerWidth, lastH = window.innerHeight;
  window.addEventListener('resize', function () {
    if (!ready || (window.innerWidth === lastW && window.innerHeight === lastH)) return;
    lastW = window.innerWidth; lastH = window.innerHeight;
    BS.configure(cfg);
  });

  // ---------- footnotes ----------
  var EPUB_NS = 'http://www.idpf.org/2007/ops';
  function typeOf(e) {
    if (!e || !e.getAttribute) return '';
    return ((e.getAttribute('epub:type') || '') + ' ' + ((e.getAttributeNS && e.getAttributeNS(EPUB_NS, 'type')) || '') + ' ' + (e.getAttribute('role') || '')).toLowerCase();
  }
  function isNoteRef(a) {
    if (/noteref/.test(typeOf(a))) return true;
    var t = a.textContent.trim();
    if (!t || t.length > 5) return false;
    return !!a.closest('sup') || /^[\[(]?([0-9]{1,3}|[*†‡§¶]|[ivx]{1,4}|[a-z])[\])]?$/i.test(t);
  }
  function noteFrom(doc, id) {
    var el = doc.getElementById(id) || doc.querySelector('[name="' + id + '"]');
    if (!el) return null;
    var blk = el;
    if (!/footnote|endnote|rearnote|note/.test(typeOf(blk)) && blk.textContent.trim().length < 3) blk = blk.parentElement || blk;
    var host = blk.closest ? (blk.closest('aside,li,dd,p,section,div') || blk) : blk;
    if (host.textContent.trim().length > 3000 && blk !== host) host = blk;
    var txt = host.textContent.replace(/\s+/g, ' ').trim();
    return txt.length ? txt.slice(0, 3000) : null;
  }
  function tryFootnote(a) {
    var url; try { url = new URL(a.href); } catch (e) { return false; }
    if (url.host !== location.host || !url.hash || !isNoteRef(a)) return false;
    var id = decodeURIComponent(url.hash.slice(1));
    if (url.pathname === location.pathname) {
      var t = noteFrom(document, id);
      if (!t) return false;
      A.onFootnote(t, a.href); return true;
    }
    fetch(url.href.split('#')[0]).then(function (r) { return r.text(); }).then(function (html) {
      var doc = new DOMParser().parseFromString(html, 'text/html');
      var t = noteFrom(doc, id);
      if (t) A.onFootnote(t, a.href); else A.onLink(a.href);
    }).catch(function () { A.onLink(a.href); });
    return true;
  }

  // ---------- touch: swipe to turn ----------
  var sx = 0, sy = 0, st = 0, dragging = false, decided = false, baseX = 0, moved = false;
  function hasSel() { var s = window.getSelection(); return s && !s.isCollapsed; }

  document.addEventListener('touchstart', function (e) {
    if (e.touches.length > 1) { decided = true; dragging = false; return; }
    var t = e.touches[0]; sx = t.clientX; sy = t.clientY; st = Date.now();
    dragging = false; decided = false; moved = false; stopAnim(); baseX = page * W;
  }, { passive: true });

  document.addEventListener('touchmove', function (e) {
    if (e.touches.length > 1) return;
    var t = e.touches[0], dx = t.clientX - sx, dy = t.clientY - sy;
    if (Math.abs(dx) > 6 || Math.abs(dy) > 6) moved = true;
    if (!decided) {
      if (hasSel()) { decided = true; return; }
      if (Math.abs(dx) > 8 && Math.abs(dx) > Math.abs(dy) * 1.1) { dragging = true; decided = true; }
      else if (Math.abs(dy) > 12) decided = true;
    }
    if (dragging) {
      e.preventDefault();
      var x = baseX - dx, max = (pageCount - 1) * W;
      if (x < 0) x = -Math.pow(-x, 0.75) * 0; // edges don't overscroll
      if (x > max) x = max;
      scrollToX(x);
    }
  }, { passive: false });

  document.addEventListener('touchend', function (e) {
    if (!dragging) return;
    var t = e.changedTouches[0], dx = t.clientX - sx, dt = Math.max(1, Date.now() - st), v = dx / dt;
    dragging = false;
    var thr = W * 0.16;
    if (dx < -thr || (v < -0.3 && dx < -24)) { if (page < pageCount - 1) animateTo(page + 1, true); else { animateTo(page, true); A.onEdge(1); } }
    else if (dx > thr || (v > 0.3 && dx > 24)) { if (page > 0) animateTo(page - 1, true); else { animateTo(page, true); A.onEdge(-1); } }
    else animateTo(page, true);
  }, { passive: true });

  document.addEventListener('click', function (e) {
    if (moved) return;
    var a = e.target.closest ? e.target.closest('a[href]') : null;
    if (a) {
      e.preventDefault();
      if (!tryFootnote(a)) A.onLink(a.href);
      return;
    }
    var m = e.target.closest ? e.target.closest('mark.bs-hl') : null;
    if (m) { e.preventDefault(); A.onHighlightTap(m.getAttribute('data-id')); return; }
    if (hasSel()) return;
    var img = e.target.closest ? e.target.closest('img') : null;
    var x = e.clientX;
    if (cfg && cfg.tapZones && x < W * 0.28) { BS.prev(); return; }
    if (cfg && cfg.tapZones && x > W * 0.72) { BS.next(); return; }
    if (img && img.naturalWidth > 200) { A.onImage(img.src); return; }
    A.onTap();
  }, true);

  window.addEventListener('scroll', function () {
    // Snap back if something (like a find or focus) scrolled us between pages
    if (!dragging && !anim && ready && Math.abs(window.scrollX - page * W) > 2) {
      var p = Math.round(window.scrollX / W); if (p !== page) { page = Math.max(0, Math.min(pageCount - 1, p)); report(); }
      scrollToX(page * W);
    }
  }, { passive: true });
})();
