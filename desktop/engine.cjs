const http = require("node:http"),
  fs = require("node:fs"),
  fsp = require("node:fs/promises"),
  path = require("node:path"),
  crypto = require("node:crypto");
const { once } = require("node:events");
const { WebSocket, WebSocketServer } = require("ws");
const mime = require("mime-types");
const {
  identity,
  derive,
  seal,
  open,
  sas,
  record,
  commit,
} = require("./crypto.cjs");
const CHUNK = 262144,
  MAX_RECORD = CHUNK + 37;
function safeName(name) {
  return (
    String(name)
      .replace(/[<>:"/\\|?*\x00-\x1f]/g, "_")
      .replace(/[. ]+$/g, "")
      .slice(0, 160) || "received-file"
  );
}
function validateOffer(m) {
  return (
    typeof m.name === "string" &&
    m.name.length <= 256 &&
    Number.isSafeInteger(m.size) &&
    m.size >= 0 &&
    ["file", "text", "link"].includes(m.kind) &&
    typeof m.mime === "string" &&
    m.mime.length <= 128 &&
    typeof m.id === "string" &&
    /^[0-9a-f-]{36}$/.test(m.id)
  );
}
class Engine {
  constructor({ name, folder, event, defaultMotion = "plane" }) {
    this.name = name;
    this.folder = folder;
    this.event = event; this.defaultMotion = defaultMotion;
    this.sessions = new Set();
    this.active = null;
    this.staged = null;
  }
  async start() {
    await fsp.mkdir(this.folder, { recursive: true });
    this.server = http.createServer((q, r) =>
      this.upload(q, r).catch((e) => {
        this.event("error", e.message);
        if (!r.headersSent) r.writeHead(400);
        r.end();
      }),
    );
    this.server.requestTimeout = 0;
    this.server.headersTimeout = 15000;
    this.server.maxConnections = 24;
    this.wss = new WebSocketServer({
      server: this.server,
      path: "/control",
      maxPayload: 65536,
    });
    this.wss.on("connection", (ws, req) =>
      this.attach(ws, req.socket.remoteAddress.replace("::ffff:", "")),
    );
    this.server.listen(0, "0.0.0.0");
    await once(this.server, "listening");
    return this.server.address().port;
  }
  async stageFile(file) {
    if (this.active) throw Error("Finish or cancel the active transfer first");
    const st = await fsp.stat(file);
    if (!st.isFile()) throw Error("Select a regular file");
    this.staged = {
      path: file,
      name: path.basename(file),
      size: st.size,
      mime: mime.lookup(file) || "application/octet-stream",
      kind: "file",
    };
    this.event("staged", this.publicStage());
  }
  stageText(text) {
    if (this.active) throw Error("Finish or cancel the active transfer first");
    if (typeof text !== "string" || !text || Buffer.byteLength(text) > 1048576)
      throw Error("Text must be 1 byte to 1 MiB");
    this.staged = {
      bytes: Buffer.from(text),
      name: /^https?:\/\/\S+$/.test(text) ? "Shared link.txt" : "Clipboard.txt",
      size: Buffer.byteLength(text),
      mime: "text/plain",
      kind: /^https?:\/\/\S+$/.test(text) ? "link" : "text",
    };
    this.event("staged", this.publicStage());
  }
  publicStage() {
    if (!this.staged) return null;
    const { name, size, kind } = this.staged;
    return { name, size, kind };
  }
  connect(peer) {
    if (this.active) throw Error("A transfer is already active");
    if (this.sessions.size >= 8) throw Error("Too many open sessions");
    const ws = new WebSocket(`ws://${peer.host}:${peer.port}/control`, {
      handshakeTimeout: 8000,
      maxPayload: 65536,
    });
    const s = this.attach(ws, peer.host);
    s.outbound = true;
    s.remotePort = peer.port;
    ws.on("open", () => this.hello(s));
    s.onReady = () =>
      this.staged ? this.offer(s, this.defaultMotion || "plane") : this.send(s, { type: "knock", motion: this.defaultMotion || "plane" });
  }
  attach(ws, host) {
    if (this.sessions.size >= 8) {
      ws.close(1013);
      return {};
    }
    const s = {
      ws,
      host,
      me: identity(),
      last: Date.now(),
      created: Date.now(),
      samples: [],
      received: 0,
      approved: false,
      tx: 0n,
      rx: 0n,
    };
    this.sessions.add(s);
    s.handshakeTimer = setTimeout(() => {
      if (!s.key) this.fail(s, "Handshake timed out");
    }, 10000);
    s.timer = setInterval(() => {
      if (
        Date.now() - s.last > 120000 ||
        (!s.start && Date.now() - s.created > 120000)
      )
        this.fail(s, "Session timed out");
      else if (ws.readyState === 1) ws.ping();
    }, 15000);
    ws.on("pong", () => (s.last = Date.now()));
    ws.on("error", (e) => this.fail(s, e.message));
    ws.on("close", () => {
      clearInterval(s.timer);
      clearTimeout(s.handshakeTimer);
      this.sessions.delete(s);
      if (this.active === s) {
        this.active = null;
        this.event("idle", null);
      }
      s.abort?.abort();
      s.request?.destroy();
    });
    ws.on("message", (data) => {
      s.queue = (s.queue || Promise.resolve())
        .then(() => this.message(s, data))
        .catch((e) => this.fail(s, e.message));
    });
    if (ws.readyState === 1) this.hello(s);
    return s;
  }
  hello(s) {
    s.ws.send(
      JSON.stringify({
        type: "hello",
        v: 1,
        commit: s.me.commit,
        name: this.name,
        port: this.server.address().port,
      }),
    );
  }
  send(s, m) {
    if (s.ws.readyState !== 1) throw Error("Peer disconnected");
    const seq = Buffer.alloc(8);
    seq.writeBigUInt64BE(s.tx++);
    s.ws.send(
      seal(
        s.key,
        Buffer.concat([seq, Buffer.from(JSON.stringify(m))]),
      ).toString("base64"),
    );
  }
  async message(s, data) {
    s.last = Date.now();
    if (!s.key) {
      const m = JSON.parse(data.toString());
      if (!s.remoteCommit) {
        if (
          m.type !== "hello" ||
          m.v !== 1 ||
          typeof m.commit !== "string" ||
          !/^[a-f0-9]{64}$/.test(m.commit) ||
          !Number.isInteger(m.port) ||
          m.port < 1024 ||
          m.port > 65535
        )
          throw Error("Invalid handshake");
        s.remoteCommit = m.commit;
        s.name = String(m.name).slice(0, 64);
        s.remotePort = m.port;
        s.ws.send(
          JSON.stringify({ type: "reveal", pub: s.me.pub, nonce: s.me.nonce }),
        );
        return;
      }
      if (
        m.type !== "reveal" ||
        typeof m.pub !== "string" ||
        m.pub.length > 256 ||
        typeof m.nonce !== "string" ||
        Buffer.from(m.nonce, "base64").length !== 32 ||
        commit(m.pub, m.nonce) !== s.remoteCommit
      )
        throw Error("Handshake commitment mismatch");
      s.key = derive(s.me, m.pub);
      s.code = sas(s.key);
      s.onReady?.();
      return;
    }
    const plain = open(s.key, Buffer.from(data.toString(), "base64"));
    if (plain.length < 8 || plain.readBigUInt64BE() !== s.rx++)
      throw Error("Replayed or out of order control message");
    const m = JSON.parse(plain.subarray(8));
    switch (m.type) {
      case "knock":
        if (this.staged && !this.active) this.offer(s, m.motion);
        else
          this.send(s, {
            type: "reject",
            reason: "No file or text staged on that device",
          });
        break;
      case "offer":
        if (this.active || !validateOffer(m)) {
          this.send(s, { type: "reject", reason: "Busy or invalid offer" });
          break;
        }
        this.active = s;
        s.offer = m;
        s.incoming = true;
        this.event("consent", {
          ...m,
          code: s.code,
          peer: s.name,
          incoming: true,
        });
        break;
      case "accept":
        if (
          !s.offer ||
          s.incoming ||
          s.accepted ||
          typeof m.token !== "string" ||
          !/^[0-9a-f]{64}$/.test(m.token)
        )
          throw Error("Unexpected acceptance");
        s.token = m.token;
        s.accepted = true;
        this.maybeStart(s);
        break;
      case "ping":
        this.send(s, { type: "pong", t: m.t, remote: Date.now() });
        break;
      case "pong":
        if (!s.syncing || m.t !== s.pingAt || !Number.isFinite(m.remote))
          throw Error("Unexpected timing response");
        s.samples.push({
          rtt: Date.now() - m.t,
          offset: m.remote - (Date.now() + m.t) / 2,
        });
        if (s.samples.length < 5) this.ping(s);
        else {
          const best = s.samples.sort((a, b) => a.rtt - b.rtt)[0];
          if (best.rtt > 500)
            throw Error("LAN latency too high to synchronize");
          s.start = Date.now() + 1200;
          this.send(s, { type: "begin", at: s.start + best.offset });
          s.syncing = false;
        }
        break;
      case "begin":
        if (
          !s.incoming ||
          !s.approved ||
          s.begun ||
          !Number.isFinite(m.at) ||
          m.at < Date.now() + 150 ||
          m.at > Date.now() + 5000
        )
          throw Error("Invalid animation schedule");
        s.begun = true;
        s.start = m.at;
        this.event("animate", {
          at: m.at,
          incoming: true,
          name: s.offer.name,
          motion: s.offer.motion || "edge",
          side: s.offer.senderPlatform === "Android" ? "top" : undefined,
        });
        this.send(s, { type: "ready" });
        break;
      case "ready":
        if (s.incoming || !s.start || s.sending || s.start < Date.now() + 100)
          throw Error("Peer missed animation deadline");
        s.sending = true;
        this.event("animate", {
          at: s.start,
          incoming: false,
          name: s.offer.name,
          motion: s.offer.motion || "edge",
        });
        setTimeout(
          () => this.sendData(s).catch((e) => this.fail(s, e.message)),
          Math.max(0, s.start + 350 - Date.now()),
        );
        break;
      case "complete":
        if (s.incoming || !s.sending) throw Error("Unexpected completion");
        this.event("done", { name: s.offer.name });
        s.ws.close();
        break;
      case "reject":
        throw Error(String(m.reason || "Declined").slice(0, 200));
      case "cancel":
        throw Error("Peer cancelled");
      default:
        throw Error("Unknown control message");
    }
  }
  offer(s, motion = this.defaultMotion || "plane") {
    if (this.active) throw Error("Busy");
    s.source = this.staged;
    s.offer = {
      motion: motion === "plane" ? "plane" : "edge",
      senderPlatform: "Windows",
      type: "offer",
      id: crypto.randomUUID(),
      ...this.publicStage(),
      mime: this.staged.mime,
    };
    this.active = s;
    this.send(s, s.offer);
    this.event("consent", {
      ...s.offer,
      code: s.code,
      peer: s.name,
      incoming: false,
      initiated: !!s.outbound,
    });
  }
  approve() {
    const s = this.active;
    if (!s || s.approved) return;
    s.approved = true;
    if (s.incoming) {
      s.token = crypto.randomBytes(32).toString("hex");
      this.send(s, { type: "accept", token: s.token });
    } else this.maybeStart(s);
    this.event("status", "Waiting for receiver to accept…");
  }
  maybeStart(s) {
    if (s.approved && s.accepted && !s.syncing) {
      s.syncing = true;
      this.ping(s);
    }
  }
  ping(s) {
    s.pingAt = Date.now();
    this.send(s, { type: "ping", t: s.pingAt });
  }
  cancel() {
    const s = this.active;
    if (s) {
      try {
        this.send(s, { type: "cancel" });
      } catch {}
      this.fail(s, "Transfer cancelled");
    }
  }
  fail(s, message) {
    if (s.failed) return;
    s.failed = true;
    if (this.active === s || s.outbound) this.event("error", message);
    s.ws?.close();
    s.abort?.abort();
    s.request?.destroy();
  }
  async sendData(s) {
    const req = http.request({
      hostname: s.host,
      port: s.remotePort,
      path: "/upload/" + s.offer.id,
      method: "POST",
      headers: {
        Authorization: "Bearer " + s.token,
        "Content-Type": "application/octet-stream",
      },
      timeout: 30000,
    });
    s.request = req;
    const response = new Promise((resolve, reject) => {
      req.once("response", (r) => {
        r.resume();
        r.once("end", () =>
          r.statusCode === 200
            ? resolve()
            : reject(Error("Receiver rejected upload: " + r.statusCode)),
        );
      });
      req.once("error", reject);
    });
    response.catch(() => {});
    req.on("timeout", () => req.destroy(Error("Upload stalled")));
    const hash = crypto.createHash("sha256");
    let index = 0,
      total = 0,
      last = 0;
    const input = s.source.path
      ? fs.createReadStream(s.source.path, { highWaterMark: CHUNK })
      : [s.source.bytes];
    try {
      for await (const raw of input) {
        for (let off = 0; off < raw.length; off += CHUNK) {
          const data = raw.subarray(off, off + CHUNK);
          hash.update(data);
          total += data.length;
          if (total > s.offer.size) throw Error("Source changed size");
          if (!req.write(record(s.key, index++, data)))
            await once(req, "drain");
          if (Date.now() - last > 80) {
            this.event("progress", { bytes: total, size: s.offer.size });
            last = Date.now();
          }
        }
      }
      if (total !== s.offer.size) throw Error("Source changed size");
      req.end(record(s.key, index, hash.digest(), true));
      await response;
    } catch (e) {
      req.destroy();
      throw e;
    }
  }
  async upload(req, res) {
    const s = this.active;
    if (
      req.method !== "POST" ||
      !s?.incoming ||
      !s.approved ||
      !s.begun ||
      s.uploading ||
      req.url !== "/upload/" + s.offer.id ||
      req.headers.authorization !== "Bearer " + s.token
    ) {
      res.writeHead(403).end();
      req.resume();
      return;
    }
    s.uploading = true;
    s.token = null;
    s.request = req;
    req.setTimeout(30000, () => req.destroy(Error("Upload stalled")));
    const tmp = path.join(
      this.folder,
      ".edgebeam-" + crypto.randomUUID() + ".part",
    );
    let out;
    try {
      out = await fsp.open(tmp, "wx");
      let buf = Buffer.alloc(0),
        index = 0,
        total = 0,
        final = false,
        last = 0;
      const hash = crypto.createHash("sha256");
      for await (const chunk of req) {
        buf = Buffer.concat([buf, chunk]);
        while (buf.length >= 4) {
          const n = buf.readUInt32BE();
          if (n < 37 || n > MAX_RECORD) throw Error("Invalid record size");
          if (buf.length < n + 4) break;
          if (final) throw Error("Data after final record");
          const data = open(s.key, buf.subarray(4, n + 4));
          buf = buf.subarray(n + 4);
          if (data.readBigUInt64BE(1) !== BigInt(index++))
            throw Error("Out of order record");
          if (data[0] === 1) {
            if (
              data.length !== 41 ||
              total !== s.offer.size ||
              !crypto.timingSafeEqual(data.subarray(9), hash.digest())
            )
              throw Error("Integrity check failed");
            final = true;
          } else if (data[0] === 0) {
            const part = data.subarray(9);
            total += part.length;
            if (total > s.offer.size)
              throw Error("Exceeded advertised file size");
            hash.update(part);
            let written = 0;
            while (written < part.length) {
              const result = await out.write(
                part,
                written,
                part.length - written,
              );
              if (result.bytesWritten === 0)
                throw Error("Could not write received data");
              written += result.bytesWritten;
            }
          } else throw Error("Unknown record");
          if (Date.now() - last > 80) {
            this.event("progress", { bytes: total, size: s.offer.size });
            last = Date.now();
          }
        }
      }
      if (!final || buf.length) throw Error("Incomplete upload");
      await out.sync();
      await out.close();
      out = null;
      let dest;
      for (let i = 0; ; i++) {
        dest = path.join(
          this.folder,
          crypto.randomUUID().slice(0, 8) + "-" + safeName(s.offer.name),
        );
        try {
          await fsp.link(tmp, dest);
          break;
        } catch (e) {
          if (e.code !== "EEXIST" || i > 3) throw e;
        }
      }
      await fsp.unlink(tmp);
      let text = null;
      if (s.offer.kind !== "file" && s.offer.size <= 1048576)
        text = await fsp.readFile(dest, "utf8");
      res.writeHead(200).end("OK");
      this.send(s, { type: "complete" });
      this.event("done", { name: s.offer.name, path: dest, text });
    } catch (e) {
      res.destroy();
      this.fail(s, e.message);
      throw e;
    } finally {
      await out?.close().catch(() => {});
      await fsp.unlink(tmp).catch(() => {});
    }
  }
  close() {
    for (const s of this.sessions) s.ws.terminate();
    this.wss.close();
    this.server.close();
  }
}
module.exports = { Engine, safeName, validateOffer };
