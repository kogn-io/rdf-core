# ADR-0015: `DatasetLifecycle#close` reports a three-valued outcome

Status: Accepted

## Context

`DatasetLifecycle#close` is the eviction trigger the hosting port
([ADR-0009](0009-dataset-hosting-module-split.md)) asks a consumer's idle/TTL
policy to invoke, and it reported nothing back. Three materially different
things could happen behind that silence, none of them throwing on any ordinary
path:

1. the dataset was open and unleased — it was shut down;
2. a lease was still open — nothing happened, and the port's own contract tells
   the policy to retry later;
3. the lifecycle was not holding that dataset at all — already evicted, never
   opened, or an identifier it has never seen — nothing happened.

The port therefore instructed a policy to retry without giving it anything to
decide on: every call looked identical from where the policy stood, so it could
neither back off nor count attempts that achieved nothing. A consumer that
leaked a `DatasetHandle` kept its dataset resident until the process ended with
nothing in the port making that observable. Nor could the outcome be
reconstructed from a second call: against a `PERSISTENT` store `list()` still
reports the dataset after a successful eviction, because it also enumerates
datasets that are persisted but not currently open. Tracked in
[issue #111](https://github.com/kogn-io/rdf-core/issues/111).

## Decision

`close` reports which of the three outcomes occurred, as a named value in the
port module (`DatasetCloseOutcome`) — three values, not two:

- folding "there was nothing to shut down" into "shut down" would let a policy
  record an eviction it never performed;
- folding it into "still in use" would have that policy wait for a lease nobody
  holds.

Both collapses are wrong in the same direction — they invent a fact the
lifecycle never observed — and each is one half of what a `boolean` return could
carry. Three materially distinct outcomes rule a `boolean` out; the values are
named on the port so that a policy reads them rather than a truth value whose
meaning has to be looked up.

**"Not open" is explicitly not an error.** `list()` legitimately reports
datasets that are persisted but closed, so "unknown to this lifecycle" and "not
open right now" are the same situation as far as `close` is concerned, and a
caller tidying up must not be punished for an identifier that has already been
tidied up. Signalling it with an exception was rejected for that reason: it
would put a routine sweep's most ordinary result on the exception path, and it
would make the difference between a hosted and a merely persisted dataset —
which the port deliberately does not expose elsewhere — into a failure mode.

A failed teardown of the backing store remains the one path that exits with the
backend's exception instead of an outcome. That is a broken store, not a fourth
outcome.

Two shapes that would have avoided a breaking change were considered and
rejected. A separate predicate ("is this dataset open?") beside a `void close`
answers about a moment that has already passed by the time the eviction runs,
and cannot be atomic with it — the very race the lease counting exists to close.
A second, differently named method returning the outcome would leave the port
with two ways to evict, one of them documented as the one not to use.

## Consequences

- An idle/TTL policy can finally act on what happened: retry only on an open
  lease, count a genuine eviction as one, and treat an already-gone identifier
  as done rather than as work outstanding. That is the capability the port was
  already asking consumers to build against.
- **The change breaks compiled callers, not only implementers.** A method's
  return type is part of its JVM descriptor, so replacing `void` does more than
  force every implementation of `DatasetLifecycle` to be updated: an
  already-compiled *caller* of `close` — whose own source is unchanged and
  recompiles untouched — fails with `NoSuchMethodError` at runtime against the
  new port. It is therefore not a patch-level change; it lands in the next MINOR
  release window, together with the other breaks deferred to it.
- The set of outcomes is now published surface. A fourth distinction later is
  not free: callers that switch over the outcomes exhaustively have to be
  recompiled, so the three values are a commitment rather than a starting point.
- Each outcome is a distinct, testable path. Before this decision no test could
  tell them apart, because from outside the port they were the same call.
