/*
 * Copyright (C) SonarSource Sàrl
 * For more information, see https://sonarsource.com/legal/
 * mailto:info AT sonarsource DOT com
 */
package org.sonar.linguist;

import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import javax.annotation.Nullable;

/** Immutable result of standalone-file analysis. */
public final class Detection {
  private final State state;

  /**
   * Creates a result containing the fields exposed by the original API.
   */
  public Detection(
    @Nullable Language language,
    boolean binary,
    boolean generated,
    boolean vendored,
    boolean documentation) {
    this(builder("")
      .language(language)
      .binary(binary)
      .text(!binary)
      .generated(generated)
      .vendored(vendored)
      .documentation(documentation));
  }

  private Detection(Builder builder) {
    state = new State(
      builder.language,
      builder.strategy,
      builder.candidates,
      builder.classifierScores,
      builder.path,
      builder.extension,
      builder.byteSize,
      builder.encoding.encoding(),
      builder.encoding.rubyEncoding(),
      builder.encoding.confidence(),
      builder.encoding.type(),
      builder.binary,
      builder.empty,
      builder.text,
      builder.fileKinds.image(),
      builder.fileKinds.solid(),
      builder.fileKinds.csv(),
      builder.fileKinds.pdf(),
      builder.fileKinds.lfsPointer(),
      builder.generated,
      builder.vendored,
      builder.documentation,
      builder.lineCount,
      builder.sourceLineCount,
      builder.textMateScope);
  }

  public Optional<Language> language() {
    return state.language();
  }

  public DetectionStrategy strategy() {
    return state.strategy();
  }

  /** Returns final candidates in upstream strategy order. */
  public List<Language> candidates() {
    return state.candidates();
  }

  /** Returns positive classifier scores in descending order, with candidate order breaking ties. */
  public Map<Language, Double> classifierScores() {
    return state.classifierScores();
  }

  public String path() {
    return state.path();
  }

  public String extension() {
    return state.extension();
  }

  public long byteSize() {
    return state.byteSize();
  }

  public Optional<String> encoding() {
    return state.encoding();
  }

  public Optional<String> rubyEncoding() {
    return state.rubyEncoding();
  }

  public Optional<Integer> encodingConfidence() {
    return state.encodingConfidence();
  }

  public Optional<String> encodingType() {
    return state.encodingType();
  }

  public boolean binary() {
    return state.binary();
  }

  public boolean empty() {
    return state.empty();
  }

  public boolean text() {
    return state.text();
  }

  public boolean image() {
    return state.image();
  }

  /** Returns whether the file is a supported STL 3D model. */
  public boolean solid() {
    return state.solid();
  }

  public boolean csv() {
    return state.csv();
  }

  public boolean pdf() {
    return state.pdf();
  }

  public boolean lfsPointer() {
    return state.lfsPointer();
  }

  public boolean generated() {
    return state.generated();
  }

  public boolean vendored() {
    return state.vendored();
  }

  public boolean documentation() {
    return state.documentation();
  }

  public int lineCount() {
    return state.lineCount();
  }

  public int sourceLineCount() {
    return state.sourceLineCount();
  }

  public Optional<String> textMateScope() {
    return state.textMateScope();
  }

  @Override
  public boolean equals(Object other) {
    return this == other || (other instanceof Detection detection && state.equals(detection.state));
  }

  @Override
  public int hashCode() {
    return state.hashCode();
  }

  @Override
  public String toString() {
    return state.toString();
  }

  static Builder builder(String path) {
    return new Builder(path);
  }

  static final class Builder {
    private Optional<Language> language = Optional.empty();
    private DetectionStrategy strategy = DetectionStrategy.NO_MATCH;
    private List<Language> candidates = List.of();
    private Map<Language, Double> classifierScores = Map.of();
    private final String path;
    private String extension;
    private long byteSize;
    private EncodingMetadata encoding = EncodingMetadata.empty();
    private boolean binary;
    private boolean empty;
    private boolean text = true;
    private FileKinds fileKinds = FileKinds.empty();
    private boolean generated;
    private boolean vendored;
    private boolean documentation;
    private int lineCount;
    private int sourceLineCount;
    private Optional<String> textMateScope = Optional.empty();

    private Builder(String path) {
      this.path = Objects.requireNonNull(path, "path");
      this.extension = extension(path);
    }

    Builder language(@Nullable Language language) {
      this.language = Optional.ofNullable(language);
      this.textMateScope = this.language.map(Language::tmScope);
      return this;
    }

    Builder strategy(DetectionStrategy strategy) {
      this.strategy = Objects.requireNonNull(strategy, "strategy");
      return this;
    }

    Builder candidates(List<Language> candidates) {
      this.candidates = Objects.requireNonNull(candidates, "candidates");
      return this;
    }

    Builder classifierScores(Map<Language, Double> classifierScores) {
      this.classifierScores = Objects.requireNonNull(classifierScores, "classifierScores");
      return this;
    }

    Builder byteSize(long byteSize) {
      this.byteSize = byteSize;
      return this;
    }

    Builder encoding(String encoding, String rubyEncoding, int confidence, String type) {
      this.encoding = new EncodingMetadata(
        Optional.of(Objects.requireNonNull(encoding, "encoding")),
        Optional.ofNullable(rubyEncoding),
        Optional.of(confidence),
        Optional.of(Objects.requireNonNull(type, "type")));
      return this;
    }

    Builder binary(boolean binary) {
      this.binary = binary;
      return this;
    }

    Builder empty(boolean empty) {
      this.empty = empty;
      return this;
    }

    Builder text(boolean text) {
      this.text = text;
      return this;
    }

    Builder fileKinds(boolean image, boolean solid, boolean csv, boolean pdf, boolean lfsPointer) {
      this.fileKinds = new FileKinds(image, solid, csv, pdf, lfsPointer);
      return this;
    }

    Builder generated(boolean generated) {
      this.generated = generated;
      return this;
    }

    Builder vendored(boolean vendored) {
      this.vendored = vendored;
      return this;
    }

    Builder documentation(boolean documentation) {
      this.documentation = documentation;
      return this;
    }

    Builder lines(int lineCount, int sourceLineCount) {
      this.lineCount = lineCount;
      this.sourceLineCount = sourceLineCount;
      return this;
    }

    Detection build() {
      return new Detection(this);
    }

    private static String extension(String path) {
      String filename = path.substring(path.lastIndexOf('/') + 1);
      int dot = filename.lastIndexOf('.');
      return dot <= 0 || dot == filename.length() - 1 ? "" : filename.substring(dot).toLowerCase(Locale.ROOT);
    }
  }

  private record EncodingMetadata(
    Optional<String> encoding,
    Optional<String> rubyEncoding,
    Optional<Integer> confidence,
    Optional<String> type) {
    private static EncodingMetadata empty() {
      return new EncodingMetadata(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
    }
  }

  private record FileKinds(boolean image, boolean solid, boolean csv, boolean pdf, boolean lfsPointer) {
    private static FileKinds empty() {
      return new FileKinds(false, false, false, false, false);
    }
  }

  private record State(
    Optional<Language> language,
    DetectionStrategy strategy,
    List<Language> candidates,
    Map<Language, Double> classifierScores,
    String path,
    String extension,
    long byteSize,
    Optional<String> encoding,
    Optional<String> rubyEncoding,
    Optional<Integer> encodingConfidence,
    Optional<String> encodingType,
    boolean binary,
    boolean empty,
    boolean text,
    boolean image,
    boolean solid,
    boolean csv,
    boolean pdf,
    boolean lfsPointer,
    boolean generated,
    boolean vendored,
    boolean documentation,
    int lineCount,
    int sourceLineCount,
    Optional<String> textMateScope) {
    private State {
      Objects.requireNonNull(language, "language");
      Objects.requireNonNull(strategy, "strategy");
      candidates = immutableCandidates(candidates);
      classifierScores = immutableClassifierScores(classifierScores, candidates);
      Objects.requireNonNull(path, "path");
      Objects.requireNonNull(extension, "extension");
      Objects.requireNonNull(encoding, "encoding");
      Objects.requireNonNull(rubyEncoding, "rubyEncoding");
      Objects.requireNonNull(encodingConfidence, "encodingConfidence");
      Objects.requireNonNull(encodingType, "encodingType");
      Objects.requireNonNull(textMateScope, "textMateScope");
    }

    private static List<Language> immutableCandidates(List<Language> candidates) {
      List<Language> copy = List.copyOf(candidates);
      if (new HashSet<>(copy).size() != copy.size()) {
        throw new IllegalArgumentException("Candidates must not contain duplicates");
      }
      return copy;
    }

    private static Map<Language, Double> immutableClassifierScores(
      Map<Language, Double> classifierScores,
      List<Language> candidates) {
      Objects.requireNonNull(classifierScores, "classifierScores");
      Map<Language, Integer> candidateOrder = new LinkedHashMap<>();
      for (int index = 0; index < candidates.size(); index++) {
        candidateOrder.put(candidates.get(index), index);
      }
      for (var entry : classifierScores.entrySet()) {
        Language language = Objects.requireNonNull(entry.getKey(), "classifier score language");
        Double score = Objects.requireNonNull(entry.getValue(), "classifier score");
        if (!candidateOrder.containsKey(language)) {
          throw new IllegalArgumentException("Classifier score language must be a candidate: " + language.name());
        }
        if (!Double.isFinite(score) || score <= 0.0) {
          throw new IllegalArgumentException("Classifier scores must be finite and positive");
        }
      }
      LinkedHashMap<Language, Double> ordered = new LinkedHashMap<>();
      classifierScores.entrySet().stream()
        .sorted(Comparator.<Map.Entry<Language, Double>>comparingDouble(Map.Entry::getValue)
          .reversed()
          .thenComparingInt(entry -> candidateOrder.get(entry.getKey())))
        .forEach(entry -> ordered.put(entry.getKey(), entry.getValue()));
      return Collections.unmodifiableMap(ordered);
    }
  }

}
