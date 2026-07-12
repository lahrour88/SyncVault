#!/usr/bin/env python3
"""
SyncVault decryption tool (PyCryptodome version).

Decrypts the .enc files produced by the SyncVault Android app.
Only asks for the salt and the password on the terminal, as requested.

Requirements:
    pip install pycryptodome

Usage:
    python decrypt_syncvault.py [folder_with_enc_files] [output_folder]

    Both arguments are optional:
      - folder_with_enc_files defaults to the current directory
      - output_folder defaults to "./decrypted"
"""

import sys
import os
import base64
import getpass

from Crypto.Protocol.KDF import PBKDF2
from Crypto.Hash import SHA256
from Crypto.Cipher import AES

PBKDF2_ITERATIONS = 210_000
KEY_LENGTH_BYTES = 32   # 256-bit AES key
IV_LENGTH_BYTES = 12    # GCM nonce length used by the app
TAG_LENGTH_BYTES = 16   # GCM auth tag length (appended at the end by Java's Cipher)

# Common image file signatures, used to guess the original extension
# since encrypted files are named by hash only (no extension kept).
MAGIC_BYTES = {
    b"\xff\xd8\xff": ".jpg",
    b"\x89PNG\r\n\x1a\n": ".png",
    b"GIF87a": ".gif",
    b"GIF89a": ".gif",
    b"BM": ".bmp",
    b"RIFF": ".webp",  # WEBP files start with RIFF....WEBP
}


def guess_extension(data: bytes) -> str:
    for magic, ext in MAGIC_BYTES.items():
        if data.startswith(magic):
            return ext
    return ".bin"


def derive_key(password: str, salt: bytes) -> bytes:
    return PBKDF2(
        password.encode("utf-8"),
        salt,
        dkLen=KEY_LENGTH_BYTES,
        count=PBKDF2_ITERATIONS,
        hmac_hash_module=SHA256,
    )


def decrypt_file(enc_path: str, key: bytes) -> bytes:
    with open(enc_path, "rb") as f:
        raw = f.read()
    iv = raw[:IV_LENGTH_BYTES]
    ciphertext = raw[IV_LENGTH_BYTES:-TAG_LENGTH_BYTES]
    tag = raw[-TAG_LENGTH_BYTES:]
    cipher = AES.new(key, AES.MODE_GCM, nonce=iv)
    return cipher.decrypt_and_verify(ciphertext, tag)


def main():
    source_dir = sys.argv[1] if len(sys.argv) > 1 else "."
    output_dir = sys.argv[2] if len(sys.argv) > 2 else "./decrypted"

    salt_b64 = input("Salt (Base64, from syncvault.salt): ").strip()
    password = getpass.getpass("Password: ")

    try:
        salt = base64.b64decode(salt_b64)
    except Exception:
        print("Error: invalid Base64 salt.")
        sys.exit(1)

    key = derive_key(password, salt)

    enc_files = [
        f for f in os.listdir(source_dir)
        if f.endswith(".enc") and not f.endswith(".enc.tmp")
    ]

    if not enc_files:
        print(f"No .enc files found in '{source_dir}'.")
        sys.exit(0)

    os.makedirs(output_dir, exist_ok=True)

    ok, failed = 0, 0
    for name in enc_files:
        enc_path = os.path.join(source_dir, name)
        try:
            plaintext = decrypt_file(enc_path, key)
        except ValueError:
            # decrypt_and_verify raises ValueError on a bad tag (wrong key/corrupted file)
            print(f"[FAILED] {name} — wrong password/salt, or corrupted file.")
            failed += 1
            continue
        except Exception as e:
            print(f"[FAILED] {name} — {e}")
            failed += 1
            continue

        base_hash = name[:-len(".enc")]
        ext = guess_extension(plaintext)
        out_path = os.path.join(output_dir, base_hash + ext)
        with open(out_path, "wb") as f:
            f.write(plaintext)
        print(f"[OK] {name} -> {os.path.basename(out_path)}")
        ok += 1

    print(f"\nDone. Decrypted: {ok}, Failed: {failed}")
    if failed and ok == 0:
        print("If every file failed, the password or salt is likely incorrect.")


if __name__ == "__main__":
    main()

