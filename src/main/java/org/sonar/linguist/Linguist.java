/*
 * Copyright (C) SonarSource Sàrl
 * For more information, see https://sonarsource.com/legal/
 * mailto:info AT sonarsource DOT com
 */
package org.sonar.linguist;

import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import javax.annotation.Nullable;

/**
 * Rules-based file language detection and committed Git repository statistics.
 * Instances may be reused across calls and threads.
 */
public final class Linguist {
  private static final List<DetectionStrategy> STRATEGY_ORDER = List.of(
    DetectionStrategy.MODELINE,
    DetectionStrategy.FILENAME,
    DetectionStrategy.SHEBANG,
    DetectionStrategy.EXTENSION,
    DetectionStrategy.XML,
    DetectionStrategy.MANPAGE,
    DetectionStrategy.HEURISTICS,
    DetectionStrategy.CLASSIFIER);

  private final LanguageCatalog catalog;
  private final PathRules pathRules;
  private final Classifier classifier;

  Linguist(LanguageCatalog catalog, PathRules pathRules, @Nullable Classifier classifier) {
    this.catalog = catalog;
    this.pathRules = pathRules;
    this.classifier = classifier;
  }

  private static final class Defaults {
    private static final LanguageCatalog CATALOG = LanguageCatalog.bundled();
    private static final PathRules PATH_RULES = new PathRules();
    private static final Classifier CLASSIFIER = BundledClassifier.get();
  }

  /** Creates a Linguist instance using the bundled upstream definitions. */
  public static Linguist create() {
    return new Linguist(Defaults.CATALOG, Defaults.PATH_RULES, Defaults.CLASSIFIER);
  }

  /**
   * Creates an instance extending the bundled definitions with custom language YAML.
   * Entries with matching {@code language_id} replace the entire bundled definition;
   * new IDs add languages. Existing filename, extension and alias tie rules still apply.
   * Groups must reference canonical names in the merged catalog.
   *
   * <p>The stream is read eagerly and remains owned by the caller, who must close it.
   * Empty YAML leaves the bundled catalog unchanged. Instances are isolated and reusable
   * across threads, just like those returned by {@link #create()}.
   *
   * @param customLanguages YAML in the bundled {@code languages.yml} format
   * @return an instance using the merged catalog and bundled path rules
   * @throws IllegalArgumentException if the YAML or merged definitions are invalid
   * @throws UncheckedIOException if reading the stream fails
   */
  public static Linguist create(InputStream customLanguages) {
    Objects.requireNonNull(customLanguages, "customLanguages");
    return new Linguist(new LanguageCatalog(customLanguages), Defaults.PATH_RULES, Defaults.CLASSIFIER);
  }

  /**
   * Returns an instance using the same language catalog, with adjusted vendored path rules. This allows customizing the
   * bundled {@code vendor.yml} without modifying it:
   *
   * <pre>{@code
   * remove:
   *   - (^|/)\.github/
   * add:
   *   - (^|/)my-vendor-dir/
   * }</pre>
   *
   * <p>{@code remove} entries are the exact text of bundled vendored rules, not paths: they disable these rules.
   * Removing a rule that does not exist fails, so that overrides do not silently become stale when the bundled rules
   * change. {@code add} entries are regular expressions in the {@code vendor.yml} format. Both keys are optional.
   *
   * <p>The stream is read eagerly and remains owned by the caller, who must close it. Empty YAML leaves the rules
   * unchanged. This instance is not modified.
   *
   * @param overrides YAML with optional {@code remove} and {@code add} lists
   * @return an instance using the adjusted vendored rules
   * @throws IllegalArgumentException if the YAML or the rules are invalid
   * @throws UncheckedIOException if reading the stream fails
   */
  public Linguist withVendoredOverrides(InputStream overrides) {
    Objects.requireNonNull(overrides, "overrides");
    return new Linguist(catalog, pathRules.withVendoredOverrides(overrides), classifier);
  }

  /**
   * Detects a standalone file without repository attribute overrides.
   *
   * <p>The relative path drives the filename, extension, generated, vendored and
   * documentation rules. Upstream patterns are anchored on the repository root, so
   * the path should be relative to it.
   *
   * <p>The content is opened once and read completely. All analysis stages share
   * the resulting byte snapshot; individual checks may inspect only a bounded
   * prefix where required.
   *
   * @param relativePath repository-relative path of the file
   * @param contentLoader opens the raw file content on demand
   * @return the language and path-based filtering flags
   * @throws UncheckedIOException if the content loader fails
   */
  public Detection detect(Path relativePath, ContentLoader contentLoader) {
    return detect(relativePath, contentLoader, DetectionOptions.defaults());
  }

  /**
   * Detects a standalone file without repository attribute overrides.
   *
   * @param relativePath repository-relative path of the file
   * @param contentLoader opens the raw file content on demand
   * @param options detection options
   * @return the language and path-based filtering flags
   * @throws UncheckedIOException if the content loader fails
   */
  public Detection detect(Path relativePath, ContentLoader contentLoader, DetectionOptions options) {
    Objects.requireNonNull(relativePath, "relativePath");
    Objects.requireNonNull(contentLoader, "contentLoader");
    Objects.requireNonNull(options, "options");
    return detect(FileContent.read(relativePath, contentLoader), options);
  }

  /**
   * Detects a standalone file using modeline, filename, shebang, extension,
   * XML, manpage, heuristics, and classifier strategies, in that order.
   * Exact extension case wins before a lowercase fallback. Unknown, binary,
   * and empty files have no language by default.
   *
   * @param filename filename or path; forward and backward slashes are accepted
   * @param content raw bytes; only the first 8192 bytes are checked for binary content
   * @return the language and path-based filtering flags
   */
  public Detection detect(String filename, byte[] content) {
    return detect(filename, content, DetectionOptions.defaults());
  }

  /**
   * Detects a standalone file using the ordered language detection strategies.
   *
   * @param filename filename or path; forward and backward slashes are accepted
   * @param content raw bytes; only the first 8192 bytes are checked for binary content
   * @param options detection options
   * @return the language and path-based filtering flags
   */
  public Detection detect(String filename, byte[] content, DetectionOptions options) {
    Objects.requireNonNull(filename, "filename");
    Objects.requireNonNull(content, "content");
    Objects.requireNonNull(options, "options");
    return detect(FileContent.copyOf(filename, content), options);
  }

  private Detection detect(FileContent content, DetectionOptions options) {
    boolean binary = BinaryDetector.isBinary(content);
    FileKindDetector.Result fileKinds = FileKindDetector.detect(content, binary);
    Optional<EncodingDetector.Result> encoding = EncodingDetector.detect(content, binary);
    LineMetrics.Result lineMetrics = LineMetrics.calculate(content, fileKinds.text(), encoding.orElse(null));
    boolean analyzable = !binary && (!fileKinds.empty() || options.allowEmpty());
    LanguageMatch match = detectLanguage(content, analyzable);
    return detection(content, match, encoding.orElse(null), binary, fileKinds, lineMetrics);
  }

  private LanguageMatch detectLanguage(FileContent content, boolean analyzable) {
    if (!analyzable) {
      return LanguageMatch.none();
    }
    List<Language> candidates = List.of();
    DetectionStrategy candidateStrategy = DetectionStrategy.NO_MATCH;
    Optional<Language> preferredLanguage = Optional.empty();
    for (DetectionStrategy strategy : STRATEGY_ORDER) {
      Optional<LanguageMatch> classifierMatch = classifierMatch(strategy, content, candidates);
      if (classifierMatch.isPresent()) {
        return classifierMatch.get();
      }
      List<Language> detected = detectCandidates(strategy, content, candidates);
      if (detected.size() == 1) {
        return LanguageMatch.of(detected, strategy);
      }
      if (detected.size() > 1) {
        boolean changed = !detected.equals(candidates);
        if (changed || strategy == DetectionStrategy.SHEBANG) {
          candidateStrategy = strategy;
          preferredLanguage = preferredLanguage(strategy, content.path(), candidates);
        }
        candidates = detected;
      }
    }
    if (candidates.isEmpty()) {
      return LanguageMatch.none();
    }
    Language language = preferredLanguage.orElseGet(candidates::getFirst);
    return LanguageMatch.of(language, candidateStrategy, candidates);
  }

  private Optional<LanguageMatch> classifierMatch(
    DetectionStrategy strategy,
    FileContent content,
    List<Language> candidates) {
    if (strategy != DetectionStrategy.CLASSIFIER || classifier == null || candidates.size() <= 1) {
      return Optional.empty();
    }
    Classifier.Result result = classifier.classify(content, candidates);
    if (result.scores().isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(
      LanguageMatch.of(result.candidates().getFirst(), strategy, result.candidates(), result.scores()));
  }

  private List<Language> detectCandidates(
    DetectionStrategy strategy,
    FileContent content,
    List<Language> candidates) {
    return switch (strategy) {
      case MODELINE -> ModelineDetector.detect(content, catalog).map(List::of).orElseGet(List::of);
      case FILENAME -> catalog.findByFilename(content.path());
      case SHEBANG -> ShebangDetector.detect(content, catalog, candidates);
      case EXTENSION -> ExtensionDetector.detect(content.path(), catalog, candidates);
      case XML -> XmlDetector.detect(content, catalog, candidates);
      case MANPAGE -> ManpageDetector.detect(content.path(), catalog, candidates);
      case HEURISTICS -> HeuristicDetector.detect(content, catalog);
      case CLASSIFIER -> List.of();
      case NO_MATCH -> throw new IllegalStateException("No-match is not a detection strategy");
    };
  }

  private Optional<Language> preferredLanguage(
    DetectionStrategy strategy,
    String path,
    List<Language> previousCandidates) {
    if (strategy == DetectionStrategy.EXTENSION && previousCandidates.isEmpty()) {
      return catalog.detect(path);
    }
    return Optional.empty();
  }

  private Detection detection(
    FileContent content,
    LanguageMatch match,
    @Nullable EncodingDetector.Result encoding,
    boolean binary,
    FileKindDetector.Result fileKinds,
    LineMetrics.Result lineMetrics) {
    Detection.Builder builder = Detection.builder(content.path())
      .language(match.language())
      .strategy(match.strategy())
      .candidates(match.candidates())
      .classifierScores(match.classifierScores())
      .byteSize(content.byteSize())
      .binary(binary)
      .empty(fileKinds.empty())
      .text(fileKinds.text())
      .fileKinds(fileKinds.image(), fileKinds.solid(), fileKinds.csv(), fileKinds.pdf(), fileKinds.lfsPointer())
      .lines(lineMetrics.lineCount(), lineMetrics.sourceLineCount())
      .generated(GeneratedDetector.detect(content))
      .vendored(pathRules.vendored(content.path()))
      .documentation(pathRules.documentation(content.path()));
    if (encoding != null) {
      builder.encoding(encoding.encoding(), encoding.rubyEncoding(), encoding.confidence(), encoding.type());
    }
    return builder.build();
  }

  private record LanguageMatch(
    @Nullable Language language,
    DetectionStrategy strategy,
    List<Language> candidates,
    Map<Language, Double> classifierScores) {
    static LanguageMatch none() {
      return new LanguageMatch(null, DetectionStrategy.NO_MATCH, List.of(), Map.of());
    }

    static LanguageMatch of(Language language, DetectionStrategy strategy) {
      return new LanguageMatch(language, strategy, List.of(language), Map.of());
    }

    static LanguageMatch of(List<Language> candidates, DetectionStrategy strategy) {
      return new LanguageMatch(candidates.getFirst(), strategy, candidates, Map.of());
    }

    static LanguageMatch of(Language language, DetectionStrategy strategy, List<Language> candidates) {
      return new LanguageMatch(language, strategy, candidates, Map.of());
    }

    static LanguageMatch of(
      Language language,
      DetectionStrategy strategy,
      List<Language> candidates,
      Map<Language, Double> classifierScores) {
      return new LanguageMatch(language, strategy, candidates, classifierScores);
    }
  }

  /**
   * Analyzes committed files at {@code HEAD}; an unborn repository returns an
   * empty result with an empty commit ID.
   *
   * @param repository path to a local working or bare Git repository
   * @return statistics for eligible committed files
   * @throws UncheckedIOException if repository access fails
   * @throws IllegalArgumentException if the path is not a repository
   */
  public RepositoryAnalysis analyzeRepository(Path repository) {
    return analyzeRepository(repository, "HEAD");
  }

  /**
   * Analyzes files at the selected commit, using only its committed attributes.
   * Symlinks and submodules are skipped. Eligible files contribute their full
   * byte sizes to upstream language groups. Unknown files never count.
   *
   * @param repository path to a local working or bare Git repository
   * @param revision commit, branch, or tag identifying the snapshot
   * @return statistics for eligible committed files
   * @throws UncheckedIOException if repository access fails
   * @throws IllegalArgumentException if the repository or revision is invalid
   */
  public RepositoryAnalysis analyzeRepository(Path repository, String revision) {
    Objects.requireNonNull(repository, "repository");
    Objects.requireNonNull(revision, "revision");
    if (revision.isBlank()) {
      throw new IllegalArgumentException("Revision must not be blank");
    }
    return new RepositoryAnalyzer(this, catalog).analyze(repository, revision);
  }

}
