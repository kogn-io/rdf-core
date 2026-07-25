# ADR-0010: SPARQL parameter binding via `Map<String, RDFTerm>` overloads

Status: Accepted

## Context

Every query and update method in the dataset ports (`SparqlQuery#select` /
`#construct` / `#ask`, `SparqlUpdate#update`, and the matching methods on
`DatasetTx`) takes a bare `String`. There is no way to bind a value: a caller
that needs to query for a specific IRI or literal — nearly every real query —
has to build the SPARQL string by concatenation and do its own IRI and literal
escaping. Getting that wrong with attacker-influenced input is SPARQL
injection; getting it wrong with benign input (a label containing a quote or a
newline, an IRI with a brace) is a malformed-query failure at runtime.

RDF4J supports this properly via `Operation#setBinding(String, Value)`; the
port did not pass it through, so a caller could not reach it without dropping
to the backend and losing the point of a backend-neutral port. Tracked in
[issue #38](https://github.com/kogn-io/rdf-core/issues/38), which also names
the precedent: `DatasetTx#contains` (ADR-0008) was introduced in the same
context — a typed, pattern-stating API turned out to be both safer and
better-behaved than a hand-built query string for the guard-read case. This
decision applies the same argument to queries and updates generally.

Two shapes were on the table for the binding API itself:

1. **`Map<String, RDFTerm>` overload** alongside every existing `String`
   method, e.g. `select(String sparql, Map<String, RDFTerm> bindings)`.
2. **A small `SparqlOperation` builder** (`sparql(String).bind("s",
   term).select()` or similar), replacing the flat method list with a
   fluent object.

## Decision

Add a `Map<String, RDFTerm>` overload next to every existing `String`-only
query/update method — `select`, `construct`, `ask` on `SparqlQuery` and
`DatasetTx`; `update` on `SparqlUpdate` and `DatasetTx` — and keep the
`String`-only overloads unchanged. Each entry of the map substitutes its value
for the same-named `?variable` before the operation runs, the way a
`PreparedStatement` parameter substitutes for `?` in JDBC. The map key is the
variable name without the leading `?` (`"s"`, not `"?s"`), matching RDF4J's
own `setBinding` convention and `BindingSet#getValue`'s naming on the read
side.

The builder shape was rejected for this port. It solves a problem this port
does not have: `SparqlOperation` would earn its keep once binding needed to
compose with something else — paging, a result-set limit, per-call timeouts —
none of which are on the ports today. Introduced now, it would be one type
learned for a single method call (`.bind(k, v).select()` instead of
`select(sparql, bindings)`), and it forces a decision this port has
deliberately avoided elsewhere: whether the same builder is shared across
`SparqlQuery`, `SparqlUpdate` and `DatasetTx`, or forked per interface. A flat
overload needs neither decision and matches the existing shape of every method
already on these three interfaces.

Binding values are plain `RDFTerm`s from `io.kogn.rdf.terms`, converted to the
backend's value type internally — `RDF4JConverters#toRDF4JValue` already does
this for every term kind used elsewhere in the RDF4J adapter, so the
implementation is direct: prepare the operation as before, then call
`setBinding` once per map entry before evaluating or executing it.

An empty map is defined to behave exactly like the `String`-only overload
(and, in the RDF4J implementation, the `String`-only overload delegates to the
bindings overload with `Map.of()`, so there is exactly one execution path per
operation kind, not two).

## Consequences

- `SparqlQuery`, `SparqlUpdate` and `DatasetTx` each gain new abstract
  methods; every implementation, including outside this repository, must add
  them. Besides `SparqlQueryRdf4j` / `SparqlUpdateRdf4j` / `DatasetTxRdf4j`,
  the hosting adapter's handle-bound delegates
  (`DatasetLifecycleRdf4j$HandleBoundSparqlQuery` /
  `$HandleBoundSparqlUpdate`) also implement these ports directly and needed
  the same additions. This is a breaking change and lands in the
  still-unreleased next version alongside whatever else is open at release
  time.
- The `String`-only overloads are unchanged in behaviour and remain the right
  choice for a query with no external values to bind (a fixed `ASK { ?s ?p
  ?o }`, a static `CONSTRUCT`). The bindings overload is additive, not a
  replacement — a caller is not forced to build a `Map` for every call.
- This does not make injection structurally impossible: a caller can still
  concatenate an unbound value into the SPARQL string instead of binding it.
  The port makes the safe path available and no more verbose than the unsafe
  one; it cannot make the unsafe one unavailable without also removing the
  `String`-only overloads, which would break every existing caller for no
  gain — the raw string form is still needed for the query structure itself
  (variables, patterns, filters), which cannot be parameterised this way.
- `DESCRIBE` remains unsupported (unchanged from the existing `SparqlQuery`
  scope) and gets no bindings overload for the same reason.
