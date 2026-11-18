// Dictation into the notes box with the browser's speech recognition (Chrome; Edge and Safari
// vary). The language follows the "Seller's language" field. Typing always works as a fallback.
(function () {
  var Rec = window.SpeechRecognition || window.webkitSpeechRecognition;
  var btn = document.getElementById('micBtn');
  var state = document.getElementById('micState');
  var box = document.getElementById('notes');
  var lang = document.getElementById('language');
  if (!btn || !box) return;
  if (!Rec) {
    btn.disabled = true;
    state.textContent = 'Dictation needs Chrome. You can type instead.';
    return;
  }
  var BCP = { hi: 'hi-IN', mr: 'mr-IN', ta: 'ta-IN', te: 'te-IN', kn: 'kn-IN', ml: 'ml-IN', bn: 'bn-IN',
              gu: 'gu-IN', pa: 'pa-IN', or: 'or-IN', ur: 'ur-IN', en: 'en-IN' };
  var rec = null;
  var base = '';

  function langName() {
    if (lang && lang.options) return lang.options[lang.selectedIndex].text;
    return BCP[lang ? lang.value : 'hi'] || 'hi-IN';
  }

  btn.addEventListener('click', function () {
    if (rec) { rec.stop(); return; }
    rec = new Rec();
    rec.lang = BCP[lang ? lang.value : 'hi'] || 'hi-IN';
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
        ? 'This browser cannot listen in ' + langName() + '. Please type instead.'
        : 'Microphone: ' + e.error;
    };
    rec.onend = function () {
      rec = null;
      btn.textContent = '🎤 Start listening';
      if (state.querySelector('.dot')) state.textContent = '';
    };
    rec.start();
    btn.textContent = '■ Stop';
    state.textContent = '';
    var dot = document.createElement('span');
    dot.className = 'dot';
    state.appendChild(dot);
    state.appendChild(document.createTextNode(' Listening in ' + langName()));
  });
})();
