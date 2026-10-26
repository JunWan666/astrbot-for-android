(() => {
  if (location.origin !== __ORIGIN__) return false;
  const service = __SERVICE__;
  const route = service === 'ASTRBOT'
    ? location.hash.split('?')[0] === '#/auth/login'
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
