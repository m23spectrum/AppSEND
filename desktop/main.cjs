const {
  app,
  BrowserWindow,
  ipcMain,
  dialog,
  clipboard,
  shell,
  screen,
  nativeTheme,
  Menu,
  Tray,
  nativeImage,
} = require("electron");
const path = require("node:path"),
  fs = require("node:fs"),
  os = require("node:os"),
  crypto = require("node:crypto");
const { Engine } = require("./engine.cjs");
const { Discovery } = require("./discovery.cjs");
let tray, quick, quickFile = null, quickSending = false;
let main,
  overlay,
  edge,
  engine,
  discovery,
  peers = [],
  selected = null,
  armed = false,
  edgeSide = "top",
  edgeSince = 0,
  edgeFired = false,
  poll,
  quitting = false;
const allowedReceived = new Set();
let preferences = { language: null, autoReceive: true, method: "normal" },
  preferencesPath;
app.setName("AppSEND");
nativeTheme.themeSource = "dark";
function send(type, data) {
  for (const window of [main, quick])
    if (window && !window.isDestroyed())
      window.webContents.send("state", { type, data });
}
function trigger() {
  if (!selected || !engine?.staged || engine.active) return;
  const p = peers.find((p) => p.id === selected);
  if (!p) {
    send("error", "Selected device is no longer on the LAN");
    return;
  }
  try {
    engine.connect(p);
  } catch (e) {
    send("error", e.message);
  }
}
function makeWindow(file, opts = {}) {
  const w = new BrowserWindow({
    width: 960,
    height: 820,
    minWidth: 720,
    minHeight: 640,
    backgroundColor: "#000000",
    icon: path.join(__dirname, "assets", process.platform === "win32" ? "icon.ico" : "icon.png"),
    show: false,
    webPreferences: {
      preload: path.join(__dirname, "preload.cjs"),
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: true,
    },
    ...opts,
  });
  w.webContents.setWindowOpenHandler(() => ({ action: "deny" }));
  w.webContents.on("will-navigate", (e) => e.preventDefault());
  w.loadFile(path.join(__dirname, "ui", file));
  return w;
}
function setupEdges() {
  const point = screen.getCursorScreenPoint(),
    display = screen.getDisplayNearestPoint(point),
    b = display.workArea;
  overlay.setBounds(b);
  edge.setBounds(
    edgeSide === "top"
      ? { x: b.x, y: b.y, width: b.width, height: 16 }
      : edgeSide === "right"
        ? { x: b.x + b.width - 16, y: b.y, width: 16, height: b.height }
        : { x: b.x, y: b.y, width: 16, height: b.height },
  );
  edge.webContents.send("edge", { side: edgeSide });
  if (armed) edge.showInactive();
  else edge.hide();
}
function showQuick(focus = true) {
  const anchor = tray.getBounds();
  const area = screen.getDisplayNearestPoint({ x: anchor.x, y: anchor.y }).workArea;
  quick.setBounds({
    x: Math.max(area.x, Math.min(Math.round(anchor.x + anchor.width / 2 - 160), area.x + area.width - 320)),
    y: area.y + 4, width: 320, height: 290,
  });
  if (focus) { quick.show(); quick.focus(); }
  else quick.showInactive();
}
function setupTray() {
  const image = nativeImage.createFromPath(path.join(__dirname, "assets", "trayTemplate.png")).resize({ width: 18, height: 18 });
  image.setTemplateImage(true);
  tray = new Tray(image);
  tray.setToolTip("AppSEND — Drop a file to send / Перетащите файл для отправки");
  quick = makeWindow("quick.html", {
    width: 320, height: 290, minWidth: 320, minHeight: 290,
    frame: false, resizable: false, minimizable: false, maximizable: false,
    fullscreenable: false, skipTaskbar: true, alwaysOnTop: true,
    transparent: true, backgroundColor: "#00000000", vibrancy: "popover",
    visualEffectState: "active", roundedCorners: true,
  });
  quick.on("blur", () => quick.hide());
  quick.on("close", event => { if (!quitting) { event.preventDefault(); quick.hide(); } });
  tray.on("click", () => quick.isVisible() ? quick.hide() : showQuick());
  tray.on("drag-enter", () => showQuick(false));
  tray.on("drop-files", (_event, files) => {
    showQuick();
    if (files.length !== 1) { quickFile = null; send("quick-file", null); send("error", "Drop one file at a time / Перетащите один файл"); return; }
    quickFile = files[0];
    send("quick-file", path.basename(quickFile));
  });
  tray.on("right-click", () => tray.popUpContextMenu(Menu.buildFromTemplate([
    { label: "Open AppSEND / Открыть AppSEND", click: () => { main.show(); main.focus(); } },
    { type: "separator" }, { role: "quit" },
  ])));
}
app.whenReady().then(async () => {
  if (!app.requestSingleInstanceLock()) {
    app.quit();
    return;
  }
  preferencesPath = path.join(app.getPath("userData"), "preferences.json");
  try {
    const saved = JSON.parse(fs.readFileSync(preferencesPath, "utf8"));
    if (typeof saved.autoReceive === "boolean") preferences.autoReceive=saved.autoReceive;
    if (["ru", "en"].includes(saved.language))
      preferences.language = saved.language;
  } catch {}
  main = makeWindow("index.html");
  if (process.platform === "darwin") {
    const action = (type) => () => {
      main.show();
      main.focus();
      handleAction({ sender: main.webContents }, { type });
    };
    Menu.setApplicationMenu(Menu.buildFromTemplate([
      { role: "appMenu" },
      { label: "File", submenu: [
        { label: "Choose File…", accelerator: "Cmd+O", click: action("choose") },
        { label: "Send Clipboard", accelerator: "Cmd+Shift+V", click: action("clipboard") },
        { label: "Send to Selected Device", accelerator: "Cmd+Return", click: action("send") },
        { type: "separator" },
        { role: "close" },
      ] },
      { role: "editMenu" },
      { role: "windowMenu" },
    ]));
  }
  main.once("ready-to-show", () => {
    if (!process.env.EDGEBEAM_SMOKE) main.show();
  });
  main.on("close", (event) => {
    if (process.platform === "darwin" && !quitting) {
      event.preventDefault();
      main.hide();
    }
  });
  main.on("closed", () => {
    main = null;
    app.quit();
  });
  overlay = makeWindow("overlay.html", {
    frame: false,
    transparent: true,
    backgroundColor: "#00000000",
    focusable: false,
    skipTaskbar: true,
    alwaysOnTop: true,
    hasShadow: false,
    minWidth: 1,
    minHeight: 1,
  });
  overlay.setIgnoreMouseEvents(true);
  overlay.setAlwaysOnTop(true, "screen-saver");
  edge = makeWindow("edge.html", {
    frame: false,
    transparent: true,
    backgroundColor: "#00000000",
    focusable: false,
    skipTaskbar: true,
    alwaysOnTop: true,
    hasShadow: false,
    minWidth: 1,
    minHeight: 1,
  });
  edge.setAlwaysOnTop(true, "screen-saver");
  if (process.platform === "darwin") setupTray();
  const settings = path.join(app.getPath("userData"), "identity.json");
  let identity;
  try {
    identity = JSON.parse(fs.readFileSync(settings, "utf8"));
  } catch {
    identity = { id: crypto.randomUUID(), name: os.hostname() };
    fs.mkdirSync(path.dirname(settings), { recursive: true });
    fs.writeFileSync(settings, JSON.stringify(identity));
  }
  engine = new Engine({
    name: identity.name,
    folder: path.join(app.getPath("downloads"), "AppSEND"),
    defaultMotion: preferences.method === "edge" ? "edge" : "plane",
    event: (type, data) => {
      if (type === "consent" && !data.incoming) {
        send("outgoing", data);
        engine.approve();
        return;
      }
      if(type === "consent" && data.incoming && preferences.autoReceive) {
        send("incoming", {name:data.name,size:data.size,peer:data.peer});
        engine.approve();
        return;
      }
      if (type === "animate") {
        setupEdges();
        overlay.showInactive();
        overlay.webContents.send("animation", {
          ...data,
          side: data.side || edgeSide,
        });
        setTimeout(
          () => {
            if (!overlay.isDestroyed()) overlay.hide();
          },
          Math.max(0, data.at - Date.now()) + 1800,
        );
      }
      if (type === "done" && data.path) allowedReceived.add(data.path);
      if (type === "error") overlay.hide();
      send(type, data);
      if (type === "consent" && (data.incoming || !data.initiated)) {
        main.show();
        main.focus();
      }
    },
  });
  try {
    const port = await engine.start();
    discovery = new Discovery(
      identity.id,
      identity.name,
      port,
      (p) => {
        peers = p;
        send("peers", p);
      },
      (e) => send("error", "Discovery: " + e.message),
    );
    const readyState = () => {
      send("identity", identity);
      send("peers", peers);
      send("status", "Ready · Private LAN discovery active");
    };
    main.webContents.on("did-finish-load", readyState);
    readyState();
  } catch (e) {
    send("error", e.message);
  }
  poll = setInterval(() => {
    if (!armed || !engine?.staged) {
      edgeSince = 0;
      return;
    }
    const p = screen.getCursorScreenPoint(),
      d = screen.getDisplayNearestPoint(p),
      b = d.workArea;
    const near =
      edgeSide === "top"
        ? p.y <= b.y + 18
        : edgeSide === "right"
          ? p.x >= b.x + b.width - 18
          : p.x <= b.x + 18;
    if (near) {
      if (!edgeSince) {
        setupEdges();
        edgeSince = Date.now();
      }
      if (!edgeFired && Date.now() - edgeSince > 700) {
        edgeFired = true;
        trigger();
      }
    } else {
      edgeSince = 0;
      edgeFired = false;
    }
  }, 40);
});
function trusted(e) {
  return [main?.webContents, edge?.webContents, quick?.webContents].includes(e.sender);
}
async function handleAction(e, { type, value }) {
  if (!trusted(e)) throw Error("Untrusted renderer");
  try {
    switch (type) {
      case "snapshot":
        return {
          peers,
          quickFileName: quickFile ? path.basename(quickFile) : null,
          busy: quickSending || !!engine?.active,
          name: os.hostname(),
          staged: engine?.publicStage(),
          ready: !!engine?.server?.listening,
          language: preferences.language,
          autoReceive: preferences.autoReceive,
          side: edgeSide,
          selected,
          armed,
          method: preferences.method || "normal",
        };
      case "quick-open":
        quick?.hide(); main.show(); main.focus();
        break;
      case "quick-send": {
        if (e.sender !== quick?.webContents) throw Error("Untrusted quick sender");
        if (quickSending || engine?.active) throw Error("A transfer is already active");
        const peer = peers.find(p => p.id === value?.peerId);
        if (!peer) throw Error("Selected device is no longer on the LAN");
        quickSending = true;
        try {
          let file = value.path === undefined ? quickFile : value.path;
          if (file === "") throw Error("Cannot read dropped file");
          if (!file) {
            const result = await dialog.showOpenDialog(quick, { properties: ["openFile"] });
            if (result.canceled) return;
            file = result.filePaths[0];
          }
          if (typeof file !== "string") throw Error("Invalid file");
          await engine.stageFile(file);
          if (!peers.some(p => p.id === peer.id)) throw Error("Selected device is no longer on the LAN");
          selected = peer.id;
          send("quick-sending", peer.name);
          engine.connect(peer);
          quickFile = null;
          send("quick-file", null);
          return { started: true };
        } finally { quickSending = false; }
        break;
      }
      case "autoReceive":
        if(typeof value !== "boolean")throw Error("Invalid receive setting");
        preferences.autoReceive=value;
        await fs.promises.writeFile(preferencesPath,JSON.stringify(preferences),"utf8");
        return value;
      case "language":
        if (!["ru", "en"].includes(value)) throw Error("Invalid language");
        preferences.language = value;
        await fs.promises.mkdir(path.dirname(preferencesPath), {
          recursive: true,
        });
        await fs.promises.writeFile(
          preferencesPath,
          JSON.stringify(preferences),
          "utf8",
        );
        return value;
      case "clear":
        if (engine.active)
          throw Error("Finish or cancel the active transfer first");
        engine.staged = null;
        send("staged", null);
        break;
      case "history": {
        if (!engine?.folder) return [];
        const entries = await fs.promises.readdir(engine.folder, {
          withFileTypes: true,
        });
        const files = await Promise.all(
          entries
            .filter((entry) => entry.isFile() && !entry.name.endsWith(".part"))
            .map(async (entry) => {
              const file = path.join(engine.folder, entry.name),
                stat = await fs.promises.stat(file);
              allowedReceived.add(file);
              return {
                name: entry.name.replace(/^[a-f0-9]{8}-/, ""),
                path: file,
                size: stat.size,
                time: stat.mtimeMs,
              };
            }),
        );
        return files.sort((a, b) => b.time - a.time).slice(0, 1000);
      }
      case "choose": {
        const result = await dialog.showOpenDialog(main, {
          properties: ["openFile"],
        });
        if (!result.canceled) await engine.stageFile(result.filePaths[0]);
        break;
      }
      case "drop":
        if (typeof value !== "string") throw Error("Invalid file");
        await engine.stageFile(value);
        if (e.sender === edge.webContents) trigger();
        break;
      case "clipboard":
        engine.stageText(clipboard.readText());
        break;
      case "text":
        engine.stageText(value);
        break;
      case "select":
        selected = peers.find((p) => p.id === value)?.id || null;
        break;
      case "method": {
        const met = value === "edge" ? "edge" : "normal";
        preferences.method = met;
        armed = met === "edge";
        setupEdges();
        if (engine) engine.defaultMotion = met === "edge" ? "edge" : "plane";
        await fs.promises.mkdir(path.dirname(preferencesPath), { recursive: true });
        await fs.promises.writeFile(preferencesPath, JSON.stringify(preferences), "utf8");
        return met;
      }
      case "arm":
        armed = !!value;
        preferences.method = armed ? "edge" : "normal";
        if (engine) engine.defaultMotion = armed ? "edge" : "plane";
        setupEdges();
        break;
      case "side":
        edgeSide = ["left", "right", "top"].includes(value) ? value : "top";
        setupEdges();
        break;
      case "send":
        if (!selected) throw Error("Select a discovered device");
        if (!engine.staged) throw Error("Stage a file or text");
        trigger();
        break;
      case "approve":
        engine.approve();
        break;
      case "cancel":
        engine.cancel();
        break;
      case "copy":
        if (typeof value === "string" && Buffer.byteLength(value) <= 1048576)
          clipboard.writeText(value);
        break;
      case "reveal":
        if (allowedReceived.has(value)) shell.showItemInFolder(value);
        break;
    }
  } catch (err) {
    send("error", err.message);
  }
}
ipcMain.handle("action", handleAction);
app.on("second-instance", () => {
  main?.show();
  main?.focus();
});
app.on("activate", () => {
  main?.show();
  main?.focus();
});
app.on("window-all-closed", () => app.quit());
app.on("before-quit", () => {
  quitting = true;
  tray?.destroy();
  clearInterval(poll);
  discovery?.close();
  engine?.close();
});
