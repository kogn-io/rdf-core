# ADR-0007: Standalone, non-transactional SHACL validation port

Status: Accepted

## Context

Some consumers need to validate a *candidate* graph against SHACL shapes
**before** writing it — without making the store itself schema-constrained. This
is the single-consumer / controlled-write case (e.g. an ontology-backed model
repository whose adapter is the sole writer): one adapter owns every write, so
validation can happen as a separate step ahead of the write rather than being
enforced inside a transaction.

A transactional, write-path variant (an RDF4J `ShaclSail` wired into the dataset
so every commit is checked) is the right tool for a *shared* store with
multiple, uncontrolled writers. But it couples validation to the store, changes
the dataset configuration, and is oversized when a single adapter controls the
write path. That variant is deferred (tracked separately) and deliberately not
built here.

RDF4J already ships a non-transactional validator
(`org.eclipse.rdf4j.sail.shacl.ShaclValidator`: load shapes + data, get a
report). The requirement is a **backend-neutral wrapper** so consumers depend on
Kogn RDF, not on `rdf4j-shacl`.

## Decision

Add a validation **port** in its own module, with an RDF4J adapter, kept
independent of the store:

- **`rdf-shacl`** (port) — `ShaclValidation.validate(data, shapes, options)`
  operating on `io.kogn.rdf.terms.ReadableGraph`, returning a neutral value
  object `ShaclReport(conforms, results)` / `ShaclResult(focusNode, path,
  severity, messages)` / `ShaclMessage(text, language)` /
  `Severity{VIOLATION, WARNING, INFO}`, plus
  `ValidationOptions`. The module depends **only on `rdf-terms`** — no rdf4j,
  no `File`, no backend type on the port (enforced by a structural test).
- **`rdf-shacl-rdf4j`** (adapter) — wraps `ShaclValidator` and maps its report
  to `ShaclReport`. It depends only on `rdf-terms`, `rdf-shacl` and rdf4j; it
  does **not** depend on `rdf-dataset`/`rdf-dataset-rdf4j`. A small local
  `ReadableGraph → org.eclipse.rdf4j.model.Model` converter keeps validation
  store-independent rather than reusing the dataset adapter's converter.

Settled semantics:

- **Severity is load-bearing for conformance.** Only `sh:Violation` sets
  `conforms = false`; `sh:Warning` and `sh:Info` are carried in `results` but
  are non-fatal. RDF4J's own `ValidationReport.conforms()` treats *any* reported
  result (regardless of severity) as non-conforming, which contradicts this — so
  the adapter ignores it and recomputes `conforms` from the mapped severities,
  re-checked by `ShaclReport`'s own invariant.
- **Messages are handed over whole; language selection is not the port's job.**
  A shape may carry one `sh:message` per language, so `ShaclResult.messages`
  carries *every* `sh:resultMessage` as a `ShaclMessage` with its language tag
  intact (`language == null` for an untagged literal; a blank tag is rejected so
  `""` cannot pass for a language). The port picks none of them and ships no
  picker: a fallback chain is a deployment decision — which language was
  requested, which the deployment defaults to, whether an untagged literal beats
  a foreign-tagged one — and belongs where that context exists. The order of
  `messages` is a parse-order artifact of the shapes graph and carries no
  meaning; callers select by tag. Superseding the earlier single `String
  message`, which reduced multilingual shapes to one arbitrarily chosen,
  tag-stripped text (issue #20).
- **RDFS subclass reasoning is an opt-in option** (`ValidationOptions
  .rdfsSubClassReasoning`, default `false`). Real and load-bearing: a shape may
  `sh:targetClass` an abstract superclass while instances carry only a subclass
  type; plain SHACL does not match across `rdfs:subClassOf`, so without it the
  shape silently never fires. The flag maps onto the RDF4J validator's
  subclass-reasoning setter.

## Consequences

- The store stays "a store of named graphs, nothing more"; the dataset modules
  and their behaviour are unaffected. Validation is a stateless capability the
  consumer invokes, not a property of the store.
- No RDF4J type appears on the SHACL port; the backend remains swappable, as for
  the dataset ports (see [ADR-0005](0005-rdf4j-backend-for-dataset-ports.md)).
- The neutral `ShaclReport` value object is the shared foundation a future
  transactional write-path enforcement can reuse: it would produce the *same*
  report, only from within a commit. Building the standalone path first is
  groundwork, not throwaway.
- The adapter depends on RDF4J's `ValidationReport`/`ValidationResult`, which are
  `@Deprecated` in RDF4J 6.0.0 and expose their fields only non-publicly; the
  only public extraction path is `report.asModel()`, which the adapter walks via
  the `sh:` vocabulary. This is a known coupling to revisit if RDF4J replaces the
  reporting API.
