# Protocolo CLP v1

Transporte: TCP, porta 47800. Quadro: `[tipo:1][tamanho:4 big-endian][payload]` (máx. 16 MiB).

## Pareamento (quadros em claro)
1. Celular → `HELLO` (1): JSON `{"v":1,"name":"Pixel","nonceC":"<base64 16 B>"}`
2. Receptor → `CHALLENGE` (2): `{"nonceS":"<base64 16 B>"}`
3. Celular → `AUTH` (3): `HMAC-SHA256(master, 'C' ‖ nonceC ‖ nonceS)`
4. Receptor → `AUTH_OK` (4): `HMAC-SHA256(master, 'S' ‖ nonceC ‖ nonceS)` (o celular verifica o receptor) ou `AUTH_FAIL` (5).

`master = PBKDF2-HMAC-SHA256(código de 8 dígitos, salt="CameraLivePro-v1", 100000 iterações, 32 B)`.
`sessionKey = HMAC-SHA256(master, 'K' ‖ nonceC ‖ nonceS)`.

## Sessão (payload cifrado)
`AES-256-GCM`, nonce = `prefixo(4 B BE) ‖ contador(8 B BE)`; prefixo 1 = celular→PC, 2 = PC→celular; contador começa em 0 e **deve** ser sequencial (repetição/reordenação falha). AAD = `[tipo][tamanho do texto cifrado]`. Payload no fio = texto cifrado ‖ tag(16).

| Tipo | Nº | Sentido | Conteúdo |
|---|---|---|---|
| CONFIG | 10 | C→PC | JSON `{width,height,fps,bitrateKbps,codec,camera}` (reenviado ao mudar) |
| VIDEO | 11 | C→PC | `[pts µs:8][flags:1 (bit0=quadro-chave)][H.264 Annex-B]`; quadros-chave levam SPS/PPS |
| PREVIEW | 12 | C→PC | JPEG ~480 px, ~2/s (só para a prévia do receptor) |
| STATS | 13 | C→PC | JSON `{fps,rttMs,bitrateKbps,battery,tempC,dropped}` 1×/s |
| PING/PONG | 14/15 | ida e volta | 8 B (nanoTime do celular); o PC devolve igual → RTT |
| CONTROL | 20 | PC→C | reservado |

Descoberta: UDP 47801. Celular envia `CLP?` por broadcast; receptor responde `CLP1|<nome do PC>|47800`.

## Por que não WebRTC / NDI / RTSP agora?
Ver [ARCHITECTURE.md](ARCHITECTURE.md). Vetores de teste: `scripts/protocol_vectors.py`.
