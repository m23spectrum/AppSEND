const { test } = require("node:test"),
  assert = require("node:assert/strict");
const crypto = require("node:crypto"),
  fs = require("node:fs/promises"),
  path = require("node:path"),
  os = require("node:os"),
  http = require("node:http");
const { identity, derive, seal, open, sas, record } = require("../crypto.cjs");
const { Engine, safeName, validateOffer } = require("../engine.cjs");
test("fixed Node/Java interop vector", () => {
  const v = require("../../protocol/golden.json");
  const me = {
    pub: v.public,
    privateKey: crypto.createPrivateKey({
      key: Buffer.from(v.private, "base64"),
      type: "pkcs8",
      format: "der",
    }),
  };
  const key = derive(me, v.remote);
  assert.equal(key.toString("hex"), v.key);
  assert.equal(sas(key), v.sas);
  assert.deepEqual(
    open(key, Buffer.from(v.packet, "base64")),
    Buffer.from(v.plain, "base64"),
  );
});
test("ECDH agreement and six digit authentication code", () => {
  const a = identity(),
    b = identity();
  const x = derive(a, b.pub),
    y = derive(b, a.pub);
  assert.deepEqual(x, y);
  assert.equal(sas(x), sas(y));
  assert.match(sas(x), /^\d{6}$/);
  assert.throws(() => derive(a, "invalid"));
});
test("GCM detects corruption, truncation and wrong keys", () => {
  const key = crypto.randomBytes(32),
    plain = crypto.randomBytes(262144),
    packet = seal(key, plain);
  assert.deepEqual(open(key, packet), plain);
  packet[25] ^= 1;
  assert.throws(() => open(key, packet));
  assert.throws(() => open(key, Buffer.alloc(8)));
  assert.throws(() => open(crypto.randomBytes(32), seal(key, plain)));
});
test("record layout and file metadata validation", () => {
  const key = crypto.randomBytes(32),
    r = record(key, 9, Buffer.from("abc"));
  assert.equal(r.readUInt32BE(), r.length - 4);
  const p = open(key, r.subarray(4));
  assert.equal(p.readBigUInt64BE(1), 9n);
  assert.equal(p.subarray(9).toString(), "abc");
  assert.equal(safeName("../../bad\\name:?.txt"), ".._.._bad_name__.txt");
  assert.equal(validateOffer({ name: "a", size: -1, kind: "file" }), false);
});
function fixture(t, options = {}) {
  let a, b, resolveDone, rejectDone;
  const events = [];
  const done = new Promise((r, j) => {
    resolveDone = r;
    rejectDone = j;
  });
  done.catch(() => {});
  const setup = (async () => {
    const root = await fs.mkdtemp(path.join(os.tmpdir(), "edgebeam-test-"));
    const event = (side) => (type, data) => {
      events.push({ side, type, data });
      if (type === "consent") {
        if (side === "a" && !options.senderApprove) return;
        setTimeout(() => {
          (side === "a" ? a : b).approve();
        }, 10);
      }
      if (type === "done" && side === "a") resolveDone();
      if (type === "error") rejectDone(Error(`${side}: ${data}`));
    };
    a = new Engine({
      name: "Sender",
      folder: path.join(root, "a"),
      event: event("a"),
    });
    b = new Engine({
      name: "Receiver",
      folder: path.join(root, "b"),
      event: event("b"),
    });
    const ports = await Promise.all([a.start(), b.start()]);
    t.after(async () => {
      a.close();
      b.close();
      await fs.rm(root, { recursive: true, force: true });
    });
    return { root, a, b, ports, events, done };
  })();
  return setup;
}
test(
  "real WebSocket handshake + streamed multi-chunk HTTP file + SHA-256",
  { timeout: 15000 },
  async (t) => {
    const f = await fixture(t, { senderApprove: true });
    const bytes = crypto.randomBytes(3 * 262144 + 37),
      file = path.join(f.root, "payload.bin");
    await fs.writeFile(file, bytes);
    await f.a.stageFile(file);
    f.a.connect({ host: "127.0.0.1", port: f.ports[1] });
    await f.done;
    const receive = f.events.find(
      (e) => e.side === "b" && e.type === "done",
    ).data;
    assert.deepEqual(await fs.readFile(receive.path), bytes);
    assert.equal(f.events.filter((e) => e.type === "animate").length, 2);
    assert.equal(
      f.events.find((e) => e.side === "a" && e.type === "consent").data.code,
      f.events.find((e) => e.side === "b" && e.type === "consent").data.code,
    );
    assert.deepEqual(
      (await fs.readdir(path.join(f.root, "b"))).filter((n) =>
        n.endsWith(".part"),
      ),
      [],
    );
  },
);
test(
  "receiver consent alone cannot trigger an upload",
  { timeout: 5000 },
  async (t) => {
    const f = await fixture(t);
    f.a.stageText("consent gate");
    f.a.connect({ host: "127.0.0.1", port: f.ports[1] });
    await new Promise((r) => setTimeout(r, 500));
    assert.equal(
      f.events.some((e) => e.type === "animate"),
      false,
    );
    assert.equal(
      f.events.some((e) => e.type === "done"),
      false,
    );
    assert.deepEqual(await fs.readdir(path.join(f.root, "b")), []);
    f.a.cancel();
  },
);
test("empty file and text transfers", { timeout: 15000 }, async (t) => {
  const f = await fixture(t, { senderApprove: true });
  const file = path.join(f.root, "empty");
  await fs.writeFile(file, "");
  await f.a.stageFile(file);
  f.a.connect({ host: "127.0.0.1", port: f.ports[1] });
  await f.done;
  const receive = f.events.find(
    (e) => e.side === "b" && e.type === "done",
  ).data;
  assert.equal((await fs.stat(receive.path)).size, 0);
});
test("unsolicited HTTP upload is denied", { timeout: 5000 }, async (t) => {
  const f = await fixture(t);
  const status = await new Promise((r, j) => {
    const q = http.request(
      {
        hostname: "127.0.0.1",
        port: f.ports[1],
        path: "/upload/random",
        method: "POST",
      },
      (s) => {
        s.resume();
        r(s.statusCode);
      },
    );
    q.on("error", j);
    q.end("bad");
  });
  assert.equal(status, 403);
  assert.deepEqual(await fs.readdir(path.join(f.root, "b")), []);
});
test("clipboard links preserve UTF-8", { timeout: 15000 }, async (t) => {
  const f = await fixture(t, { senderApprove: true });
  f.a.stageText("https://example.org/привет?q=🌿");
  f.a.connect({ host: "127.0.0.1", port: f.ports[1] });
  await f.done;
  assert.equal(
    f.events.find((e) => e.side === "b" && e.type === "done").data.text,
    "https://example.org/привет?q=🌿",
  );
});
async function authorizedClient(f, t) {
  const { WebSocket } = require("ws"),
    { once } = require("node:events");
  const ws = new WebSocket(`ws://127.0.0.1:${f.ports[1]}/control`);
  t.after(() => ws.terminate());
  let key,
    remoteCommit,
    tx = 0n,
    rx = 0n;
  const me = identity(),
    messages = [],
    waiters = [];
  function emit(m) {
    messages.push(m);
    for (const wake of waiters.splice(0)) wake();
  }
  ws.on("message", (raw) => {
    if (!key) {
      const m = JSON.parse(raw);
      if (m.type === "hello") {
        remoteCommit = m.commit;
        emit({ type: "hello" });
      } else {
        assert.equal(
          require("../crypto.cjs").commit(m.pub, m.nonce),
          remoteCommit,
        );
        key = derive(me, m.pub);
        emit({ type: "reveal" });
      }
    } else {
      const p = open(key, Buffer.from(raw.toString(), "base64"));
      assert.equal(p.readBigUInt64BE(), rx++);
      emit(JSON.parse(p.subarray(8)));
    }
  });
  async function wait(type) {
    while (true) {
      const m = messages.find((m) => m.type === type);
      if (m) return m;
      await new Promise((r) => waiters.push(r));
    }
  }
  await once(ws, "open");
  ws.send(
    JSON.stringify({
      type: "hello",
      v: 1,
      commit: me.commit,
      name: "Protocol tester",
      port: 45872,
    }),
  );
  await wait("hello");
  ws.send(JSON.stringify({ type: "reveal", pub: me.pub, nonce: me.nonce }));
  await wait("reveal");
  const send = (m) => {
    const seq = Buffer.alloc(8);
    seq.writeBigUInt64BE(tx++);
    const encoded = seal(
      key,
      Buffer.concat([seq, Buffer.from(JSON.stringify(m))]),
    ).toString("base64");
    ws.send(encoded);
    return encoded;
  };
  const id = crypto.randomUUID();
  send({
    type: "offer",
    id,
    name: "malicious.bin",
    size: 3,
    kind: "file",
    mime: "application/octet-stream",
  });
  const { token } = await wait("accept");
  send({ type: "begin", at: Date.now() + 1000 });
  await wait("ready");
  return { key, token, id, ws, send };
}
for (const scenario of ["tampered", "truncated", "sequence", "hash"])
  test(
    `reject ${scenario} upload and remove partial file`,
    { timeout: 6000 },
    async (t) => {
      const f = await fixture(t);
      const a = await authorizedClient(f, t);
      let bytes = record(
        a.key,
        scenario === "sequence" ? 8 : 0,
        Buffer.from("abc"),
      );
      if (scenario === "tampered") bytes[20] ^= 1;
      else if (scenario === "truncated")
        bytes = bytes.subarray(0, bytes.length - 3);
      else
        bytes = Buffer.concat([
          bytes,
          record(
            a.key,
            1,
            scenario === "hash"
              ? Buffer.alloc(32)
              : crypto.createHash("sha256").update("abc").digest(),
            true,
          ),
        ]);
      await new Promise((resolve) => {
        const q = http.request(
          {
            hostname: "127.0.0.1",
            port: f.ports[1],
            path: "/upload/" + a.id,
            method: "POST",
            headers: { Authorization: "Bearer " + a.token },
          },
          (r) => {
            r.resume();
            r.on("end", resolve);
          },
        );
        q.on("error", resolve);
        q.end(bytes);
      });
      await new Promise((r) => setTimeout(r, 80));
      assert.deepEqual(await fs.readdir(path.join(f.root, "b")), []);
      assert.ok(f.events.some((e) => e.side === "b" && e.type === "error"));
    },
  );
test(
  "replayed encrypted control message closes the session",
  { timeout: 5000 },
  async (t) => {
    const f = await fixture(t);
    const a = await authorizedClient(f, t);
    const encoded = a.send({ type: "ping", t: Date.now() });
    a.ws.send(encoded);
    await new Promise((r) => a.ws.once("close", r));
    assert.ok(
      f.events.some((e) => e.type === "error" && /Replayed/.test(e.data)),
    );
  },
);
