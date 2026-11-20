# ADR-0029: A null argument raises `NullPointerException`; corrects ADR-0017

Status: Accepted (2026-11-20) — corrects the null-base rule of
[ADR-0017](0017-content-addressed-iri-takes-the-base.md)

## Context

ADR-0017 states: "A base containing `#`, or a null base, raises
`IllegalArgumentException`." The first half is a rejection of the input and
stays. The second half made `rdf-cid` one of several modules that reported a
`null` argument as `IllegalArgumentException`, while `rdf-versioning`,
`rdf-dataset-hosting`'s `DatasetLifecycle` and the `rdf-terms` factory threw
`NullPointerException`, and the SPARQL adapters let a `null` string reach the
backend, where it came back as a port exception. A caller could not tell from
the type whether its input was refused or its call was broken.

## Decision

Every public port, implementation and value object of the library reports
`null` for a required argument as `NullPointerException` (Effective Java,
item 49), raised before the backend is touched and outside every translation
region. A value that is not `null` but invalid stays `IllegalArgumentException`
or the exception the port documents for it.

For `rdf-cid` this means: a `null` base or graph raises `NullPointerException`;
a base containing `#` (and every other failed precondition of ni-rdf/1 §3)
still raises `IllegalArgumentException` with the spec's failure code. A `null`
triple or term *inside* the graph is a broken term, not an argument, and stays
`[UNSUPPORTED_TERM]`.

## Consequences

- Breaking for a caller of `generateIRI` or `BaseRebaser#rebase` that caught
  `IllegalArgumentException` for a `null` argument; `rdf-cid` is in no release.
  The same change in published modules (`rdf-shacl`, `rdf-dataset-hosting`,
  `rdf-dataset-rdf4j`) sits in the 0.4.0 break window.
- An `IllegalArgumentException` from `rdf-cid` is now always a rejection of the
  input; without a failure code it comes from the graph or the term factory.
- ADR-0017 stays as written and points here from its status line; everything
  else it decides stands.
