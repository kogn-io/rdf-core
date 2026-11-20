# ADR-0028: The hosting port names its acts and its states once; corrects ADR-0015 and ADR-0018

Status: Accepted (2026-11-19) — corrects the names in
[ADR-0015](0015-three-valued-dataset-close-outcome.md) and
[ADR-0018](0018-dataset-maintenance-port.md); their decisions stand

## Context

ADR-0015 decides that the eviction call reports a three-valued outcome and names
it `DatasetLifecycle#close` / `DatasetCloseOutcome`. ADR-0018 decides that clearing
up after a failed delete is a port of its own and shows it as
`listUnfinishedDeletes` / `clearUnfinishedDelete`. Both decisions are sound; three
names are not.

- **`close` means two acts.** `DatasetLifecycle#close(DatasetId)` shuts the store
  down, for an `IN_MEMORY` dataset as destructively as `delete`, while
  `DatasetHandle#close()` only releases a lease. A caller reads `close(id)` as the
  counterpart of `acquire(id)` and loses an in-memory dataset. The port's own
  `shutDownAll()` is documented as "the same act" as `close(id)`.
- **One state has five names.** What a failed delete leaves behind is called
  "unfinished delete", "remains", "partial", "deletion in progress" and "failed
  delete" across the port and its implementation. "Unfinished delete" names a
  process, not a state, so `clearUnfinishedDelete` reads as "abort the delete"
  (data stays) as well as "remove what is left" (data gone for good), and it does
  the latter.
- **`DatasetStoreConfig#fullTextSearch` has one valid value.** The only
  implementation rejects `true`, and the component is named before full-text search
  (#6) is designed.

Separately, `DatasetLifecycleRdf4j` was a public, non-final class of about 1100
lines that carried the lifecycle, the on-disk layout and marker protocol, and the
handle decorators; its tests overrode two package-private methods in anonymous
subclasses, so the class had to stay non-final, and a consumer could override
`acquire`, `close` and `delete` and bypass the per-key lock and the lease count
its correctness depends on.

## Decision

- `DatasetLifecycle#close(DatasetId)` becomes `shutDown(DatasetId)`, beside
  `shutDownAll()`. `DatasetCloseOutcome` becomes `DatasetShutdownOutcome`, with the
  values `SHUT_DOWN`, `STILL_LEASED` and `NOT_OPEN`; the three-valued decision of
  ADR-0015 is unchanged. `DatasetHandle#close()` stays.
- The state is called a *deletion leftover* everywhere: `listUnfinishedDeletes`
  becomes `listDeletionLeftovers`, `clearUnfinishedDelete` becomes
  `clearDeletionLeftover`; `DatasetCleanupOutcome` keeps its name and its values.
  The maintenance port of ADR-0018 is otherwise as decided. The on-disk marker file
  `.deleting` is stored format and keeps its name.
- `DatasetStoreConfig` loses `fullTextSearch`: `DatasetStoreConfig(Persistence)`.
  Full-text configuration is designed with #6.
- `DatasetLifecycleRdf4j` is `final`. The on-disk layout, the marker protocol, the
  delete walk and the directory scan move to the package-private `DatasetStorage`,
  which a package-private constructor takes; the handle decorators and the cached
  dataset move to package-private files. Tests replace the collaborators instead of
  subclassing the lifecycle.

## Consequences

- Breaking for every caller of the renamed methods and types, and of the
  two-argument `DatasetStoreConfig` constructor and `fullTextSearch()`; and for
  anyone who subclassed `DatasetLifecycleRdf4j`. All of it sits in the 0.4.0 break
  window; `DatasetShutdownOutcome` (as `DatasetCloseOutcome`), `DatasetCleanupOutcome`
  and `DatasetMaintenance` were not yet released.
- An implementation of `DatasetLifecycle` outside this repository has to rename its
  `close(DatasetId)`.
- The on-disk format is unchanged: a directory carrying `.deleting` is a deletion
  leftover for an instance of either version.
- ADR-0015 and ADR-0018 stay as written and point here from their status lines;
  this ADR is their correction.
