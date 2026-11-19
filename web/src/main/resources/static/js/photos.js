// Shrinks phone photos in the browser before upload (fast on slow networks, upright, under the
// size limit) and shows previews. Also shows a "please wait" overlay while the form submits.
(function () {
  var MAX_SIDE = 1600;
  var MAX_FILES = 8;
  var form = document.getElementById('sellerForm');
  var input = document.getElementById('photos');
  var previews = document.getElementById('previews');
  if (!form) return;

  function shrink(file) {
    if (!file.type.startsWith('image/') || !window.createImageBitmap) return Promise.resolve(file);
    return createImageBitmap(file, { imageOrientation: 'from-image' }).then(function (bmp) {
      var scale = Math.min(1, MAX_SIDE / Math.max(bmp.width, bmp.height));
      var c = document.createElement('canvas');
      c.width = Math.round(bmp.width * scale);
      c.height = Math.round(bmp.height * scale);
      c.getContext('2d').drawImage(bmp, 0, 0, c.width, c.height);
      return new Promise(function (resolve) {
        c.toBlob(function (blob) {
          resolve(blob ? new File([blob], file.name.replace(/\.\w+$/, '') + '.jpg', { type: 'image/jpeg' }) : file);
        }, 'image/jpeg', 0.85);
      });
    }).catch(function () { return file; }); // e.g. a format this browser cannot decode: send as is
  }

  if (input && previews) {
    input.addEventListener('change', function () {
      var files = Array.prototype.slice.call(input.files, 0, MAX_FILES);
      var texts = document.getElementById('micText');
      previews.textContent = (texts && texts.getAttribute('data-preparing')) || 'Preparing photos…';
      Promise.all(files.map(shrink)).then(function (small) {
        if (window.DataTransfer) {
          var dt = new DataTransfer();
          small.forEach(function (f) { dt.items.add(f); });
          input.files = dt.files;
        }
        previews.textContent = '';
        small.forEach(function (f) {
          var img = document.createElement('img');
          img.src = URL.createObjectURL(f);
          img.alt = '';
          previews.appendChild(img);
        });
      });
    });
  }

  var overlay = document.getElementById('overlay');
  var submit = document.getElementById('submitBtn');
  form.addEventListener('submit', function () {
    if (overlay) overlay.classList.remove('hidden');
    if (submit) submit.disabled = true;
  });
  window.addEventListener('pageshow', function () { // coming back with the back button
    if (overlay) overlay.classList.add('hidden');
    if (submit) submit.disabled = false;
  });
})();
