# File transfer optimization

This fork-only change reduces desktop memory copying without changing the
wire format or requiring an Android update.

- Assemble fragmented TCP records once; use a view for complete records.
  The old parser repeatedly concatenated the full incomplete record with each
  incoming network chunk.
- Encrypt the header and payload incrementally and assemble the wire packet in
  one destination buffer, removing intermediate full-size concatenations.
- Return decrypted bytes after AES-GCM authentication without copying the
  plaintext into another buffer.

Record length limits, order checks, AES-256-GCM authentication, final SHA-256,
backpressure, short-write handling, fsync and partial-file cleanup remain active.

## Validation

26 Node tests passed. New checks cover the original wire layout, fragmented and
coalesced records, every truncation of a small frame, invalid record lengths,
trailing bytes and duplicate final records.

A local 128 MiB file-transfer benchmark ran five times per version. Median
throughput was 211.6 MiB/s before and 311.1 MiB/s after (~47% higher). Every
received file matched SHA-256. The timer includes encrypted upload and receiver
completion/fsync; it excludes discovery, handshake and animation preparation.

This is an IPv4 localhost measurement on one Mac, not a Wi-Fi benchmark. Runs
varied, were performed in separate processes and can be affected by CPU/disk
cache and system load. No fixed speedup is promised on a phone or WLAN. The
Android client was not changed; a new phone-to-Mac hardware benchmark has not
been performed for this version.

To run the same benchmark on this version:

```sh
cd desktop
node test/transfer-bench.cjs
```

An optional first argument selects an alternative engine.cjs (with its crypto.cjs
and dependencies available), and a second argument selects the run count.
