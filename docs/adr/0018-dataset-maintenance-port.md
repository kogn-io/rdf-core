# ADR-0018: Clearing up after a failed delete is a port of its own

Status: Accepted

## Context

A `DatasetLifecycle#delete` can fail with the dataset half gone — removing a
persistent dataset's storage is a directory walk, and a locked file or a
permission problem stops it in the middle. The hosting port then refuses the
identifier in `acquire` and leaves it out of `list`, so the remains are never
handed out as though they were a dataset.

Nothing in the port acknowledged that state beyond refusing it. A consumer that
has to account for the data — a deletion obligation it must meet, a maintenance
run — had no way to ask what is still lying around: after a restart the only
record was an on-disk marker and an `ERROR` log line, and a log line is not a
channel a library can promise its caller. Nor could it clear the remains on
purpose: `acquire` retries the cleanup, but on success creates and seeds a new
dataset, which a maintenance run does not want, and between that `acquire` and
the `delete` it would have to follow up with, the identifier holds a real,
openable dataset. Tracked in
[issue #115](https://github.com/kogn-io/rdf-core/issues/115).

## Decision

A new port `DatasetMaintenance` in `rdf-dataset-hosting`, beside
`DatasetLifecycle` rather than inside it:

```java
Set<DatasetId> listUnfinishedDeletes();                  // the identifiers carrying such remains
DatasetCleanupOutcome clearUnfinishedDelete(DatasetId id); // CLEARED or NOTHING_TO_CLEAR
```

`DatasetLifecycleRdf4j` implements both, so a consumer takes both views from
the one instance that owns the storage location.

**Not two more methods on `DatasetLifecycle`.** That would add abstract methods
to a published interface and break every implementation outside this
repository, for a capability that is no part of normal dataset use. A `default`
method that throws was rejected for the reason ADR-0013 gives: it compiles, and
moves the break to runtime. The split holds on the merits too — clearing up
after a failed delete is maintenance, not working with a dataset, and a consumer
that never runs maintenance never sees the port.

**Clearing refuses an intact dataset.** Getting rid of a dataset is `delete`,
the one path that guards against open leases. Remains have no lease by
definition, so a cleanup that also accepted an intact dataset would be a second
way to delete that skips the single safeguard the destructive command has. An
identifier carrying neither a dataset nor remains is not an error: it reports
`NOTHING_TO_CLEAR`, the same shape `close` takes on an unknown identifier
(ADR-0015), because a run that finds the work already done — by another caller,
or by an `acquire` retrying the cleanup itself — has nothing to recover from. A
cleanup that fails again exits with the backend's exception and leaves the
identifier barred.

**Enumeration is all-or-nothing.** If the storage cannot be read, the call
fails rather than return a partial set, which a caller meeting an obligation
could not tell from a complete one.

## Consequences

- The remains of a failed delete are observable and clearable through the port,
  across a process restart, without creating anything in their place; an
  operator no longer has to work on the storage directory guided by a log line.
- Purely additive: no existing interface changes, so no implementation or caller
  outside this repository has to follow, and the change can ship in any MINOR
  release.
- An implementation of `DatasetLifecycle` outside this repository that can leave
  remains behind gains a second port to implement if its consumers need
  maintenance; nothing forces it to.
- `listUnfinishedDeletes` and `DatasetLifecycle#list` never overlap: an
  identifier is reported as usable or as remains, not both.
