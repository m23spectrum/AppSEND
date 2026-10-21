/* Departure 0вЂ“350 ms, arrival 350вЂ“1000 ms, then a short hold. */
(function (root) {
  const clamp = (v) => Math.max(0, Math.min(1, v));
  const out = (t) => 1 - Math.pow(1 - t, 3);
  const back = (t) => 1 + 2.2 * Math.pow(t - 1, 3) + 1.2 * Math.pow(t - 1, 2);
  function sample(ms, incoming, plane) {
    const t = clamp((ms - (incoming ? 350 : 0)) / (incoming ? 650 : 350));
    return {
      t, progress: incoming ? (plane ? back(t) : out(t)) : t * t * t,
      alpha: ms < 0 ? 0 : incoming ? clamp(t * 7) * (1 - clamp((ms - 1200) / 350)) : 1 - t * t,
      glow: Math.pow(Math.max(0, 1 - Math.abs(ms - 350) / 450), 2),
      file: incoming || !plane ? 1 : 0,
    };
  }
  if (typeof module !== 'undefined') module.exports = { sample };
  else root.HandoffMotion = { sample };
})(typeof window === 'undefined' ? globalThis : window);
