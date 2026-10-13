# Linguist Java Data Schemas

This directory defines serialization and versioning contracts shared by
upstream synchronization, generated runtime data, and conformance tooling.
It establishes the common rules and reserves independent artifact versions.
Complete schemas for generated runtime data and conformance fixtures must be
defined before those artifacts are produced.

## Files

- `upstream-manifest.schema.json` validates `upstream/manifest.json`.
- `runtime-data-manifest.schema.json` validates the packaged runtime data
  manifest.
- `schema-versions.json` is the registry of independently versioned artifact
  families.

## Canonical JSON

All committed JSON produced by Linguist build tooling follows these rules:

- Encode as UTF-8 without a byte-order mark.
- End every file with one LF newline and emit no CRLF line endings.
- Indent with two spaces and emit no trailing whitespace.
- Sort object keys lexicographically by Unicode code point.
- Preserve array order when it has domain meaning. Otherwise sort by the
  documented stable key; manifest files are sorted by `path`.
- Write integers in base 10 without a leading plus sign or leading zeroes.
- Write non-integral finite values in plain decimal notation without a trailing
  zero or decimal point. NaN and infinities are forbidden.
- Escape only characters required by JSON. Do not depend on the host locale.

Canonical output never contains absolute paths, random identifiers, process
IDs, hostnames, or environment-specific values.

## Paths and Imported Bytes

- Manifest paths are relative upstream Git paths using `/` separators.
- Paths must not be absolute and must not contain `.` or `..` segments or `\\`.
- Preserve upstream case and Unicode code points exactly; do not apply host
  filesystem case folding or Unicode normalization.
- SHA-256 values are lowercase hexadecimal over the exact imported bytes.
- Imported file contents and line endings remain unchanged. The LF rule applies
  only to generated JSON and other Java-owned text artifacts.
- Git modes are six-digit octal strings. Symlinks record both their blob hash
  and their target text; they are never followed while hashing.

## Versions

- Every artifact family has its own positive integer schema version.
- Increment an artifact version whenever readers must interpret a field, value,
  ordering rule, or semantic meaning differently.
- Adding an optional field still requires a version increment unless existing
  readers explicitly ignore unknown fields and the schema permits them.
- Increment `registryVersion` when the registry structure changes, not when an
  artifact version value changes.
- Readers reject unsupported versions instead of guessing or silently dropping
  data.

## Audit Timestamp

`upstream/manifest.json#importedAt` records when a changed snapshot was imported.
It is audit metadata and is excluded from generated runtime content and hashes.
Synchronization preserves the existing value when the origin, commit, and file
set are unchanged, so repeating an import of the same revision produces no diff.

## License Provenance

Every manifest entry identifies a license record. The current snapshot uses
`upstream-mit`, mapped to `upstream/linguist/LICENSE` with SPDX identifier
`MIT`. Future third-party inputs require their own license identifier, copied
license text, and manifest association before import.
