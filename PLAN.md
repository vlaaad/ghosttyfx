# Plan

- Drop handwritten FFM bindings.
- Generate jextract bindings per target and package them as 4 libs:
  - `ghosttyfx-linux-x86_64`
  - `ghosttyfx-macos-x86_64`
  - `ghosttyfx-macos-aarch64`
  - `ghosttyfx-windows-x86_64`
- Keep the generated Java package/class names the same across all 4 libs.
- Never try to share one generated source tree across OSes.

- Add one shared renderer lib:
  - `ghosttyfx`
  - Java + JavaFX only
  - depends on exactly one bindings lib being present

- Renderer code uses generated Java APIs directly.
- Keep the shared layer thin:
  - opaque handles
  - common function calls
  - common structs only
- Avoid target-specific ABI assumptions in shared code.

- CI builds native libs and jextract bindings for all 4 targets.
- Publish/download artifacts per target.
- Local/runtime packaging selects one bindings lib for the current platform.

- Goal: minimal abstraction, per-platform ABI correctness, shared JavaFX renderer.
