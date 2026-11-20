// Left rail: collapsed to icons by default. "Expand" widens it and the choice is remembered.
// On phones the rail is a drawer opened by the menu button; the dimmed area or Esc closes it.
(function () {
  var KEY = 'rail-open';
  var body = document.body;
  var rail = document.querySelector('.sidebar#rail');
  var menuBtn0 = document.querySelector('.app-bar .menu-btn');
  var mobile = window.matchMedia('(max-width: 960px)');

  // On pages without a rail the menu button opens the header's links instead.
  if (!rail && menuBtn0) {
    menuBtn0.addEventListener('click', function () {
      var open = body.classList.toggle('top-open');
      menuBtn0.setAttribute('aria-expanded', open ? 'true' : 'false');
    });
    document.addEventListener('click', function (e) {
      if (body.classList.contains('top-open') && !e.target.closest('.app-bar')) {
        body.classList.remove('top-open');
        menuBtn0.setAttribute('aria-expanded', 'false');
      }
    });
    document.querySelectorAll('.app-bar .nav a').forEach(function (a) {
      a.addEventListener('click', function () { body.classList.remove('top-open'); });
    });
    if (mobile.addEventListener) mobile.addEventListener('change', function () {
      if (!mobile.matches) body.classList.remove('top-open');
    });
  }

  function setOpen(open, remember) {
    body.classList.toggle('sb-open', open);
    document.querySelectorAll('.rail-toggle, .app-bar .menu-btn').forEach(function (b) {
      b.setAttribute('aria-expanded', open ? 'true' : 'false');
    });
    if (remember !== false && !mobile.matches) {
      try { localStorage.setItem(KEY, open ? '1' : '0'); } catch (e) { /* private mode: just don't remember */ }
    }
  }

  if (rail) {
    try {
      if (!mobile.matches && localStorage.getItem(KEY) === '1') setOpen(true, false);
    } catch (e) { /* ignore */ }
    rail.querySelectorAll('.rail-toggle').forEach(function (b) {
      b.addEventListener('click', function () { setOpen(!body.classList.contains('sb-open')); });
    });
    var menuBtn = document.querySelector('.app-bar .menu-btn');
    if (menuBtn) menuBtn.addEventListener('click', function () {
      if (rail) { setOpen(!body.classList.contains('sb-open'), false); }
      else {
        var open = body.classList.toggle('top-open');
        menuBtn.setAttribute('aria-expanded', open ? 'true' : 'false');
      }
    });
    rail.querySelectorAll('.scrim').forEach(function (s) {
      s.addEventListener('click', function () { setOpen(false, false); });
    });
    if (mobile.addEventListener) mobile.addEventListener('change', function () {
      if (mobile.matches) setOpen(false, false);
    });
  }

  document.addEventListener('keydown', function (e) {
    if (e.key === 'Escape') {
      document.querySelectorAll('details.me-menu[open]').forEach(function (d) { d.removeAttribute('open'); });
      body.classList.remove('top-open');
      if (mobile.matches && body.classList.contains('sb-open')) setOpen(false, false);
    }
  });

  // Close the account menu when clicking anywhere else.
  document.addEventListener('click', function (e) {
    document.querySelectorAll('details.me-menu[open]').forEach(function (d) {
      if (!d.contains(e.target)) d.removeAttribute('open');
    });
  });
})();
