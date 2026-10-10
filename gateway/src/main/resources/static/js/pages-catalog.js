/* CineFlow — афиша, страница фильма, карта кинотеатров */
(function () {
  'use strict';
  const CF = window.CF, esc = CF.esc;

  const days = (n = 7) => Array.from({ length: n }, (_, i) => { const d = new Date(); d.setDate(d.getDate() + i); return d; });
  const dayLabel = (d, i) => i === 0 ? 'Сегодня' : i === 1 ? 'Завтра' : CF.weekday(d);
  const dayRange = (d) => {
    const from = new Date(d); const now = new Date();
    if (CF.isoDate(d) === CF.isoDate(now)) from.setTime(now.getTime()); else from.setHours(0, 0, 0, 0);
    const to = new Date(d); to.setDate(to.getDate() + 1); to.setHours(3, 0, 0, 0);
    const iso = (x) => `${CF.isoDate(x)}T${String(x.getHours()).padStart(2, '0')}:${String(x.getMinutes()).padStart(2, '0')}:00`;
    return { from: iso(from), to: iso(to) };
  };

  const posterCard = (m, extra = '') => `
    <a class="poster-card" href="#/movie/${m.id}">
      <div class="poster">
        <img src="${esc(m.posterUrl)}" alt="Постер «${esc(m.title)}»" loading="lazy">
        <span class="badge age">${esc(m.ageRating)}</span>
        ${m.rating ? `<span class="badge">★ ${m.rating.toFixed(1)}</span>` : ''}
      </div>
      <div><h3>${esc(m.title)}</h3><p class="meta">${esc(m.genre)} · ${m.durationMin} мин</p></div>
      ${extra}
    </a>`;

  // ---------------- карта (фишка №29) ----------------
  CF.renderMap = (el, cinemas, sessions = []) => {
    if (!window.L) {
      el.innerHTML = `<div class="map-fallback">Карта загружается из интернета. Список кинотеатров:<br><br>${cinemas.map((c) => `<b>${esc(c.name)}</b> — ${esc(c.address)}`).join('<br>')}</div>`;
      return;
    }
    const map = L.map(el, { scrollWheelZoom: false, attributionControl: true });
    const dark = document.documentElement.dataset.theme === 'dark';
    L.tileLayer(dark ? 'https://{s}.basemaps.cartocdn.com/dark_all/{z}/{x}/{y}{r}.png' : 'https://{s}.basemaps.cartocdn.com/light_all/{z}/{x}/{y}{r}.png',
      { attribution: '&copy; OpenStreetMap, &copy; CARTO', maxZoom: 19 }).addTo(map);
    const icon = L.divIcon({ className: '', html: '<div class="pin"></div>', iconSize: [30, 30], iconAnchor: [15, 30], popupAnchor: [0, -28] });
    const bounds = [];
    cinemas.forEach((c) => {
      const next = sessions.filter((s) => s.cinemaId === c.id).slice(0, 4);
      const html = `<b style="font-family:var(--font-display)">${esc(c.name)}</b><br><span class="muted">${esc(c.address)}</span>
        <div class="times" style="margin-top:10px">${next.map((s) => `<a class="time-chip" href="#/session/${s.id}" title="${esc(s.movieTitle)}">${CF.time(s.startTime)} · ${esc(s.movieTitle.split(':')[0])}</a>`).join('') || '<span class="muted">Сегодня сеансов больше нет</span>'}</div>`;
      L.marker([c.lat, c.lng], { icon }).addTo(map).bindPopup(html, { maxWidth: 280 });
      bounds.push([c.lat, c.lng]);
    });
    if (bounds.length) map.fitBounds(bounds, { padding: [50, 50], maxZoom: 13 });
    CF.onLeave(() => map.remove());
  };

  // ---------------- главная ----------------
  CF.pages.home = async (el) => {
    const city = CF.state.city;
    const ds = days();
    let day = 0;
    el.innerHTML = `
      <div class="marquee" id="marquee" hidden><div class="marquee-track" id="marqueeTrack"></div></div>
      <h1 class="hero-title">Кино в <button id="heroCity">${esc(CF.inCity(city.name))}</button></h1>
      <p class="hero-sub">Выберите фильм и сеанс — места в зале обновляются у всех зрителей в реальном времени.</p>
      <div class="days" id="days" role="tablist">${ds.map((d, i) => `<button class="day ${i === 0 ? 'on' : ''}" data-i="${i}" role="tab"><span>${dayLabel(d, i)}</span><b>${d.getDate()}</b></button>`).join('')}</div>
      <div class="poster-grid" id="grid">${'<div class="skeleton" style="aspect-ratio:2/3"></div>'.repeat(8)}</div>
      <section class="section" id="recs" hidden>
        <div class="section-head"><div><h2 id="recTitle"></h2><p class="muted" id="recReason"></p></div></div>
        <div class="rec-strip" id="recList"></div>
      </section>
      <section class="section">
        <div class="section-head"><h2>Кинотеатры на карте</h2><a class="btn btn-sm" href="#/map">Все кинотеатры</a></div>
        <div class="map" id="homeMap"></div>
      </section>`;
    CF.$('#heroCity', el).addEventListener('click', (e) => CF.cityPicker(e.currentTarget));

    // бегущая строка: текст приходит из Config Server (pricing.banner)
    CF.api('/api/pricing/rules', { quiet: true }).then((r) => {
      if (!r || !r.banner) return;
      const t = esc(r.banner) + ' &nbsp;🎬&nbsp; ';
      CF.$('#marqueeTrack', el).innerHTML = `<span>${t.repeat(4)}</span><span>${t.repeat(4)}</span>`;
      CF.$('#marquee', el).hidden = false;
    }).catch(() => {});

    const movies = await CF.api('/api/movies?cityId=' + city.id);
    const renderDay = async () => {
      const { from, to } = dayRange(ds[day]);
      let sessions = [];
      try { sessions = await CF.api(`/api/sessions?cityId=${city.id}&from=${from}&to=${to}`); } catch (e) { /* покажем без времени */ }
      const byMovie = {};
      sessions.forEach((s) => (byMovie[s.movieId] = byMovie[s.movieId] || []).push(s));
      const list = movies.filter((m) => byMovie[m.id]);
      CF.$('#grid', el).innerHTML = list.length ? list.map((m) => posterCard(m,
        `<div class="times">${byMovie[m.id].slice(0, 5).map((s) => `<span class="time-chip">${CF.time(s.startTime)}</span>`).join('')}${byMovie[m.id].length > 5 ? `<span class="time-chip">+${byMovie[m.id].length - 5}</span>` : ''}</div>`)).join('')
        : `<div class="empty" style="grid-column:1/-1">На этот день сеансов больше нет. Посмотрите завтра.</div>`;
      return sessions;
    };
    CF.$('#days', el).addEventListener('click', (e) => {
      const b = e.target.closest('.day'); if (!b) return;
      day = +b.dataset.i;
      CF.$$('.day', el).forEach((x) => x.classList.toggle('on', x === b));
      renderDay();
    });
    const todaySessions = await renderDay();

    // рекомендации (фишка №6)
    const uid = CF.state.user ? CF.state.user.id : '';
    CF.api(`/api/movies/recommendations?cityId=${city.id}&userId=${uid}`, { quiet: true }).then((r) => {
      if (!r || !r.movies.length) return;
      CF.$('#recTitle', el).textContent = r.title;
      CF.$('#recReason', el).textContent = r.reason;
      CF.$('#recList', el).innerHTML = r.movies.map((m) => posterCard(m)).join('');
      CF.$('#recs', el).hidden = false;
    }).catch(() => {});

    const cinemas = await CF.api('/api/cinemas?cityId=' + city.id);
    CF.renderMap(CF.$('#homeMap', el), cinemas, todaySessions);
  };

  // ---------------- страница фильма ----------------
  CF.pages.movie = async (el, [id]) => {
    const m = await CF.api('/api/movies/' + id);
    const ds = days();
    el.innerHTML = `
      <section class="movie-hero" id="hero" style="--movie:${esc(m.color)}">
        <div class="poster"><img id="posterImg" src="${esc(m.posterUrl)}" alt="Постер «${esc(m.title)}»" crossorigin="anonymous"></div>
        <div>
          <h1>${esc(m.title)}</h1>
          <div class="facts">
            <span class="chip">${esc(m.genre)}</span><span class="chip">${m.year}</span>
            <span class="chip">${m.durationMin} мин</span><span class="chip">${esc(m.ageRating)}</span>
          </div>
          <p class="rating">★ ${m.rating ? m.rating.toFixed(1) : '—'} <span class="small muted" style="font-family:var(--font-body)">${m.reviewsCount} ${CF.plural(m.reviewsCount, 'отзыв', 'отзыва', 'отзывов')}</span></p>
          <p style="margin-top:14px;max-width:62ch">${esc(m.description)}</p>
          <p class="muted small" style="margin-top:10px">Режиссёр: ${esc(m.director)}</p>
          ${m.trailerYoutubeId
            ? `<div class="trailer"><iframe src="https://www.youtube-nocookie.com/embed/${esc(m.trailerYoutubeId)}?rel=0" title="Трейлер «${esc(m.title)}»" allow="accelerometer; encrypted-media; picture-in-picture" allowfullscreen loading="lazy"></iframe></div>`
            : `<a class="btn" style="margin-top:20px" target="_blank" rel="noopener" href="https://www.youtube.com/results?search_query=${encodeURIComponent(m.title + ' трейлер')}">Смотреть трейлер на YouTube</a>`}
        </div>
      </section>
      <section class="section">
        <div class="section-head"><h2>Сеансы в ${esc(CF.inCity(CF.state.city.name))}</h2></div>
        <div class="days" id="days">${ds.map((d, i) => `<button class="day ${i === 0 ? 'on' : ''}" data-i="${i}"><span>${dayLabel(d, i)}</span><b>${d.getDate()}</b></button>`).join('')}</div>
        <div id="sessions" class="panel" style="margin-top:14px"></div>
      </section>
      <section class="section grid-2">
        <div><h2>Отзывы</h2><div id="reviews" style="margin-top:10px"></div></div>
        <div class="panel" id="reviewForm"></div>
      </section>`;

    // цвет страницы под постер (фишка №23)
    const img = CF.$('#posterImg', el);
    const tint = () => {
      try {
        if (!window.ColorThief) return;
        const [r, g, b] = new ColorThief().getColor(img);
        CF.$('#hero', el).style.setProperty('--movie', `rgb(${r},${g},${b})`);
      } catch (e) { /* остаётся цвет из базы */ }
    };
    if (img.complete) tint(); else img.addEventListener('load', tint);

    const renderSessions = async (i) => {
      const d = ds[i];
      const list = await CF.api(`/api/movies/${id}/sessions?cityId=${CF.state.city.id}&date=${CF.isoDate(d)}`);
      const by = {};
      list.forEach((s) => (by[s.cinemaName] = by[s.cinemaName] || { s, items: [] }).items.push(s));
      CF.$('#sessions', el).innerHTML = Object.keys(by).length ? Object.values(by).map(({ s, items }) => `
        <div class="cinema-block">
          <div><h3>${esc(s.cinemaName)}</h3><p class="small muted">${esc(s.cinemaAddress)}</p></div>
          <div class="times">${items.map((x) => `<a class="session-chip" href="#/session/${x.id}"><b>${CF.time(x.startTime)}</b><span>${esc(x.hallName)} · ${esc(x.hallType)} · от ${CF.money(Math.round(x.basePrice * 0.8 / 10) * 10)}</span></a>`).join('')}</div>
        </div>`).join('') : '<p class="muted">В этот день фильм в вашем городе не показывают.</p>';
    };
    CF.$('#days', el).addEventListener('click', (e) => {
      const b = e.target.closest('.day'); if (!b) return;
      CF.$$('.day', el).forEach((x) => x.classList.toggle('on', x === b));
      renderSessions(+b.dataset.i);
    });
    renderSessions(0);

    const renderReviews = async () => {
      const list = await CF.api(`/api/movies/${id}/reviews`);
      CF.$('#reviews', el).innerHTML = list.length ? list.slice(0, 12).map((r) => `
        <div class="review"><div class="row" style="justify-content:space-between"><b>${esc(r.userName)}</b><span class="stars">${'★'.repeat(r.rating)}<span class="muted">${'★'.repeat(5 - r.rating)}</span></span></div>
        ${r.text ? `<p style="margin-top:6px">${esc(r.text)}</p>` : ''}<p class="small muted" style="margin-top:4px">${CF.date(r.createdAt)}</p></div>`).join('')
        : '<p class="muted">Отзывов пока нет.</p>';
    };
    renderReviews();

    // отзыв — только после сеанса (фишка №14): проверку делает movie-service, спрашивая booking-service
    let stars = 5;
    const form = CF.$('#reviewForm', el);
    form.innerHTML = `
      <h3>Оставить отзыв</h3>
      <p class="small muted" style="margin:6px 0 14px">Отзыв можно оставить, если ваш билет на этот фильм отсканировали на входе.</p>
      <div class="star-input" id="stars">${[1, 2, 3, 4, 5].map((n) => `<button data-n="${n}" class="on" aria-label="${n} из 5">★</button>`).join('')}</div>
      <textarea class="input" id="rtext" rows="3" placeholder="Что понравилось?" style="margin-top:12px"></textarea>
      <button class="btn btn-primary" id="rsend" style="margin-top:12px">Отправить отзыв</button>`;
    CF.$('#stars', form).addEventListener('click', (e) => {
      const b = e.target.closest('button'); if (!b) return;
      stars = +b.dataset.n;
      CF.$$('#stars button', form).forEach((x) => x.classList.toggle('on', +x.dataset.n <= stars));
    });
    CF.$('#rsend', form).addEventListener('click', async () => {
      const u = await CF.requireUser('Войдите, чтобы оставить отзыв');
      try {
        await CF.api(`/api/movies/${id}/reviews`, { body: { userId: u.id, userName: u.name, rating: stars, text: CF.$('#rtext', form).value } });
        CF.toast('Спасибо! Отзыв опубликован', 'ok');
        CF.$('#rtext', form).value = '';
        renderReviews();
      } catch (e) { CF.toast(e.message, 'err'); }
    });
  };

  // ---------------- кинотеатры ----------------
  CF.pages.map = async (el) => {
    const city = CF.state.city;
    el.innerHTML = `<h1>Кинотеатры</h1><p class="hero-sub">Сеть CineFlow в ${esc(CF.inCity(city.name))}. Нажмите на кинотеатр, чтобы увидеть ближайшие сеансы.</p>
      <div class="map" id="bigMap" style="height:min(62vh,560px);margin-top:22px"></div>
      <div class="grid-2" id="cinemaList" style="margin-top:22px"></div>`;
    const now = new Date(), to = new Date(now.getTime() + 8 * 3600e3);
    const iso = (x) => `${CF.isoDate(x)}T${String(x.getHours()).padStart(2, '0')}:${String(x.getMinutes()).padStart(2, '0')}:00`;
    const [cinemas, sessions] = await Promise.all([
      CF.api('/api/cinemas?cityId=' + city.id),
      CF.api(`/api/sessions?cityId=${city.id}&from=${iso(now)}&to=${iso(to)}`),
    ]);
    CF.renderMap(CF.$('#bigMap', el), cinemas, sessions);
    CF.$('#cinemaList', el).innerHTML = cinemas.map((c) => {
      const next = sessions.filter((s) => s.cinemaId === c.id).slice(0, 6);
      return `<div class="panel"><h3>${esc(c.name)}</h3><p class="small muted">${esc(c.address)}</p>
        <div class="times" style="margin-top:12px">${next.map((s) => `<a class="session-chip" href="#/session/${s.id}"><b>${CF.time(s.startTime)}</b><span>${esc(s.movieTitle)}</span></a>`).join('') || '<span class="muted">Сегодня сеансов больше нет</span>'}</div></div>`;
    }).join('');
  };
})();
