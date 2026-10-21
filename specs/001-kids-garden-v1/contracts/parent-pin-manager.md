# Contract: ParentPinManager

**Layer**: security boundary between parent UI, credential storage and SessionManager.

## Public surface

| Operation | Input → output | Contract |
| --- | --- | --- |
| createPin(pinChars, confirmChars) | caller-owned CharArray pair → Result<PinCredential> | Require one to six ASCII digits and constant-time equal confirmation. Generate fresh 16-byte SecureRandom salt; derive PBKDF2WithHmacSHA256 with exactly 180000 iterations (credential v2) and 32-byte output off main thread. Return credential metadata only after caller clears inputs. |
| verifyPin(pinChars, credential) | CharArray + bounded validated credential → Result<VerifiedParentAccess> | Validate credential version, algorithm, iteration/key sizes and salt/hash lengths before deriving. Compare equal-size verifiers using MessageDigest.isEqual. Clear working arrays best effort. Return opaque short-lived access, never hash/password/token text. |
| changePin(access, currentPinChars, newPinChars, confirmChars) | live session-bound access + inputs → Result<PinCredential> | Reauthenticate current credential; check one-use parent access/generation; create unique salt; persist atomically via SettingsRepository, then revoke every parent/phone grant and old access token. Do not confirm until durable save succeeds. |
| recordFailure / retryStatus | volatile verifier-owned counter/time → state | After 5 consecutive failures impose 30 seconds using elapsed time; successful verification resets in memory. Process death clears this best-effort counter. No failure log or persistent counter. |

No empty/short PIN, Unicode numerals, whitespace trimming, PIN hint, recovery password, device-unlock substitution, cloud reset or silent fallback credential. Short-PIN (one to six digits) entropy and bypassability after clear-data/reinstall are clearly disclosed as ordinary-app limits.

## Credential record

Credential allowlist (owner change 2026-10-21): credentialVersion=2 with iterations=180000 for new credentials, or credentialVersion=1 with iterations=600000 (accepted, then re-hashed as v2 on the next correct PIN); algorithm=`PBKDF2WithHmacSHA256`; derivedKeyLengthBytes=32; Base64 salt decoding to 16 bytes; Base64 verifier/hash decoding to 32 bytes. No other algorithm or work factor accepted in v1. Wrong-shape JSON returns credential-corrupt/setup-required; never trigger unbounded hash work.

PIN characters must not enter navigation arguments, saved-state, analytics, exception text, Android logs, crash SDKs or persistent storage. UI uses a numeric secure/masked field, no clipboard persistence/screenshot recording by Kids Garden, removes text on submit/cancel/recreation, and clears buffers best effort after asynchronous verification.

## Tests

Verify each boundary/character class, mismatch, wrong/corrupt fields, random distinct salts for identical PIN, equal PIN acceptance, wrong rejection, constant-length comparison, lockout/reset, elapsed-time expiry, no restart persistence, cancellation and storage failure. Synthetic vectors only. Hash values in fixtures are test data, never live credentials.
