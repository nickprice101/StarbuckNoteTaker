# Follow-up Tasks

_Last reviewed: April 3, 2026_

## Documentation alignment
- [x] Refresh repository Markdown docs so they match implemented app behavior and current ML pipeline artifacts.

## Engineering backlog (current)
- [ ] Add a deterministic instrumentation smoke test for share-intent flows (`SEND`/`SEND_MULTIPLE`) covering text, single image, and mixed attachments.
- [ ] Expand reminder/alarm UX tests to include exact-alarm permission denied paths on newer Android versions.
- [ ] Add explicit regression tests for summarizer enhanced-summary formatting against saved golden samples.
- [ ] Reduce unchecked-cast warnings in `SummarizerModelRobolectricTest` by introducing typed test helper wrappers.

## Nice-to-have
- [ ] Add a concise architecture diagram in the root `README.md` for onboarding.
