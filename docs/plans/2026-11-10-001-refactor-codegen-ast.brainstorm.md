---
artifact_contract: ce-brainstorm-state/v1
status: finalized
finalized: 2026-11-10
finalized_note: >-
  Brainstorm complete; requirements-only plan written and status:ready at
  ce-plan_path. Consumer set corrected to the six generator source-mappings,
  the four :recordbuf-codegen test sources, and the CodeGenerator/Main
  dispatch entry -- all uniformly on soabase today (no generator-local builder
  layer; there is nothing to consolidate). Modifier shape refined: the
  visibility dimension is a single Visibility enum + visibility(Visibility)
  setter (PUBLIC/PROTECTED/PACKAGE_PRIVATE/PRIVATE); static/final/sealed are
  flag fields and abstract/default/@Deprecated are orthogonal method-modifier
  flags (default and abstract are not visibilities -- a default interface
  method is necessarily public; interface methods are abstract by default).
  Next: ce-work / ce-code-review executes ce-plan_path.
source: manual
synthesis: >-
  A single, internally-phased improvement to recordbuf codegen's consumer-facing
  source AST (the API used by :recordbuf-generator and any future consumer):
  (1) hand-written ergonomic builders -- named flag toggles that default
  off + collection accumulators/batch helpers + a single visibility(Visibility)
  enum setter instead of per-visibility toggles -- replace the soabase
  @RecordBuilder surface on the node records, with modifiers becoming a
  structured type (one Visibility enum + static/final/sealed/sealed flags,
  with abstract/default/@Deprecated as orthogonal method-modifier flags)
  instead of a Modifiers.of("...") string joined by the renderer, and
  (2) a small, extensible closed idiom set of structured statements +
  recurring expression shapes (control-flow + expression fragments) lifted
  out of List<Line>, with the renderer taking ownership of indentation,
  brace-balancing, and line-joining and a raw text-fragment as the escape
  for irregular idioms. Behavior-preserving + format-clean in output; all
  consumers (the six generator source-mappings, the four :recordbuf-codegen
  test sources, and the CodeGenerator/Main dispatch entry -- all uniformly on
  the soabase *Builder surface today, no generator-local builder layer) are
  migrated to the hand-written builders; soabase is removed from :recordbuf
  codegen but left on the generator's internal model records (a known
  follow-up that reuses this same pattern). Forward-looking design posture:
  the API is kept decoupled + extensible so future code-generation projects
  can build on it -- without scope-expanding the current plan.
ce-plan: plan_written
ce-plan_path: docs/plans/2026-11-10-001-refactor-codegen-ast-plan.md
