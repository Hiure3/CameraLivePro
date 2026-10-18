#!/usr/bin/env python3
"""Implementação de referência (Python) do protocolo CLP v1, usada para gerar
vetores de teste que os testes C# validam. Ver docs/PROTOCOL.md."""
import hashlib, hmac, struct, json
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

SALT = b"CameraLivePro-v1"
def master(code: str) -> bytes:
    return hashlib.pbkdf2_hmac("sha256", code.encode(), SALT, 100_000, 32)
def mac(key, tag, nc, ns): return hmac.new(key, tag + nc + ns, hashlib.sha256).digest()
def nonce(prefix: int, counter: int): return struct.pack(">IQ", prefix, counter)
def seal(key, prefix, counter, ftype, plaintext):
    ct_len = len(plaintext) + 16
    aad = bytes([ftype]) + struct.pack(">I", ct_len)
    return AESGCM(key).encrypt(nonce(prefix, counter), plaintext, aad)

if __name__ == "__main__":
    code = "12345678"; nc = bytes(range(16)); ns = bytes(range(16, 32))
    m = master(code)
    out = {
      "code": code, "nonceC": nc.hex(), "nonceS": ns.hex(),
      "master": m.hex(),
      "clientProof": mac(m, b"C", nc, ns).hex(),
      "serverProof": mac(m, b"S", nc, ns).hex(),
      "sessionKey": mac(m, b"K", nc, ns).hex(),
    }
    key = bytes.fromhex(out["sessionKey"])
    out["sealed"] = seal(key, 1, 0, 11, b"hello-video").hex()
    print(json.dumps(out, indent=2))
