(() => {
  if (window !== window.top || location.origin !== 'http://127.0.0.1:5099') return 'untrusted';
  const input = document.querySelector('input#login-password[name="password"][autocomplete="current-password"]');
  if (!input || !input.closest('form') || !input.getClientRects().length || input.disabled || input.readOnly) return 'waiting';
  if (input.value) return 'occupied';
  const setter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')?.set;
  if (!setter) return 'waiting';
  setter.call(input, __INITIAL_PASSWORD__);
  input.dispatchEvent(new Event('input', { bubbles: true }));
  input.dispatchEvent(new Event('change', { bubbles: true }));
  return 'filled';
})();
