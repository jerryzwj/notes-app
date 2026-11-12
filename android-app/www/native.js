// 安卓壳（Capacitor）原生能力封装。浏览器里跑的时候所有方法都是惰性的，不会影响网页。
(function () {
  const cap = window.Capacitor;
  const native = Boolean(cap && typeof cap.isNativePlatform === 'function' && cap.isNativePlatform());

  // 安卓壳的 WebView 源是 https://localhost，接口要打回线上域名（Worker 已放行 CORS）
  const apiOrigin = native ? 'https://note.wenjun.eu.org' : '';

  function plugin(name) {
    try {
      const p = cap && cap.Plugins ? cap.Plugins[name] : null;
      return p && typeof p.then !== 'function' ? p : null;
    } catch (e) {
      return null;
    }
  }

  const SecureStore = plugin('SecureStore');
  const Biometric = plugin('Biometric');
  const NativeFiles = plugin('NativeFiles');
  const ShareReceiver = plugin('ShareReceiver');
  const Backup = plugin('Backup');
  const Clipboard = plugin('Clipboard');

  async function clipboardRead() {
    if (!Clipboard) return '';
    try {
      const res = await Clipboard.read();
      return res && res.text ? res.text : '';
    } catch (e) {
      return '';
    }
  }

  async function clipboardWrite(text) {
    if (!Clipboard) return false;
    try {
      await Clipboard.write({ text });
      return true;
    } catch (e) {
      return false;
    }
  }

  async function secureSet(key, value) {
    if (!SecureStore || !value) return;
    try {
      await SecureStore.set({ key, value });
    } catch (e) {
      console.warn('secure set failed', e);
    }
  }

  async function secureGet(key) {
    if (!SecureStore) return null;
    try {
      const res = await SecureStore.get({ key });
      return res && res.value ? res.value : null;
    } catch (e) {
      return null;
    }
  }

  async function secureRemove(key) {
    if (!SecureStore) return;
    try {
      await SecureStore.remove({ key });
    } catch (e) {}
  }

  async function saveFile(filename, content, mime) {
    if (!NativeFiles) return false;
    try {
      const res = await NativeFiles.save({ filename, content, mime });
      return res && res.path ? res.path : true;
    } catch (e) {
      return false;
    }
  }

  async function biometricAvailable() {
    if (!Biometric) return false;
    try {
      const res = await Biometric.isAvailable();
      return Boolean(res && res.available);
    } catch (e) {
      return false;
    }
  }

  async function authenticate(title, subtitle) {
    if (!Biometric) return false;
    try {
      const res = await Biometric.authenticate({ title, subtitle });
      return Boolean(res && res.ok);
    } catch (e) {
      return false;
    }
  }

  async function takeSharedText() {
    if (!ShareReceiver) return '';
    try {
      const res = await ShareReceiver.getPending();
      const text = res && res.text ? res.text : '';
      if (text) await ShareReceiver.clear();
      return text;
    } catch (e) {
      return '';
    }
  }

  async function scheduleBackup(hour) {
    if (!Backup) return null;
    try {
      return await Backup.schedule({ hour, minute: 0 });
    } catch (e) {
      return null;
    }
  }

  async function backupNow() {
    if (!Backup) return null;
    try {
      await Backup.runNow();
      return true;
    } catch (e) {
      return false;
    }
  }

  async function backupStatus() {
    if (!Backup) return null;
    try {
      return await Backup.status();
    } catch (e) {
      return null;
    }
  }

  window.NativeBridge = {
    native,
    apiOrigin,
    secure: { set: secureSet, get: secureGet, remove: secureRemove },
    files: { save: saveFile },
    bio: { available: biometricAvailable, authenticate },
    share: { take: takeSharedText },
    clipboard: { read: clipboardRead, write: clipboardWrite },
    backup: { schedule: scheduleBackup, now: backupNow, status: backupStatus },
  };
})();
