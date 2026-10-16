# Linguist Java

A Java 21 port of the retained GitHub Linguist runtime: standalone-file language
detection, file metadata, and committed Git repository language statistics.

Detection follows the upstream strategy order: modeline, filename, shebang,
extension, XML, manpage, heuristics, and classifier. Runtime catalogs, path
rules, heuristic rules, and the trained classifier model are bundled in the JAR.

## Build

Build with the included Gradle wrapper and a JDK 21 installation:

```sh
./gradlew build
```

The build resolves dependencies from Maven Central and requires no SonarSource
credentials, scanner checkout, Ruby installation, or native compiler. Runtime
dependencies are ICU4J, JGit, and SnakeYAML.

The Maven coordinates are `org.sonarsource.linguist:sonar-linguist-java`.
The initial development version is `0.1.0-SNAPSHOT`; it has not been released.
To install the library, sources, Javadoc, and POM in your local Maven repository:

```sh
./gradlew publishToMavenLocal
```

With `mavenLocal()` enabled in the consuming build:

```kotlin
dependencies {
  implementation("org.sonarsource.linguist:sonar-linguist-java:0.1.0-SNAPSHOT")
}
```

`./gradlew installDist` builds a CLI distribution with runtime dependencies in
`build/install/sonar-linguist-java`. The library JAR itself is not a fat JAR.

## Standalone-file CLI

The application analyzes only the explicitly listed files and emits one compact
JSON object per file, separated by `\n` on every platform:

```sh
./gradlew run --args='src/Main.java scripts/build'
```

Each JSON line contains the selected language, winning strategy, final
candidates, classifier scores, and all file facts exposed by `Detection`.
Results preserve argument order. The command does not traverse directories,
open a Git repository, or apply `.gitattributes`; repository analysis is
available only through the Java API.

Empty files have no language by default. Use `--allow-empty` to run path-based
detection strategies for them. Use `--` before filenames beginning with `-`:

```sh
./gradlew run --args='--allow-empty -- -generated.rb'
```

Invalid arguments exit with status 2. A file read or analysis failure exits with
status 1 and produces no partial stdout output.

## Usage

```java
import org.sonar.linguist.Detection;
import org.sonar.linguist.DetectionOptions;
import org.sonar.linguist.Linguist;
import org.sonar.linguist.RepositoryAnalysis;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;

Linguist linguist = Linguist.create();

Path root = Path.of("/projects/my-app");
Path file = Path.of("src/Main.java");
Detection fromPath = linguist.detect(file, () -> Files.newInputStream(root.resolve(file)));
Detection fromBytes = linguist.detect(
    "src/Main.java",
    "class Main {}".getBytes(StandardCharsets.UTF_8));
Detection emptyByName = linguist.detect(
    "src/Main.java",
    new byte[0],
    new DetectionOptions(true));
fromPath.language().ifPresent(language ->
    System.out.printf("%s (id %d)%n", language.name(), language.languageId()));

RepositoryAnalysis head =
    linguist.analyzeRepository(Path.of("/projects/my-app"));
RepositoryAnalysis revision =
    linguist.analyzeRepository(Path.of("/projects/my-app"), "v1.0.0");

head.languages().forEach((language, stats) ->
    System.out.printf("%s: %.2f%%%n", language.name(), stats.percentage()));
```

`detect(Path, ContentLoader)` takes a repository-relative path, opens the content
once, reads it completely, and closes the stream. All analysis stages share that
immutable byte snapshot, although individual checks may inspect only a bounded
prefix. The string/byte-array overload makes a defensive copy of the supplied
bytes. `Language.languageId()` is the upstream `language_id`, stable across
language renames.

`DetectionOptions.defaults()` rejects empty files before language strategies
run. `new DetectionOptions(true)` allows filename, extension, and other
applicable strategies to identify an empty file. Binary files are never sent to
language detection.

`Detection` exposes:

- The language, winning `DetectionStrategy`, final candidates, and ordered
  positive classifier scores.
- Path, longest extension, full byte size, detected encoding metadata, and
  bundled TextMate scope.
- Binary, empty, text, image, STL solid, CSV, PDF, and Git LFS pointer facts.
- Generated, vendored, and documentation flags, plus line and source-line
  counts.

`Language` exposes the bundled catalog metadata: ID, canonical and escaped
names, type, aliases, extensions, filenames, interpreters, group, editor modes,
color, wrapping, and popularity.

Instances can be shared across threads. Repository result maps are immutable.
File/repository I/O failures use `UncheckedIOException`; invalid repository paths
or revisions use `IllegalArgumentException`.

## Custom language definitions

Supply custom YAML when creating an instance to extend the bundled catalog:

```java
try (var input = Files.newInputStream(Path.of("custom-languages.yml"))) {
  Linguist custom = Linguist.create(input);
  Detection detected = custom.detect("src/file.example", new byte[0]);
}
```

The stream is read eagerly; the caller owns and closes it. The instance can be
reused after closing the stream and shared across threads. Custom catalogs are
isolated from other instances. `Linguist.create()` continues to use bundled data.

Use the same language-name-keyed YAML format as the bundled `languages.yml`:

```yaml
Example:
  language_id: -1
  type: programming
  extensions: [.example]
  filenames: [Examplefile]
  aliases: [ex]
  interpreters: [example]
  group: Java
```

A new `language_id` adds a language. An existing ID replaces the **entire**
built-in definition, including its name. Omitted extensions, filenames, aliases,
and group are removed, rather than inherited. Names and IDs must be unique in
the merged catalog; custom entries require a nonblank name, a 32-bit integer ID,
and a type (`programming`, `markup`, `data`, or `prose`). Use IDs that do not
collide with bundled definitions when adding languages.

Groups refer to final canonical names. Renaming a group requires also replacing
languages that reference its old name. Existing filename, extension, and alias
tie rules still apply; custom definitions have no special priority over other
IDs. Generated, vendored, and documentation path rules remain bundled; see
[Vendored rule overrides](#vendored-rule-overrides) to adjust vendored rules.

Replacing a trained bundled language keeps its classifier centroid through the
stable language ID, including when the language is renamed. Newly added IDs are
untrained: they remain candidates but receive no classifier score. If no
candidate can be scored, detection keeps the earlier strategy and candidate
order instead of inventing a score.

Empty YAML adds nothing. Invalid YAML or definitions throw
`IllegalArgumentException`; stream read failures throw `UncheckedIOException`.
Custom YAML is supplied explicitly; repository files are not discovered automatically.

## Vendored rule overrides

Adjust the bundled `vendor.yml` rules without modifying them:

```java
try (var input = Files.newInputStream(Path.of("vendor-overrides.yml"))) {
  Linguist custom = Linguist.create().withVendoredOverrides(input);
}
```

```yaml
remove:
  - (^|/)\.github/
add:
  - (^|/)third-party-code/
```

`remove` entries are the exact text of bundled vendored rules, not paths: they
disable these rules, while paths still matching other rules stay vendored (for
example `node_modules/lib/.github/`). Removing a rule that is not bundled throws
`IllegalArgumentException`, so that overrides don't silently become stale when
upstream rules change. `add` entries are regular expressions in the
`vendor.yml` format. Both keys are optional, and empty YAML changes nothing.
The original instance is not modified, and the language catalog is kept.

## Detection rules

- Run modeline, exact filename, shebang, longest extension, XML, manpage,
  heuristic, and classifier strategies in that order. A unique match terminates
  detection; ambiguous candidates flow into later strategies where applicable.
- For example, `Dockerfile` is recognized by filename and `.gradle.kts` takes
  precedence over `.kts`.
- Prefer exact extension case before a lowercase fallback. Unlisted uppercase
  extensions fall back to lowercase: `.JAVA` becomes `.java`, and `.C` becomes `.c`.
- If ambiguity remains unscored, use the catalog's preferred extension language
  or the first retained candidate. `candidates()` and `strategy()` expose this
  fallback rather than hiding it. Preferred extension fallbacks are SQL for
  `.sql`, C for `.h`, Rust for `.rs`, and HTML for `.html`; other extension
  ties use alphabetical language-name order.
- Unknown or binary files return an empty `Detection.language()`.
- Generated, vendored, and documentation flags are independent of the language.
  Forward and backward slashes are accepted in detection paths. Use repository-
  relative paths when supplying content to preserve root-relative path rules.
- Binary detection samples the first 8192 bytes, looking for NULs or a high
  proportion of control bytes. UTF-16/32 BOMs are treated as text. This is a
  lightweight approximation; it does not fully validate or identify encodings.

Vendored and documentation detection use the bundled upstream path patterns.
Generated detection ports the retained upstream filename, extension, path, and
content rules. It is heuristic: handwritten files can match generated markers,
and generators unknown to the pinned upstream version can be missed.

## Repository statistics

- Analyze committed contents at `HEAD` or a selected commit, branch, or tag.
  Working and bare repositories are supported. No checkout or index mutation
  is performed; untracked files and working tree edits are ignored.
- Skip symlinks and submodules. Exclude binary, unknown, generated, vendored,
  and documentation files. Programming and markup categories count by default;
  data and prose categories do not.
- Read committed root and nested `.gitattributes` using JGit's matching and
  precedence. Working tree, global, and `.git/info/attributes` rules are ignored.
- Support `linguist-language` (canonical names or aliases), `linguist-generated`,
  `linguist-vendored`, `linguist-documentation`, and `linguist-detectable`.
  Bare flags and `true` enable a boolean; `-flag` and `flag=false` disable it;
  `!flag` restores the default. Unknown language overrides exclude the file.
  Binary files remain excluded regardless of overrides.
- Exclude a detected Git LFS pointer only when committed attributes resolve
  `filter=lfs` for that path. An untracked pointer or a committed `-filter`
  override remains analyzable.
- Aggregate eligible files by upstream language group. Each file contributes its
  entire blob size; detection reads at most 128 KiB and does not truncate byte
  totals.
  `includedFiles()` maps repository-relative paths to those groups.
- Percentages are `group bytes / total included bytes * 100`, on a 0–100 scale.
  Zero-byte totals yield zero percentages. An unborn repository at `HEAD`
  returns an empty commit ID and empty statistics.
- Each call performs a full scan. There is no incremental cache or language
  breakdown within a mixed-language file.
- Return empty statistics for repository trees containing 100,000 or more
  blob entries, before loading file contents.

Generic SQL is a data language and excluded by default. To include it:

```gitattributes
*.sql linguist-detectable=true
```

To correct simple detection or path exclusions:

```gitattributes
*.h linguist-language=cpp
src/generated-looking.java linguist-generated=false
node_modules/my-code/** linguist-vendored=false
```

## Classifier model workflow

The runtime model and canonical metadata are committed as
`src/main/resources/org/sonar/linguist/classifier-model.bin` and
`classifier-model.json`. Normal `build`, `test`, and `check` execution does not
download training inputs.

After changing the tokenizer, pinned upstream commit, or training behavior,
regenerate both artifacts from the repository root:

```sh
./gradlew updateClassifierModel
```

This networked maintenance task downloads the GitHub archive for the exact
commit in `upstream/manifest.json` into `build/classifier-model`, validates every
archive path, extracts only `samples/`, trains twice, verifies byte-for-byte
reproducibility, checks ambiguous sample classifications, and atomically
publishes the first result. Training and scoring use `StrictMath`, so the model
is bit-identical across platforms and JDK builds. Downloaded archives and extracted samples are
temporary build data and are removed before the task exits.

The metadata records the upstream provenance, sample digest and count, schema
version, model hash, and tokenizer-source hash. Verify committed artifacts
offline with:

```sh
./gradlew verifyClassifierModel --offline
```

`check` depends on this verification task, so a tokenizer edit without refreshed
metadata fails the regular module build.

## Handwritten tokenizer maintenance

`DirectTokenizerLexer.java` is a handwritten Java translation of
`ext/linguist/tokenizer.l` from
`https://github.com/github-linguist/linguist.git`. The authoritative upstream
revision is the commit in `upstream/manifest.json`; the current upstream version
is recorded there as well. The Flex specification is reference source, not a
runtime resource, and is intentionally not copied into the maintained snapshot.

The Java implementation must preserve these observable Flex semantics:

- Treat input as bytes, using a one-to-one ISO-8859-1 mapping rather than
  decoding source text as UTF-8.
- Inspect at most the first 100,000 bytes and truncate every emitted dynamic or
  shebang payload to 16 bytes.
- Select the longest matching rule and use upstream rule order to break equal-
  length ties.
- Preserve beginning-of-line rules, byte-oriented line terminators, shebang
  extraction, normalized comment tokens, and the exact order of emitted tokens.
- Preserve every exclusive comment state and its closing delimiter, while
  skipping strings, numeric literals, comment bodies, and unmatched bytes as
  defined by the upstream rules.
- Keep `Tokenizer.tokenize(byte[])` deterministic because classifier training
  and runtime classification must produce the same token stream.

To port a future upstream `tokenizer.l` change manually:

1. Read the old and new specifications directly from the corresponding upstream
   Git commits, for example with
   `git show <commit>:ext/linguist/tokenizer.l`. Diff those two committed files;
   do not use an upstream working-tree copy as the source of truth.
2. Translate changes to macros, ordered rules, start conditions, and state exits
   into `DirectTokenizerLexer.java`. Keep unchanged rules in their existing
   relative order so longest-match ties remain stable.
3. Add focused cases to `DirectTokenizerLexerTest` for every changed rule and
   its boundary interactions. Update `TokenizerTest` when truncation or the
   public token sequence is affected.
4. Run the focused tokenizer tests before regenerating the classifier:

   ```sh
   ./gradlew test \
     --tests 'org.sonar.linguist.DirectTokenizerLexerTest' \
     --tests 'org.sonar.linguist.TokenizerTest'
   ```

5. Run `./gradlew updateClassifierModel`. Tokenizer source
   changes invalidate the metadata hash, and token changes can alter every
   trained centroid.
6. Review both committed model artifacts, then run
   `./gradlew check --offline` to verify the model metadata
   and all module tests without downloading upstream inputs.

## Upstream data

Language definitions and vendor/documentation rules are pinned to the commit in
[THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md). The upstream MIT license is in
[LICENSE-UPSTREAM](LICENSE-UPSTREAM); both are included in the library JAR.
These rules provide broad coverage, but this implementation does not claim exact
GitHub Linguist compatibility.

## Differences from GitHub Linguist

The bundled upstream data is pinned to Linguist 9.7.0, commit
`73ee9a3b277f398c37ca4ab0dea0193849de6460`. This is an independent Java
implementation, not a drop-in replacement for the Ruby API. Compatibility is
covered by Java regression tests; exact parity is not guaranteed.

- Detection exposes immutable results, strategy traces, candidates, and scores.
  Standalone detection accepts bytes or a content loader and does not consult
  `.gitattributes`. Empty files can optionally be identified by path.
- Custom language catalogs and vendored-rule overrides are explicit, per-instance
  YAML inputs. No scanner properties, analyzer language keys, project configuration,
  or Sonar-specific exclusion rules are bundled. The `org.sonar.linguist` package
  identifies the publisher; using the API requires no Sonar products.
- Binary detection uses a bounded byte heuristic and BOM recognition. Encoding
  metadata uses ICU4J. Ruby regular expressions are translated into bounded Java
  matchers, so edge cases can differ.
- The tokenizer is handwritten Java. The deterministic Java classifier model is
  trained from pinned upstream samples and shipped with the library; Ruby/native
  tooling and Ruby Marshal models are not required or supported.
- Repository analysis uses JGit and committed snapshots only. It performs a full
  scan, has no incremental Ruby cache, skips symlinks and submodules, and applies
  committed `.gitattributes` only. Detection reads at most 128 KiB per blob while
  statistics count its full size. Trees with 100,000 or more blobs return empty statistics.
- The CLI emits JSON Lines for explicitly listed files. Repository statistics
  are available through the Java API; the Ruby `git-linguist` CLI is not reproduced.

The following upstream features are deliberately excluded.

MiniMime-backed MIME analysis is not ported. The library does not derive MIME
types from filenames or expose Linguist's MIME-derived binary hint, content
type, or content disposition. Binary detection remains based on bounded file
contents and encoding markers such as UTF-16/32 byte-order marks.

GitHub display and syntax-highlighting policy is not ported. The API does not
expose Linguist's `large`, `viewable`, `safe_to_colorize`, or
`high_ratio_of_long_lines` facts. These values do not participate in language
detection; line and source-line counts remain available independently.


## License

Java code is distributed under the [GNU LGPL v3](LICENSE). Imported GitHub
Linguist data is distributed under the [MIT license](LICENSE-UPSTREAM).
[THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md) records its provenance.
