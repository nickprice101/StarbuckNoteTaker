# Follow-up Tasks

## Fix unit tests referencing `PreparedAttachment`
- **Context:** Running `./gradlew test --console=plain` fails because `NoteDetailScreenTest` cannot access the `PreparedAttachment` type (it's private and lacks a companion object).
- **Proposed actions:**
  - Expose a factory or test fixture helper that constructs `PreparedAttachment` instances for tests, or adjust the test to use the public API.
  - Ensure both debug and release unit test compilations succeed.
- **Blocking status:** Prevents running the unit test suite successfully.
