/*
 * Copyright (C) SonarSource Sàrl
 * For more information, see https://sonarsource.com/legal/
 * mailto:info AT sonarsource DOT com
 */
package org.sonar.linguist;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;
import static org.assertj.core.api.Assertions.within;

class ClassifierTest {
  private static final Language ALPHA = new Language(10, "Alpha", LanguageType.PROGRAMMING);
  private static final Language BETA = new Language(20, "Beta", LanguageType.PROGRAMMING);
  private static final Language UNTRAINED = new Language(30, "Untrained", LanguageType.PROGRAMMING);

  @Test
  void evaluatesTfIcfCosineSimilarityAndOrdersPositiveScoresDescending() {
    Classifier classifier = classifier(
      sample(10, "alpha"),
      sample(10, "alpha"),
      sample(20, "beta"),
      sample(20, "beta"));

    Classifier.Result result = classifier.classify(
      "alpha alpha beta unknown".getBytes(StandardCharsets.UTF_8),
      List.of(BETA, UNTRAINED, ALPHA));

    double alphaWeight = 1.0 + Math.log(2.0);
    double norm = Math.sqrt(alphaWeight * alphaWeight + 1.0);
    assertThat(result.scores().keySet()).containsExactly(ALPHA, BETA);
    assertThat(result.scores().get(ALPHA)).isCloseTo(alphaWeight / norm, within(1.0e-12));
    assertThat(result.scores().get(BETA)).isCloseTo(1.0 / norm, within(1.0e-12));
  }

  @Test
  void evaluatesOnlyTheFirstFiftyKibibytes() {
    Classifier classifier = classifier(sample(10, "+"), sample(10, "+"));
    byte[] outsideLimit = new byte[50 * 1024 + 1];
    Arrays.fill(outsideLimit, (byte) ' ');
    outsideLimit[50 * 1024] = '+';
    byte[] insideLimit = outsideLimit.clone();
    insideLimit[50 * 1024 - 1] = '+';

    assertThat(classifier.classify(outsideLimit, List.of(ALPHA)).scores()).isEmpty();
    assertThat(classifier.classify(insideLimit, List.of(ALPHA)).scores())
      .containsExactly(entry(ALPHA, 1.0));
  }

  @Test
  void findsFilesystemNamedLanguageCentroidByStableLanguageId() {
    Language fstar = LanguageCatalog.bundled().byAlias("F*").orElseThrow();
    assertThat(fstar.filesystemName()).contains("Fstar");
    Classifier classifier = classifier(
      sample(fstar.languageId(), "fstartoken"),
      sample(fstar.languageId(), "fstartoken"));

    assertThat(classifier.classify("fstartoken".getBytes(StandardCharsets.UTF_8), List.of(fstar)).scores())
      .containsExactly(entry(fstar, 1.0));
  }

  @Test
  void preservesCandidateOrderForEqualAndUnavailableScores() {
    Classifier classifier = classifier(
      sample(10, "shared"),
      sample(10, "shared"),
      sample(20, "shared"),
      sample(20, "shared"),
      sample(40, "gamma"),
      sample(40, "gamma"));
    Language gamma = new Language(40, "Gamma", LanguageType.PROGRAMMING);

    Classifier.Result result = classifier.classify(
      "shared".getBytes(StandardCharsets.UTF_8),
      List.of(UNTRAINED, BETA, gamma, ALPHA));

    assertThat(result.scores().keySet()).containsExactly(BETA, ALPHA);
    assertThat(result.candidates()).containsExactly(BETA, ALPHA, UNTRAINED, gamma);
  }

  @Test
  void preservesAllCandidateOrderWhenNoScoreIsAvailable() {
    Classifier classifier = classifier(sample(10, "alpha"), sample(10, "alpha"));
    List<Language> candidates = List.of(UNTRAINED, ALPHA, BETA);

    Classifier.Result result = classifier.classify("unknown".getBytes(StandardCharsets.UTF_8), candidates);

    assertThat(result.scores()).isEmpty();
    assertThat(result.candidates()).containsExactlyElementsOf(candidates);
    assertThat(classifier.classify("alpha".getBytes(StandardCharsets.UTF_8), List.of()).candidates()).isEmpty();
  }

  private static Classifier classifier(Sample... samples) {
    ClassifierTrainer trainer = new ClassifierTrainer();
    for (Sample sample : samples) {
      trainer.addSample(sample.languageId(), List.of(sample.token()));
    }
    return new Classifier(trainer.build());
  }

  private static Sample sample(int languageId, String token) {
    return new Sample(languageId, token);
  }

  private record Sample(int languageId, String token) {
  }
}
