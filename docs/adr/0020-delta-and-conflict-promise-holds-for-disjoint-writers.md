# ADR-0020: The delta and conflict promise of `add`/`remove` holds for disjoint writers only

Status: Accepted (2026-10-04) — corrects the scope of the promise in
[ADR-0012](0012-per-triple-conflict-surface-for-add-remove.md)

## Context

ADR-0012 narrowed the conflict surface of `DatasetTx#add`/`#remove` to the
triples they touch and stated the outcome broadly: concurrent transactions adding
or removing disjoint triples both commit, and the delta that `add`/`remove`
return is not distorted by concurrent writers to the same named graph.

The first half holds as stated. The second does not, once two writers touch the
*same* triple. Both observe it as absent, both write it, both commit, and both
report it as inserted (or removed): the reported deltas then sum to more than the
net change in the store. Outside a transaction, `GraphStore#add`/`#remove` behave
the same way. This is the isolation the backend provides, not a defect of the
adapter; the full-review finding R1-2 (#147, handled in #153) measured it.

Closing the gap would take a stricter isolation level for `GraphStore` plus a
conflict exception on a port that has none, which is a breaking change to the
port. The one known consumer only echoes the delta in a response and never sums
it, so an overlapping write shows up as a cosmetic count, not as a cumulative
error.

## Decision

The promise is limited to what holds. For `add` and `remove`, on `GraphStore`
and on `DatasetTx`:

- Writers of **disjoint** triples do not conflict, and the delta each returns is
  exact. This is the ADR-0012 promise, unchanged.
- Writers of **overlapping** triples get whatever the backend's isolation gives
  them. The port promises neither a conflict nor an exact delta; two writers
  adding the same triple may both report it as inserted, and likewise for removal.

No stricter isolation level and no additional conflict exception are introduced.
The port Javadoc states the same restriction.

The delta is a per-call report of what that call changed. A consumer must not
sum deltas across concurrent writers to infer the size of the store; it asks
`count` for that.

## Consequences

- The wording of ADR-0012 ("the conflict surface is exactly the triples they
  touch", the delta unaffected by concurrent writers) is read as applying to
  disjoint writers; this record is where the restriction is stated.
- No code change and no breaking change. The disjoint-writer behaviour that
  ADR-0012 established stays as it is.
- A consumer that needs the exact net effect of overlapping writers reads the
  count, or guards the triples with `DatasetTx#contains` (ADR-0008), which does
  conflict.
- Should a consumer ever need exact deltas under overlapping writers, that is a
  new decision with a port change, to be made then.
