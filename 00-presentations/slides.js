/* ═══════════════════════════════════════════════════════════════
   GCP MASTERCLASS — Slide Navigation Engine
   Clean crossfade transition
   ═══════════════════════════════════════════════════════════════ */
(function () {
  'use strict';

  const slides = document.querySelectorAll('.slide');
  const total = slides.length;
  let current = 0;
  let locked = false; // prevent rapid clicks during transition

  // ── Auto-fill slide numbers ──
  slides.forEach((s, i) => {
    const snum = s.querySelector('.snum');
    if (snum) snum.textContent = (i + 1) + ' / ' + total;
  });

  // ── Core: show slide ──
  function showSlide(n) {
    if (n === current || n < 0 || n >= total || locked) return;
    locked = true;

    var goingBack = n < current;
    var outgoing = slides[current];
    var incoming = slides[n];

    // Set initial position for incoming slide (off-screen)
    if (goingBack) {
      incoming.classList.add('entering-prev');
    }

    // Outgoing: active → exiting
    outgoing.classList.remove('active');
    outgoing.classList.add(goingBack ? 'exiting-prev' : 'exiting');

    // Force reflow so the entering-prev position takes effect before animating
    incoming.offsetHeight;

    // Incoming: hidden → active (remove entering class to trigger transition)
    incoming.classList.remove('exiting', 'exiting-prev', 'entering-prev');
    incoming.classList.add('active');

    // Cleanup after transition
    function onDone() {
      outgoing.removeEventListener('transitionend', onDone);
      outgoing.classList.remove('exiting', 'exiting-prev');
      locked = false;
    }
    outgoing.addEventListener('transitionend', onDone, { once: true });

    // Safety: unlock after transition duration (500ms + buffer)
    setTimeout(function () { locked = false; outgoing.classList.remove('exiting', 'exiting-prev'); }, 550);

    current = n;
    updateUI();
  }

  function go(dir) {
    showSlide(current + dir);
  }

  // ── UI updates ──
  function updateUI() {
    // Progress bar
    var bar = document.getElementById('progress');
    if (bar) bar.style.width = ((current + 1) / total * 100) + '%';

    // Nav buttons
    var prev = document.getElementById('btnPrev');
    var next = document.getElementById('btnNext');
    if (prev) prev.disabled = current === 0;
    if (next) next.disabled = current === total - 1;

    // Hint
    if (current > 0) {
      var hint = document.getElementById('hint');
      if (hint) hint.classList.add('hide');
    }

    // URL hash
    if (history.replaceState) {
      history.replaceState(null, '', '#slide=' + (current + 1));
    }
  }

  // ── Keyboard ──
  document.addEventListener('keydown', function (e) {
    switch (e.key) {
      case 'ArrowRight': case ' ': case 'PageDown':
        e.preventDefault(); go(1); break;
      case 'ArrowLeft': case 'PageUp':
        e.preventDefault(); go(-1); break;
      case 'Home':
        e.preventDefault(); showSlide(0); break;
      case 'End':
        e.preventDefault(); showSlide(total - 1); break;
      case 'f': case 'F':
        if (!document.fullscreenElement) document.documentElement.requestFullscreen();
        else document.exitFullscreen();
        break;
    }
  });

  // ── Nav button clicks ──
  var btnPrev = document.getElementById('btnPrev');
  var btnNext = document.getElementById('btnNext');
  if (btnPrev) btnPrev.addEventListener('click', function () { go(-1); });
  if (btnNext) btnNext.addEventListener('click', function () { go(1); });

  // ── Touch swipe ──
  var tx = 0;
  document.addEventListener('touchstart', function (e) { tx = e.touches[0].clientX; }, { passive: true });
  document.addEventListener('touchend', function (e) {
    var diff = tx - e.changedTouches[0].clientX;
    if (Math.abs(diff) > 60) go(diff > 0 ? 1 : -1);
  }, { passive: true });

  // ── Init from URL hash ──
  function initFromHash() {
    var match = location.hash.match(/slide=(\d+)/);
    if (match) {
      var n = parseInt(match[1], 10) - 1;
      if (n >= 0 && n < total && n !== 0) {
        slides[0].classList.remove('active');
        slides[n].classList.add('active');
        current = n;
      }
    }
    updateUI();
  }

  initFromHash();

  // ── Expose go() for any inline onclick (though we avoid them) ──
  window.go = go;
  window.showSlide = showSlide;

})();
