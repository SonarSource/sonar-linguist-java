/*
 * Copyright (C) SonarSource Sàrl
 * For more information, see https://sonarsource.com/legal/
 * mailto:info AT sonarsource DOT com
 */
package org.sonar.linguist;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

final class Classifier {
  private static final int MAXIMUM_EVALUATION_BYTES = 50 * 1024;

  private final ClassifierModel model;

  Classifier(ClassifierModel model) {
    this.model = Objects.requireNonNull(model, "model");
  }

  Result classify(FileContent content, List<Language> candidates) {
    Objects.requireNonNull(content, "content");
    return classify(content.bytes(), candidates);
  }

  Result classify(byte[] content, List<Language> candidates) {
    Objects.requireNonNull(content, "content");
    Objects.requireNonNull(candidates, "candidates");
    if (candidates.isEmpty()) {
      return new Result(List.of(), Map.of());
    }

    int evaluationLength = Math.min(content.length, MAXIMUM_EVALUATION_BYTES);
    List<String> tokens = Tokenizer.tokenize(Arrays.copyOf(content, evaluationLength));
    Map<Integer, Integer> termFrequencies = new HashMap<>();
    for (String token : tokens) {
      Integer index = model.vocabulary().get(token);
      if (index != null) {
        termFrequencies.merge(index, 1, Integer::sum);
      }
    }
    if (termFrequencies.isEmpty()) {
      return new Result(candidates, Map.of());
    }

    Map<Integer, Double> vector = new TreeMap<>();
    termFrequencies.forEach((index, frequency) -> {
      double termFrequency = 1.0 + Math.log(frequency);
      vector.put(index, termFrequency * model.inverseClassFrequency(index));
    });
    normalize(vector);

    List<Score> scores = new ArrayList<>();
    for (Language candidate : candidates) {
      Objects.requireNonNull(candidate, "candidate");
      Map<Integer, Double> centroid = model.centroids().get(candidate.languageId());
      if (centroid != null) {
        double similarity = similarity(vector, centroid);
        if (similarity > 0.0) {
          scores.add(new Score(candidate, similarity));
        }
      }
    }
    scores.sort((left, right) -> Double.compare(right.value(), left.value()));

    Map<Language, Double> orderedScores = new LinkedHashMap<>();
    scores.forEach(score -> orderedScores.put(score.language(), score.value()));
    List<Language> rankedCandidates = new ArrayList<>(orderedScores.keySet());
    candidates.stream()
      .filter(candidate -> !orderedScores.containsKey(candidate))
      .forEach(rankedCandidates::add);
    return new Result(rankedCandidates, orderedScores);
  }

  private static void normalize(Map<Integer, Double> vector) {
    double norm = Math.sqrt(vector.values().stream().mapToDouble(value -> value * value).sum());
    vector.replaceAll((index, value) -> value / norm);
  }

  private static double similarity(Map<Integer, Double> vector, Map<Integer, Double> centroid) {
    double similarity = 0.0;
    for (var entry : vector.entrySet()) {
      similarity += entry.getValue() * centroid.getOrDefault(entry.getKey(), 0.0);
    }
    return similarity;
  }

  private record Score(Language language, double value) {
  }

  record Result(List<Language> candidates, Map<Language, Double> scores) {
    Result {
      candidates = List.copyOf(candidates);
      scores = Collections.unmodifiableMap(new LinkedHashMap<>(scores));
    }
  }
}
