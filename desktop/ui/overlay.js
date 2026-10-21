let generation = 0;
window.beam.onAnimation(({ at, incoming, name, side, motion }) => {
  const run = ++generation;
  const card = document.getElementById('card'), planeIcon = document.getElementById('plane'),
    glow = document.getElementById('glow'), ripple = document.getElementById('ripple'), liquid = document.getElementById('liquid'), contact = document.getElementById('contact');
  document.getElementById('name').textContent = name;
  document.getElementById('contact-name').textContent = name;
  const isPlane = motion === 'plane', top = side === 'top', right = side === 'right';
  const width = window.innerWidth, height = window.innerHeight;
  glow.className = ripple.className = top ? 'top' : right ? 'right' : 'left';
  liquid.style.width=(top?width:height)+'px';liquid.style.height='110px';
  liquid.style.left=(right?width:0)+'px';liquid.style.top=(!top&&!right?height:0)+'px';
  const base = performance.now() + at - Date.now();
  function frame(now) {
    if (run !== generation) return;
    const ms = now - base, s = HandoffMotion.sample(ms, incoming, isPlane);
    let x, y, rotation = 0, scale;
    if (isPlane) {
      if (incoming) {
        x = width * (.28 + .25 * s.progress) + Math.sin(s.t * Math.PI) * width * .08;
        y = -140 + (height * .46 + 140) * s.progress;
        rotation = 12 * (1 - s.t); scale = .65 + .35 * s.t;
      } else {
        x = width * (.5 + .18 * s.progress);
        y = height * .5 - (height * .5 + 170) * s.progress;
        rotation = -38 * s.progress; scale = 1 - .7 * s.progress;
      }
    } else {
      const distance = incoming ? 1 - s.progress : s.progress;
      x = top ? width / 2 : right ? width - 130 + 260 * distance : 130 - 260 * distance;
      y = top ? 140 - 280 * distance : height / 2;
      scale = incoming ? .5 + .5 * s.t : 1 - .55 * s.t;
    }
    const scaleX=isPlane?scale:incoming?.8+.2*s.t:1+.12*Math.sin(s.t*Math.PI);
    const scaleY=isPlane?scale:incoming?.08+.92*s.t:1-.92*s.t;
    const transform = `translate3d(${x - 112}px,${y - 54}px,0) rotate(${rotation}deg) scale(${scaleX},${scaleY})`;
    card.style.transform = planeIcon.style.transform = transform;
    card.style.opacity = String(s.alpha * s.file);
    planeIcon.style.opacity = String(isPlane ? s.alpha * (1 - s.file) : 0);
    glow.style.opacity = String(isPlane ? 0 : s.glow*.72);
    liquid.style.opacity=String(isPlane?0:s.glow*.75);
    contact.style.opacity=String(isPlane||ms<0?0:Math.max(0,1-Math.abs(ms-250)/330));
    contact.style.transform=`translateY(${-12*(1-Math.max(0,1-Math.abs(ms-250)/330))}px)`;
    liquid.style.transform=`rotate(${top?0:right?90:-90}deg) scaleY(${.12+s.glow*.88})`;
    ripple.style.opacity = String(isPlane ? incoming ? Math.max(0, 1 - Math.abs(ms - 1000) / 180) * .5 : 0 : 0);
    ripple.style.transform = isPlane ? `translate3d(${width * .53 - 140}px,${height * .46 + 45}px,0) scale(${.7 + s.t * .5})` : `scale(${.55 + Math.max(0, ms) / 1200})`;
    ripple.classList.toggle('landing', isPlane);
    if (ms < 1600) requestAnimationFrame(frame);
    else { card.style.opacity = planeIcon.style.opacity = glow.style.opacity = ripple.style.opacity = liquid.style.opacity = contact.style.opacity = '0'; }
  }
  requestAnimationFrame(frame);
});
