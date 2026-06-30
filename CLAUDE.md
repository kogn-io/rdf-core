# Kogn RDF — Hinweise für Mitwirkende

Backend-agnostische RDF-Abstraktion: reines Datenmodell, Dataset-Ports und ein
austauschbares RDF4J-Backend. Veröffentlicht unter Apache-2.0.

## Module

| Verzeichnis | Artefakt | Rolle |
|---|---|---|
| `cg-rdf-terms` | `io.kogn.rdf:rdf-terms` | RDF-Datenmodell (Term-Interfaces, Graph-Typen, Vocab). **Library-frei** — keine externen Dependencies. |
| `cg-rdf-dataset` | `io.kogn.rdf:rdf-dataset` | Backend-agnostische Dataset-Ports: `GraphStore`, `SparqlQuery` (read), `SparqlUpdate` (write), `DatasetTransactor`, `DatasetTx`. |
| `cg-rdf-dataset-rdf4j` | `io.kogn.rdf:rdf-dataset-rdf4j` | RDF4J-Implementierung der Dataset-Ports. |

Verzeichnisnamen sind historisch `cg-rdf-*`, die Artefakte/Packages `io.kogn.rdf.*`.

## Tech-Stack

- **Java 25** (`maven.compiler.release=25`)
- **Maven 4** über eingecheckten Wrapper (`./mvnw`) — kein lokales Maven nötig.
- Versionen zentral via `${revision}` in der Root-`pom.xml`.

## Bauen & Testen

```bash
./mvnw verify          # bauen + testen (rdf4j-Integrationstests laufen mit)
./mvnw -pl <modul> test
```

Deploy in die Maven-Registry läuft **ausschließlich über CI** (Forgejo Actions:
Push auf `develop`), nicht lokal.

## Konventionen

- **Conventional Commits** (`type(scope): subject`), **Semantic Versioning**.
- `record` für Value Objects (Commons-RDF-orientierte Term-Typen).
- `cg-rdf-terms` bleibt **library-frei** — dort keine Dependencies hinzufügen.
- Bestehenden Patterns folgen, nicht raten — im Code nachschauen.
- Tests: JUnit 5 + AssertJ. Domänenlogik testen; Bugfix = Failing-Test-first.
- **IMMER Datei lesen vor Edit** (Build formatiert via spotless automatisch).
- **Lizenz-Header**: Jede `.java` trägt am Dateianfang den SPDX-Header
  (`// SPDX-License-Identifier: Apache-2.0` + `// Copyright <Jahr> Fred Hauschel`).
  Quelle ist `license-header-java.txt`; spotless (`licenseHeader`) schreibt/erzwingt
  ihn — `spotless:apply` ergänzt fehlende, `spotless:check` (in `verify`) ist das
  CI-Gate. Ausnahme: `package-info.java` (von spotless bewusst ausgelassen).
