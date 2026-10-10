/* CineFlow — админка, экран бара, контроль на входе */
(function () {
  'use strict';
  const CF = window.CF, esc = CF.esc;
  const charts = [];
  const chart = (canvas, cfg) => {
    if (!window.Chart) { canvas.parentElement.innerHTML = '<p class="muted">Графики загружаются из интернета (Chart.js).</p>'; return null; }
    const css = getComputedStyle(document.documentElement);
    Chart.defaults.color = css.getPropertyValue('--muted').trim();
    Chart.defaults.borderColor = css.getPropertyValue('--line').trim();
    Chart.defaults.font.family = 'Manrope, sans-serif';
    const c = new Chart(canvas, cfg);
    charts.push(c);
    CF.onLeave(() => c.destroy());
    return c;
  };
  const brass = () => getComputedStyle(document.documentElement).getPropertyValue('--brass').trim();
  const guard = (el, roles) => {
    const u = CF.state.user;
    if (u && roles.includes(u.role)) return true;
    el.innerHTML = `<div class="empty"><h2>Раздел для сотрудников</h2><p style="margin-top:8px">Войдите как ${roles.map((r) => ({ ADMIN: 'admin@cineflow.ru', BAR: 'bar@cineflow.ru', CONTROLLER: 'control@cineflow.ru' }[r])).join(' или ')}.</p><button class="btn btn-primary" id="li">Войти</button></div>`;
    CF.$('#li', el).addEventListener('click', () => { CF.setUser(null); CF.login('Служебный вход', () => CF.route()); });
    return false;
  };
  const isoLocal = (d) => `${CF.isoDate(d)}T${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}:00`;

  // ================= админка =================
  CF.pages.admin = async (el, [tab = 'analytics']) => {
    if (!guard(el, ['ADMIN'])) return;
    const tabs = { analytics: 'Аналитика', demo: 'Живое демо', chaos: 'Хаос и Circuit Breaker', config: 'Конфигурация', promos: 'Промокоды', system: 'Сервисы' };
    el.innerHTML = `<h1>Админка</h1><div class="tabs">${Object.entries(tabs).map(([k, v]) => `<a class="chip ${k === tab ? 'on' : ''}" href="#/admin/${k}">${v}</a>`).join('')}</div><div id="tab"></div>`;
    await ({ analytics, demo, chaos, config, promos, system })[tab](CF.$('#tab', el));
  };

  async function analytics(el) {
    const a = await CF.api('/api/admin/analytics');
    const t = a.totals;
    el.innerHTML = `
      <div class="kpis">
        <div class="kpi"><span class="muted small">Выручка за 14 дней</span><b>${CF.money(t.revenue)}</b></div>
        <div class="kpi"><span class="muted small">Сегодня</span><b>${CF.money(t.today)}</b></div>
        <div class="kpi"><span class="muted small">Продано билетов</span><b>${t.tickets}</b></div>
        <div class="kpi"><span class="muted small">Средний чек</span><b>${CF.money(t.avgCheck)}</b></div>
        <div class="kpi"><span class="muted small">Доля бара в выручке</span><b>${t.barShare}%</b></div>
        <div class="kpi"><span class="muted small">Заказов с баром</span><b>${t.barAttach}%</b></div>
      </div>
      ${t.tickets ? '' : '<p class="note" style="margin-top:16px">Данных пока мало — нажмите «Демо-продажи за 2 недели» во вкладке «Живое демо».</p>'}
      <div class="grid-2" style="margin-top:18px">
        <div class="panel"><h3>Выручка по дням</h3><div class="chart-box"><canvas id="c1"></canvas></div></div>
        <div class="panel"><h3>Топ-5 фильмов</h3><div class="chart-box"><canvas id="c2"></canvas></div></div>
        <div class="panel"><h3>Загруженность залов</h3><div class="chart-box"><canvas id="c3"></canvas></div></div>
        <div class="panel"><h3>Продажи прямо сейчас</h3><div class="feed" id="feed"><p class="muted">Ждём покупок…</p></div></div>
      </div>`;
    const b = brass();
    chart(CF.$('#c1', el), { type: 'bar', data: { labels: a.revenueByDay.map((d) => d.date.slice(5).split('-').reverse().join('.')),
      datasets: [{ label: 'Билеты', data: a.revenueByDay.map((d) => d.tickets), backgroundColor: b, borderRadius: 6 },
        { label: 'Бар', data: a.revenueByDay.map((d) => d.bar), backgroundColor: '#FF8A4C', borderRadius: 6 }] },
      options: { maintainAspectRatio: false, scales: { x: { stacked: true }, y: { stacked: true } } } });
    chart(CF.$('#c2', el), { type: 'bar', data: { labels: a.topMovies.map((m) => m.title), datasets: [{ label: 'Билетов', data: a.topMovies.map((m) => m.tickets), backgroundColor: b, borderRadius: 6 }] },
      options: { indexAxis: 'y', maintainAspectRatio: false, plugins: { legend: { display: false } } } });
    chart(CF.$('#c3', el), { type: 'bar', data: { labels: a.halls.map((h) => h.hall), datasets: [{ label: '% мест', data: a.halls.map((h) => h.occupancy), backgroundColor: '#4FB8B0', borderRadius: 6 }] },
      options: { maintainAspectRatio: false, plugins: { legend: { display: false } }, scales: { y: { max: 100 } } } });
    liveFeed(CF.$('#feed', el));
  }

  function liveFeed(feed) {
    if (!CF.ws.available()) return;
    let first = true;
    CF.ws.subscribe('/topic/admin/sales', (s) => {
      if (first) { feed.innerHTML = ''; first = false; }
      const d = document.createElement('div');
      d.innerHTML = `<b>${CF.money(s.amount)}</b> · ${esc(s.movie)} · ${s.seats} ${CF.plural(s.seats, 'место', 'места', 'мест')} · ${esc(s.cinema)}${s.bar ? ' · 🍿 ' + CF.money(s.bar) : ''}`;
      feed.prepend(d);
      while (feed.children.length > 40) feed.lastChild.remove();
    });
  }

  async function demo(el) {
    const now = new Date();
    const sessions = await CF.api(`/api/sessions?cityId=${CF.state.city.id}&from=${isoLocal(now)}&to=${isoLocal(new Date(now.getTime() + 30 * 3600e3))}`);
    el.innerHTML = `
      <div class="grid-2">
        <div class="panel stack">
          <h3>100 зрителей одновременно</h3>
          <p class="muted">Боты ведут курсоры по залу, дерутся за лучшие места по центру и оплачивают. Откройте зал в соседней вкладке — всё видно вживую.</p>
          <select class="input" id="sess">${sessions.slice(0, 80).map((s) => `<option value="${s.id}">${CF.time(s.startTime)} · ${esc(s.movieTitle)} · ${esc(s.cinemaName)}, ${esc(s.hallName)}</option>`).join('')}</select>
          <div class="row"><input class="input" id="cnt" type="number" value="100" min="1" max="200" style="width:110px"><button class="btn btn-primary" id="bots">Запустить ботов</button><button class="btn" id="openHall">Открыть зал</button></div>
          <div class="kpis" id="botStats"></div>
        </div>
        <div class="panel stack">
          <h3>Сценарии для защиты</h3>
          <button class="btn" id="seed">Демо-продажи за 2 недели (для графиков)</button>
          <button class="btn" id="fill">Полный зал → лист ожидания</button>
          <button class="btn" id="soon">Сеанс через час → напоминание в Telegram</button>
          <div id="demoOut"></div>
        </div>
        <div class="panel"><h3>Продажи выбранного сеанса по минутам</h3><div class="chart-box"><canvas id="tl"></canvas></div></div>
        <div class="panel"><h3>Лента продаж</h3><div class="feed" id="feed"><p class="muted">Ждём покупок…</p></div></div>
      </div>`;
    const sel = CF.$('#sess', el), out = CF.$('#demoOut', el);
    const renderBots = (b) => {
      CF.$('#botStats', el).innerHTML = `
        <div class="kpi"><span class="small muted">Боты</span><b>${b.finished}/${b.bots}</b></div>
        <div class="kpi"><span class="small muted">Купили</span><b style="color:var(--ok)">${b.success}</b></div>
        <div class="kpi"><span class="small muted">Конфликтов за место</span><b>${b.conflicts}</b></div>
        <div class="kpi"><span class="small muted">Ошибок оплаты</span><b>${b.failed}</b></div>
        <div class="kpi"><span class="small muted">Двойных продаж</span><b style="color:${b.doubleSales ? 'var(--danger)' : 'var(--ok)'}">${b.running ? '…' : b.doubleSales}</b></div>`;
    };
    renderBots(await CF.api('/api/admin/bots'));
    if (CF.ws.available()) CF.ws.subscribe('/topic/admin/bots', renderBots); else CF.every(1500, async () => renderBots(await CF.api('/api/admin/bots', { quiet: true })));
    CF.$('#openHall', el).addEventListener('click', () => window.open('#/session/' + sel.value, '_blank'));
    CF.$('#bots', el).addEventListener('click', async () => {
      window.open('#/session/' + sel.value, '_blank');
      renderBots(await CF.api('/api/admin/bots', { body: { sessionId: +sel.value, count: +CF.$('#cnt', el).value } }));
    });
    const run = (btn, fn) => CF.$(btn, el).addEventListener('click', async (e) => {
      e.target.disabled = true;
      try { out.innerHTML = await fn(); } catch (err) { out.innerHTML = `<p class="note err">${esc(err.message)}</p>`; }
      e.target.disabled = false;
    });
    run('#seed', async () => { const r = await CF.api('/api/admin/demo/seed', { body: {} }); return `<p class="note">Создано ${r.tickets} билетов за 2 недели. Откройте «Аналитику».</p>`; });
    run('#fill', async () => {
      const r = await CF.api('/api/admin/demo/fill', { body: {} });
      return `<p class="note">«${esc(r.movie)}», ${esc(r.cinema)}, ${esc(r.hall)} — ${CF.dateTime(r.startTime)}.<br>${esc(r.note)}. Встаньте в очередь с другого аккаунта.</p><a class="btn btn-sm" style="margin-top:8px" target="_blank" href="#/session/${r.sessionId}">Открыть зал</a>`;
    });
    run('#soon', async () => {
      const s = await CF.api('/api/sessions/demo-soon', { body: {} });
      return `<p class="note">Сеанс «${esc(s.movieTitle)}» в ${CF.time(s.startTime)} (${esc(s.cinemaName)}). Купите на него билет с привязанным Telegram — напоминание придёт в течение 30 секунд.</p><a class="btn btn-sm" style="margin-top:8px" href="#/session/${s.id}">Купить билет</a>`;
    });
    const tl = chart(CF.$('#tl', el), { type: 'line', data: { labels: [], datasets: [{ label: 'Продано за минуту', data: [], borderColor: brass(), backgroundColor: 'transparent', tension: .35, pointRadius: 0 }] },
      options: { maintainAspectRatio: false, animation: { duration: 300 }, plugins: { legend: { display: false } }, scales: { y: { beginAtZero: true, ticks: { precision: 0 } } } } });
    const loadTl = async () => {
      if (!tl) return;
      const d = await CF.api(`/api/admin/analytics/session/${sel.value}/timeline`, { quiet: true }).catch(() => null);
      if (!d) return;
      tl.data.labels = d.map((x) => x.minute.slice(0, 5)); tl.data.datasets[0].data = d.map((x) => x.sold); tl.update();
    };
    sel.addEventListener('change', loadTl); loadTl(); CF.every(3000, loadTl);
    liveFeed(CF.$('#feed', el));
  }

  async function chaos(el) {
    el.innerHTML = `
      <div class="grid-2">
        <div class="panel stack">
          <h3>Кнопка хаоса: платёжный сервис</h3>
          <p class="muted">FAIL — каждая оплата падает с ошибкой. SLOW — оплата «висит» 6 секунд, а booking-service ждёт только 3 (таймаут). Когда падает больше половины последних вызовов (минимум 4), Circuit Breaker размыкается и перестаёт дёргать упавший сервис.</p>
          <div class="seg" id="mode"><button data-m="OK">Работает</button><button data-m="SLOW">Тормозит</button><button data-m="FAIL" class="FAIL">Упал</button></div>
          <p class="small muted" id="payStats"></p>
          <p class="note">Для полной остановки сервиса: <b>docker compose stop concession-service</b> — сайт покажет «Плёнка оборвалась» на экране бара, а билеты продолжат продаваться без бара.</p>
        </div>
        <div class="panel"><h3>Circuit Breaker'ы booking-service</h3><p class="small muted">Обновляется каждые 2 секунды из Spring Boot Actuator</p><div id="cbs" style="margin-top:8px"></div></div>
      </div>`;
    const seg = CF.$('#mode', el);
    const showMode = (r) => {
      seg.querySelectorAll('button').forEach((b) => b.classList.toggle('on', b.dataset.m === r.mode));
      CF.$('#payStats', el).textContent = `Проведено платежей: ${r.processed} · отклонено: ${r.failed}`;
    };
    showMode(await CF.api('/api/payments/chaos'));
    seg.addEventListener('click', async (e) => {
      const b = e.target.closest('button'); if (!b) return;
      showMode(await CF.api('/api/payments/chaos', { body: { mode: b.dataset.m } }));
      CF.toast(b.dataset.m === 'OK' ? 'Платёжный сервис снова работает' : 'Хаос включён: ' + b.textContent, b.dataset.m === 'OK' ? 'ok' : 'err');
    });
    const names = { payment: 'Оплата', pricing: 'Цены', concession: 'Бар', user: 'Бонусы', movie: 'Каталог' };
    const load = async () => {
      const r = await CF.api('/ops/booking/circuitbreakers', { quiet: true }).catch(() => null);
      if (!r) { CF.$('#cbs', el).innerHTML = '<p class="muted">booking-service недоступен</p>'; return; }
      CF.$('#cbs', el).innerHTML = Object.entries(r.circuitBreakers || {}).map(([k, v]) => `
        <div class="cb"><div><b>${names[k] || k}</b><p class="small muted">вызовов ${v.bufferedCalls}, ошибок ${v.failedCalls}, отказов в открытом состоянии ${v.notPermittedCalls}, сбоев ${v.failureRate}</p></div>
        <span class="state ${v.state}">${v.state}</span></div>`).join('');
    };
    load(); CF.every(2000, load);
    CF.every(3000, async () => { const r = await CF.api('/api/payments/chaos', { quiet: true }).catch(() => null); if (r) showMode(r); });
  }

  async function config(el) {
    const r = await CF.api('/api/pricing/rules');
    el.innerHTML = `
      <div class="grid-2">
        <div class="panel stack">
          <h3>Правила цен из Spring Cloud Config</h3>
          <table class="t">${Object.entries(r).map(([k, v]) => `<tr><td>${esc(k)}</td><td class="num"><b>${esc(v)}</b></td></tr>`).join('')}</table>
        </div>
        <div class="panel stack">
          <h3>Обновление без перезапуска</h3>
          <p class="muted">1. Откройте в VS Code файл <b>config-repo/pricing-service.yml</b>.<br>2. Поменяйте, например, <b>center-coefficient</b> или <b>banner</b> и сохраните.<br>3. Нажмите кнопку — pricing-service перечитает настройки из Config Server, а цены на схеме зала и бегущая строка на главной изменятся сразу.</p>
          <button class="btn btn-primary" id="refresh">Обновить конфигурацию</button>
          <div id="refOut"></div>
        </div>
      </div>`;
    CF.$('#refresh', el).addEventListener('click', async () => {
      try {
        const changed = await CF.api('/ops/pricing-service/refresh', { body: {} });
        CF.$('#refOut', el).innerHTML = `<p class="note">${changed.length ? 'Изменились: ' + changed.map(esc).join(', ') : 'Изменений нет — сохраните файл и попробуйте снова'}</p>`;
        if (changed.length) setTimeout(() => CF.route(), 1500);
      } catch (e) { CF.$('#refOut', el).innerHTML = `<p class="note err">${esc(e.message)}</p>`; }
    });
  }

  async function promos(el) {
    const render = async () => {
      const list = await CF.api('/api/promos');
      el.innerHTML = `
        <div class="grid-2">
          <div class="panel"><h3>Промокоды</h3><table class="t" style="margin-top:10px"><tr><th>Код</th><th>Скидка</th><th>Условия</th><th>Использован</th><th></th></tr>
            ${list.map((p) => `<tr><td><b>${esc(p.code)}</b><br><span class="small muted">${esc(p.description || '')}</span></td>
              <td>${p.type === 'PERCENT' ? p.amount + '%' : CF.money(p.amount)}</td>
              <td class="small">${[p.dayOfWeek && 'день: ' + p.dayOfWeek, p.morningOnly && 'утро', p.validUntil && 'до ' + p.validUntil].filter(Boolean).join(', ') || '—'}</td>
              <td class="num">${p.uses}${p.maxUses ? '/' + p.maxUses : ''}</td>
              <td><button class="btn btn-sm btn-danger" data-del="${esc(p.code)}">Удалить</button></td></tr>`).join('')}</table></div>
          <form class="panel stack" id="pf"><h3>Новый промокод</h3>
            <div class="field"><label>Код</label><input class="input" name="code" required placeholder="BIRTHDAY"></div>
            <div class="row"><select class="input" name="type" style="flex:1"><option value="PERCENT">Процент</option><option value="FIXED">Фиксированная, ₽</option></select>
              <input class="input" name="amount" type="number" required placeholder="20" style="flex:1"></div>
            <div class="field"><label>Описание</label><input class="input" name="description" placeholder="Скидка в день рождения"></div>
            <div class="row"><select class="input" name="dayOfWeek" style="flex:1"><option value="">Любой день</option>${['MONDAY', 'TUESDAY', 'WEDNESDAY', 'THURSDAY', 'FRIDAY', 'SATURDAY', 'SUNDAY'].map((d, i) => `<option value="${d}">${['Пн', 'Вт', 'Ср', 'Чт', 'Пт', 'Сб', 'Вс'][i]}</option>`).join('')}</select>
              <input class="input" name="maxUses" type="number" placeholder="Лимит (0 — без)" style="flex:1"></div>
            <div class="row"><input class="input" name="validUntil" type="date" style="flex:1"><label class="row small"><input type="checkbox" name="morningOnly"> только утро</label></div>
            <button class="btn btn-primary">Создать</button></form>
        </div>`;
      el.querySelectorAll('[data-del]').forEach((b) => b.addEventListener('click', async () => { await CF.api('/api/promos/' + b.dataset.del, { method: 'DELETE' }); render(); }));
      CF.$('#pf', el).addEventListener('submit', async (e) => {
        e.preventDefault();
        const f = new FormData(e.target);
        try {
          await CF.api('/api/promos', { body: { code: f.get('code'), type: f.get('type'), amount: +f.get('amount'), description: f.get('description'),
            dayOfWeek: f.get('dayOfWeek') || null, maxUses: +f.get('maxUses') || 0, validUntil: f.get('validUntil') || null, morningOnly: !!f.get('morningOnly'), active: true } });
          CF.toast('Промокод создан', 'ok'); render();
        } catch (err) { CF.toast(err.message, 'err'); }
      });
    };
    await render();
  }

  async function system(el) {
    const host = location.hostname;
    const load = async () => {
      const list = await CF.api('/api/system/services', { quiet: true }).catch(() => []);
      CF.$('#svc', el).innerHTML = list.map((s) => `<div class="cb"><div><b>${esc(s.service.toLowerCase())}</b><p class="small muted">${s.hosts.map(esc).join(', ')}</p></div><span class="state UP">${s.instances} ${CF.plural(s.instances, 'экземпляр', 'экземпляра', 'экземпляров')}</span></div>`).join('') || '<p class="muted">Eureka недоступна</p>';
    };
    el.innerHTML = `
      <div class="grid-2">
        <div class="panel"><h3>Зарегистрировано в Eureka</h3><div id="svc"></div></div>
        <div class="panel stack"><h3>Инструменты</h3>
          <a class="btn" target="_blank" href="http://${host}:8761">Eureka Dashboard</a>
          <a class="btn" target="_blank" href="/swagger-ui.html">Swagger UI всех сервисов</a>
          <a class="btn" target="_blank" href="http://${host}:8888/pricing-service/default">Config Server: pricing-service</a>
          <a class="btn" target="_blank" href="/actuator/health">Actuator Gateway: health</a>
          <a class="btn" target="_blank" href="http://${host}:8082/actuator/metrics">Actuator booking-service: метрики</a>
          <a class="btn" target="_blank" href="http://${host}:8025">MailHog: письма с билетами</a>
        </div>
      </div>`;
    load(); CF.every(4000, load);
  }

  // ================= экран бара (фишка №4) =================
  CF.pages.bar = async (el) => {
    if (!guard(el, ['ADMIN', 'BAR'])) return;
    const next = { NEW: ['COOKING', 'Начать готовить'], COOKING: ['DELIVERING', 'Несём к креслу'], DELIVERING: ['DELIVERED', 'Доставлено'] };
    el.innerHTML = `<h1>Бар</h1><p class="hero-sub">Заказы к креслам. Зритель получает статус в Telegram при каждом шаге.</p><div class="kanban" id="kb" style="margin-top:22px"></div>`;
    const render = async () => {
      const list = await CF.api('/api/bar/orders');
      const col = (st, title) => `<div class="col"><h3>${title} · ${list.filter((o) => o.status === st).length}</h3>${list.filter((o) => o.status === st).map((o) => `
        <div class="order"><b>${esc(o.hallName)} · ${esc(o.seatLabel)}</b><span class="small muted">${esc(o.cinemaName)} · «${esc(o.movieTitle)}» в ${CF.time(o.startTime)}</span>
        <span>${esc(o.itemsText)}</span><span class="small num">${CF.money(o.total)} · #${o.id}</span>
        <button class="btn btn-sm btn-primary" data-id="${o.id}" data-next="${next[st][0]}">${next[st][1]}</button></div>`).join('') || '<p class="muted small">Пусто</p>'}</div>`;
      CF.$('#kb', el).innerHTML = col('NEW', 'Новые') + col('COOKING', 'Готовятся') + col('DELIVERING', 'Несём');
      el.querySelectorAll('[data-next]').forEach((b) => b.addEventListener('click', async () => {
        await CF.api(`/api/bar/orders/${b.dataset.id}/status`, { method: 'PATCH', body: { status: b.dataset.next } }); render();
      }));
    };
    await render();
    CF.every(3000, () => render().catch(() => {}));
  };

  // ================= контроль на входе (фишка №10) =================
  CF.pages.control = async (el) => {
    if (!guard(el, ['ADMIN', 'CONTROLLER'])) return;
    const now = new Date();
    const sessions = await CF.api(`/api/sessions?cityId=${CF.state.city.id}&from=${isoLocal(new Date(now.getTime() - 3 * 3600e3))}&to=${isoLocal(new Date(now.getTime() + 6 * 3600e3))}`).catch(() => []);
    el.innerHTML = `
      <h1>Контроль</h1><p class="hero-sub">Наведите камеру на QR-код билета. Можно ввести код вручную.</p>
      <div class="grid-2" style="margin-top:22px">
        <div class="stack">
          <select class="input" id="ctrlSess"><option value="">Любой сеанс</option>${sessions.map((s) => `<option value="${s.id}">${CF.time(s.startTime)} · ${esc(s.movieTitle)} · ${esc(s.cinemaName)}, ${esc(s.hallName)}</option>`).join('')}</select>
          <div id="reader" class="panel"></div>
          <form class="row" id="manual"><input class="input" id="code" placeholder="Код билета" style="flex:1"><button class="btn btn-primary">Проверить</button></form>
          <p class="small muted">Камера работает на localhost или по HTTPS. С телефона откройте сайт через ngrok или используйте Telegram-бота: контролёр отправляет фото QR-кода.</p>
        </div>
        <div id="result"><div class="empty">Ждём билет</div></div>
      </div>`;
    const beep = (ok) => {
      try {
        const a = new (window.AudioContext || window.webkitAudioContext)(); const o = a.createOscillator(); const g = a.createGain();
        o.frequency.value = ok ? 880 : 220; o.connect(g); g.connect(a.destination); g.gain.value = .15; o.start(); o.stop(a.currentTime + (ok ? .15 : .4));
      } catch (e) { /* без звука */ }
    };
    let busy = false;
    const check = async (code) => {
      if (busy) return; busy = true;
      try {
        const r = await CF.api('/api/control/scan', { body: { code, sessionId: +CF.$('#ctrlSess', el).value || null } });
        const ok = r.result === 'OK';
        beep(ok);
        CF.$('#result', el).innerHTML = `<div class="scan-result ${ok ? 'OK' : 'bad'}"><div class="big">${ok ? 'Проходите' : { ALREADY_USED: 'Уже использован', WRONG_SESSION: 'Не тот сеанс', REFUNDED: 'Возвращён', NOT_FOUND: 'Не найден', EXPIRED: 'Сеанс прошёл' }[r.result] || 'Отказ'}</div>
          <p style="margin-top:12px;font-size:18px">${esc(r.message)}</p>${r.ticket ? `<p style="margin-top:8px;opacity:.85">«${esc(r.ticket.movieTitle)}» · ${CF.dateTime(r.ticket.startTime)} · ${esc(r.ticket.buyerName || '')}</p>` : ''}</div>`;
      } catch (e) { CF.toast(e.message, 'err'); }
      setTimeout(() => { busy = false; }, 1800);
    };
    CF.$('#manual', el).addEventListener('submit', (e) => { e.preventDefault(); const v = CF.$('#code', el).value.trim(); if (v) check(v); });
    if (window.Html5Qrcode) {
      const qr = new Html5Qrcode('reader');
      qr.start({ facingMode: 'environment' }, { fps: 10, qrbox: 230 }, (text) => check(text), () => {})
        .catch(() => { CF.$('#reader', el).innerHTML = '<p class="muted">Камера недоступна. Введите код вручную.</p>'; });
      CF.onLeave(() => { try { qr.stop().catch(() => {}); } catch (e) { /* уже остановлен */ } });
    } else CF.$('#reader', el).innerHTML = '<p class="muted">Сканер загружается из интернета. Введите код вручную.</p>';
  };
})();
