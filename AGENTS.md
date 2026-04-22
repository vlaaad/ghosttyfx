Be concise.
When in plan mode: no plans, no questions, only discuss.
Git: read-only, never stage/commit anything unless explicitly asked for.
Code: data-driven - final classes, interfaces, records, lambdas; prefer var; fail-fast: check on edges, trust internals; prefer local reasoning; extract helpers only for real reuse or domain concepts; trivial single-use code is inlined; locals must declare locals only at real assignment.
Tests: mvn clean test
Project context: unreleased library, bring Ghostty to Java/JavaFX, inspired by Ghostling - ghostling/main.c