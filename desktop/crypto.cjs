const c = require("node:crypto");
const INFO = Buffer.from("edgebeam/v1");
function identity() {
  const { privateKey, publicKey } = c.generateKeyPairSync("ec", {
    namedCurve: "prime256v1",
  });
  const pub = publicKey
      .export({ type: "spki", format: "der" })
      .toString("base64"),
    nonce = c.randomBytes(32).toString("base64");
  return { privateKey, pub, nonce, commit: commit(pub, nonce) };
}
function commit(pub, nonce) {
  return c
    .createHash("sha256")
    .update(Buffer.from(pub, "base64"))
    .update(Buffer.from(nonce, "base64"))
    .digest("hex");
}
function derive(me, remote) {
  const a = Buffer.from(me.pub, "base64"),
    b = Buffer.from(remote, "base64");
  const salt = c
    .createHash("sha256")
    .update(Buffer.concat([a, b].sort(Buffer.compare)))
    .digest();
  const shared = c.diffieHellman({
    privateKey: me.privateKey,
    publicKey: c.createPublicKey({ key: b, format: "der", type: "spki" }),
  });
  return Buffer.from(c.hkdfSync("sha256", shared, salt, INFO, 32));
}
function seal(key, bytes, nonce = c.randomBytes(12)) {
  const cipher = c.createCipheriv("aes-256-gcm", key, nonce);
  return Buffer.concat([
    nonce,
    cipher.update(bytes),
    cipher.final(),
    cipher.getAuthTag(),
  ]);
}
function open(key, bytes) {
  if (bytes.length < 28) throw Error("Truncated encrypted record");
  const d = c.createDecipheriv("aes-256-gcm", key, bytes.subarray(0, 12));
  d.setAuthTag(bytes.subarray(-16));
  const plain = d.update(bytes.subarray(12, -16));
  d.final(); // Authenticate before returning any plaintext.
  return plain;
}
function sas(key) {
  return String(
    c.createHash("sha256").update(key).update("sas").digest().readUInt32BE() %
      1000000,
  ).padStart(6, "0");
}
function record(key, index, data, final = false) {
  const h = Buffer.alloc(9);
  h[0] = final ? 1 : 0;
  h.writeBigUInt64BE(BigInt(index), 1);
  const packet = Buffer.allocUnsafe(data.length + 41);
  packet.writeUInt32BE(packet.length - 4);
  const nonce = packet.subarray(4, 16);
  c.randomFillSync(nonce);
  const cipher = c.createCipheriv("aes-256-gcm", key, nonce);
  let offset = 16;
  for (const part of [cipher.update(h), cipher.update(data), cipher.final(), cipher.getAuthTag()]) {
    part.copy(packet, offset);
    offset += part.length;
  }
  return packet;
}
module.exports = { identity, derive, seal, open, sas, record, commit };
