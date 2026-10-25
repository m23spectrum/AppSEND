const { test } = require('node:test');
const assert = require('node:assert/strict');
const { EventEmitter } = require('node:events');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

function load(file, mocks, platform) {
  const context = {
    require: (name) => name in mocks ? mocks[name] : require(name),
    module: { exports: {} }, __dirname: path.join(__dirname, '..'),
    process: { platform, env: {} }, Buffer,
    setInterval: () => 1, clearInterval() {}, setTimeout() {},
  };
  vm.runInNewContext(fs.readFileSync(path.join(__dirname, '..', file), 'utf8'), context, { filename: file });
  return context.module.exports;
}

for (const [platform, label] of [['darwin', 'macOS'], ['win32', 'Windows'], ['linux', 'Linux']]) {
  test(`LAN discovery advertises ${label} and accepts Android peers`, () => {
    const socket = new EventEmitter();
    let advertisement, peers;
    socket.bind = (_port, _host, callback) => callback();
    socket.setBroadcast = () => {};
    socket.send = (packet) => { advertisement = JSON.parse(packet); };
    socket.close = () => {};
    const { Discovery } = load('discovery.cjs', {
      'node:dgram': { createSocket: () => socket },
      'node:os': { networkInterfaces: () => ({}) },
    }, platform);
    const discovery = new Discovery('desktop-id', 'Desktop', 12345, (value) => { peers = value; }, assert.fail);
    assert.equal(advertisement.platform, label);
    socket.emit('message', Buffer.from(JSON.stringify({ magic: 'edgebeam', v: 1, id: 'phone-id', name: 'Phone', platform: 'Android', port: 12346 })), { address: '192.168.1.2' });
    assert.equal(peers.length, 1);
    assert.equal(peers[0].platform, 'Android');
    discovery.close();
  });
}

for (const platform of ['darwin', 'win32']) {
  test(`${platform}: window close, Dock activation and quit lifecycle`, async () => {
    const windows = [];
    let tray, onEngineEvent; let approvals = 0; const staged = [], connected = [];
    class Tray extends EventEmitter { constructor() { super(); tray = this; } getBounds() { return {x:1000,y:0,width:22,height:25}; } setToolTip() {} popUpContextMenu() {} destroy() { this.destroyed = true; } }
    const nativeImage = { createFromPath: () => ({ resize() { return this; }, setTemplateImage() {} }) };
    const app = new EventEmitter();
    Object.assign(app, { setName() {}, whenReady: () => Promise.resolve(), requestSingleInstanceLock: () => true, getPath: () => '/unused', quit() { this.quitCalled = true; } });
    class Window extends EventEmitter {
      constructor(options) {
        super(); this.options = options;
        this.webContents = new EventEmitter();
        this.webContents.setWindowOpenHandler = () => {};
        this.webContents.send = () => {};
        windows.push(this);
      }
      loadFile() {} show() { this.visible = true; } hide() { this.visible = false; }
      isVisible() { return !!this.visible; } showInactive() { this.visible = true; }
      focus() {} setIgnoreMouseEvents() {} setAlwaysOnTop() {}
      setBounds(bounds) { this.bounds = bounds; } isDestroyed() { return false; }
    }
    class Engine { constructor(options) { onEngineEvent = options.event; } approve() { approvals++; } async start() { return 12345; } async stageFile(file) { staged.push(file); } connect(peer) { connected.push(peer); this.active = {}; } close() {} }
    const actions = {};
    let menu;
    load('main.cjs', {
      electron: { app, Tray, nativeImage, Menu: { buildFromTemplate: (items) => items, setApplicationMenu: (items) => { menu = items; } }, BrowserWindow: Window, ipcMain: { handle: (key, fn) => { actions[key] = fn; } }, nativeTheme: {}, screen: { getCursorScreenPoint: () => ({ x: 100, y: 100 }), getDisplayNearestPoint: () => ({ bounds: { x: 0, y: 0, width: 1440, height: 900 }, workArea: { x: 0, y: 25, width: 1440, height: 825 } }) } },
      'node:fs': { readFileSync: () => '{"id":"desktop-id","name":"Desktop"}' },
      './engine.cjs': { Engine }, './discovery.cjs': { Discovery: class { constructor(id,name,port,changed) { changed([{id:'phone',name:'Xiaomi 13T',platform:'Android',host:'127.0.0.1',port:12346}]); } close() {} } },
    }, platform);
    await new Promise(setImmediate);
    const main = windows[0];
    const deliveredEvents = [];
    main.webContents.send = (channel, message) => deliveredEvents.push(message);
    onEngineEvent('consent', {incoming:false,initiated:false,name:'prepared.txt',peer:'Phone'});
    assert.equal(approvals,1);
    assert.equal(deliveredEvents.at(-1).type,'outgoing');
    assert.ok(!deliveredEvents.some(e=>e.type==='consent'));

    if (platform === 'darwin') {
      assert.equal(menu[0].role, 'appMenu');
      assert.equal(menu[1].submenu[0].accelerator, 'Cmd+O');
      assert.equal(menu[1].submenu[2].accelerator, 'Cmd+Return');
    } else assert.equal(menu, undefined);
    main.show();
    let prevented = false;
    main.emit('close', { preventDefault() { prevented = true; } });
    assert.equal(prevented, platform === 'darwin');
    if (platform === 'darwin') assert.equal(main.visible, false);
    app.emit('activate');
    assert.equal(main.visible, true);
    await actions.action({ sender: main.webContents }, { type: 'arm', value: false });
    assert.equal(windows[1].bounds.y, 25);
    assert.equal(windows[2].bounds.y, 25);
    if (platform === 'darwin') {
      const quick = windows[3];
      tray.emit('drag-enter'); assert.equal(quick.visible, true);
      assert.ok(quick.bounds.x >= 0); assert.equal(quick.bounds.y, 29);
      let closePrevented = false;
      quick.emit('close', { preventDefault() { closePrevented = true; } });
      assert.equal(closePrevented, true); assert.equal(quick.visible, false);
      tray.emit('click'); assert.equal(quick.visible, true);
      const before = staged.length;
      await actions.action({sender:main.webContents},{type:'quick-send',value:{peerId:'phone',path:'/test/file'}});
      assert.equal(staged.length,before, 'only the quick window may send through this action');
      tray.emit('drop-files', {}, ['/test/file']);
      const result = await actions.action({sender:quick.webContents},{type:'quick-send',value:{peerId:'phone'}});
      assert.equal(result.started,true); assert.equal(staged.at(-1),'/test/file'); assert.equal(connected.at(-1).id,'phone');
      await actions.action({sender:quick.webContents},{type:'quick-send',value:{peerId:'phone'}});
      assert.equal(connected.length,1, 'active transfers cannot be started twice');
    }
    app.emit('before-quit');
    if (platform === 'darwin') assert.equal(tray.destroyed,true);
    prevented = false;
    main.emit('close', { preventDefault() { prevented = true; } });
    assert.equal(prevented, false, 'Cmd+Q must close the app');
    main.emit('closed');
    assert.equal(app.quitCalled, true);
  });
}
