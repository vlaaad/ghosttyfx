# Terminal Frame Progress

Status legend: `todo`, `doing`, `done`.

## Current target
- `done` replace `snapshot()` with render-oriented `frame()`
- `done` bind libghostty render-state API
- `done` add detached Java frame model
- `done` rewire terminal session to cache and reuse frame rows/styles
- `done` update tests to exercise `frame()`

## Notes
- `frame()` is viewport-only and backed by libghostty render state.
- Inspector APIs (`cell/row/screen`) remain detached and slower.
- Native libghostty data is the source of truth.
- Keep all mutable terminal access serialized on one internal actor thread.
