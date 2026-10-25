const { test } = require('node:test');
const assert = require('node:assert/strict');
const crypto = require('node:crypto');
const { readRecords } = require('../engine.cjs');
const { record, seal, open } = require('../crypto.cjs');
const CHUNK = 262144;
async function collect(source) {
  const result = [];
  for await (const packet of readRecords(source)) result.push(Buffer.from(packet));
  return result;
}
function* split(bytes, sizes) {
  let offset = 0, index = 0;
  while (offset < bytes.length) {
    const end = Math.min(bytes.length, offset + sizes[index++ % sizes.length]);
    yield bytes.subarray(offset, end);
    offset = end;
  }
}

test('optimized encryption is byte-compatible with the original record format', () => {
  const key = crypto.randomBytes(32), nonces = new Set();
  for (const length of [0, 1, 16, 4096, CHUNK]) {
    for (const final of [false, true]) {
      const data = crypto.randomBytes(length);
      const packet = record(key, 123, data, final);
      const header = Buffer.alloc(9);
      header[0] = Number(final); header.writeBigUInt64BE(123n, 1);
      const body = seal(key, Buffer.concat([header, data]), packet.subarray(4, 16));
      assert.equal(packet.readUInt32BE(), body.length);
      assert.deepEqual(packet.subarray(4), body);
      assert.deepEqual(open(key, packet.subarray(4)), Buffer.concat([header, data]));
      nonces.add(packet.subarray(4, 16).toString('hex'));
    }
  }
  assert.equal(nonces.size, 10);
});

test('record parser handles split headers, payloads and coalesced frames', async () => {
  const key = crypto.randomBytes(32);
  const records = [record(key, 0, Buffer.from('abc')), record(key, 1, crypto.randomBytes(CHUNK)), record(key, 2, crypto.randomBytes(32), true)];
  const expected = records.map(p => p.subarray(4));
  const bytes = Buffer.concat(records);
  for (const sizes of [[bytes.length], [4, CHUNK + 37], [1, 2, 3, 65536, 8, 23, 262144]]) {
    assert.deepEqual(await collect(split(bytes, sizes)), expected);
  }
  assert.deepEqual(await collect(split(records[0], [1])), [expected[0]]);
});

test('record parser rejects invalid sizes and every incomplete header/payload', async () => {
  for (const length of [0, 36, CHUNK + 38, 0xffffffff]) {
    const header = Buffer.alloc(4); header.writeUInt32BE(length);
    await assert.rejects(collect([header]), /Invalid record size/);
  }
  const bytes = record(crypto.randomBytes(32), 0, Buffer.from('abc'));
  for (let n = 1; n < bytes.length; n++) {
    await assert.rejects(collect(split(bytes.subarray(0, n), [1, 2, 7])), /Incomplete upload/);
  }
});
