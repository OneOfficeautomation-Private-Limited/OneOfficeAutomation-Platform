"""Cases for the Hash and encrypt group: the samples (fuller converter) and one case per command they only use in
passing (file encryption and the RSA commands)."""
from helpers_core import resample, vault_ref


def _keys(f, folder):
    f.act("ooa.crypto/rsa_generate_keys", desc="A fresh RSA key pair for this case", folder=folder,
          if_keys_exist="overwrite", key_size=2048, out={"public_key": "rsa_pub", "private_key": "rsa_priv"})


def add(ua):
    resample(ua)
    for n, t in (("rsa_pub", "File"), ("rsa_priv", "File"), ("rsa_cipher", "String"), ("rsa_plain", "Any"),
                 ("rsa_sig", "String"), ("rsa_valid", "Boolean"), ("file_text", "String")):
        ua.var(n, t)
    ua.var("cred_pass", "Credential")

    with ua.case("ooa.crypto/encrypt_file", "a text file into an encrypted copy",
                 "Encrypt file writes an AES-256 encrypted copy of a file, with a passphrase from the vault. Checks: "
                 "the copy exists and does not hold the plain text.") as f:
        f.set("cred_pass", {"$credential": vault_ref("test/crypto/pass")}, desc="Passphrase from the vault")
        f.act("ooa.file/write_text", desc="A plain text file", path="ut_tmp/enc/plain.txt", text="Invoice 42: 120.50 EUR")
        f.act("ooa.crypto/encrypt_file", desc="Encrypt it", path="ut_tmp/enc/plain.txt", target="ut_tmp/enc/plain.enc",
              passphrase="$cred_pass$")
        f.act("ooa.crypto/hash_file", desc="Fingerprint of the plain file", path="ut_tmp/enc/plain.txt",
              out={"hash": "file_text"})
        f.act("ooa.crypto/hash_file", desc="Fingerprint of the encrypted copy", path="ut_tmp/enc/plain.enc",
              out={"hash": "rsa_sig"})
        ua.check("ne", "$rsa_sig$", "$file_text$", what="the encrypted copy differs from the plain file")
        f.act("ooa.file/get_info", desc="Size of the encrypted copy", path="ut_tmp/enc/plain.enc",
              out={"info": "rsa_plain"})
        ua.check("gt", "$rsa_plain.size_bytes$", 22, what="the encrypted copy is larger than the 22-byte original")

    with ua.case("ooa.crypto/decrypt_file", "back to the original text",
                 "Decrypt file turns a file made by Encrypt file back into the original, with the same passphrase. "
                 "Checks: the decrypted file has the original text.") as f:
        f.set("cred_pass", {"$credential": vault_ref("test/crypto/pass")}, desc="Passphrase from the vault")
        f.act("ooa.file/write_text", desc="A plain text file", path="ut_tmp/dec/plain.txt", text="Order 7, 3 boxes")
        f.act("ooa.crypto/encrypt_file", desc="Encrypt it", path="ut_tmp/dec/plain.txt", target="ut_tmp/dec/plain.enc",
              passphrase="$cred_pass$")
        f.act("ooa.crypto/decrypt_file", desc="Decrypt it again", path="ut_tmp/dec/plain.enc",
              target="ut_tmp/dec/back.txt", passphrase="$cred_pass$")
        f.act("ooa.file/read_text", desc="Read the decrypted file", path="ut_tmp/dec/back.txt",
              out={"text": "file_text"})
        ua.check_eq("file_text", "Order 7, 3 boxes")

    with ua.case("ooa.crypto/rsa_encrypt", "a short text with the public key",
                 "RSA encrypt turns a short text into Base64 with a public key. Checks: the result is Base64 text "
                 "that does not hold the original.") as f:
        _keys(f, "ut_tmp/rsa1")
        f.act("ooa.crypto/rsa_encrypt", desc="Encrypt with the public key", text="PIN 4711", public_key="$rsa_pub$",
              out={"result": "rsa_cipher"})
        ua.check("matches", "$rsa_cipher$", "^[A-Za-z0-9+/=]{100,}$", what="rsa_cipher is Base64")
        ua.check("notContains", "$rsa_cipher$", "4711", what="rsa_cipher")

    with ua.case("ooa.crypto/rsa_decrypt", "back with the private key",
                 "RSA decrypt turns text from RSA encrypt back with the matching private key; the result is a "
                 "Credential, so it is checked through a text made from it (Base64 of the plain text).") as f:
        _keys(f, "ut_tmp/rsa2")
        f.act("ooa.crypto/rsa_encrypt", desc="Encrypt with the public key", text="PIN 4711", public_key="$rsa_pub$",
              out={"result": "rsa_cipher"})
        f.act("ooa.crypto/rsa_decrypt", desc="Decrypt with the private key", text="$rsa_cipher$",
              private_key="$rsa_priv$", out={"result": "rsa_plain"})
        ua.check("notEmpty", "$rsa_plain$", what="rsa_plain (a Credential) holds a value")
        f.act("ooa.crypto/hmac", desc="Sign the text probe with the decrypted value as key", text="probe",
              key="$rsa_plain$", out={"signature": "file_text"})
        f.comment("HMAC-SHA256 of probe with the key PIN 4711 is 3d0ca6c3...7dae: the decrypted value is PIN 4711")
        ua.check_eq("file_text", "3d0ca6c3e870d4790c6dcffe6a21a485bfea08cf2484843cd2c8a66cb7387dae")

    with ua.case("ooa.crypto/rsa_sign", "a signature with the private key",
                 "RSA sign signs a text with a private key (SHA256withRSA) and gives a Base64 signature. Checks: the "
                 "signature is Base64 and RSA verify accepts it.") as f:
        _keys(f, "ut_tmp/rsa3")
        f.act("ooa.crypto/rsa_sign", desc="Sign the text", text="Pay 100 to ACME", private_key="$rsa_priv$",
              out={"signature": "rsa_sig"})
        ua.check("matches", "$rsa_sig$", "^[A-Za-z0-9+/=]{100,}$", what="rsa_sig is Base64")
        f.act("ooa.crypto/rsa_verify", desc="Check it", text="Pay 100 to ACME", signature="$rsa_sig$",
              public_key="$rsa_pub$", out={"valid": "rsa_valid"})
        ua.check_eq("rsa_valid", True)

    with ua.case("ooa.crypto/rsa_verify", "a changed text does not verify",
                 "RSA verify checks a signature with the public key and gives true or false instead of failing. "
                 "Checks: true for the signed text, false for a changed one.") as f:
        _keys(f, "ut_tmp/rsa4")
        f.act("ooa.crypto/rsa_sign", desc="Sign the text", text="Pay 100 to ACME", private_key="$rsa_priv$",
              out={"signature": "rsa_sig"})
        f.act("ooa.crypto/rsa_verify", desc="Check the signed text", text="Pay 100 to ACME", signature="$rsa_sig$",
              public_key="$rsa_pub$", out={"valid": "rsa_valid"})
        ua.check_eq("rsa_valid", True)
        f.act("ooa.crypto/rsa_verify", desc="Check a changed text", text="Pay 900 to ACME", signature="$rsa_sig$",
              public_key="$rsa_pub$", out={"valid": "rsa_valid"})
        ua.check_eq("rsa_valid", False)

