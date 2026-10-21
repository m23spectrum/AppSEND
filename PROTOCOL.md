# AppSEND LAN protocol, version 1

All integers below are big-endian on the binary wire. All JSON is UTF-8. Peer IPs are obtained from incoming UDP datagrams, never supplied by announcement JSON. IPv4 is the discovery/transport address family in this implementation.

## Discovery

Bind UDP 45871 with reuse enabled. Every two seconds, broadcast this JSON to `255.255.255.255` and each active interface's directed broadcast address:

```json
{"magic":"edgebeam","v":1,"id":"installation-uuid","name":"device label","platform":"Windows","port":49152}
```

`port` is the actual HTTP/WebSocket listener port. Android uses `platform: "Android"`. IDs are 1–64 ASCII alphanumeric/hyphen characters. Ignore yourself, invalid ports, unsupported versions and datagrams larger than 2048 bytes. Update a peer from the datagram source address. Expire it after seven seconds. Android holds a multicast lock while discovery runs; no location lookup is performed.

## Committed key agreement

Connect WebSocket `/control` on the advertised port. Maximum frame payload is 65536 bytes. Both parties generate a fresh P-256 ECDH key pair and a fresh 32-byte random commitment nonce for each connection.

1. Both send a plaintext `hello`: `type`, `v:1`, `name`, `port`, and `commit` = lowercase hex SHA-256(SPKI public-key DER || commitment nonce). No public key is revealed yet.
2. Only after receiving the remote commitment, send plaintext `reveal`: `type:"reveal"`, `pub` = Base64 SPKI public-key DER, `nonce` = Base64 commitment nonce.
3. Verify the reveal against the stored commitment. Reject a duplicate hello, wrong type, invalid curve/key or mismatched commitment.
4. Compute the P-256 shared secret. Sort the two SPKI byte arrays using unsigned lexicographic byte order. `salt = SHA256(lowerSPKI || higherSPKI)`.
5. Derive 32 bytes with HKDF-SHA256: IKM = shared secret, salt as above, info = UTF-8 `edgebeam/v1`. Java implements Extract with HMAC(salt, shared) and Expand with HMAC(PRK, info || 0x01).
6. Authentication code: take the first four bytes of SHA256(key || UTF-8 `sas`) as unsigned uint32, reduce modulo 1,000,000, and left-pad to six decimal digits. This calculation remains for wire/test compatibility. AppSEND 1.4 does not display the code; it therefore does not authenticate the first key agreement. Commit-before-reveal alone is not peer authentication.

Discovery and hello labels are public, untrusted convenience labels. This protocol does not use public CAs or persistent device keys. There is no persistent trust database or authenticated peer identity in the 1.2 UI. Independent security review is required before treating this custom protocol as production-assured.

## Encrypted control messages

Each endpoint has an independent transmit counter starting at zero and expects an independent receive counter starting at zero. Encrypt `uint64 counter || UTF8(JSON)` with AES-256-GCM, using a fresh random 12-byte nonce and 16-byte authentication tag. Send Base64(nonce || ciphertext || tag) as a WebSocket text frame. Reject failed tags or any counter other than the next expected value. Counters are inside authenticated plaintext.

| Type | Required fields | Meaning |
|---|---|---|
| `knock` | none | Ask the remote peer to offer its staged item |
| `offer` | `id`, `name`, `size`, `mime`, `kind` | File UUID, name, exact byte count, MIME, `file` / `text` / `link` |
| `accept` | `token` | Receiver approved; 32 random bytes as lowercase hex, one-use upload capability |
| `ping` | `t` | Sender UTC milliseconds |
| `pong` | `t`, `remote` | Echo `t` and receiver UTC milliseconds |
| `begin` | `at` | Animation start in receiver UTC milliseconds |
| `ready` | none | Receiver accepted the animation schedule |
| `complete` | none | Receiver verified and committed the complete file |
| `reject` | `reason` | Decline or state error |
| `cancel` | none | Cancel and clean up |

Only one offered/accepted transfer is active per application. Busy recipients reject offers. A knock with no staged item is rejected. Offers constrain name to 256 characters, MIME to 128, UUID to its canonical lowercase UUID character shape, and size to 0…2^53−1. Duplicate approvals and invalid state transitions fail.

AppSEND 1.1 adds optional encrypted `motion` (`edge` or `plane`) and `senderPlatform` fields to offers. A `knock` may include the preferred `motion`; the staged-item sender echoes that choice in its offer. Missing motion defaults to `edge`. These fields choose a visual transition and do not change consent or data framing. From AppSEND 1.3 Android edge animations exit/enter at the logical top near the physical top/proximity edge; Windows receives at the monitor top. This matches the phone held above the monitor facing down. Plane mode uses a flight transition. Older peers may ignore these optional fields.

The sender's local authorization and the receiver's `accept` are **both** required before synchronization starts. In 1.2, a locally initiated Send/gesture authorizes the sender without a second dialog. Incoming file offers always require the protocol `accept`, but from AppSEND 1.4 the apps send it automatically by default. This is not human approval. The automatic-receive setting can restore a receive dialog. A remote `knock` does not authorize reading a staged item: its owner receives a separate send approval. The Windows engine marks local outgoing offers with the local-only `initiated` event flag; this is not accepted from the wire. Incoming automatic acceptance never approves a remote `knock` that asks to read a staged item; that remains a separate owner authorization.

## Animation synchronization

The sender performs five ping/pong exchanges and picks the sample with minimum RTT. For that sample:

```text
offset = receiverTime - (senderSendTime + senderReceiveTime) / 2
senderStart = senderNow + 1200 ms
receiverStart = senderStart + offset
```

Reject RTT above 500 ms. The receiver accepts a schedule only if it is 150…5000 ms in its future, then sends `ready`. The sender requires at least 100 ms remaining when `ready` arrives. Both schedule their animations. Upload begins at senderStart + 350 ms. Android vibrates near the 350 ms animation peak. Rendering uses requestAnimationFrame with compositor transforms on Windows and Compose's frame clock with draw/layer state reads on Android. Version 1.2 departs during 0–350 ms and arrives during 350–1000 ms; the landing overshoots and settles, then fades by 1550 ms. A swipe may already have displayed a local departure before approval; Android records this in its local-only departureShown flag and suppresses a duplicate departure. No new trusted wire field is introduced. The overlay is not proof of a completed transfer.

Clock symmetry is an approximation; Wi-Fi jitter, frame scheduling, background throttling and display latency can differ. A transfer-start visual is not a completion claim. Byte progress and integrity confirmation remain independent.

## HTTP data records

Sender performs a streaming `POST /upload/{offer.id}` with `Authorization: Bearer {token}` and `Content-Type: application/octet-stream`. HTTP chunked transfer encoding is permitted. Files are not multipart-buffered or Base64-encoded. The recipient requires an approved, scheduled, incoming offer, matching ID and unused token. The token is consumed when the upload starts.

HTTP body consists of records, independent of HTTP chunk boundaries:

```text
uint32 N
12-byte GCM nonce
N−28 bytes of ciphertext
16-byte GCM tag
```

Decrypted record:

```text
uint8  type           0 = file data, 1 = final digest
uint64 index          starts at 0, increments for every record
bytes  payload        at most 262144 bytes of data; or exactly 32 digest bytes
```

`N` must be between 37 and 262181 inclusive. Data records update SHA-256 and the received byte count. A final record must contain SHA-256 of all plaintext file bytes, at exactly the advertised total size. Empty files consist of a final record at index zero with SHA-256(empty). Reject oversize bodies, wrong indices, tag failure, wrong digest, unexpected type, trailing records or premature EOF. HTTP transfer decoding and arbitrary network fragmentation must not affect record boundaries.

Write only to a private random `.edgebeam-UUID.part` file. Flush to disk after verification. Publish under a sanitized name with a random UUID prefix, avoiding overwrite. On failure remove the partial output and close the session. Return HTTP 200 and encrypted `complete` only after the verified file has been committed. User text is never evaluated as HTML or executed.

The upload token travels in a plaintext HTTP header, but the file body is authenticated and encrypted. An observer can learn IPs, device labels, traffic volume, timing, HTTP resource UUIDs and the one-use token. The token cannot create valid encrypted records. Network attackers can still deny service. The transport is not TLS and is not designed to conceal that metadata.

WebSocket ping/pong provides connection health. Control inactivity and consent wait expire after approximately two minutes; streaming progress refreshes transfer activity. Windows rejects stalled HTTP sockets after 30 seconds. Android's client sets 8-second connect and 30-second read timeouts, with session timeout/stream closure providing server-side cancellation. There is no transfer resume or persistent trust database.

`protocol/golden.json` and both test suites pin the ECDH/HKDF, GCM and SAS representations. The fixture private key is deliberately public test data and is never used by either application.
