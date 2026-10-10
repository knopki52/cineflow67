/* CineFlow — билеты, профиль, «Твой киногод», киноассистент */
(function () {
  'use strict';
  const CF = window.CF, esc = CF.esc;

  const needLogin = (el, why) => {
    el.innerHTML = `<div class="empty"><h2>${esc(why)}</h2><p style="margin-top:8px">Войдите по имени и почте — это займёт 5 секунд.</p><button class="btn btn-primary" id="li">Войти</button></div>`;
    CF.$('#li', el).addEventListener('click', () => CF.login(why, () => CF.route()));
  };

  // ---------------- мои билеты ----------------
  CF.pages.tickets = async (el) => {
    if (!CF.state.user) return needLogin(el, 'Здесь будут ваши билеты');
    const u = CF.state.user;
    const list = await CF.api('/api/tickets?userId=' + u.id);
    const now = Date.now();
    const active = list.filter((t) => t.status === 'PAID' && new Date(t.startTime).getTime() + 3 * 3600e3 > now);
    const past = list.filter((t) => !active.includes(t));
    const actions = (t) => {
      if (t.status === 'PAID' && t.refundPercent > 0) return `<button class="btn btn-sm btn-danger" data-refund="${t.id}" data-amount="${t.refundEstimate}" data-pct="${t.refundPercent}" style="margin-top:10px">Вернуть ${t.refundPercent}% · ${CF.money(t.refundEstimate)}</button>`;
      if (t.status === 'PAID') return '<p class="small muted" style="margin-top:10px">До сеанса меньше 2 часов — вернуть нельзя</p>';
      if (t.status === 'USED') return `<a class="btn btn-sm" href="#/movie/${t.movieId}" style="margin-top:10px">Оставить отзыв</a>`;
      return '';
    };
    el.innerHTML = `
      <h1>Мои билеты</h1>
      <p class="hero-sub">Покажите QR-код контролёру на входе. Возврат: больше суток до сеанса — 100%, от 2 до 24 часов — 50%.</p>
      ${u.telegramLinked ? '' : `<div class="note" style="margin-top:18px;display:flex;gap:12px;align-items:center;flex-wrap:wrap"><span style="flex:1;min-width:200px">Привяжите Telegram — бот пришлёт билет, напомнит о сеансе за час и сообщит, если освободится место из листа ожидания.</span><button class="btn btn-sm btn-primary" id="tgLink">Привязать Telegram</button></div>`}
      <section class="section"><h2>Предстоящие</h2><div class="ticket-list" style="margin-top:16px">${active.map((t) => CF.ticketStub(t, actions(t))).join('') || '<div class="empty">Билетов пока нет. <br><a class="btn btn-primary" href="#/">Выбрать фильм</a></div>'}</div></section>
      ${past.length ? `<section class="section"><h2>История</h2><div class="ticket-list" style="margin-top:16px">${past.map((t) => CF.ticketStub(t, actions(t))).join('')}</div></section>` : ''}`;
    const tg = CF.$('#tgLink', el);
    tg && tg.addEventListener('click', CF.linkTelegram);
    el.querySelectorAll('[data-refund]').forEach((b) => b.addEventListener('click', () => {
      const m = CF.modal(`<div class="sheet-head"><h2>Вернуть билет?</h2><button class="close" data-close>×</button></div>
        <p>Вернём <b>${CF.money(+b.dataset.amount)}</b> (${b.dataset.pct}% от оплаченного). Место сразу станет свободным, а если на сеанс есть очередь — его получит первый в листе ожидания.</p>
        <div class="row" style="margin-top:18px"><button class="btn btn-danger" id="yes">Вернуть билет</button><button class="btn" data-close>Оставить</button></div>`);
      CF.$('#yes', m.el).addEventListener('click', async () => {
        try { await CF.api(`/api/tickets/${b.dataset.refund}/refund`, { body: { userId: u.id } }); m.close(); CF.toast('Билет возвращён, деньги уже в пути', 'ok'); CF.route(); }
        catch (e) { CF.toast(e.message, 'err'); }
      });
    }));
  };

  CF.linkTelegram = async () => {
    const u = await CF.requireUser('Войдите, чтобы привязать Telegram');
    try {
      const r = await CF.api(`/api/users/${u.id}/telegram/code`, { body: {} });
      CF.modal(`<div class="sheet-head"><div><h2>Telegram-бот</h2><p class="muted" style="margin-top:6px">Откройте ссылку и нажмите «Start» — аккаунт привяжется автоматически.</p></div><button class="close" data-close>×</button></div>
        <div class="stack"><a class="btn btn-primary" href="${esc(r.link)}" target="_blank" rel="noopener">Открыть @${esc(r.bot)}</a>
        <p class="small muted">Или отправьте боту команду: <b>/start link_${esc(r.code)}</b></p>
        <button class="btn" data-close id="tgDone">Готово, я привязал</button></div>`);
      CF.$('#tgDone').addEventListener('click', async () => { await CF.refreshUser(); CF.route(); });
    } catch (e) { CF.toast(e.message, 'err'); }
  };

  // ---------------- профиль и бонусы (фишка №5) ----------------
  CF.pages.profile = async (el) => {
    if (!CF.state.user) return needLogin(el, 'Профиль и бонусы');
    const u = await CF.refreshUser();
    const history = await CF.api(`/api/users/${u.id}/points-history`, { quiet: true }).catch(() => []);
    const next = { SILVER: 'Silver: 7% баллами', GOLD: 'Gold: 10% и попкорн в подарок' }[u.nextLevel];
    el.innerHTML = `
      <div class="row" style="justify-content:space-between"><h1>${esc(u.name)}</h1><button class="btn btn-sm" id="logout">Выйти</button></div>
      <p class="hero-sub">${esc(u.email || 'Аккаунт из Telegram')}${u.role !== 'VIEWER' ? ' · роль ' + esc(u.role) : ''}</p>
      <div class="grid-2" style="margin-top:24px">
        <div class="level-card ${u.level}">
          <p>Уровень ${CF.levelName(u.level)} · ${u.percent}% баллами с покупок</p>
          <p class="pts num">${u.points.toLocaleString('ru-RU')}</p><p style="opacity:.8">баллов · 1 балл = 1 ₽, можно оплатить до 50% билета</p>
          ${u.nextLevel ? `<div class="progress"><i style="width:${Math.round(u.progress * 100)}%"></i></div><p class="small" style="margin-top:8px;opacity:.85">До уровня ${next} осталось ${CF.money(u.toNextLevel)} покупок</p>` : '<p style="margin-top:16px">Максимальный уровень. Попкорн — за наш счёт.</p>'}
        </div>
        <div class="panel stack">
          <h3>Мой киногод</h3><p class="muted">Сколько фильмов вы посмотрели, любимый жанр, любимое место в зале и ваш кинотип.</p>
          <div class="row"><a class="btn btn-primary" href="#/wrapped">Смотреть киногод</a><button class="btn" id="demo">Заполнить демо-историей</button></div>
          <h3 style="margin-top:8px">Telegram</h3>
          <p class="muted">${u.telegramLinked ? 'Привязан' + (u.telegramUsername ? ' к @' + esc(u.telegramUsername) : '') + '. Билеты и напоминания приходят в бот.' : 'Не привязан.'}</p>
          ${u.telegramLinked ? '' : '<button class="btn" id="tg">Привязать Telegram</button>'}
        </div>
      </div>
      <section class="section"><h2>Операции с баллами</h2>
        <table class="t" style="margin-top:12px"><tr><th>Дата</th><th>Операция</th><th>Баллы</th></tr>
        ${history.map((h) => `<tr><td>${CF.dateTime(h.createdAt)}</td><td>${{ EARN: 'Начисление', SPEND: 'Оплата баллами', RETURN: 'Возврат баллов', REVOKE: 'Списание за возврат' }[h.type]} · заказ ${h.orderId || '—'}</td><td class="num">${['EARN', 'RETURN'].includes(h.type) ? '+' : '−'}${h.points}</td></tr>`).join('') || '<tr><td colspan="3" class="muted">Операций пока нет</td></tr>'}</table></section>`;
    CF.$('#logout', el).addEventListener('click', () => { CF.setUser(null); CF.go('#/'); });
    const tg = CF.$('#tg', el); tg && tg.addEventListener('click', CF.linkTelegram);
    CF.$('#demo', el).addEventListener('click', async (e) => {
      e.target.disabled = true;
      try { await CF.api('/api/admin/demo/seed?userId=' + u.id, { body: {} }); CF.toast('История за год создана — баллы начислятся через пару секунд', 'ok'); setTimeout(() => CF.route(), 2500); }
      catch (err) { CF.toast(err.message, 'err'); e.target.disabled = false; }
    });
  };

  // ---------------- «Твой киногод» (фишка №25) ----------------
  CF.pages.wrapped = async (el) => {
    if (!CF.state.user) return needLogin(el, 'Ваш киногод');
    const u = CF.state.user;
    const w = await CF.api('/api/wrapped/' + u.id);
    if (!w.films) {
      el.innerHTML = `<div class="empty"><h2>Киногод пока пуст</h2><p style="margin-top:8px">Сходите в кино или заполните демо-историю в профиле.</p><a class="btn btn-primary" href="#/profile">В профиль</a></div>`;
      return;
    }
    const maxG = Math.max(...w.genres.map((g) => g.count));
    const slides = [
      { bg: '#2A1124', html: `<div><p class="kicker rise">CineFlow · ${w.year}</p><h2 class="rise">${esc(u.name)}, это был ваш киногод</h2><p class="kicker rise">Нажмите, чтобы листать</p></div>` },
      { bg: '#5A1F44', html: `<div><p class="kicker rise">Вы посмотрели</p><div class="big rise num">${w.films}</div><p class="kicker rise">${CF.plural(w.films, 'фильм', 'фильма', 'фильмов')} — это ${w.hours} ${CF.plural(w.hours, 'час', 'часа', 'часов')} в темноте зала. Первым был «${esc(w.firstMovie)}».</p></div>` },
      { bg: '#7A3B12', html: `<div><p class="kicker rise">Любимый жанр</p><h2 class="rise">${esc(w.favoriteGenre)}</h2><p class="kicker rise">${w.favoriteGenreShare}% всех ваших сеансов</p>
          <div class="genre-bars rise">${w.genres.map((g) => `<div><span>${esc(g.genre)}</span><i style="width:${g.count * 100 / maxG}%"></i><b>${g.count}</b></div>`).join('')}</div></div>` },
      { bg: '#123B3A', html: `<div><p class="kicker rise">Ваше место в зале</p><div class="big rise">${w.favoriteRow}</div><p class="kicker rise">ряд, ${esc(w.favoriteZone)}. ${w.sameSeatTimes > 1 ? `Одно и то же кресло вы выбрали ${w.sameSeatTimes} раза.` : 'Вы любите пробовать разные места.'}</p></div>` },
      { bg: '#1B1240', html: `<div><p class="kicker rise">После 21:00</p><div class="big rise num">${w.nightSessions}</div><p class="kicker rise">ночных сеансов. Утром — ${w.morningSessions}. Чаще всего вы ходили в ${esc(w.topCinema)}.</p></div>` },
      { bg: '#8A5A08', html: `<div><p class="kicker rise">Съедено</p><div class="big rise">🍿 ${w.popcorn}</div><p class="kicker rise">${CF.plural(w.popcorn, 'ведро', 'ведра', 'вёдер')} попкорна, принесённого прямо к креслу</p></div>` },
      { bg: '#3B0F2E', html: `<div><p class="kicker rise">Ваш кинотип</p><div class="big rise" style="font-size:clamp(80px,22vw,150px)">${w.kinotypeEmoji}</div><h2 class="rise">${esc(w.kinotype)}</h2><p class="kicker rise">${esc(w.kinotypeText)}</p></div>` },
      { bg: '#1A0B16', html: `<div class="stack" style="justify-items:center"><canvas id="shareCard" width="540" height="960" style="width:min(270px,60vw);border-radius:18px;box-shadow:0 20px 60px rgba(0,0,0,.5)"></canvas>
          <div class="row" style="justify-content:center"><button class="btn btn-primary" id="download">Сохранить карточку</button><button class="btn" style="color:#fff" id="again">Ещё раз</button></div></div>` },
    ];
    el.innerHTML = '';
    const st = document.createElement('div');
    st.className = 'story';
    st.innerHTML = `<div class="story-bars">${slides.map(() => '<i><b></b></i>').join('')}</div>
      <button class="close story-close" aria-label="Закрыть">×</button>
      ${slides.map((s, i) => `<section class="slide" style="background:radial-gradient(120% 90% at 50% 0%, ${s.bg}, #0B0409)" data-i="${i}">${s.html}</section>`).join('')}
      <div class="story-nav"><button aria-label="Назад" data-dir="-1"></button><button aria-label="Дальше" data-dir="1"></button></div>`;
    document.body.appendChild(st);
    let i = -1, t0 = 0, raf;
    const DUR = 5200;
    const show = (n) => {
      i = Math.max(0, Math.min(slides.length - 1, n));
      st.querySelectorAll('.slide').forEach((s, k) => s.classList.toggle('on', k === i));
      st.querySelectorAll('.story-bars b').forEach((b, k) => { b.style.width = k < i ? '100%' : '0'; });
      st.querySelector('.story-nav').style.display = i === slides.length - 1 ? 'none' : '';
      t0 = performance.now();
      if (i === slides.length - 1) drawCard();
    };
    const loop = (now) => {
      const bar = st.querySelectorAll('.story-bars b')[i];
      const p = Math.min(1, (now - t0) / DUR);
      if (bar) bar.style.width = p * 100 + '%';
      if (p >= 1 && i < slides.length - 1) show(i + 1);
      raf = requestAnimationFrame(loop);
    };
    const close = () => { cancelAnimationFrame(raf); st.remove(); document.removeEventListener('keydown', key); CF.go('#/profile'); };
    const key = (e) => { if (e.key === 'ArrowRight') show(i + 1); if (e.key === 'ArrowLeft') show(i - 1); if (e.key === 'Escape') close(); };
    document.addEventListener('keydown', key);
    st.querySelector('.story-close').addEventListener('click', close);
    st.querySelector('.story-nav').addEventListener('click', (e) => { const b = e.target.closest('button'); if (b) show(i + +b.dataset.dir); });
    CF.onLeave(() => { cancelAnimationFrame(raf); st.remove(); document.removeEventListener('keydown', key); });
    show(0); raf = requestAnimationFrame(loop);

    function drawCard() {
      const c = st.querySelector('#shareCard'); const x = c.getContext('2d');
      const g = x.createLinearGradient(0, 0, 0, 960); g.addColorStop(0, '#5A1F44'); g.addColorStop(1, '#1A0B16');
      x.fillStyle = g; x.fillRect(0, 0, 540, 960);
      x.fillStyle = '#E3B65C'; x.font = '800 30px Unbounded, sans-serif'; x.fillText('CineFlow · ' + w.year, 44, 80);
      x.fillStyle = '#fff'; x.font = '900 190px Unbounded, sans-serif'; x.fillText(String(w.films), 40, 300);
      x.font = '700 30px Manrope, sans-serif'; x.fillText(CF.plural(w.films, 'фильм', 'фильма', 'фильмов') + ' за год', 48, 350);
      x.font = '120px sans-serif'; x.fillText(w.kinotypeEmoji, 44, 520);
      x.font = '800 36px Unbounded, sans-serif';
      const words = w.kinotype.split(' '); let line = '', y = 600;
      words.forEach((wd) => { if ((line + wd).length > 16) { x.fillText(line, 44, y); line = ''; y += 46; } line += wd + ' '; });
      x.fillText(line, 44, y);
      x.font = '600 26px Manrope, sans-serif'; x.fillStyle = 'rgba(255,255,255,.8)';
      [`Жанр: ${w.favoriteGenre}`, `Ряд ${w.favoriteRow}, ${w.favoriteZone}`, `Попкорн: ${w.popcorn}`, `${w.hours} ч в кино`].forEach((t, k) => x.fillText(t, 48, y + 80 + k * 42));
      st.querySelector('#download').onclick = () => { const a = document.createElement('a'); a.download = 'cineflow-kinogod.png'; a.href = c.toDataURL('image/png'); a.click(); };
      st.querySelector('#again').onclick = () => show(0);
    }
  };

  // ---------------- киноассистент (фишка №28) ----------------
  CF.pages.assistant = async (el) => {
    const examples = ['Хочу комедию в пятницу вечером, недорого, вдвоём', 'Ужастик сегодня после 21', 'Фантастика завтра в IMAX на троих', 'Мультфильм в субботу утром с детьми'];
    el.innerHTML = `
      <h1>Киноассистент</h1>
      <p class="hero-sub">Напишите, как другу: жанр, день, время, сколько вас и бюджет. Ассистент найдёт сеанс в ${esc(CF.inCity(CF.state.city.name))} и места рядом.</p>
      <div class="chat" id="chat" style="margin-top:24px">
        <div class="msg bot">Привет! Что хотите посмотреть?</div>
        <div class="row">${examples.map((x) => `<button class="chip" data-ex="${esc(x)}">${esc(x)}</button>`).join('')}</div>
      </div>
      <form class="composer" id="composer"><input class="input" id="q" placeholder="Например: «Хочу комедию в пятницу вечером, вдвоём»" autocomplete="off"><button class="btn btn-primary">Найти</button></form>`;
    const chat = CF.$('#chat', el);
    const add = (cls, html) => { const d = document.createElement('div'); d.className = 'msg ' + cls; d.innerHTML = html; chat.appendChild(d); d.scrollIntoView({ behavior: 'smooth', block: 'end' }); return d; };
    const ask = async (text) => {
      add('me', esc(text));
      const typing = add('bot', '<span class="typing"><span></span><span></span><span></span></span>');
      try {
        const r = await CF.api('/api/assistant', { body: { text, cityId: CF.state.city.id, userId: CF.state.user && CF.state.user.id } });
        await CF.sleep(500);
        typing.innerHTML = `<div class="row" style="margin-bottom:8px">${r.understood.chips.map((c) => `<span class="chip">${esc(c)}</span>`).join('')}</div>
          <p>${esc(r.reply)}</p>
          ${r.suggestions.map((sg, i) => `<div class="suggest"><img src="${esc(sg.session.posterUrl)}" alt="">
            <div><b>${esc(sg.session.movieTitle)}</b><p class="small">${CF.dateTime(sg.session.startTime)} · ${esc(sg.session.cinemaName)}, ${esc(sg.session.hallName)}</p><p class="small muted">${esc(sg.why)}</p></div>
            <button class="btn btn-primary btn-sm" data-take="${i}">Беру · ${CF.money(sg.total)}</button></div>`).join('')}`;
        typing.querySelectorAll('[data-take]').forEach((b) => b.addEventListener('click', async () => {
          const sg = r.suggestions[+b.dataset.take];
          const u = await CF.requireUser('Войдите, чтобы забронировать');
          try {
            await CF.api('/api/booking/hold', { body: { sessionId: sg.session.id, userId: u.id, seats: sg.seats } });
            CF.go('#/session/' + sg.session.id);
            setTimeout(() => CF.checkout({ sessionId: sg.session.id, seats: sg.seats, onDone: () => CF.route() }), 600);
          } catch (e) { CF.toast(e.message, 'err'); }
        }));
      } catch (e) { typing.innerHTML = esc(e.message); }
    };
    CF.$('#composer', el).addEventListener('submit', (e) => { e.preventDefault(); const v = CF.$('#q', el).value.trim(); if (v) { CF.$('#q', el).value = ''; ask(v); } });
    chat.addEventListener('click', (e) => { const b = e.target.closest('[data-ex]'); if (b) ask(b.dataset.ex); });
  };
})();
