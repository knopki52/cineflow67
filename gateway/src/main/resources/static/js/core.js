/* CineFlow — ядро: состояние, API, роутер, WebSocket, общие помощники */
(function () {
  'use strict';
  const CF = window.CF = { pages: {}, cleanup: [] };

  // ---------------- состояние ----------------
  const load = (k, d) => { try { const v = localStorage.getItem(k); return v ? JSON.parse(v) : d; } catch (e) { return d; } };
  const save = (k, v) => { try { localStorage.setItem(k, JSON.stringify(v)); } catch (e) { /* приватный режим */ } };
  CF.state = { user: load('cf-user', null), city: load('cf-city', { id: 1, name: 'Москва' }), cities: [] };
  CF.setUser = (u) => { CF.state.user = u; save('cf-user', u); CF.renderChrome(); };
  CF.setCity = (c) => { CF.state.city = { id: c.id, name: c.name }; save('cf-city', CF.state.city); CF.renderChrome(); CF.route(); };

  // ---------------- помощники ----------------
  const $ = CF.$ = (sel, root = document) => root.querySelector(sel);
  CF.$$ = (sel, root = document) => Array.from(root.querySelectorAll(sel));
  CF.esc = (s) => String(s ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
  CF.money = (n) => (Math.round(n || 0)).toLocaleString('ru-RU') + ' ₽';
  CF.time = (iso) => new Date(iso).toLocaleTimeString('ru-RU', { hour: '2-digit', minute: '2-digit' });
  CF.date = (iso) => new Date(iso).toLocaleDateString('ru-RU', { day: 'numeric', month: 'long' });
  CF.dateTime = (iso) => CF.date(iso) + ', ' + CF.time(iso);
  CF.weekday = (d) => d.toLocaleDateString('ru-RU', { weekday: 'short' });
  CF.isoDate = (d) => `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
  CF.plural = (n, a, b, c) => { const m10 = n % 10, m100 = n % 100; return m10 === 1 && m100 !== 11 ? a : (m10 >= 2 && m10 <= 4 && (m100 < 10 || m100 >= 20)) ? b : c; };
  CF.sleep = (ms) => new Promise((r) => setTimeout(r, ms));
  CF.levelName = (l) => ({ BASE: 'Базовый', SILVER: 'Silver', GOLD: 'Gold' }[l] || l);
  /** «в Москве», «в Казани» — предложный падеж для городов сети */
  CF.inCity = (name) => ({ 'Москва': 'Москве', 'Санкт-Петербург': 'Санкт-Петербурге', 'Казань': 'Казани' }[name] || name);
  CF.html = (el, html) => { el.innerHTML = html; return el; };

  CF.toast = (text, kind = '') => {
    const t = document.createElement('div');
    t.className = 'toast ' + kind;
    t.textContent = text;
    $('#toasts').appendChild(t);
    setTimeout(() => t.remove(), 4200);
  };

  // ---------------- API ----------------
  class ApiError extends Error {
    constructor(message, status, body) { super(message); this.status = status; this.body = body; }
  }
  CF.ApiError = ApiError;

  CF.api = async (path, opts = {}) => {
    let res;
    try {
      res = await fetch(path, {
        method: opts.method || (opts.body !== undefined ? 'POST' : 'GET'),
        headers: opts.body !== undefined ? { 'Content-Type': 'application/json' } : {},
        body: opts.body !== undefined ? JSON.stringify(opts.body) : undefined,
      });
    } catch (e) {
      if (!opts.quiet) CF.filmBreak('gateway');
      throw new ApiError('Нет связи с сервером', 0, null);
    }
    const ct = res.headers.get('content-type') || '';
    const body = ct.includes('json') ? await res.json().catch(() => null) : await res.text().catch(() => null);
    if (!res.ok) {
      // сервис целиком недоступен — Gateway отдал fallback Circuit Breaker'а
      if (res.status === 503 && body && body.error === 'SERVICE_DOWN') {
        if (!opts.quiet) CF.filmBreak(body.service);
        throw new ApiError(body.message, 503, body);
      }
      const msg = (body && (body.message || body.error)) || ('Ошибка ' + res.status);
      throw new ApiError(msg, res.status, body);
    }
    return body;
  };

  // ---------------- WebSocket (STOMP) ----------------
  // Один клиент на всё приложение; подписки страниц снимаются при уходе со страницы.
  const ws = CF.ws = { client: null, connected: false, subs: new Map(), seq: 0 };
  ws.available = () => !!window.StompJs;
  ws.connect = () => {
    if (!ws.available() || ws.client) return;
    const url = (location.protocol === 'https:' ? 'wss://' : 'ws://') + location.host + '/ws';
    ws.client = new StompJs.Client({ brokerURL: url, reconnectDelay: 2500, heartbeatIncoming: 10000, heartbeatOutgoing: 10000 });
    ws.client.onConnect = () => {
      ws.connected = true;
      ws.subs.forEach((s) => { s.handle = ws.client.subscribe(s.dest, (m) => s.cb(JSON.parse(m.body))); });
      document.dispatchEvent(new CustomEvent('cf-ws', { detail: true }));
    };
    ws.client.onWebSocketClose = () => { ws.connected = false; document.dispatchEvent(new CustomEvent('cf-ws', { detail: false })); };
    ws.client.activate();
  };
  ws.subscribe = (dest, cb) => {
    const id = ++ws.seq;
    const s = { dest, cb, handle: null };
    ws.subs.set(id, s);
    if (ws.connected) s.handle = ws.client.subscribe(dest, (m) => cb(JSON.parse(m.body)));
    const off = () => { try { s.handle && s.handle.unsubscribe(); } catch (e) { /* уже закрыто */ } ws.subs.delete(id); };
    CF.cleanup.push(off);
    return off;
  };
  ws.send = (dest, body) => { if (ws.connected) ws.client.publish({ destination: dest, body: JSON.stringify(body) }); };

  CF.every = (ms, fn) => { const t = setInterval(fn, ms); CF.cleanup.push(() => clearInterval(t)); return t; };
  CF.onLeave = (fn) => CF.cleanup.push(fn);

  // ---------------- роутер ----------------
  CF.route = async () => {
    CF.cleanup.splice(0).forEach((fn) => { try { fn(); } catch (e) { console.warn(e); } });
    const parts = (location.hash.replace(/^#\/?/, '') || '').split('/').filter(Boolean);
    const name = parts[0] || 'home';
    const page = CF.pages[name] || CF.pages.notfound;
    const main = $('#main');
    main.innerHTML = '';
    CF.$$('[data-route]').forEach((a) => a.classList.toggle('active', a.dataset.route === name));
    window.scrollTo({ top: 0 });
    try {
      await page(main, parts.slice(1).map(decodeURIComponent));
    } catch (e) {
      console.error(e);
      if (e instanceof ApiError && e.status === 503 && e.body && e.body.error === 'SERVICE_DOWN') return;
      if (e instanceof ApiError && e.status === 0) return;
      main.innerHTML = `<div class="empty"><h2>Не получилось загрузить страницу</h2><p class="muted" style="margin-top:8px">${CF.esc(e.message)}</p><button class="btn btn-primary" onclick="CF.route()">Повторить</button></div>`;
    }
  };
  CF.go = (hash) => { if (location.hash === hash) CF.route(); else location.hash = hash; };

  // ---------------- шапка: город, вход, роли, тема ----------------
  CF.renderChrome = () => {
    $('#cityName').textContent = CF.state.city.name;
    const u = CF.state.user;
    $('#profileBtn').textContent = u ? u.name : 'Войти';
    CF.$$('.staff').forEach((a) => { a.hidden = !(u && a.dataset.roles.split(',').includes(u.role)); });
  };

  CF.requireUser = (why = 'Войдите, чтобы продолжить') => new Promise((resolve) => {
    if (CF.state.user) return resolve(CF.state.user);
    CF.login(why, resolve);
  });

  CF.modal = (html, { onClose, dim = false } = {}) => {
    const back = document.createElement('div');
    back.className = 'sheet-backdrop';
    back.innerHTML = `<div class="sheet" role="dialog" aria-modal="true">${html}</div>`;
    if (!dim) back.style.background = 'rgba(8,2,6,.55)';
    document.body.appendChild(back);
    const close = () => { back.remove(); document.removeEventListener('keydown', esc); onClose && onClose(); };
    const esc = (e) => { if (e.key === 'Escape') close(); };
    document.addEventListener('keydown', esc);
    back.addEventListener('mousedown', (e) => { if (e.target === back) close(); });
    back.querySelectorAll('[data-close]').forEach((b) => b.addEventListener('click', close));
    const first = back.querySelector('input, button:not([data-close])');
    if (first) setTimeout(() => first.focus(), 50);
    return { el: back.firstElementChild, close };
  };

  CF.login = (why, done) => {
    const m = CF.modal(`
      <div class="sheet-head"><div><h2>Вход</h2><p class="muted" style="margin-top:6px">${CF.esc(why)}</p></div>
      <button class="close" data-close aria-label="Закрыть">×</button></div>
      <form class="stack" id="loginForm">
        <div class="field"><label for="ln">Имя</label><input class="input" id="ln" required autocomplete="given-name" placeholder="Иван"></div>
        <div class="field"><label for="le">Почта</label><input class="input" id="le" type="email" required autocomplete="email" placeholder="ivan@example.ru"></div>
        <p class="small muted">Служебные входы для защиты: admin@cineflow.ru, control@cineflow.ru, bar@cineflow.ru</p>
        <button class="btn btn-primary btn-block" type="submit">Войти</button>
      </form>`);
    m.el.querySelector('#loginForm').addEventListener('submit', async (e) => {
      e.preventDefault();
      try {
        const u = await CF.api('/api/users/login', { body: { name: $('#ln', m.el).value, email: $('#le', m.el).value } });
        CF.setUser(u);
        m.close();
        CF.toast('Добро пожаловать, ' + u.name + '! На счёте ' + u.points + ' баллов', 'ok');
        done && done(u);
      } catch (err) { CF.toast(err.message, 'err'); }
    });
  };

  CF.refreshUser = async () => {
    if (!CF.state.user) return null;
    try { const u = await CF.api('/api/users/' + CF.state.user.id, { quiet: true }); CF.setUser(u); return u; } catch (e) { return CF.state.user; }
  };

  CF.cityPicker = async (anchor) => {
    if (!CF.state.cities.length) CF.state.cities = await CF.api('/api/cities');
    const r = anchor.getBoundingClientRect();
    const dd = document.createElement('div');
    dd.className = 'dropdown';
    dd.style.left = r.left + 'px';
    dd.style.top = (r.bottom + window.scrollY + 6) + 'px';
    dd.innerHTML = CF.state.cities.map((c) => `<button data-id="${c.id}" class="${c.id === CF.state.city.id ? 'on' : ''}">${CF.esc(c.name)}</button>`).join('');
    document.body.appendChild(dd);
    const off = (e) => { if (!dd.contains(e.target)) { dd.remove(); document.removeEventListener('mousedown', off); } };
    setTimeout(() => document.addEventListener('mousedown', off));
    dd.addEventListener('click', (e) => {
      const b = e.target.closest('button'); if (!b) return;
      CF.setCity(CF.state.cities.find((c) => c.id === +b.dataset.id));
      dd.remove();
    });
  };

  CF.start = () => {
    CF.renderChrome();
    $('#cityBtn').addEventListener('click', (e) => CF.cityPicker(e.currentTarget));
    $('#profileBtn').addEventListener('click', () => CF.state.user ? CF.go('#/profile') : CF.login('Билеты, бонусы и уведомления в Telegram', () => CF.route()));
    $('#themeBtn').addEventListener('click', () => {
      const next = document.documentElement.dataset.theme === 'dark' ? 'light' : 'dark';
      document.documentElement.dataset.theme = next;
      try { localStorage.setItem('cf-theme', next); } catch (e) { /* ignore */ }
      document.querySelector('meta[name=theme-color]').content = next === 'dark' ? '#1A0B16' : '#F6F0F4';
    });
    window.addEventListener('hashchange', CF.route);
    ws.connect();
    CF.effects && CF.effects.init();
    CF.route();
  };

  CF.pages.notfound = (el) => { CF.filmBreak(null, 'Такой страницы нет'); el.innerHTML = ''; };
})();
