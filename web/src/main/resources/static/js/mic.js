// Dictation into the notes box with the browser's speech recognition (Chrome; Edge and Safari
// vary). Typing always works as a fallback.
(function () {
  var Rec = window.SpeechRecognition || window.webkitSpeechRecognition;
  var btn = document.getElementById('micBtn');
  var state = document.getElementById('micState');
  var box = document.getElementById('notes');
  var texts = document.getElementById('micText');
  if (!btn || !box) return;

  function t(name, fallback) {
    var v = texts && texts.getAttribute('data-' + name);
    return v || fallback;
  }

  if (!Rec) {
    btn.disabled = true;
    state.textContent = t('unsupported', 'Dictation needs Chrome. You can type instead.');
    return;
  }
  var rec = null;
  var base = '';
  var startLabel = btn.textContent;

  btn.addEventListener('click', function () {
    if (rec) { rec.stop(); return; }
    rec = new Rec();
    rec.lang = 'en-IN';
    rec.continuous = true;
    rec.interimResults = true;
    base = box.value ? box.value.trim() + ' ' : '';
    rec.onresult = function (e) {
      var finalText = '', interim = '';
      for (var i = 0; i < e.results.length; i++) {
        if (e.results[i].isFinal) finalText += e.results[i][0].transcript + ' ';
        else interim += e.results[i][0].transcript;
      }
      box.value = base + finalText + interim;
    };
    rec.onerror = function (e) {
      state.textContent = e.error === 'language-not-supported'
        ? t('nolang', 'This browser cannot listen here. Please type instead.')
        : 'Microphone: ' + e.error;
    };
    rec.onend = function () {
      rec = null;
      btn.textContent = startLabel;
      if (state.querySelector('.dot')) state.textContent = '';
    };
    rec.start();
    btn.textContent = '■ ' + t('stop', 'Stop');
    state.textContent = '';
    var dot = document.createElement('span');
    dot.className = 'dot';
    state.appendChild(dot);
    state.appendChild(document.createTextNode(' ' + t('listening', 'Listening')));
  });
})();
