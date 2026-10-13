/*
 * Copyright (C) SonarSource Sàrl
 * For more information, see https://sonarsource.com/legal/
 * mailto:info AT sonarsource DOT com
 */
package org.sonar.linguist;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DetectionTest {
  private static final Language JAVA = new Language(181, "Java", LanguageType.PROGRAMMING);
  private static final Language KOTLIN = new Language(189, "Kotlin", LanguageType.PROGRAMMING);
  private static final Language PYTHON = new Language(303, "Python", LanguageType.PROGRAMMING);

  @Test
  void exposesCompleteStandaloneAnalysisResult() {
    Detection detection = Detection.builder("src/template.html.erb")
      .language(JAVA)
      .strategy(DetectionStrategy.CLASSIFIER)
      .candidates(List.of(JAVA, KOTLIN))
      .classifierScores(Map.of(JAVA, 0.75, KOTLIN, 0.25))
      .byteSize(123)
      .encoding("UTF-8", "UTF-8", 100, "text")
      .binary(false)
      .empty(false)
      .text(true)
      .fileKinds(true, true, true, true, true)
      .generated(true)
      .vendored(true)
      .documentation(true)
      .lines(12, 8)
      .build();

    assertThat(detection.language()).contains(JAVA);
    assertThat(detection.strategy()).isEqualTo(DetectionStrategy.CLASSIFIER);
    assertThat(detection.candidates()).containsExactly(JAVA, KOTLIN);
    assertThat(detection.classifierScores()).containsExactlyEntriesOf(orderedScores(JAVA, 0.75, KOTLIN, 0.25));
    assertThat(detection.path()).isEqualTo("src/template.html.erb");
    assertThat(detection.extension()).isEqualTo(".erb");
    assertThat(detection.byteSize()).isEqualTo(123);
    assertThat(detection.encoding()).contains("UTF-8");
    assertThat(detection.rubyEncoding()).contains("UTF-8");
    assertThat(detection.encodingConfidence()).contains(100);
    assertThat(detection.encodingType()).contains("text");
    assertThat(detection.binary()).isFalse();
    assertThat(detection.empty()).isFalse();
    assertThat(detection.text()).isTrue();
    assertThat(detection.image()).isTrue();
    assertThat(detection.solid()).isTrue();
    assertThat(detection.csv()).isTrue();
    assertThat(detection.pdf()).isTrue();
    assertThat(detection.lfsPointer()).isTrue();
    assertThat(detection.generated()).isTrue();
    assertThat(detection.vendored()).isTrue();
    assertThat(detection.documentation()).isTrue();
    assertThat(detection.lineCount()).isEqualTo(12);
    assertThat(detection.sourceLineCount()).isEqualTo(8);
    assertThat(detection.textMateScope()).contains("none");
  }

  @Test
  void defensivelyCopiesCollections() {
    List<Language> candidates = new ArrayList<>(List.of(JAVA));
    Map<Language, Double> scores = new LinkedHashMap<>(Map.of(JAVA, 1.0));
    Detection detection = Detection.builder("Main.java")
      .candidates(candidates)
      .classifierScores(scores)
      .build();

    candidates.add(KOTLIN);
    scores.put(KOTLIN, 0.5);

    assertThat(detection.candidates()).containsExactly(JAVA);
    assertThat(detection.classifierScores()).containsOnlyKeys(JAVA);
    List<Language> detectedCandidates = detection.candidates();
    Map<Language, Double> classifierScores = detection.classifierScores();
    assertThatThrownBy(() -> detectedCandidates.add(KOTLIN))
      .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> classifierScores.put(KOTLIN, 0.5))
      .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void ordersClassifierScoresDescendingAndUsesCandidateOrderForTies() {
    Map<Language, Double> scores = new LinkedHashMap<>();
    scores.put(JAVA, 0.25);
    scores.put(KOTLIN, 0.75);
    scores.put(PYTHON, 0.75);

    Detection detection = Detection.builder("Main.java")
      .candidates(List.of(JAVA, PYTHON, KOTLIN))
      .classifierScores(scores)
      .build();

    assertThat(detection.candidates()).containsExactly(JAVA, PYTHON, KOTLIN);
    assertThat(detection.classifierScores()).containsExactlyEntriesOf(
      orderedScores(PYTHON, 0.75, KOTLIN, 0.75, JAVA, 0.25));
  }

  @Test
  void rejectsInvalidCandidateAndClassifierScoreCollections() {
    Detection.Builder duplicateCandidates = Detection.builder("Main.java")
      .candidates(List.of(JAVA, JAVA));
    Detection.Builder missingCandidateScore = Detection.builder("Main.java")
      .candidates(List.of(JAVA))
      .classifierScores(Map.of(KOTLIN, 0.5));
    Detection.Builder invalidScore = Detection.builder("Main.java")
      .candidates(List.of(JAVA))
      .classifierScores(Map.of(JAVA, Double.NaN));

    assertThatThrownBy(duplicateCandidates::build)
      .isInstanceOf(IllegalArgumentException.class)
      .hasMessage("Candidates must not contain duplicates");
    assertThatThrownBy(missingCandidateScore::build)
      .isInstanceOf(IllegalArgumentException.class)
      .hasMessage("Classifier score language must be a candidate: Kotlin");
    assertThatThrownBy(invalidScore::build)
      .isInstanceOf(IllegalArgumentException.class)
      .hasMessage("Classifier scores must be finite and positive");
  }

  @Test
  void preservesOriginalConstructionAndValueEquality() {
    Detection first = new Detection(JAVA, false, true, false, true);
    Detection second = new Detection(JAVA, false, true, false, true);

    assertThat(first).isEqualTo(second).hasSameHashCodeAs(second);
    assertThat(first.language()).contains(JAVA);
    assertThat(first.generated()).isTrue();
    assertThat(first.vendored()).isFalse();
    assertThat(first.documentation()).isTrue();
  }

  private static Map<Language, Double> orderedScores(
    Language firstLanguage,
    double firstScore,
    Language secondLanguage,
    double secondScore) {
    Map<Language, Double> scores = new LinkedHashMap<>();
    scores.put(firstLanguage, firstScore);
    scores.put(secondLanguage, secondScore);
    return scores;
  }

  private static Map<Language, Double> orderedScores(
    Language firstLanguage,
    double firstScore,
    Language secondLanguage,
    double secondScore,
    Language thirdLanguage,
    double thirdScore) {
    Map<Language, Double> scores = orderedScores(firstLanguage, firstScore, secondLanguage, secondScore);
    scores.put(thirdLanguage, thirdScore);
    return scores;
  }
}
