(() => {
  if (location.origin !== __ORIGIN__) return false;
  const service = __SERVICE__;
  const hash = location.hash;
  const page = hash.split('?')[0];
  if (service === 'ASTRBOT' && page === '#/auth/setup') return true;
  if (service === 'ASTRBOT' && location.pathname === '/' && !page.startsWith('#/auth/')) {
    // An earlier login may have left an authenticated browser on the welcome page.
    // Resume setup only if the server confirms it for the still-current document.
    const token = localStorage.getItem('token');
    if (!token || !document.documentElement) return false;
    const state = document.documentElement.dataset;
    if (state.androidSetupChecked) return true;
    state.androidSetupChecked = 'pending';
    fetch('/api/v1/auth/setup-status', {cache: 'no-store', redirect: 'error'})
      .then(response => response.ok ? response.json() : null)
      .then(result => {
        if (result?.data?.setup_required === true && result?.data?.skip_default_password_auth === false &&
            location.hash === hash && localStorage.getItem('token') === token) {
          state.androidSetupChecked = 'resumed';
          location.replace('#/auth/setup');
        } else {
          delete state.androidSetupChecked;
        }
      }).catch(() => { delete state.androidSetupChecked; });
    return true;
  }
  const route = service === 'ASTRBOT'
    ? page === '#/auth/login'
    : /\/web_login\/?$/.test(location.pathname);
  if (!route) return false;
  const password = document.querySelector(service === 'ASTRBOT'
    ? '.login-form input[name="password"][autocomplete="current-password"]'
    : 'input[name="password"][autocomplete="current-password"]');
  const username = document.querySelector(service === 'ASTRBOT'
    ? '.login-form input[name="username"]' : 'input[name="username"]');
  const form = password?.closest('form');
  if (!password || !form || password.disabled || form.dataset.androidInitialFilled) return false;
  // A partially entered form belongs to the user. Do not overwrite it, even on a later callback.
  if (password.value || (service === 'ASTRBOT' && username?.value)) {
    form.dataset.androidInitialFilled = 'user'; return true;
  }
  const setter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set;
  const fill = (input, value) => {
    if (!input || input.readOnly || input.disabled) return;
    setter.call(input, value);
    input.dispatchEvent(new Event('input', {bubbles: true}));
    input.dispatchEvent(new Event('change', {bubbles: true}));
  };
  if (service === 'ASTRBOT') fill(username, __USERNAME__);
  fill(password, __PASSWORD__);
  form.dataset.androidInitialFilled = 'generated';
  return true;
})();
