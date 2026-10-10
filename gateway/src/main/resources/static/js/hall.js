/* CineFlow — живая схема зала, курсоры зрителей, оплата, совместный поход */
(function () {
  'use strict';
  const CF = window.CF, esc = CF.esc;
  const clientId = 'c-' + Math.random().toString(36).slice(2, 9);
  const myColor = `hsl(${Math.floor(Math.random() * 360)} 85% 66%)`;

  // ---------------- билет-корешок (используется и на странице билетов) ----------------
  CF.ticketStub = (t, actions = '') => `
    <article class="stub">
      <div>
        <span class="status ${t.status}">${{ PAID: 'Действует', USED: 'Использован', REFUNDED: 'Возвращён' }[t.status] || t.status}</span>
        <h3 style="margin-top:8px">${esc(t.movieTitle)}</h3>
        <p class="where">${esc(t.cinemaName)} · ${esc(t.hallName)}</p>
        <p class="where">${CF.dateTime(t.startTime)}</p>
        <p class="seatbig">Ряд ${t.row} · Место ${t.seat}</p>
        <p class="small muted">${CF.money(t.price)}${t.refundAmount ? ' · возвращено ' + CF.money(t.refundAmount) : ''}</p>
        ${actions}
      </div>
      <div class="qr"><img src="/api/tickets/${t.id}/qr.png?size=240" alt="QR-код билета" loading="lazy"><p class="small muted" style="margin-top:6px;word-break:break-all">${t.id.slice(0, 8)}</p></div>
    </article>`;

  const screenSvg = `<svg viewBox="0 0 600 70" preserveAspectRatio="none" aria-hidden="true">
      <defs><linearGradient id="beam" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="var(--screen-glow)"/><stop offset="1" stop-color="transparent"/></linearGradient></defs>
      <path d="M10 40 Q300 0 590 40 L590 70 Q300 34 10 70 Z" fill="url(#beam)" opacity=".55"/>
      <path d="M10 40 Q300 0 590 40" fill="none" stroke="var(--text)" stroke-width="3" stroke-linecap="round"/></svg>`;

  // ---------------- страница сеанса ----------------
  CF.pages.session = async (el, [sid]) => {
    const sessionId = +sid;
    const me = () => (CF.state.user ? 'u:' + CF.state.user.id : null);
    let data = await CF.api(`/api/booking/sessions/${sessionId}/map?userId=${CF.state.user ? CF.state.user.id : ''}`);
    const s = data.session;
    const cells = new Map(data.seats.map((c) => [c.row + '-' + c.seat, c]));
    let expiresAt = data.myHoldExpiresAt;

    el.innerHTML = `
      <div class="session-head">
        <img src="${esc(s.posterUrl)}" alt="">
        <div style="flex:1;min-width:220px">
          <a href="#/movie/${s.movieId}" style="text-decoration:none"><h2>${esc(s.movieTitle)}</h2></a>
          <p class="muted">${CF.dateTime(s.startTime)} · ${esc(s.cinemaName)} · ${esc(s.hallName)} (${esc(s.hallType)})</p>
        </div>
        <div class="row" id="factors"></div>
      </div>
      <div class="stats-bar"><div class="row" style="justify-content:space-between;margin-bottom:8px">
        <span id="statsText" class="num"></span><span class="small muted" id="liveState"></span></div>
        <div class="track"><div class="fill" id="statsFill"></div></div></div>
      <div class="hall-layout">
        <div class="hall-wrap" id="hallWrap">
          <div class="screen">${screenSvg}<div class="screen-label">экран</div></div>
          <div class="hall" id="hall"><div class="cursors" id="cursors"></div></div>
          <div class="legend"><span><i></i>Свободно</span><span><i class="l-mine"></i>Ваше</span><span><i class="l-held"></i>Выбирает другой</span><span><i class="l-group"></i>Совместный поход</span><span><i class="l-sold"></i>Продано</span></div>
        </div>
        <aside class="side" id="side"></aside>
      </div>
      <div class="tooltip" id="tip"></div>`;

    CF.$('#factors', el).innerHTML = (data.factors || []).map((f) => `<span class="chip">${esc(f)}</span>`).join('');
    const hall = CF.$('#hall', el), tip = CF.$('#tip', el), side = CF.$('#side', el);

    // --- отрисовка мест ---
    const centerRow = (r) => r > s.rows * 0.3 && r <= Math.ceil(s.rows * 0.75);
    const centerSeat = (c) => c > s.seatsPerRow * 0.25 && c <= Math.ceil(s.seatsPerRow * 0.75);
    let rowsHtml = '';
    for (let r = 1; r <= s.rows; r++) {
      rowsHtml += `<div class="hall-row"><span class="row-num">${r}</span>`;
      for (let c = 1; c <= s.seatsPerRow; c++) rowsHtml += `<button class="seat" data-r="${r}" data-c="${c}" aria-label="Ряд ${r}, место ${c}"></button>`;
      rowsHtml += `<span class="row-num">${r}</span></div>`;
    }
    hall.insertAdjacentHTML('beforeend', rowsHtml);
    const seatEl = (r, c) => hall.querySelector(`.seat[data-r="${r}"][data-c="${c}"]`);
    const paint = (cell, flash) => {
      const b = seatEl(cell.row, cell.seat);
      if (!b) return;
      b.className = 'seat ' + cell.state + (cell.state === 'FREE' && centerRow(cell.row) && centerSeat(cell.seat) ? ' center' : '') + (flash ? ' flash' : '');
      b.dataset.price = cell.price;
      b.disabled = cell.state === 'SOLD';
      b.setAttribute('aria-label', `Ряд ${cell.row}, место ${cell.seat}, ${{ FREE: CF.money(cell.price), MINE: 'выбрано вами', HELD: 'выбирает другой зритель', GROUP: 'совместный поход', SOLD: 'продано' }[cell.state]}`);
    };
    cells.forEach((c) => paint(c));

    const mine = () => [...cells.values()].filter((c) => c.state === 'MINE');
    const updateStats = (st) => {
      CF.$('#statsText', el).innerHTML = `Продано <b>${st.sold}</b> из ${st.total} · ${st.percent}%${st.held ? ` · выбирают сейчас: ${st.held}` : ''}`;
      CF.$('#statsFill', el).style.width = st.percent + '%';
    };
    updateStats(data);

    // --- боковая панель ---
    let timerT = null;
    const renderSide = async () => {
      const sel = mine().sort((a, b) => a.row - b.row || a.seat - b.seat);
      clearInterval(timerT);
      const allTaken = [...cells.values()].every((c) => c.state !== 'FREE');
      if (!sel.length && allTaken) {
        let pos = null;
        if (CF.state.user) pos = await CF.api(`/api/waitlist?sessionId=${sessionId}&userId=${CF.state.user.id}`, { quiet: true }).catch(() => null);
        side.innerHTML = `<div class="panel stack"><h3>Зал полностью продан</h3>
          ${pos ? `<p>Вы в листе ожидания: <b>${pos.position || 1}-й</b> из ${pos.queue || 1}. Как только место освободится, мы удержим его за вами на 10 минут и пришлём уведомление в Telegram.</p>
                   <button class="btn" id="wlLeave">Выйти из очереди</button>`
                : `<p class="muted">Встаньте в лист ожидания: если кто-то вернёт билет или не оплатит бронь, место достанется первому в очереди.</p>
                   <button class="btn btn-primary" id="wlJoin">Встать в очередь</button>`}</div>`;
        const j = CF.$('#wlJoin', side), l = CF.$('#wlLeave', side);
        j && j.addEventListener('click', async () => {
          const u = await CF.requireUser('Войдите, чтобы встать в очередь');
          await CF.api('/api/waitlist', { body: { sessionId, userId: u.id, userName: u.name } });
          CF.toast('Вы в листе ожидания', 'ok'); renderSide();
        });
        l && l.addEventListener('click', async () => { await CF.api(`/api/waitlist?sessionId=${sessionId}&userId=${CF.state.user.id}`, { method: 'DELETE' }); renderSide(); });
        return;
      }
      if (!sel.length) {
        side.innerHTML = `<div class="panel"><h3>Выберите места</h3><p class="muted" style="margin-top:8px">Нажмите на кресло — оно закрепится за вами на 5 минут, остальные зрители увидят его занятым. Цена зависит от ряда, времени и заполненности зала.</p>
          <p class="small muted" style="margin-top:12px">Цены от ${CF.money(data.priceMin)} до ${CF.money(data.priceMax)}</p></div>`;
        return;
      }
      const sum = sel.reduce((a, c) => a + c.price, 0);
      side.innerHTML = `<div class="panel stack">
        <div class="row" style="justify-content:space-between"><h3>${sel.length} ${CF.plural(sel.length, 'место', 'места', 'мест')}</h3><span class="timer" id="timer"></span></div>
        <div class="sel-list">${sel.map((c) => `<div class="sel-item"><span>Ряд ${c.row}, место ${c.seat}</span><b class="num">${CF.money(c.price)}</b></div>`).join('')}</div>
        <div class="line total"><span>Итого</span><span class="num">${CF.money(sum)}</span></div>
        <button class="btn btn-primary btn-block" id="toPay">Перейти к оплате</button>
        ${sel.length >= 2 ? '<button class="btn btn-block" id="group">Позвать друзей — каждый платит сам</button>' : ''}
        <button class="btn btn-sm" id="clear">Снять выбор</button></div>`;
      CF.$('#toPay', side).addEventListener('click', () => CF.checkout({ sessionId, seats: sel.map((c) => ({ row: c.row, seat: c.seat })), onDone: reload }));
      CF.$('#clear', side).addEventListener('click', async () => { await CF.api('/api/booking/release', { body: { sessionId, userId: CF.state.user.id, seats: [] } }); reload(); });
      const g = CF.$('#group', side);
      g && g.addEventListener('click', () => createGroup(sel));
      const tick = () => {
        const t = CF.$('#timer', side); if (!t || !expiresAt) return;
        const left = Math.max(0, expiresAt - Date.now());
        t.textContent = `${Math.floor(left / 60000)}:${String(Math.floor(left / 1000) % 60).padStart(2, '0')}`;
        t.classList.toggle('low', left < 60000);
        if (left <= 0) { clearInterval(timerT); CF.toast('Время удержания вышло, места освобождены', 'err'); reload(); }
      };
      tick(); timerT = setInterval(tick, 1000);
    };
    CF.onLeave(() => clearInterval(timerT));

    const reload = async () => {
      data = await CF.api(`/api/booking/sessions/${sessionId}/map?userId=${CF.state.user ? CF.state.user.id : ''}`);
      data.seats.forEach((c) => { cells.set(c.row + '-' + c.seat, c); paint(c); });
      expiresAt = data.myHoldExpiresAt;
      updateStats(data);
      renderSide();
    };
    renderSide();

    const createGroup = async (sel) => {
      const u = CF.state.user;
      try {
        const g = await CF.api('/api/groups', { body: { organizerId: u.id, organizerName: u.name, sessionId, seats: sel.map((c) => ({ row: c.row, seat: c.seat })) } });
        const link = `${location.origin}/#/group/${g.id}`;
        CF.modal(`<div class="sheet-head"><div><h2>Совместный поход</h2><p class="muted" style="margin-top:6px">Места держатся 10 минут. Отправьте ссылку друзьям — каждый выберет своё кресло и оплатит сам.</p></div><button class="close" data-close>×</button></div>
          <div class="stack"><input class="input" value="${esc(link)}" readonly id="glink">
          <div class="row"><button class="btn btn-primary" id="gcopy">Скопировать ссылку</button>
          <a class="btn" target="_blank" rel="noopener" href="https://t.me/share/url?url=${encodeURIComponent(link)}&text=${encodeURIComponent('Идём на «' + s.movieTitle + '»? Выбери своё место:')}">Отправить в Telegram</a>
          <a class="btn" href="#/group/${g.id}" data-close>Открыть статус</a></div></div>`);
        CF.$('#gcopy').addEventListener('click', () => { navigator.clipboard && navigator.clipboard.writeText(link); CF.toast('Ссылка скопирована', 'ok'); });
        reload();
      } catch (e) { CF.toast(e.message, 'err'); }
    };

    // --- клики по креслам ---
    hall.addEventListener('click', async (e) => {
      const b = e.target.closest('.seat'); if (!b) return;
      const r = +b.dataset.r, c = +b.dataset.c;
      const cell = cells.get(r + '-' + c);
      if (cell.state === 'SOLD') return;
      if (cell.state === 'HELD' || cell.state === 'GROUP') { CF.toast('Это место сейчас выбирает другой зритель'); return; }
      const u = await CF.requireUser('Войдите, чтобы выбрать места');
      try {
        if (cell.state === 'MINE') {
          await CF.api('/api/booking/release', { body: { sessionId, userId: u.id, seats: [{ row: r, seat: c }] } });
          cell.state = 'FREE'; cell.by = null;
        } else {
          const res = await CF.api('/api/booking/hold', { body: { sessionId, userId: u.id, seats: [{ row: r, seat: c }] } });
          cell.state = 'MINE'; cell.by = me(); expiresAt = res.expiresAt;
        }
        paint(cell, true); renderSide();
      } catch (err) { CF.toast(err.message, 'err'); reload(); }
    });

    // --- подсказка с ценой ---
    hall.addEventListener('mousemove', (e) => {
      const b = e.target.closest('.seat');
      if (!b) { tip.classList.remove('on'); return; }
      const cell = cells.get(b.dataset.r + '-' + b.dataset.c);
      const zone = cell.row <= 2 ? 'Первые ряды — дешевле' : centerRow(cell.row) && centerSeat(cell.seat) ? 'Центр зала — лучший звук и обзор' : '';
      tip.innerHTML = `Ряд ${cell.row}, место ${cell.seat}<br><b>${CF.money(cell.price)}</b>${zone ? `<br><span class="small muted">${zone}</span>` : ''}`;
      tip.style.left = Math.min(innerWidth - 220, e.clientX + 18) + 'px';
      tip.style.top = (e.clientY + 18) + 'px';
      tip.classList.add('on');
    });
    hall.addEventListener('mouseleave', () => tip.classList.remove('on'));

    // --- геометрия: единицы зала <-> пиксели ---
    const geo = () => {
      const a = seatEl(1, 1), b2 = seatEl(Math.min(2, s.rows), Math.min(2, s.seatsPerRow));
      const px = s.seatsPerRow > 1 ? b2.offsetLeft - a.offsetLeft : a.offsetWidth;
      const py = s.rows > 1 ? b2.offsetTop - a.offsetTop : a.offsetHeight;
      return { x0: a.offsetLeft, y0: a.offsetTop, px, py };
    };

    // --- живые курсоры (фишка №20 и №32) ---
    const cursorsEl = CF.$('#cursors', el);
    const remote = new Map();
    const drawCursor = (m) => {
      if (m.clientId === clientId) return;
      let rc = remote.get(m.clientId);
      if (m.leave) { if (rc) { rc.el.remove(); remote.delete(m.clientId); } return; }
      if (m.touch && m.row) {
        const b = seatEl(m.row, m.seat);
        if (b) { b.style.setProperty('--peek', m.color); b.classList.add('peek'); setTimeout(() => b.classList.remove('peek'), 2600); }
        return;
      }
      if (!rc) {
        const d = document.createElement('div');
        d.className = 'rcursor';
        d.innerHTML = `<svg viewBox="0 0 24 24"><path d="M3 2l7 19 2.5-8L21 10z" fill="${m.color}" stroke="#160812" stroke-width="1.2"/></svg><span style="background:${m.color}">${esc(m.name)}</span>`;
        cursorsEl.appendChild(d);
        rc = { el: d };
        remote.set(m.clientId, rc);
      }
      const g = geo();
      rc.el.style.transform = `translate(${g.x0 + m.x * g.px}px, ${g.y0 + m.y * g.py}px)`;
      rc.seen = Date.now();
    };
    CF.every(2000, () => remote.forEach((rc, id) => { if (Date.now() - rc.seen > 10000) { rc.el.remove(); remote.delete(id); } }));

    let lastSent = 0;
    const sendCursor = (e) => {
      if (!CF.ws.connected || Date.now() - lastSent < 60) return;
      lastSent = Date.now();
      const g = geo(), r = hall.getBoundingClientRect();
      const x = (e.clientX - r.left - g.x0) / g.px, y = (e.clientY - r.top - g.y0) / g.py;
      const b = e.target.closest && e.target.closest('.seat');
      CF.ws.send('/app/cursor', { sessionId, clientId, name: CF.state.user ? CF.state.user.name : 'Гость', color: myColor, x, y,
        row: b ? +b.dataset.r : null, seat: b ? +b.dataset.c : null, touch: false, leave: false });
    };
    if (matchMedia('(pointer: fine)').matches) hall.addEventListener('mousemove', sendCursor);
    else hall.addEventListener('touchstart', (e) => {
      const b = e.target.closest('.seat'); if (!b || !CF.ws.connected) return;
      CF.ws.send('/app/cursor', { sessionId, clientId, name: CF.state.user ? CF.state.user.name : 'Гость', color: myColor, x: 0, y: 0, row: +b.dataset.r, seat: +b.dataset.c, touch: true, leave: false });
    }, { passive: true });
    const leave = () => CF.ws.send('/app/cursor', { sessionId, clientId, name: '', color: '', x: 0, y: 0, touch: false, leave: true });
    hall.addEventListener('mouseleave', leave);
    CF.onLeave(leave);

    // --- подписки WebSocket (фишки №1 и №12) ---
    if (CF.ws.available()) {
      CF.ws.subscribe(`/topic/session/${sessionId}/seats`, (m) => {
        const cell = cells.get(m.row + '-' + m.seat); if (!cell) return;
        cell.by = m.by || null;
        cell.state = m.state === 'FREE' ? 'FREE' : m.state === 'SOLD' ? 'SOLD'
          : (m.by && m.by === me()) ? 'MINE' : (m.by && m.by.startsWith('g:')) || m.state === 'GROUP' ? 'GROUP' : 'HELD';
        paint(cell, true);
        if (m.by === me() || m.state === 'FREE' || m.state === 'SOLD') renderSide();
      });
      CF.ws.subscribe(`/topic/session/${sessionId}/stats`, updateStats);
      CF.ws.subscribe(`/topic/session/${sessionId}/cursors`, drawCursor);
      const liveState = (on) => { const l = CF.$('#liveState', el); if (l) l.textContent = on ? '● в реальном времени' : 'переподключение…'; };
      liveState(CF.ws.connected);
      const h = (e) => { liveState(e.detail); if (e.detail) reload(); };
      document.addEventListener('cf-ws', h);
      CF.onLeave(() => document.removeEventListener('cf-ws', h));
    } else {
      CF.$('#liveState', el).textContent = 'обновление каждые 4 секунды';
      CF.every(4000, reload);
    }
  };

  // ---------------- оформление заказа ----------------
  CF.checkout = async ({ sessionId, seats, groupId = null, onDone }) => {
    const u = await CF.requireUser('Войдите, чтобы оплатить');
    let menu = null;
    try { menu = await CF.api('/api/bar/menu', { quiet: true }); } catch (e) { menu = null; }
    const cart = {};
    let promo = '', points = 0, timerT;
    CF.effects.lights(true);
    const m = CF.modal(`<div class="sheet-head"><div><h2>Оформление</h2><p class="muted" id="coSub" style="margin-top:4px"></p></div><button class="close" data-close aria-label="Закрыть">×</button></div>
      <div id="coBody" class="stack"><div class="skeleton" style="height:180px"></div></div>`, {
      dim: true, onClose: () => { clearInterval(timerT); CF.effects.lights(false); },
    });
    const body = CF.$('#coBody', m.el);
    const req = () => ({ userId: u.id, sessionId, seats, promoCode: promo || null, usePoints: points,
      barItems: Object.entries(cart).filter(([, q]) => q > 0).map(([itemId, qty]) => ({ itemId: +itemId, qty })), groupId });

    let q = null;
    const render = () => {
      const s = q.session;
      CF.$('#coSub', m.el).textContent = `${s.movieTitle} · ${CF.dateTime(s.startTime)} · ${s.hallName}`;
      body.innerHTML = `
        <div>${q.tickets.items.map((i) => `<div class="line"><span>Ряд ${i.row}, место ${i.seat}<span class="factors">${esc(i.factors.join(' · ') || 'Базовая цена')}</span></span><b class="num">${CF.money(i.price)}</b></div>`).join('')}
        ${q.tickets.discount ? `<div class="line" style="color:var(--ok)"><span>Промокод ${esc(q.tickets.promoCode)}</span><b class="num">−${CF.money(q.tickets.discount)}</b></div>` : ''}</div>
        <div class="row"><input class="input" id="promo" placeholder="Промокод" value="${esc(promo)}" style="flex:1;min-width:160px"><button class="btn" id="applyPromo">Применить</button></div>
        ${q.maxPoints > 0 ? `<div class="field"><label for="pts">Оплатить баллами: <b id="ptsVal">${points}</b> из ${q.pointsAvailable} (не больше половины билетов)</label>
          <input type="range" id="pts" min="0" max="${q.maxPoints}" step="10" value="${points}"></div>` : ''}
        ${menu ? `<div><h3 style="margin-bottom:10px">Бар к вашему креслу${q.level === 'GOLD' ? ' · попкорн в подарок' : ''}</h3><div class="menu-grid">${menu.map((it) => `
          <div class="menu-item"><span class="emoji">${it.emoji}</span><b style="font-size:14px">${esc(it.name)}</b><span class="small muted">${CF.money(it.price)}</span>
          <div class="qty"><button data-minus="${it.id}" aria-label="Убрать">−</button><b class="num">${cart[it.id] || 0}</b><button data-plus="${it.id}" aria-label="Добавить">+</button></div></div>`).join('')}</div></div>`
          : '<p class="note">Бар сейчас не принимает заказы — можно купить билеты без него.</p>'}
        ${q.bar ? `<div class="line"><span>Бар: ${q.bar.lines.map((l) => l.emoji + '×' + l.qty).join(' ')}</span><b class="num">${CF.money(q.bar.total)}</b></div>` : ''}
        ${points ? `<div class="line"><span>Баллы</span><b class="num">−${CF.money(points)}</b></div>` : ''}
        <div class="line total"><span>К оплате</span><span class="num">${CF.money(q.total)}</span></div>
        <p class="small muted">Начислим ${q.earnPoints} баллов · уровень ${CF.levelName(q.level)} · <span class="timer" id="coTimer"></span></p>
        ${q.messages.map((x) => `<p class="note">${esc(x)}</p>`).join('')}
        <div id="coErr"></div>
        <button class="btn btn-primary btn-block" id="pay" style="padding:16px">Оплатить ${CF.money(q.total)}</button>`;
      CF.$('#applyPromo', body).addEventListener('click', () => { promo = CF.$('#promo', body).value.trim(); refresh(); });
      const p = CF.$('#pts', body);
      p && p.addEventListener('input', () => { CF.$('#ptsVal', body).textContent = p.value; });
      p && p.addEventListener('change', () => { points = +p.value; refresh(); });
      body.querySelectorAll('[data-plus],[data-minus]').forEach((b) => b.addEventListener('click', () => {
        const id = b.dataset.plus || b.dataset.minus;
        cart[id] = Math.max(0, (cart[id] || 0) + (b.dataset.plus ? 1 : -1));
        refresh();
      }));
      CF.$('#pay', body).addEventListener('click', pay);
      clearInterval(timerT);
      const tick = () => {
        const t = CF.$('#coTimer', body); if (!t || !q.holdExpiresAt) return;
        const left = Math.max(0, q.holdExpiresAt - Date.now());
        t.textContent = `места держатся ещё ${Math.floor(left / 60000)}:${String(Math.floor(left / 1000) % 60).padStart(2, '0')}`;
      };
      tick(); timerT = setInterval(tick, 1000);
    };
    const refresh = async () => {
      try { q = await CF.api('/api/booking/quote', { body: req() }); render(); }
      catch (e) { body.innerHTML = `<p class="note err">${esc(e.message)}</p>`; }
    };

    const pay = async () => {
      const btn = CF.$('#pay', body);
      btn.disabled = true; btn.textContent = 'Проводим оплату…';
      try {
        const res = await CF.api('/api/booking/checkout', { body: req() });
        m.close();
        CF.effects.lights(true);
        const c = CF.effects.curtain(`
          <h2 style="color:#fff;text-align:center">Приятного просмотра!</h2>
          <div class="printer"></div>
          <div class="printed">${res.tickets.map((t, i) => CF.ticketStub(t).replace('<article class="stub">', `<article class="stub" style="animation-delay:${1.1 + i * 0.5}s">`)).join('')}</div>
          ${res.messages.map((x) => `<p class="note" style="color:#fff">${esc(x)}</p>`).join('')}
          <p style="color:#fff;opacity:.8;text-align:center">Оплачено ${CF.money(res.paidAmount)}${res.barItems ? ' · бар принесёт к креслу: ' + esc(res.barItems) : ''}.<br>Билеты отправлены на почту${u.telegramLinked ? ' и в Telegram' : ''}.</p>
          <div class="row" style="justify-content:center"><a class="btn btn-primary" href="#/tickets" data-x>Мои билеты</a><button class="btn" style="color:#fff" data-x>Вернуться к залу</button></div>`);
        c.querySelectorAll('[data-x]').forEach((b) => b.addEventListener('click', () => { c.remove(); CF.effects.lights(false); onDone && onDone(); }));
        CF.refreshUser();
      } catch (e) {
        btn.disabled = false; btn.textContent = 'Повторить оплату';
        CF.$('#coErr', body).innerHTML = `<p class="note err">${esc(e.message)}</p>`;
      }
    };
    refresh();
  };

  // ---------------- совместный поход (фишка №9) ----------------
  CF.pages.group = async (el, [gid]) => {
    const render = async () => {
      const g = await CF.api('/api/groups/' + gid);
      const s = g.session;
      el.innerHTML = `
        <div class="session-head"><img src="${esc(s.posterUrl)}" alt=""><div>
          <h2>${esc(g.organizerName)} зовёт в кино</h2>
          <p class="muted">«${esc(s.movieTitle)}» · ${CF.dateTime(s.startTime)} · ${esc(s.cinemaName)}, ${esc(s.hallName)}</p></div></div>
        <div class="stats-bar"><p style="margin-bottom:8px">Оплатили <b>${g.paid} из ${g.total}</b>${g.expired ? ' · время на оплату вышло' : ''}</p>
          <div class="track"><div class="fill" style="width:${g.paid * 100 / g.total}%"></div></div></div>
        <div class="panel" style="margin-top:22px"><h3>Выберите своё место</h3>
          <p class="muted small" style="margin:6px 0 16px">Неоплаченные места держатся до ${CF.time(g.expiresAt)}. Каждый платит только за себя.</p>
          <div class="row">${g.seats.map((x) => x.status === 'WAITING'
            ? `<button class="btn" data-r="${x.row}" data-c="${x.seat}">Ряд ${x.row}, место ${x.seat} — занять</button>`
            : `<span class="chip ${x.status === 'PAID' ? 'on' : ''}">Ряд ${x.row}, место ${x.seat} · ${x.status === 'PAID' ? esc(x.paidBy || 'оплачено') : 'освобождено'}</span>`).join('')}</div></div>
        <div class="row" style="margin-top:18px"><button class="btn" id="share">Скопировать ссылку для друзей</button><a class="btn" href="#/session/${s.id}">Схема зала</a></div>`;
      el.querySelectorAll('[data-r]').forEach((b) => b.addEventListener('click', () =>
        CF.checkout({ sessionId: s.id, seats: [{ row: +b.dataset.r, seat: +b.dataset.c }], groupId: g.id, onDone: render })));
      CF.$('#share', el).addEventListener('click', () => { navigator.clipboard && navigator.clipboard.writeText(location.href); CF.toast('Ссылка скопирована', 'ok'); });
    };
    await render();
    CF.every(5000, () => { if (!document.querySelector('.sheet-backdrop, .curtain')) render().catch(() => {}); });
  };
})();
