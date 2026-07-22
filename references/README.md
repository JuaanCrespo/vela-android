# references/ — External References

This directory holds **links, notes, and reference snippets** that inform
the Android lab implementation. It does **not** hold code copied from
`G:\vela` or third-party SDKs.

## What belongs here

- Alpaca v2 REST API contract notes (public Alpaca docs).
- Alpaca v2 WebSocket streaming notes (public Alpaca docs).
- Android Foreground Service guidance for API 29+ (notes only).
- Android Keystore patterns for `EncryptedSharedPreferences`.
- TensorFlow Lite / ONNX Runtime Mobile inference notes (future).
- Notes on Room migration patterns equivalent to SQLAlchemy migrations.
- Decisions log: DI choice, serialization choice, min SDK, etc.

Each note should be a short Markdown file with:
- Source (URL or `G:\vela:path` reference — for read-only audit only).
- One-paragraph summary.
- Decision or open question.

## What does not belong here

- Any file copied from `G:\vela`.
- Any credential or `.env`.
- Vendor SDK source code (use Gradle dependencies in the `android/`
  project instead).

Phase 0 leaves this directory empty.
