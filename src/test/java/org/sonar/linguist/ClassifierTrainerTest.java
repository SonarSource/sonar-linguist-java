/*
 * Copyright (C) SonarSource Sàrl
 * For more information, see https://sonarsource.com/legal/
 * mailto:info AT sonarsource DOT com
 */
package org.sonar.linguist;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;
import static org.assertj.core.api.Assertions.within;

class ClassifierTrainerTest {
  @Test
  void constructsTfIcfVocabularyAndNormalizedLanguageCentroids() {
    ClassifierTrainer trainer = new ClassifierTrainer();
    trainer.addSample(20, List.of("beta", "beta", "shared", "one-document-only", "one-document-only"));
    trainer.addSample(10, List.of("alpha", "alpha", "shared"));
    trainer.addSample(20, List.of("beta", "shared"));
    trainer.addSample(10, List.of("alpha", "shared"));

    ClassifierModel model = trainer.build();

    assertThat(model.vocabulary()).containsExactly(
      entry("alpha", 0),
      entry("beta", 1),
      entry("shared", 2));
    double languageSpecificIcf = 1.0 + Math.log(2.0);
    assertThat(model.inverseClassFrequency(0)).isCloseTo(languageSpecificIcf, within(1.0e-12));
    assertThat(model.inverseClassFrequency(1)).isCloseTo(languageSpecificIcf, within(1.0e-12));
    assertThat(model.inverseClassFrequency(2)).isEqualTo(1.0);

    assertThat(model.centroids()).containsOnlyKeys(10, 20);
    assertThat(model.centroids().get(10)).containsOnlyKeys(0, 2);
    assertThat(model.centroids().get(20)).containsOnlyKeys(1, 2);
    assertThat(l2Norm(model.centroids().get(10))).isCloseTo(1.0, within(1.0e-12));
    assertThat(l2Norm(model.centroids().get(20))).isCloseTo(1.0, within(1.0e-12));
    assertThat(model.centroids().get(10).get(0)).isCloseTo(model.centroids().get(20).get(1), within(1.0e-12));
    assertThat(model.centroids().get(10).get(2)).isCloseTo(model.centroids().get(20).get(2), within(1.0e-12));
  }

  @Test
  void tokenizesByteSamplesAndKeepsEmptyCentroids() {
    ClassifierTrainer trainer = new ClassifierTrainer();
    trainer.addSample(10, "alpha + alpha".getBytes(StandardCharsets.UTF_8));
    trainer.addSample(10, "alpha".getBytes(StandardCharsets.UTF_8));
    trainer.addSample(20, List.of("discarded"));

    ClassifierModel model = trainer.build();

    assertThat(model.vocabulary()).containsExactly(entry("alpha", 0));
    assertThat(model.centroids().get(10)).containsExactly(entry(0, 1.0));
    assertThat(model.centroids().get(20)).isEmpty();
  }

  private static double l2Norm(Map<Integer, Double> vector) {
    return Math.sqrt(vector.values().stream().mapToDouble(value -> value * value).sum());
  }
}
