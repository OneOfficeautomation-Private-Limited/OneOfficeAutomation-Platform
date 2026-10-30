# PGP

Toolkit `ooa.pgp`, version 1.0.0. Import keys, encrypt, decrypt, sign and verify files with OpenPGP (GnuPG).

[All toolkits](README.md)

## Contents

- **Keys**: Create key pair, Import key
- **Encryption**: Decrypt file, Encrypt file
- **Signing**: Sign file, Verify signature

## Keys

### Create key pair

`ooa.pgp/create_keys` (action)

Generate a new OpenPGP key pair (signing key plus encryption subkey) and optionally export it to armored key files.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Public key file | `public_key_file` | File path | No |  | Main | Where to save the armored public key (.asc) to share with partners. |
| Private key file | `private_key_file` | File path | No |  | Main | Where to save the armored private key (.asc) as a backup; keep it secret. |
| Algorithm | `algorithm` | Text | No | `ed25519` | Main | Key type: ed25519 (modern, small) or RSA for older systems. One of: `ed25519`, `rsa3072`, `rsa4096` |
| Passphrase | `passphrase` | Credential (vault) | No |  | Main | Passphrase that protects the private key; strongly recommended. |
| Name | `name` | Text | Yes |  | Main | Key owner name. Example: `Finance Agent` |
| Email | `email` | Text | Yes |  | Main | Key owner email address. Example: `finance-agent@example.com` |
| Comment | `comment` | Text | No |  | More options | Optional comment stored in the user id. |
| Expires | `expires` | Text | No | `2y` | More options | Lifetime, e.g. 1y, 6m, 90d, a date like 2028-12-31, or never. |
| Overwrite | `overwrite` | Yes/No | No | Yes | More options | Replace key files that are already there; when off, the step fails before any key is made. |
| Gnupg home | `gnupg_home` | File path | No |  | More options | Separate GnuPG key folder for this agent; the runner user's keyring when empty. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `fingerprint` | Text | Fingerprint of the new key. |
| `user_id` | Text | User id stored in the key, e.g. Finance Agent <agent@example.com>. |
| `public_key_file` | File path | Exported public key, or empty. |
| `private_key_file` | File path | Exported private key, or empty. |

*Time limit 600 s; needs: filesystem.*

### Import key

`ooa.pgp/import_key` (action)

Import a public or private key file (.asc or .gpg).

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Path | `path` | File path | Yes |  | Main | Key file. |
| Passphrase | `passphrase` | Credential (vault) | No |  | Main; tick The key has a passphrase | Passphrase, when importing a protected private key. |
| Gnupg home | `gnupg_home` | File path | No |  | More options | Separate GnuPG key folder for this agent; the runner user's keyring when empty. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `fingerprints` | List | Fingerprints of imported keys. |

*Time limit 60 s; needs: filesystem.*

## Encryption

### Decrypt file

`ooa.pgp/decrypt` (action)

Decrypt a file with your private key.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Private key file | `private_key_file` | File path | No |  | Main; tick Use a private key file | Private key file (.asc or .gpg) to decrypt with, without importing it into a keyring first. |
| Passphrase | `passphrase` | Credential (vault) | No |  | Main | Private key passphrase from the vault. |
| Path | `path` | File path | Yes |  | Main | Encrypted file. |
| Target | `target` | File path | No |  | Main | Decrypted file to create; when empty, the encrypted file's name without .pgp, .gpg or .asc. |
| Overwrite | `overwrite` | Yes/No | No | Yes | Main | Replace an existing target file. |
| Gnupg home | `gnupg_home` | File path | No |  | More options | Separate GnuPG key folder for this agent; the runner user's keyring when empty. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `target` | File path | Full path of the decrypted file. |

*Time limit 300 s; needs: filesystem.*

### Encrypt file

`ooa.pgp/encrypt` (action)

Encrypt a file for one or more recipients' public keys, or with a passphrase only (symmetric), choosing the cipher and compression.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Recipients | `recipients` | Any | No |  | Main; choice Encrypt for = Keys in the keyring | Recipient key ids, fingerprints or email addresses (text or List); may be empty when public_key_file is given. |
| Public key file | `public_key_file` | File path | No |  | Main; choice Encrypt for = A public key file | Recipient's public key file (.asc or .gpg) to encrypt for, without importing it into a keyring first. |
| Passphrase | `passphrase` | Credential (vault) | No |  | Main; choice Encrypt for = A passphrase only | Passphrase that can decrypt the file. Alone it gives passphrase-only (symmetric) encryption; with recipients either the keys or the passphrase can decrypt. |
| Path | `path` | File path | Yes |  | Main | File to encrypt. |
| Target | `target` | File path | No |  | Main | Encrypted file to create; when empty, next to the file with .pgp added (.asc with armor), e.g. report.csv.pgp. |
| Overwrite | `overwrite` | Yes/No | No | Yes | Main | Replace an existing target file. |
| Armor | `armor` | Yes/No | No | No | Main | ASCII-armored output (text) instead of binary. |
| Cipher | `cipher` | Text | No | `default` | More options | Symmetric cipher for the data; default lets GnuPG choose from the recipients' key preferences (AES-128 for a passphrase). Older ciphers (CAST5, 3DES, BLOWFISH) only for old partners. One of: `default`, `AES256`, `AES192`, `AES128`, `CAMELLIA256`, `CAMELLIA192`, `CAMELLIA128`, `TWOFISH`, `CAST5`, `3DES`, `BLOWFISH` |
| Compression | `compression` | Text | No | `default` | More options | Compression before encryption; none for files that are already compressed, such as .zip or .jpg. One of: `default`, `none`, `zip`, `zlib`, `bzip2` |
| Gnupg home | `gnupg_home` | File path | No |  | More options | Separate GnuPG key folder for this agent; the runner user's keyring when empty. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `target` | File path | Full path of the encrypted file. |

*Time limit 300 s; needs: filesystem.*

## Signing

### Sign file

`ooa.pgp/sign` (action)

Create a detached signature (.sig) proving the file came from you.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Path | `path` | File path | Yes |  | Main | File to sign. |
| Signature | `signature` | File path | Yes |  | Main | Signature file to create, e.g. report.csv.sig. |
| Key | `key` | Text | No |  | Main; choice Signing key = A specific key | Signing key id or email; the default key when empty. |
| Passphrase | `passphrase` | Credential (vault) | No |  | Main | Private key passphrase from the vault. |
| Gnupg home | `gnupg_home` | File path | No |  | More options | Separate GnuPG key folder for this agent; the runner user's keyring when empty. |

*Time limit 300 s; needs: filesystem.*

### Verify signature

`ooa.pgp/verify` (action)

Check a detached signature; returns the result instead of failing.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Path | `path` | File path | Yes |  | Main | Signed file. |
| Signature | `signature` | File path | Yes |  | Main | Signature file. |
| Gnupg home | `gnupg_home` | File path | No |  | More options | Separate GnuPG key folder for this agent; the runner user's keyring when empty. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `valid` | Yes/No | True when the signature is good. |
| `signer` | Text | Signer's user id when valid. |
| `fingerprint` | Text | Signing key fingerprint when valid. |

*Time limit 300 s; safe to repeat; needs: filesystem.*
