# Hash, encryption and encoding

Toolkit `ooa.crypto`, version 1.0.0. Hashes, HMAC signatures, AES-256 encryption of text and files, and Base64 encoding.

[All toolkits](README.md)

## Contents

- **Encoding**: Base64 decode, Base64 encode
- **Encryption**: Decrypt file, Decrypt text, Encrypt file, Encrypt text
- **Hash**: Hash file, Hash text, HMAC signature
- **RSA**: RSA decrypt text, RSA encrypt text, Generate RSA key pair, RSA sign, RSA verify

## Encoding

### Base64 decode

`ooa.crypto/base64_decode` (action)

Decode Base64 into text.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Text | `text` | Text | Yes |  | Main | Base64 text (standard or URL-safe). |
| Encoding | `encoding` | Text | No | `utf-8` | More options | Character encoding of the decoded text. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `result` | Text | Decoded text. |

*Time limit 60 s; safe to repeat.*

### Base64 encode

`ooa.crypto/base64_encode` (action)

Encode text as Base64.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Text | `text` | Text | Yes |  | Main | Text to encode. |
| URL-safe alphabet | `url_safe` | Yes/No | No | No | Main | Use the URL-safe alphabet (- and _ instead of + and /) without padding. |
| Encoding | `encoding` | Text | No | `utf-8` | More options | Character encoding used to turn the text into bytes, e.g. utf-8, utf-16le, windows-1252. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `result` | Text | Base64 text. |

*Time limit 60 s; safe to repeat.*

## Encryption

### Decrypt file

`ooa.crypto/decrypt_file` (action)

Decrypt a file produced by Encrypt file.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Path | `path` | File path | Yes |  | Main | Encrypted file. |
| Target | `target` | File path | Yes |  | Main | Decrypted file to create. |
| Passphrase | `passphrase` | Credential (vault) | Yes |  | Main | Passphrase from the vault. |
| Overwrite | `overwrite` | Yes/No | No | Yes | More options | Replace an existing target file. |

*Time limit 60 s; needs: filesystem.*

### Decrypt text

`ooa.crypto/decrypt_text` (action)

Decrypt text produced by Encrypt text.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Text | `text` | Text | Yes |  | Main | Encrypted Base64 text. |
| Passphrase | `passphrase` | Credential (vault) | Yes |  | Main | Passphrase from the vault. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `result` | Credential (vault) | Decrypted text, kept as a Credential so it is never logged. |

*Time limit 60 s.*

### Encrypt file

`ooa.crypto/encrypt_file` (action)

Encrypt a file with AES-256 into a new file.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Path | `path` | File path | Yes |  | Main | File to encrypt. |
| Target | `target` | File path | Yes |  | Main | Encrypted file to create. |
| Passphrase | `passphrase` | Credential (vault) | Yes |  | Main | Passphrase from the vault. |
| Overwrite | `overwrite` | Yes/No | No | Yes | More options | Replace an existing target file. |

*Time limit 60 s; needs: filesystem.*

### Encrypt text

`ooa.crypto/encrypt_text` (action)

Encrypt text with AES-256 using a passphrase from the vault; returns Base64.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Text | `text` | Text | Yes |  | Main | Text to encrypt. |
| Passphrase | `passphrase` | Credential (vault) | Yes |  | Main | Passphrase from the vault. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `result` | Text | Encrypted text (Base64). |

*Time limit 60 s.*

## Hash

### Hash file

`ooa.crypto/hash_file` (action)

Return the hex hash of a file, e.g. to verify a download.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Path | `path` | File path | Yes |  | Main | File path. |
| Algorithm | `algorithm` | Text | No | `SHA-256` | Main | Hash algorithm. One of: `SHA-256`, `SHA-384`, `SHA-512`, `SHA3-256`, `SHA-1`, `MD5` |
| Output format | `output_format` | Text | No | `hex` | More options | How the result is written: hex (lower case), hex_upper or base64. One of: `hex`, `hex_upper`, `base64` |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `hash` | Text | Lower-case hex digest. |

*Time limit 60 s; safe to repeat; needs: filesystem.*

### Hash text

`ooa.crypto/hash_text` (action)

Return the hex hash of a text (UTF-8).

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Text | `text` | Text | Yes |  | Main | Text to hash. |
| Algorithm | `algorithm` | Text | No | `SHA-256` | Main | Hash algorithm; SHA-1 and MD5 only for legacy checks. One of: `SHA-256`, `SHA-384`, `SHA-512`, `SHA3-256`, `SHA-1`, `MD5` |
| Output format | `output_format` | Text | No | `hex` | More options | How the result is written: hex (lower case), hex_upper or base64. One of: `hex`, `hex_upper`, `base64` |
| Encoding | `encoding` | Text | No | `utf-8` | More options | Character encoding used to turn the text into bytes, e.g. utf-8, utf-16le, windows-1252. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `hash` | Text | Lower-case hex digest. |

*Time limit 60 s; safe to repeat.*

### HMAC signature

`ooa.crypto/hmac` (action)

Compute an HMAC-SHA256 signature with a secret key from the vault, e.g. to sign or verify webhooks.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Text | `text` | Text | Yes |  | Main | Message. |
| Key | `key` | Credential (vault) | Yes |  | Main | Secret key from the vault. |
| Algorithm | `algorithm` | Text | No | `SHA-256` | Main | Hash used inside the HMAC. One of: `SHA-256`, `SHA-384`, `SHA-512`, `SHA-1` |
| Output format | `output_format` | Text | No | `hex` | More options | How the result is written: hex (lower case), hex_upper or base64. One of: `hex`, `hex_upper`, `base64` |
| Key is base64 | `key_is_base64` | Yes/No | No | No | More options | The key is Base64 text to decode into bytes (as some services issue it). |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `signature` | Text | Hex signature. |

*Time limit 60 s; safe to repeat.*

## RSA

### RSA decrypt text

`ooa.crypto/rsa_decrypt` (action)

Decrypt Base64 text encrypted with the matching public key.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Text | `text` | Text | Yes |  | Main | Encrypted Base64 text. |
| Private key | `private_key` | File path | Yes |  | Main | Private key PEM file (PKCS#8). |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `result` | Credential (vault) | Decrypted text, kept as a Credential. |

*Time limit 60 s.*

### RSA encrypt text

`ooa.crypto/rsa_encrypt` (action)

Encrypt a short text (up to about 300 characters) with a public key (RSA-OAEP SHA-256); returns Base64.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Text | `text` | Text | Yes |  | Main | Text to encrypt. |
| Public key | `public_key` | File path | Yes |  | Main | Public key PEM file. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `result` | Text | Encrypted Base64 text. |

*Time limit 60 s.*

### Generate RSA key pair

`ooa.crypto/rsa_generate_keys` (action)

Create public.pem and private.pem (RSA 3072-bit) in a folder; keep the private key protected.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Folder | `folder` | File path | Yes |  | Main | Folder for the key files. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `public_key` | File path | Public key path. |
| `private_key` | File path | Private key path. |

*Time limit 60 s; needs: filesystem.*

### RSA sign

`ooa.crypto/rsa_sign` (action)

Sign text with a private key (SHA256withRSA); returns a Base64 signature.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Text | `text` | Text | Yes |  | Main | Text to sign. |
| Private key | `private_key` | File path | Yes |  | Main | Private key PEM file (PKCS#8). |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `signature` | Text | Base64 signature. |

*Time limit 60 s.*

### RSA verify

`ooa.crypto/rsa_verify` (action)

Check an RSA signature with the public key; returns the result instead of failing.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Text | `text` | Text | Yes |  | Main | Signed text. |
| Signature | `signature` | Text | Yes |  | Main | Base64 signature. |
| Public key | `public_key` | File path | Yes |  | Main | Public key PEM file. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `valid` | Yes/No | True when the signature matches. |

*Time limit 60 s; safe to repeat.*
