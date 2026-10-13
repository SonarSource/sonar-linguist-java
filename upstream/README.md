# Upstream Linguist Snapshot

`upstream/linguist/` contains the upstream data inputs used by the Java port,
copied byte-for-byte from GitHub Linguist commit
`5fbdfcb8133be2bed88bf3ce62b2335f50474525`.

The snapshot preserves upstream-relative paths so future imports produce
recognizable Git diffs. Files below `upstream/linguist/` must not be edited
manually. Java implementations, generated runtime resources, manifests, and
local documentation belong outside the snapshot.

## Imported Paths

| Path | Purpose |
| --- | --- |
| `LICENSE` | License for imported upstream data. |
| `lib/linguist/languages.yml` | Language catalog definitions. |
| `lib/linguist/heuristics.yml` | Ordered ambiguity-resolution rules. |
| `lib/linguist/vendor.yml` | Vendored-path rules. |
| `lib/linguist/documentation.yml` | Documentation-path rules. |
| `lib/linguist/generic.yml` | Generic extensions excluded from classification. |
| `lib/linguist/popular.yml` | Popular-language metadata. |
| `grammars.yml` | Grammar metadata and provenance input only. |

`upstream/manifest.json` is the machine-readable allowlist and provenance record.
Its contract is defined by `schemas/upstream-manifest.schema.json`. Snapshot
synchronization must enforce the same rules.

## Preservation Rules

- Import only files tracked by the selected upstream commit.
- Preserve raw bytes, relative paths, filename case, executable modes, and
  symbolic-link targets.
- Never import untracked files or working-tree modifications.
- Keep production builds independent of this tree; normal compilation and tests
  consume committed Java sources and generated runtime resources.
- Do not initialize or copy grammar submodules. `grammars.yml` is metadata only.

## Deliberate Exclusions

- Ruby source, Ruby build metadata, Ruby executables, and Ruby test
  infrastructure.
- Ruby or native-Ruby oracle tooling; this project does not require Ruby.
- Flex specifications, generated Flex C sources, and native tokenizer wrappers.
  Tokenizer maintenance consults `tokenizer.l` directly from the pinned
  upstream revision without adding it to this snapshot.
- `bin/git-linguist` and Ruby Marshal cache compatibility, which are not supported.
- Grammar repositories and compiled grammar artifacts.
- Full sample, test, and fixture trees. Training samples are fetched only by `updateClassifierModel`; runtime data
  and small regression fixtures are committed separately.
- Repository administration, CI configuration, release tooling, editor
  configuration, unrelated documentation, and unrelated scripts.

Until `syncUpstream` exists, refresh the snapshot from Git objects rather than
copying a checkout's working tree. This prevents local or ignored files from
entering the snapshot.
