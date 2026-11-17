// Run after OAuthTests.exe: node BrowserFillTests.js
// Synthetic DOM fixtures exercise the exact script emitted by the application.
const vm = require('node:vm');
const fs = require('node:fs');
const assert = require('node:assert/strict');
let passed = 0;
function check(value, label) { assert(value, label); passed++; }
for (const [provider, host] of [['openai', 'auth.openai.com'], ['claude', 'claude.ai']]) {
    const script = fs.readFileSync(`.checks/fill-${provider}.js`, 'utf8');
    class Input {
        constructor(type, name, autocomplete = '', maxLength = -1) {
            Object.assign(this, { type, name, id: name, autocomplete, maxLength, dataset: {}, disabled: false, readOnly: false, events: [], _value: '' });
        }
        get value() { return this._value; }
        set value(v) { this._value = v; }
        getClientRects() { return this.hidden ? [] : [{}]; }
        dispatchEvent(event) { this.events.push(event.type); }
    }
    const account = new Input('email', 'email'), password = new Input('password', 'password'), otp = new Input('text', 'otp', 'one-time-code');
    const inputs = [account, password, otp];
    const frame = {}, context = { window: frame, top: frame, location: { protocol: 'https:', port: '', hostname: host }, document: { body: { innerText: 'Enter a code from your authenticator app' }, querySelectorAll: () => inputs }, HTMLInputElement: Input, Event: class { constructor(type) { this.type = type; } } };
    vm.createContext(context);
    const run = (code = script) => vm.runInContext(code, context);
    check(run() === true && account.value === 'alice@example.com', `${provider} email filled`);
    check(password.value === String.raw` Synthetic\"Password `, `${provider} password preserves whitespace and escaping`);
    check(otp.value === '123456', `${provider} authenticator OTP filled`);
    otp.type = 'password'; otp.value = ''; run(); check(otp.value === '123456', `${provider} masked OTP never receives account password`); otp.type = 'text';
    check(account.events.join(',') === 'input,change' && password.events.join(',') === 'input,change', `${provider} reactive form events`);
    run(script.replace('123456', '654321'));
    check(otp.value === '654321', `${provider} generated OTP refreshes at rollover`);
    otp.value = 'manual'; run(); check(otp.value === 'manual', `${provider} user-edited OTP retained`);
    account.value = 'other@example.com'; password.value = 'user edit'; run();
    check(account.value === 'other@example.com' && password.value === 'user edit', `${provider} existing account/password untouched`);
    otp.value = ''; context.document.body.innerText = 'We sent an email code'; run(); check(otp.value === '', `${provider} email challenge never receives TOTP`);
    context.document.body.innerText = 'Authenticator app code';
    for (const origin of [{ protocol: 'http:' }, { hostname: host + '.evil.invalid' }, { port: '8443' }, { hostname: 'accounts.google.com' }]) {
        const before = { ...context.location }; Object.assign(context.location, origin); account.value = ''; password.value = ''; otp.value = '';
        check(run() === false && inputs.every(e => !e.value), `${provider} untrusted origin blocked`); Object.assign(context.location, before);
    }
    context.top = {}; check(run() === false, `${provider} cross-frame injection blocked`); context.top = frame;
    inputs.splice(0, inputs.length, new Input('email', 'email'), new Input('password', 'password', 'new-password'));
    inputs[0].disabled = true; run(); check(inputs.every(e => !e.value), `${provider} disabled fields and signup passwords ignored`);
    inputs[0].disabled = false; inputs[0].hidden = true; run(); check(!inputs[0].value, `${provider} hidden fields ignored`);
    inputs.splice(0, inputs.length, ...Array.from({ length: 6 }, (_, i) => new Input('text', `code-${i}`, '', 1)));
    run(); check(inputs.map(e => e.value).join('') === '123456', `${provider} split OTP digits`);
    run(script.replace('123456', '654321')); check(inputs.map(e => e.value).join('') === '654321', `${provider} split OTP rollover`);
    context.document.body.innerText = 'Authenticator or email code'; inputs.forEach(e => e.value = ''); run();
    check(inputs.every(e => !e.value), `${provider} ambiguous email challenge retained for user`);
}
console.log(`PASS: ${passed} browser filling checks; origins, exact password, events, rollover, split OTP, user edits and email challenges.`);
