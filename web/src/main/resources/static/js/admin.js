// Confirmation for buttons that publish, hide or send (data-confirm="question").
(function () {
  document.querySelectorAll('[data-confirm]').forEach(function (btn) {
    btn.addEventListener('click', function (e) {
      if (!window.confirm(btn.getAttribute('data-confirm'))) e.preventDefault();
    });
  });
})();
