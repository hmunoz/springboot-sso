# Movie creation procedure as a single source of truth (plan T4)

**Locator:** `springboot-sso/odd/tasks/mcp-movie-creation-procedure.md` · Engram topic `odd/mcp-movie-creation-procedure/tasks`

## Objective

Keep the movie creation procedure in exactly one place (catalog-service) and have the agent consume it, instead of keeping two diverging copies.

## Problem

- `MovieMcpPrompts` (`catalog-alta-pelicula`) publishes the real procedure: search first because the title is UNIQUE, read `catalog://genres`, price and imageUrl input rules, then `create_movie`.
- `CatalogSubAgent` hardcodes its own sentence, "También podés CREAR nuevas películas usando create_movie", and never mentions search-before-create.
- Nothing consumes the MCP prompt. It requires `titulo`, and the system prompt is built before the model reasons, so the prompt cannot be injected.

## Why

Two copies of the same domain knowledge have already diverged. The server owns the validation rules, so the server has to publish them.

## Scope

- catalog-service: extract the procedure steps into one place. Expose them as the resource `catalog://procedures/movie-creation` (no arguments, `movie-permission-read`). `catalog-alta-pelicula` renders the same steps with `titulo`.
- videoclub-agent: `CatalogSubAgent` injects the procedure resource the same way it injects genres, with the same degradation on failure, and drops the hardcoded create sentence. The `json:movies` output format stays in the agent: it is a UI contract, not a domain rule.
- Replace the deprecated mcp-core constructors in `MovieMcpPrompts` and `MembershipMcpPrompts`, and the deprecated `onConstructor` in `NativeRuntimeHintsTest`.
- Update plan T4, 10.2 and 10.3 in `docs/MCP-resoruce-prompt-plan.md`.

## Constraints

- The user commits. No commits and no git index changes.
- `titulo` stays a required argument of the MCP prompt.

## TDD

- Mode: off. No TDD configuration exists in the project or the session.
- Checks: `./mvnw -B test` in each touched module.

## Tasks

- [x] T4.1 catalog-service: single procedure source, new resource, prompt reuses it, builders replace the deprecated constructors, tests
- [x] T4.2 membership-service: builders replace the deprecated constructors in `MembershipMcpPrompts`
- [x] T4.3 catalog-service: replace the deprecated `onConstructor` in `NativeRuntimeHintsTest`
- [x] T4.4 videoclub-agent: inject `catalog://procedures/movie-creation`, remove the hardcoded create sentence, tests
- [x] T4.5 docs: mark T4 done and update 10.2 and 10.3

## Acceptance criteria

- The procedure text exists in exactly one place in catalog-service.
- The agent system prompt contains the procedure from the resource and no hardcoded `create_movie` sentence.
- A failed procedure read degrades the prompt and does not fail the request.
- No deprecation warnings remain in the touched classes.
- All three modules' tests pass.

## Progress

All five tasks implemented by a single delegated writer.

- **T4.1** — Added `catalog-service/.../mcp/MovieCreationProcedure.java` (package-private, `steps()`),
  the single source of the procedure text (title-independent step 1). `MovieMcpResources` gained
  `movieCreationProcedure()` publishing `catalog://procedures/movie-creation`
  (`@PreAuthorize("hasAuthority('movie-permission-read')")`), returning a Markdown heading plus the
  shared steps. `MovieMcpPrompts.altaPelicula` now renders an intro with `titulo` plus the same
  shared steps, and both `MovieMcpPrompts` and the resource builders replaced the deprecated
  `new GetPromptResult(String, List)` / `new TextContent(String)` constructors with
  `GetPromptResult.builder(...).description(...).build()` and `TextContent.builder(text).build()`.
  Verified against `mcp-core-2.0.0.jar` with `javap -v`: both constructors carry
  `Deprecated: true` / `RuntimeVisibleAnnotations: java.lang.Deprecated`; `PromptMessage(Role,
  Content)` carries no such attribute. Updated `McpPromptsTest` (kept the `titulo`-substitution and
  `search_movies` assertions, added an assertion on the new title-independent step-1 wording) and
  `McpResourcesSecurityTest` (discoverability of the new fixed URI, allow/deny with
  `movie-permission-read`, anonymous denial, content assertions for `search_movies` /
  `catalog://genres` / `create_movie`, and the regression anchor
  `procedureResourceAndPromptShareTheSameSteps` asserting the resource's and the prompt's step text
  — from `"1. Busca"` onward — are byte-identical).
- **T4.2** — `MembershipMcpPrompts.altaSocio` replaced the same two deprecated constructors with the
  builders. No behavior change; confirmed no remaining `new GetPromptResult`/`new TextContent` in
  membership-service.
- **T4.3** — `NativeRuntimeHintsTest` replaced `RuntimeHintsPredicates.reflection().onConstructor(...)`
  with `.onConstructorInvocation(...)`. Verified with `javap -v` against
  `spring-core-7.0.9.jar` (the resolved version via `./mvnw dependency:tree`): `onConstructor`
  carries `@Deprecated(since = "7.0", forRemoval = true)` whose javadoc (from the sources jar)
  explicitly names `onConstructorInvocation(Constructor)` or `onType(Class)` as the replacement.
  `onConstructorInvocation` checks the INVOKE-level hint, matching exactly what
  `NativeRuntimeHints` registers (`MemberCategory.INVOKE_DECLARED_CONSTRUCTORS`), so it is
  semantically equivalent for this test.
- **T4.4** — `CatalogSubAgent` dropped the hardcoded "También podés CREAR..." sentence. Generalized
  `genresSection()` into `resourceSection(uri, header)`, called once for
  `catalog://procedures/movie-creation` (new header "--- PROCEDIMIENTO DE ALTA DE PELÍCULAS
  (recurso MCP catalog://procedures/movie-creation) ---") and once for `catalog://genres`
  (unchanged header), in that order, both read per-call (never in the constructor) with the same
  WARN-and-degrade-to-"" behavior on `RuntimeException` or blank content. `CatalogSubAgentTest`
  updated: added tests for the procedure section (included on success, omitted on failure while
  genres still appears) and a dedicated test asserting the hardcoded create sentence is gone.
  Fixed two pre-existing genres tests to also stub the now-also-called procedure URI (otherwise
  MockitoExtension's strict stubbing throws `PotentialStubbingProblem`, which `resourceSection`'s
  broad `catch (RuntimeException e)` was silently swallowing — tests still passed but for the wrong
  reason; fixed to stub both URIs explicitly). Checked `videoclub-agent/README.md`: it only
  describes `CatalogSubAgent` generically (no mention of the hardcoded sentence or a genres-specific
  injection mechanism), so no doc change was needed there.
- **T4.5** — `docs/MCP-resoruce-prompt-plan.md`: 10.2 table's "Procedimiento de alta" row marked
  done via the new resource; the WARNING block below it replaced with a NOTE stating the
  duplication is resolved, naming `MovieCreationProcedure#steps()` as the single source and
  `McpResourcesSecurityTest.procedureResourceAndPromptShareTheSameSteps` as the regression anchor.
  10.3 table's Recurso row marked implemented+consumed (`catalog://genres`,
  `catalog://procedures/movie-creation`); Prompt row marked not-consumed "by design", linking T4.
  T4's **Estado** changed to done, stating the resource path was chosen and why (always-needed
  context vs. a user-chosen flow with a known title). No separate resource-listing TOC section was
  found beyond section 10.3's mechanism table, which was already updated.

### Verification evidence (observed)

1. `cd catalog-service && ./mvnw -B test`: BUILD SUCCESS, `Tests run: 26, Failures: 0, Errors: 0,
   Skipped: 0` (includes 3 `McpPromptsTest` + 10 `McpResourcesSecurityTest`, up from 3 + 8 before
   this change).
2. `cd membership-service && ./mvnw -B test`: BUILD SUCCESS, `Tests run: 31, Failures: 0, Errors: 0,
   Skipped: 0`.
3. `cd videoclub-agent && ./mvnw -B test`: BUILD SUCCESS, `Tests run: 28, Failures: 0, Errors: 0,
   Skipped: 0` (`CatalogSubAgentTest` up from 5 to 7 tests, all green, no stray
   `PotentialStubbingProblem` warnings after the stub fix).
4. Deprecation scan, both services: `./mvnw -B clean test-compile -Dmaven.compiler.showDeprecation=true
   -Dmaven.compiler.showWarnings=true 2>&1 | rg -i '\[WARNING\].*deprecat'` — empty output in both
   catalog-service and membership-service. No remaining deprecation warnings.
5. `rg -n 'create_movie' videoclub-agent/src/main` — empty output. The hardcoded sentence and every
   other `create_movie` mention are gone from the agent's main source (the tool name itself only
   still appears server-side and in test/prompt text, as expected).

No environment-caused partial results: no Docker/DB/Keycloak dependency was touched by any of the
three test suites run.

## Next step

None. All T4.1–T4.5 tasks are complete and verified. Optional follow-up (not part of this task,
not authorized here): T3 (server `instructions` as domain description) and T6 (extracting the
`json:movies` format contract out of the `String.format`) remain pending in
`docs/MCP-resoruce-prompt-plan.md`, unchanged by this work.
