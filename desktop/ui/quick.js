const $ = id => document.getElementById(id);
let ru = true, busy = false, fileName = null, peers = [];
const text = (a,b) => ru ? a : b;
function render() {
  $('hint').textContent = fileName || text('Перетащите файл на устройство, чтобы отправить','Drop a file onto a device to send');
  $('heading').textContent = text('Мои устройства','My devices');
  $('empty').textContent = peers.length ? '' : text('Откройте AppSEND на телефоне в той же сети Wi-Fi.','Open AppSEND on your phone on the same Wi-Fi network.');
  $('open').textContent = text('Открыть AppSEND','Open AppSEND');
  $('devices').replaceChildren();
  for (const peer of peers) {
    const button = document.createElement('button');
    button.className = 'device'; button.disabled = busy;
    button.setAttribute('aria-label', text('Отправить на ','Send to ') + peer.name);
    const symbol = document.createElement('span'); symbol.className = 'symbol'; symbol.textContent = peer.platform === 'Android' ? '▯' : '▣';
    const name = document.createElement('span'); name.className = 'name'; name.textContent = peer.name;
    button.append(symbol,name);
    button.onclick = () => send(peer.id);
    button.ondragover = event => { event.preventDefault(); event.dataTransfer.dropEffect = 'copy'; button.classList.add('over'); };
    button.ondragleave = () => button.classList.remove('over');
    button.ondrop = event => {
      event.preventDefault(); event.stopPropagation(); document.body.classList.remove('dragging');
      if (event.dataTransfer.files.length !== 1) { $('status').textContent = text('Перетаскивайте по одному файлу','Drop one file at a time'); return; }
      send(peer.id,window.beam.filePath(event.dataTransfer.files[0]));
    };
    $('devices').append(button);
  }
}
async function send(peerId,path) {
  if (busy) return;
  busy = true; render();
  try { const result = await window.beam.action('quick-send',{peerId,path}); busy = !!result?.started; }
  catch (error) { busy = false; $('status').textContent = error.message; }
  render();
}
document.addEventListener('dragover',event => { event.preventDefault(); document.body.classList.add('dragging'); });
document.addEventListener('dragleave',event => { if (!event.relatedTarget) document.body.classList.remove('dragging'); });
document.addEventListener('drop',event => { event.preventDefault(); document.body.classList.remove('dragging'); $('status').textContent = text('Перетащите файл на значок устройства','Drop the file onto a device'); });
document.addEventListener('keydown',event => { if(event.key === 'Escape') window.close(); });
$('open').onclick = () => window.beam.action('quick-open');
window.beam.onState(({type,data}) => {
  if (type === 'peers') { peers = data; render(); }
  if (type === 'quick-file') { fileName = data; render(); }
  if (type === 'quick-sending') $('status').textContent = text('Отправка → ','Sending → ') + data;
  if (type === 'progress') $('status').textContent = text('Отправка: ','Sending: ') + Math.round(data.size ? data.bytes / data.size * 100 : 100) + '%';
  if (type === 'done') { busy = false; render(); $('status').textContent = text('✓ Доставлено','✓ Delivered'); }
  if (type === 'error') { busy = false; render(); $('status').textContent = data; }
});
window.beam.action('snapshot').then(state => { ru = state.language !== 'en'; peers = state.peers; fileName = state.quickFileName; busy = state.busy; render(); });
