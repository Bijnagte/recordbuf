# docs/

Project documentation and the compound-engineering knowledge store.

## Layout

```
docs/
  README.md              This file.
  protobuf.dev-index.md  Why recordbuf's java_interface / java_enum_interface options
                        live outside protobuf's public namespace.
  known-findings.md      Generator findings that are not proto constructs, so not in the gap ledger.
  solutions/             Compound-engineering knowledge store (created by `ce-compound`).
  plans/                 Execution plans (created by `ce-plan`).
  CONCEPTS.md            Shared domain vocabulary — created on demand if/when needed.
```

## `solutions/` — the knowledge store

`ce-compound` (from `pi-compound-engineering`) distills solved problems and
reusable guidance into durable notes here, organized by category and tagged with
YAML frontmatter (`module`, `tags`, `problem_type`, …) so they can be re-found
later. The store compounds: documenting a fix once makes the next similar one
faster.

- **Bug track** — `docs/solutions/<bug-category>/` (e.g. `build-errors/`,
  `runtime-errors/`, `performance-issues/`, `logic-errors/`).
- **Knowledge track** — `docs/solutions/<knowledge-category>/` (e.g.
  `architecture-patterns/`, `tooling-decisions/`, `conventions/`,
  `best-practices/`).

Categories are auto-detected from the problem; a category directory is created the
first time an entry of that kind lands. Each entry is a single Markdown file with
frontmatter plus a fixed section shape (bug: Problem / Symptoms / What Didn't Work
/ Solution / Why / Prevention; knowledge: Context / Guidance / Why This Matters /
When to Apply / Examples).

The store is searched by agents before working in a documented area, so keep
entries current. `ce-compound-refresh` audits older entries for drift.
