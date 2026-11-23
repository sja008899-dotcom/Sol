# Security Architecture — Sol (سول)

This document summarizes the security controls, cryptographic formats, network safeguards, and honesty/safety invariants implemented in **Sol**.

---

## 1. Local Storage & Cryptography

### 1.1 Encrypted Portable Backups (`SOL_BACKUP_V3`)
Implemented in `app/src/main/java/com/example/sol/security/EncryptedBackupManager.kt`:
- **Format:** Streaming `SOL_BACKUP_V3` container (magic header line + authenticated JSON header + encrypted compressed payload).
- **Key Derivation:** `PBKDF2WithHmacSHA256`, **600,000 iterations** (configurable cap `MAX_KDF_ITERATIONS = 5,000,000`), fresh 16-byte `SecureRandom` salt, minimum password length 8 characters.
- **Authenticated Encryption:** `AES/GCM/NoPadding` with a 256-bit key, fresh 12-byte `SecureRandom` IV, 128-bit authentication tag, and the serialized JSON header bound via `cipher.updateAAD(headerBytes)`.
- **Compression & Streaming:** `GZIP` stream nested inside `CipherOutputStream` / `DecryptingGcmInputStream` + `JsonWriter`/`JsonReader` so multi-megabyte book libraries export/import on 4 GB RAM phones without OOM.
- **Atomic Restore:** Decryption and AEAD authentication complete into staging structures *before* `database.withTransaction { ... }` executes; any wrong password, tampered ciphertext byte, or truncated stream aborts without altering existing Room tables or DataStore preferences.

### 1.2 Speaker Identity Profile (`EncryptedFile`)
- Optional owner voice embeddings (`com.example.sol.speaker.OwnerProfile`) are encrypted at rest using Jetpack Security (`androidx.security.crypto.EncryptedFile` backed by `MasterKey` `AES256_GCM`).
- **Fail-safe default:** When the speaker ONNX model or enrolled owner profile is absent, `SpeakerIdManager` reports `InactiveFailSafe` and never blocks commands silently or fakes verification.

### 1.3 Database Upgrade Safety & Android Backup Exclusion
- `MemoryDatabase.buildDatabase(..., isDebugBuild = false)` disables `fallbackToDestructiveMigration` in release builds so schema upgrades never wipe user data silently.
- `android:allowBackup="false"` paired with `data_extraction_rules.xml` and `backup_rules.xml` blocks unencrypted Google cloud auto-backup and device-to-device migration of private tables.

---

## 2. Network & Download Security (`SolHttpClient`, `DownloadWorker`)

Implemented in `app/src/main/java/com/example/sol/network/SolHttpClient.kt` and `DownloadWorker.kt`:
- **Explicit User Confirmation Only:** No silent background network calls, telemetry, or analytics.
- **HTTPS Enforcement:** Rejects `http://` or non-HTTPS URLs initially and across every HTTP redirect (`InsecureSchemeException`, `MAX_REDIRECTS = 8`).
- **SSRF Protection:** Resolves DNS before connecting and blocks loopback, site-local, link-local, multicast, unspecified, ULA IPv6, and CGNAT (`100.64.0.0/10`) addresses (`PrivateHostBlockedException`).
- **Allowlist & Content Validation:** Model downloads are restricted to `DEFAULT_MODEL_ALLOWED_HOSTS` (`huggingface.co`, `hf.co`, `alphacephei.com`) and reject HTML error/login pages (`text/html`) instead of saving corrupt binaries.
- **Resumable Verified Downloads & Fail-Safe `TODO(verify)`:**
  - Supports HTTP `206 Partial Content` (`Range` resume) and verifies free disk space (`SolStorageGuard`: `1.25 × payload + 1 GiB` headroom).
  - Verifies exact SHA-256 and expected byte length before renaming `.part` to final destination; mismatched hashes delete the `.part` file (`IntegrityMismatchException`).
  - **Fail-safe policy:** Any catalog entry whose SHA-256 is `"TODO(verify)"` or missing is refused by `DownloadWorker` (`IntegrityMetadataMissingException`).
- **User-Supplied Piper Model Checksums:** `PiperConfig` validates `.onnx` and `.onnx.json` SHA-256 digests against the owner-configured checksums before loading into `onnxruntime-android`, refusing mismatched files with a Persian error rather than crashing.

---

## 3. Execution, Communication & Prompt-Injection Safeguards

- **Confirmation Gate (`DialogueManager` / `TaskManager` / `ConfirmationManager`):** Calls (`ACTION_DIAL`), SMS (`ACTION_SENDTO`), web searches, web book fetches, and destructive backup restores NEVER execute without explicit user confirmation in the UI.
- **Sol Transparency:** Outgoing SMS previews and call scripts clearly identify Sol as the sender/assistant.
- **SLM Prompt Isolation (`LocalSlmEngine` & `BookRagEngine`):** Book chunks and web text are enclosed in `<DATA>...</DATA>` boundaries with injection sanitization; post-generation validation blocks forged tool/call/SMS directives and falls back honestly when no local `.litertlm` model is installed.
- **Release Signing Integrity:** Release builds never embed keystores or passwords in VCS; CI reads signing materials solely from GitHub Actions secrets (`KEYSTORE_BASE64`, `STORE_PASSWORD`, `KEY_PASSWORD`, `DEBUG_KEYSTORE_BASE64`).

---

## 4. Reporting a Vulnerability

*(Placeholder — update before public distribution)*  
If you discover a security issue in Sol, please open a private security advisory in the repository's GitHub **Security -> Advisories** tab or contact the repository owner directly rather than filing a public issue with exploit details.
