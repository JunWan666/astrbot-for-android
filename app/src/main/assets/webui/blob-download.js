(() => {
  'use strict';
  if (window !== window.top) return;
  const key = __ASTRBOT_BLOB_READER_KEY__;
  const limit = 8 * 1024 * 1024;
  const entries = new Map();
  let retainedBytes = 0;
  const create = URL.createObjectURL;
  const revoke = URL.revokeObjectURL;
  const click = HTMLAnchorElement.prototype.click;
  const forget = (url, entry) => {
    if (entries.get(url) !== entry) return;
    entries.delete(url);
    retainedBytes -= entry.blob.size;
    if (entry.timer) clearTimeout(entry.timer);
  };
  URL.createObjectURL = function (object) {
    const url = create.apply(this, arguments);
    // Keep bounded references, never copies. Large files retain normal browser behavior.
    if (object instanceof Blob && object.size <= limit &&
        entries.size < 32 && retainedBytes + object.size <= limit) {
      entries.set(url, {blob: object, selected: false, name: '', timer: null});
      retainedBytes += object.size;
    }
    return url;
  };
  const select = anchor => {
    if (!(anchor instanceof HTMLAnchorElement)) return;
    const url = anchor.href;
    const entry = entries.get(url);
    if (!entry || entry.selected) return;
    entry.selected = true;
    entry.name = anchor.download.slice(0, 150);
    entry.timer = setTimeout(() => forget(url, entry), 30000);
  };
  HTMLAnchorElement.prototype.click = function () {
    select(this);
    return click.apply(this, arguments);
  };
  document.addEventListener('click', event => {
    const target = event.target;
    if (target instanceof Element) select(target.closest('a'));
  }, true);
  URL.revokeObjectURL = function (url) {
    const address = String(url);
    const entry = entries.get(address);
    if (entry && !entry.selected) forget(address, entry);
    // Revoke on schedule as requested by the page. The selected Blob itself remains
    // readable for the native DownloadListener after an immediate URL revocation.
    return revoke.apply(this, arguments);
  };
  Object.defineProperty(window, key, {value: url => {
    const entry = entries.get(url);
    if (!entry || !entry.selected) return null;
    const result = {blob: entry.blob, name: entry.name};
    forget(url, entry);
    return result;
  }});
})();
