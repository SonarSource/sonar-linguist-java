/*
 * Copyright (C) SonarSource Sàrl
 * For more information, see https://sonarsource.com/legal/
 * mailto:info AT sonarsource DOT com
 */
package org.sonar.linguist;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LinguistClassifierTest {
  private final LanguageCatalog catalog = LanguageCatalog.bundled();
  private final Language objectiveC = catalog.byAlias("Objective-C").orElseThrow();

  @Test
  void exposesClassifierWinnerScoresAndRankedCandidates() {
    Linguist linguist = linguistWithClassifier();

    Detection detection = linguist.detect("source.m", bytes("objcmarker"));

    assertThat(detection.language()).contains(objectiveC);
    assertThat(detection.strategy()).isEqualTo(DetectionStrategy.CLASSIFIER);
    assertThat(detection.candidates()).extracting(Language::name).containsExactly(
      "Objective-C", "Limbo", "M", "MATLAB", "MUF", "Mercury", "Wolfram Language");
    assertThat(detection.classifierScores()).containsExactlyEntriesOf(Map.of(objectiveC, 1.0));
  }

  @Test
  void retainsPreviousWinnerAndStrategyWhenClassifierHasNoScore() {
    Linguist linguist = linguistWithClassifier();

    Detection detection = linguist.detect("source.m", bytes("unknown"));

    assertThat(detection.language()).contains(catalog.detect("source.m").orElseThrow());
    assertThat(detection.strategy()).isEqualTo(DetectionStrategy.EXTENSION);
    assertThat(detection.candidates()).extracting(Language::name)
      .containsExactly("Limbo", "M", "MATLAB", "MUF", "Mercury", "Objective-C", "Wolfram Language");
    assertThat(detection.classifierScores()).isEmpty();
  }

  @Test
  void defaultLinguistUsesBundledClassifierModel() {
    Detection detection = Linguist.create().detect("matrix.m", detectionContent());

    assertThat(detection.language()).map(Language::name).contains("MATLAB");
    assertThat(detection.strategy()).isEqualTo(DetectionStrategy.CLASSIFIER);
    assertThat(detection.classifierScores()).isNotEmpty();

    Linguist overridden = Linguist.create().withVendoredOverrides(new ByteArrayInputStream(new byte[0]));
    assertThat(overridden.detect("matrix.m", detectionContent()))
      .extracting(Detection::language, Detection::strategy, Detection::classifierScores)
      .containsExactly(detection.language(), detection.strategy(), detection.classifierScores());
  }

  private static byte[] detectionContent() {
    return bytes("""
      x = linspace(0, 100, 101);
      y = zeros(101, 101);
      for i = 1:101
        y(i, i) = x(i);
      end
      figure
      pcolor(x, y)
      shading flat
      """);
  }

  private Linguist linguistWithClassifier() {
    ClassifierTrainer trainer = new ClassifierTrainer();
    trainer.addSample(objectiveC.languageId(), List.of("objcmarker"));
    trainer.addSample(objectiveC.languageId(), List.of("objcmarker"));
    return new Linguist(catalog, new PathRules(), new Classifier(trainer.build()));
  }

  private static byte[] bytes(String content) {
    return content.getBytes(StandardCharsets.UTF_8);
  }
}
