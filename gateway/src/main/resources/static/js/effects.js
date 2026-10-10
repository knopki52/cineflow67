/* CineFlow — эффекты: собственный курсор, свет в зале, занавес с печатью билета, «Плёнка оборвалась» */
(function () {
  'use strict';
  const CF = window.CF;
  const reduce = matchMedia('(prefers-reduced-motion: reduce)').matches;
  const E = CF.effects = {};

  // ---------------- собственный курсор (фишка №20) ----------------
  E.init = () => {
    if (!matchMedia('(pointer: fine)').matches) return;
    const dot = document.getElementById('cursorDot');
    const label = dot.querySelector('.cursor-label');
    document.documentElement.classList.add('custom-cursor');
    let tx = -100, ty = -100, x = -100, y = -100;
    const k = reduce ? 1 : 0.28;
    window.addEventListener('mousemove', (e) => {
      tx = e.clientX; ty = e.clientY;
      dot.classList.add('on');
      const t = e.target instanceof Element ? e.target : null;
      const seat = t && t.closest('.seat');
      const interactive = t && t.closest('a, button, [role=button], .seat, label, summary, .leaflet-marker-icon');
      const typing = t && t.closest('input, textarea, select, iframe');
      dot.style.opacity = typing ? '0' : '';
      dot.classList.toggle('hover', !!interactive);
      const blocked = seat && (seat.classList.contains('SOLD') || seat.classList.contains('HELD') || seat.classList.contains('GROUP'));
      dot.classList.toggle('blocked', !!blocked);
      if (seat && seat.classList.contains('FREE') && seat.dataset.price) {
        label.textContent = CF.money(+seat.dataset.price);
        dot.classList.add('priced');
      } else if (seat && seat.classList.contains('MINE')) {
        label.textContent = 'Ваше место';
        dot.classList.add('priced');
      } else dot.classList.remove('priced');
    }, { passive: true });
    document.addEventListener('mouseleave', () => dot.classList.remove('on'));
    const loop = () => {
      x += (tx - x) * k; y += (ty - y) * k;
      dot.style.transform = `translate(${x}px, ${y}px)`;
      requestAnimationFrame(loop);
    };
    loop();
  };

  // ---------------- свет в зале гаснет (фишка №22) ----------------
  E.lights = (on) => document.getElementById('lights').classList.toggle('on', on);

  /** Занавес раздвигается, из «принтера» выезжают билеты. */
  E.curtain = (innerHtml) => {
    const c = document.createElement('div');
    c.className = 'curtain';
    c.innerHTML = `<div class="curtain-stage">${innerHtml}</div>`;
    document.body.appendChild(c);
    requestAnimationFrame(() => setTimeout(() => c.classList.add('open'), reduce ? 0 : 450));
    return c;
  };

  // ---------------- «Плёнка оборвалась» (фишка №31) ----------------
  const names = {
    'movie-service': 'Каталог фильмов', 'booking-service': 'Бронирование', 'pricing-service': 'Сервис цен',
    'payment-service': 'Оплата', 'user-service': 'Профили и бонусы', 'concession-service': 'Бар', gateway: 'Сервер',
  };
  let stopGame = null;

  CF.filmBreak = (service, title) => {
    if (document.querySelector('.film-break')) return;
    const fb = document.createElement('div');
    fb.className = 'film-break';
    fb.setAttribute('role', 'alertdialog');
    const what = service ? (names[service] || service) : null;
    fb.innerHTML = `
      <div class="beam"></div>
      <div class="content">
        <svg class="strip" viewBox="0 0 520 120" aria-hidden="true">
          <g fill="#2A1426" stroke="#5A3550" stroke-width="2">
            <path d="M0 10 H230 L245 40 L225 62 L250 88 L232 110 H0 Z"/>
            <path d="M520 10 H290 L276 36 L298 58 L270 84 L288 110 H520 Z"/>
          </g>
          <g fill="#070306">${Array.from({ length: 7 }, (_, i) => `<rect x="${12 + i * 31}" y="16" width="16" height="10" rx="2"/><rect x="${12 + i * 31}" y="94" width="16" height="10" rx="2"/>`).join('')}
          ${Array.from({ length: 7 }, (_, i) => `<rect x="${298 + i * 31}" y="16" width="16" height="10" rx="2"/><rect x="${298 + i * 31}" y="94" width="16" height="10" rx="2"/>`).join('')}</g>
          <rect x="20" y="36" width="190" height="48" rx="4" fill="#E3B65C" opacity=".25"/>
          <rect x="306" y="36" width="190" height="48" rx="4" fill="#E3B65C" opacity=".25"/>
        </svg>
        <h1 style="font-size:clamp(28px,6vw,48px)">${CF.esc(title || 'Плёнка оборвалась')}</h1>
        <p style="opacity:.8;max-width:46ch">${what
          ? `${CF.esc(what)} сейчас не отвечает. Circuit Breaker уже переключил сайт на запасной сценарий — остальные разделы работают. Киномеханик чинит проектор.`
          : 'Такой страницы нет. Зато есть попкорн — ловите, пока ищем нужный зал.'}</p>
        <canvas class="game" width="520" height="292" aria-label="Мини-игра: лови попкорн"></canvas>
        <p class="small" style="opacity:.6">Двигайте ведро мышью, пальцем или стрелками</p>
        <div class="row" style="justify-content:center">
          <button class="btn btn-primary" data-retry>Повторить</button>
          <button class="btn" data-home style="color:#fff">На главную</button>
        </div>
      </div>`;
    document.body.appendChild(fb);
    const close = () => { stopGame && stopGame(); fb.remove(); };
    fb.querySelector('[data-retry]').addEventListener('click', () => { close(); CF.route(); });
    fb.querySelector('[data-home]').addEventListener('click', () => { close(); CF.go('#/'); });
    stopGame = popcornGame(fb.querySelector('canvas'));
  };

  function popcornGame(canvas) {
    const ctx = canvas.getContext('2d');
    const W = canvas.width, H = canvas.height;
    let bucket = W / 2, score = 0, missed = 0, running = true, last = performance.now(), spawn = 0;
    const items = [];
    const move = (clientX) => { const r = canvas.getBoundingClientRect(); bucket = Math.max(30, Math.min(W - 30, (clientX - r.left) * (W / r.width))); };
    canvas.addEventListener('pointermove', (e) => move(e.clientX));
    canvas.addEventListener('pointerdown', (e) => move(e.clientX));
    const key = (e) => { if (e.key === 'ArrowLeft') bucket = Math.max(30, bucket - 28); if (e.key === 'ArrowRight') bucket = Math.min(W - 30, bucket + 28); };
    window.addEventListener('keydown', key);
    const kernel = (x, y, r, rot) => {
      ctx.save(); ctx.translate(x, y); ctx.rotate(rot);
      ctx.fillStyle = '#FFF4D6';
      for (let i = 0; i < 5; i++) { ctx.beginPath(); ctx.arc(Math.cos(i * 1.25) * r * .55, Math.sin(i * 1.25) * r * .55, r * .55, 0, Math.PI * 2); ctx.fill(); }
      ctx.fillStyle = '#F2C14E'; ctx.beginPath(); ctx.arc(0, 0, r * .35, 0, Math.PI * 2); ctx.fill();
      ctx.restore();
    };
    const frame = (now) => {
      if (!running) return;
      const dt = Math.min(50, now - last); last = now;
      spawn -= dt;
      if (spawn <= 0) { items.push({ x: 20 + Math.random() * (W - 40), y: -10, v: 0.08 + Math.random() * 0.1 + score * 0.002, r: 7 + Math.random() * 4, rot: Math.random() * 6 }); spawn = 520 - Math.min(320, score * 8); }
      ctx.clearRect(0, 0, W, H);
      ctx.fillStyle = 'rgba(255,240,200,.06)';
      ctx.beginPath(); ctx.moveTo(W / 2 - 30, 0); ctx.lineTo(W / 2 + 30, 0); ctx.lineTo(W, H); ctx.lineTo(0, H); ctx.fill();
      for (let i = items.length - 1; i >= 0; i--) {
        const p = items[i];
        p.y += p.v * dt; p.rot += 0.004 * dt;
        kernel(p.x, p.y, p.r, p.rot);
        if (p.y > H - 46 && p.y < H - 20 && Math.abs(p.x - bucket) < 34) { items.splice(i, 1); score++; }
        else if (p.y > H + 10) { items.splice(i, 1); missed++; }
      }
      // ведро в полоску
      ctx.save(); ctx.translate(bucket, H - 30);
      ctx.beginPath(); ctx.moveTo(-32, -18); ctx.lineTo(32, -18); ctx.lineTo(24, 26); ctx.lineTo(-24, 26); ctx.closePath();
      ctx.fillStyle = '#F3EFFA'; ctx.fill(); ctx.clip();
      ctx.fillStyle = '#C8102E';
      for (let sx = -40; sx < 40; sx += 16) ctx.fillRect(sx, -20, 8, 50);
      ctx.restore();
      ctx.fillStyle = '#E3B65C'; ctx.font = '800 18px Unbounded, sans-serif';
      ctx.fillText('🍿 ' + score, 14, 28);
      ctx.fillStyle = 'rgba(243,239,250,.5)'; ctx.font = '600 13px Manrope, sans-serif';
      ctx.fillText('мимо: ' + missed, W - 80, 26);
      requestAnimationFrame(frame);
    };
    requestAnimationFrame(frame);
    return () => { running = false; window.removeEventListener('keydown', key); };
  }
})();
