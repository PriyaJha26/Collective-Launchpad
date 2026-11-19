// Dictation into the notes box with the browser's speech recognition (Chrome; Edge and Safari
// vary). The language follows the "Seller's language" field. Typing always works as a fallback.
(function () {
  var Rec = window.SpeechRecognition || window.webkitSpeechRecognition;
  var btn = document.getElementById('micBtn');
  var state = document.getElementById('micState');
  var box = document.getElementById('notes');
  var lang = document.getElementById('language');
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
  var BCP = { hi: 'hi-IN', mr: 'mr-IN', ta: 'ta-IN', te: 'te-IN', kn: 'kn-IN', ml: 'ml-IN', bn: 'bn-IN',
              gu: 'gu-IN', pa: 'pa-IN', or: 'or-IN', ur: 'ur-IN', en: 'en-IN' };
  var NAMES = { hi: 'हिन्दी', mr: 'मराठी', ta: 'தமிழ்', te: 'తెలుగు', kn: 'ಕನ್ನಡ', ml: 'മലയാളം', bn: 'বাংলা',
                gu: 'ગુજરાતી', pa: 'ਪੰਜਾਬੀ', or: 'ଓଡ଼ିଆ', ur: 'اردو', en: 'English' };
  var rec = null;
  var base = '';
  var startLabel = btn.textContent;

  function code() {
    return lang && BCP[lang.value] ? lang.value : 'hi';
  }

  function langName() {
    if (lang && lang.options) return lang.options[lang.selectedIndex].text;
    return NAMES[code()];
  }

  btn.addEventListener('click', function () {
    if (rec) { rec.stop(); return; }
    rec = new Rec();
    rec.lang = BCP[code()];
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
        ? t('nolang', 'This browser cannot listen in this language. Please type instead.') + ' (' + langName() + ')'
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
    state.appendChild(document.createTextNode(' ' + t('listening', 'Listening') + ' · ' + langName()));
  });
})();
