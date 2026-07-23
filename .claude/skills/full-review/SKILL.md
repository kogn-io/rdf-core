---
name: full-review
description: "Whole-library audit of kogn-rdf — ports against their implementations, not a diff. Trigger: /full-review, 'review the whole library', 'full review', 'audit the ports', 'is this library sound'. Run before a release or when confidence in the abstraction has slipped. NOT for pull requests — use /review or /code-review ultra <PR#> for those."
---

# Full Review — contract audit of the whole library

Audits **the library as it stands**, independent of what changed recently.

## Why this exists (read before running)

The diff reviewers (`/review`, `/code-review`) cannot find the defects this skill targets,
and no checklist added to them would help. The reason is structural: the most damaging
problems in this repository live in the *relationship between two files that are both
unchanged*.

Worked example from the 2026-07-23 audit — `GraphStore.java` promises a triple-count delta
"measured atomically … concurrent writers cannot distort", while `GraphStoreRdf4j.java` opens
its transaction with a bare `conn.begin()` and therefore runs at `SNAPSHOT_READ`. Both files
had been stable for months. No diff touched them, so no diff review could see it.

The unit of work here is therefore **one port interface plus its implementation**, never a
range of commits.

## What this skill will not do

It will not tell you the library is fine. A green result is not the deliverable — the
**coverage report** in Phase 7 is. State plainly what was examined, how, and what was left
unexamined and why. A reviewer that claims completeness it did not achieve repeats exactly
the failure mode this repository already has: documentation that sounds more precise than the
code behind it.

## Ground rules

- **Verify, never recall.** Every claim about backend behaviour is checked against the actual
  artifact — read the class from the jar (`javap`), read the RDF4J source, write a throwaway
  test. Stating an exception hierarchy or an isolation default from memory is a defect in the
  review, not a shortcut. The 2026-07-23 audit confirmed `MalformedQueryException extends
  RDF4JException` by unpacking `rdf4j-query-6.0.0.jar`; that is the standard.
- **A finding needs a failure scenario.** Concrete inputs or interleaving → concrete wrong
  outcome. "This looks fragile" is not a finding.
- **Distinguish a limit from a hole.** A contract that deliberately excludes a class of
  backends is a boundary to be documented. A contract that the shipped implementation itself
  fails to meet is a hole. Say which one you found; they get different priorities.
- **Do not open issues without confirmation.** Report first, ask, then file.

## Scope

```bash
./mvnw -q -DskipTests compile          # make sure the tree builds before auditing it
find . -name "*.java" -not -path "*/target/*" | wc -l
```

Modules and their audit weight:

| Module | Weight | Why |
|---|---|---|
| `rdf-dataset` | **highest** | pure contract; every promise here is a promise for all future backends |
| `rdf-dataset-rdf4j` | **highest** | the only thing that has to keep those promises |
| `rdf-shacl` / `rdf-shacl-rdf4j` | medium | smaller surface, value types, no concurrency |
| `rdf-terms` | low | library-free data model, no I/O, no state |

At ~6k LOC the whole tree fits in one pass. Do not sample.

---

## Phase 1 — Contract audit (ports vs. implementation)

For every method on every interface in `rdf-dataset` and `rdf-shacl`, read the Javadoc, then
read the implementing method, and compare. Four sweeps, each of which has already caught
something:

1. **Every `@throws`.** Is the documented type the type actually thrown? Follow the call into
   the backend and check the real hierarchy from the jar.
   → found #31 (eight methods document `IllegalArgumentException`; `MalformedQueryException`
   is thrown).
2. **Every guarantee about isolation, atomicity or ordering.** Is it backed by the actual
   transaction settings — the isolation level passed to `begin(...)`, the connection scope,
   the commit path?
   → found #32 (delta atomicity claimed, `SNAPSHOT_READ` delivered).
3. **Every "implementations must" / "must not".** Is it enforced anywhere, or only written
   down? An unenforced "must not" is a defect whenever violating it fails silently.
   → found #34 (nested transactions forbidden, never checked).
4. **Every adjective describing runtime behaviour** — `lazily`, `eagerly`, `materialised`,
   `estimate`, `exactly`, `snapshot`, `atomically`. Check each against the implementation, and
   check them against **each other** across the ports.
   → found #36 (`count()` documented as an estimate while the `add()` delta must be exact —
   both are `conn.size()`) and #37 (`DatasetTx.select` documented lazy, implemented eager).

## Phase 2 — Concurrency and transactions

- Which isolation level does each write path actually use? Every `begin()` without an explicit
  level inherits a store default — name the default and say whether it carries the contract.
- Trace the commit path: what happens on conflict, is the failure surfaced as a neutral type,
  can a consumer act on it without importing a backend package? (→ #30)
- Guard reads: does the read that protects a write participate in conflict detection? This
  repository has measured, non-obvious behaviour here — read `docs/adr/0008-datasettx-contains-guard.md`
  before reasoning about it, and heed the memory entry `flaky-rate-needs-1000-runs`: a single
  passing run proves nothing about a race, and rates observed here ran 6–12% across machines.
- Nesting, re-entrancy, and what happens when the work function throws an `Error` rather than
  a `RuntimeException`.

## Phase 3 — Resource lifecycle and failure paths

For every class that owns a resource (`DatasetLifecycleRdf4j` above all):

- Walk each **failure** path, not the happy path. If teardown throws midway, what state is
  left behind — and is it still reachable through the public API? (→ #33: a failed
  `deleteStorageOnDisk` leaves a shut-down store in the cache, so the next `acquire` hands out
  a dead handle.)
- Is an invariant that the class documents actually enforced, or does it depend on callers
  obeying prose? (→ #35: leases protect nothing, because the accessors hand out shared,
  lease-blind port objects.)
- Every `AutoCloseable`: double-close, use-after-close, close-during-use.
- See the memory entry `lifecycle-callback-exception-path` — this class of bug has appeared
  here before.

## Phase 4 — Backend neutrality

For each port method, hold it against a **second, hypothetical backend** — a remote SPARQL
endpoint (Fuseki over HTTP, Neptune, Stardog) and an embedded alternative (Jena TDB2):

- Could that backend implement this method as specified?
- If not, what does the port offer it instead of silent non-compliance — a capability query,
  a documented `UnsupportedOperationException`, a split interface? (→ #40: `DatasetTransactor`
  requires commit-time conflict detection, which the SPARQL protocol cannot provide, and
  nothing in the port lets an adapter say so.)
- Does any port leak concerns that are not RDF — hosting, storage layout, process lifetime?
  (→ #41: `DatasetLifecycle`.)

## Phase 5 — Test coverage gaps

Not "is coverage high", but: **which documented promise has no test?** For every contract
sentence found in Phase 1, look for the test that would fail if the implementation stopped
honouring it. A promise without such a test is how #31 survived — the invalid-query path had
no test at all.

Call out concurrency assertions specifically: a race asserted by a single run is untested, no
matter what the coverage number says.

## Phase 6 — Documentation consistency

Check `README.md`, `ARCHITECTURE.md`, `CLAUDE.md` and `docs/adr/*` against the code — but only
where this audit's findings touch them. Two questions:

- Does any of them assert something the code no longer does?
- Did this audit surface a decision that is *made* but nowhere recorded as an ADR?

Sample IRIs and illustrative snippets are not drift. An overstated claim that is already the
subject of an open issue is not drift either — note it, do not duplicate it.

## Phase 7 — Report

Two sections. The second is the point of the exercise.

```markdown
## Full review — <date>, <commit>

### Findings

Ranked most severe first. Per finding:
- file:line
- what the contract says / what the code does
- concrete failure scenario
- hole or limit?
- suggested fix, and whether it breaks the public API

### Coverage

| Area | How it was checked | Confidence |
|---|---|---|
| ... | ... | high / partial / not checked |

**Not examined, and why:** <explicit list>
**Checked but not provable here:** <e.g. race behaviour under load, other JVMs, other OSes>
```

Then, and only then, offer to open issues. Propose titles, labels
(`bug` / `enhancement` / `documentation` / `question` plus `priority: high|medium|low`) and
the ranking; file them after confirmation. Group API-breaking fixes explicitly — this
repository releases them together, see the memory entry
`shacl-message-break-consumer-migration-open`.

## Execution notes

Phases 1–4 are independent and may run as parallel subagents over disjoint files; phases 5–7
need the combined result and run in the main session. Whoever runs a phase must verify its
findings against the real artifact before reporting — an unverified finding costs more than a
missed one, because it teaches you to distrust the report.

Report prose in the language of the conversation; code, identifiers and anything written into
the repository or the issue tracker in English.
