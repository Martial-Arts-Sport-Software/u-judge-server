# ADR-006: Local TLS And Mobile Trust

- Status: Accepted
- Date: 2026-09-26
- Decision: option A, approved by the product owner on 2026-09-26
- Requirements: `DEV-004`, `DEV-006`, `NFR-005`, `NFR-006`, `SYS-001`; client `CLI-014`, `CLI-015`, `CLI-017`, `CLI-091`, `CLI-093`
- Refines: ADR-002 and ADR-004 "TLS with locally managed certificate and trust flow"

## Context

ADR-002 and ADR-004 require TLS with a locally managed certificate and trust flow but do not select one. The server issues a
reconnect credential in `GET /v1/pairing-status/{requestId}` only over a secure transport, and the runtime currently serves
plain HTTP from the Ktor CIO engine, so pairing cannot finish end to end. The venue network is an isolated Wi-Fi router
without internet (`SYS-001`), without a DNS name for the peer, and with a desktop IP address assigned by DHCP. Pilot devices
are an Android 11 phone, an iPhone on iOS 26 and Android emulators; judges are not expected to install profiles or change
system settings.

The Ktor CIO server engine has no TLS connector; the Netty engine supports one.

## Options

| Option | Description | Advantages | Risks | Pilot fit |
| --- | --- | --- | --- | --- |
| A. Self-signed peer certificate, pinned on first use with a verification code | Desktop generates one certificate per peer; the client pins its public-key hash on first contact and shows a short code that the operator compares with the desktop before approval | No OS settings, no camera, works offline and with any IP; approval step already exists | TOFU is only as strong as the code comparison; certificate rotation requires re-pairing | Recommended |
| B. Self-signed certificate delivered by QR code | Desktop shows a QR code with host, port and fingerprint; the judge scans it | Strong binding without manual comparison; replaces manual host entry | Camera permission and QR scanning on Android and iOS; extra UI in both apps | Post-v1 upgrade path |
| C. Local CA installed on phones | Desktop creates a CA; devices install it as a trusted root | Standard TLS validation | iOS profile install and Android user-CA opt-in per device; operator burden at every event | Rejected |
| D. Public CA certificate for a real domain | DNS name resolving to the LAN address | Standard validation | Requires internet, a domain and fixed IP; violates `SYS-001` | Rejected |
| E. Plain HTTP with application-level encryption | Custom key exchange over HTTP | No certificates | Non-standard cryptography; WebSocket and HTTP both need it | Rejected |

## Decision

1. On first start the desktop peer generates an ECDSA P-256 key pair and a self-signed SHA256withECDSA X.509 certificate
   (validity 10 years, subject `CN=U'Judge <peerId>`) with Ktor `ktor-network-tls-certificates`. It is stored as PKCS#12
   in `<application data>/tls/` next to the PostgreSQL cluster (ADR-003); the keystore password is a random value in a
   separate file, and both files are readable only by the current user. The certificate is reused on every start; it is
   never committed or logged.
2. The server serves HTTP and WebSocket over TLS only (`https`, `wss`) on one port (`8443` by default) through the Ktor
   Netty engine. There is no plain-HTTP listener, so metadata, pairing and realtime use the same channel. mDNS
   `_u-judge._tcp` advertises that port.
3. The trust anchor is the SHA-256 hash of the certificate's SubjectPublicKeyInfo (SPKI pin). Hostname and chain validation
   are replaced by an exact pin comparison, so DHCP address changes do not break trust.
4. First contact (trust on first use): the client opens TLS without a pin, records the presented SPKI hash and derives a
   6-digit verification code from it: the first three bytes of the SHA-256 SPKI hash as an unsigned big-endian integer modulo
   1 000 000, zero-padded and shown as `123 456`. The desktop
   devices screen shows the same code for its own certificate. The client shows the code on the pairing screen before and
   while the request is pending; the operator approves only when the judge's code matches. The pin is used for the rest of
   the pairing flow, including the pairing request and credential delivery.
5. After acceptance the client stores the pin next to the reconnect credential in platform secure storage (Android
   Keystore-backed storage, iOS Keychain), scoped to the server `peerId`. Every later HTTP and WebSocket connection to that
   peer requires the stored pin; a mismatch is a terminal typed `server_identity_changed` error, the credential is not sent
   and the client does not fall back to TOFU silently. Recovery is an explicit "forget server" action followed by new
   pairing.
6. Certificate rotation in v1 is deleting the peer certificate, which forces re-pairing of all devices. Automatic rotation
   and QR delivery (option B) are post-v1.
7. The server treats every request on the TLS connector as secure delivery; the `credentialDeliveryIsSecure` check stays and
   rejects plain transports in tests.

## Consequences

- Server switches the embedded engine from CIO to Netty; the Ktor version is unchanged.
- Client uses custom trust for OkHttp (Android `X509TrustManager` with SPKI pin, hostname verification replaced by the pin)
  and Darwin (`handleChallenge` with `SecTrust` SPKI comparison). Android `network_security_config` is not needed because no
  cleartext traffic remains.
- `metadataEndpoint` and manual host entry switch to `https`; realtime switches to `wss`.
- Android emulators reach the desktop through `10.0.2.2` with manual host entry; the pin makes the address irrelevant to trust.
- A MITM during first contact is detected only if the operator compares codes. The desktop approval control shows the code
  next to the pending request to make the comparison part of approval.

## Acceptance Evidence

- Server: Ktor TLS acceptance test proves metadata, pairing status with credential and `wss` handshake over TLS; the same
  certificate is reused after restart; a plain-HTTP request cannot obtain a credential.
- Client: host tests for pin capture, code derivation, stored-pin enforcement and mismatch rejection; Android emulator run
  against `./gradlew :desktop:run` with matching codes on both screens.
- iPhone pin enforcement is verified on a physical device in I3.

## Decision Record

| Field | Value |
| --- | --- |
| Certificate | Per-peer self-signed ECDSA P-256, PKCS#12 in application-data directory, reused across restarts |
| Listener | TLS only, one port, Ktor Netty engine |
| Trust anchor | SHA-256 SPKI pin |
| First contact | TOFU with 6-digit verification code compared by operator before approval |
| Pin storage | Platform secure storage with the reconnect credential, scoped to `peerId` |
| Pin mismatch | Terminal `server_identity_changed`; explicit forget and re-pairing |
| Rotation | Manual, forces re-pairing; automatic rotation post-v1 |
| QR delivery | Post-v1 |
