// Shop page: "Ask / order" pre-fills the message box with the product name.
(function () {
  document.querySelectorAll('[data-product]').forEach(function (a) {
    a.addEventListener('click', function () {
      var box = document.getElementById('message');
      if (!box) return;
      if (!box.value.trim()) {
        box.value = 'Hello, I would like to order: ' + a.getAttribute('data-product') + '. ';
      }
      setTimeout(function () { box.focus(); }, 300);
    });
  });
})();
