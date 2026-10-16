/*
 * Copyright (C) SonarSource Sàrl
 * For more information, see https://sonarsource.com/legal/
 * mailto:info AT sonarsource DOT com
 */
package org.sonar.linguist;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

final class ClassifierTrainer {
  private static final int MINIMUM_DOCUMENT_FREQUENCY = 2;

  private final Map<Integer, List<Map<String, Integer>>> samplesByLanguage = new TreeMap<>();

  void addSample(int languageId, byte[] content) {
    addSample(languageId, Tokenizer.tokenize(Objects.requireNonNull(content, "content")));
  }

  void addSample(int languageId, List<String> tokens) {
    Objects.requireNonNull(tokens, "tokens");
    Map<String, Integer> termFrequencies = new HashMap<>();
    for (String token : tokens) {
      termFrequencies.merge(Objects.requireNonNull(token, "token"), 1, Integer::sum);
    }
    samplesByLanguage.computeIfAbsent(languageId, ignored -> new ArrayList<>()).add(termFrequencies);
  }

  ClassifierModel build() {
    Map<String, Integer> vocabulary = buildVocabulary();
    double[] inverseClassFrequencies = computeInverseClassFrequencies(vocabulary);
    Map<Integer, Map<Integer, Double>> centroids = computeCentroids(vocabulary, inverseClassFrequencies);
    return new ClassifierModel(vocabulary, inverseClassFrequencies, centroids);
  }

  private Map<String, Integer> buildVocabulary() {
    Map<String, Integer> documentFrequencies = new HashMap<>();
    samplesByLanguage.values().stream()
      .flatMap(List::stream)
      .forEach(sample -> sample.keySet().forEach(token -> documentFrequencies.merge(token, 1, Integer::sum)));

    Map<String, Integer> vocabulary = new LinkedHashMap<>();
    documentFrequencies.entrySet().stream()
      .filter(entry -> entry.getValue() >= MINIMUM_DOCUMENT_FREQUENCY)
      .map(Map.Entry::getKey)
      .sorted()
      .forEach(token -> vocabulary.put(token, vocabulary.size()));
    return vocabulary;
  }

  private double[] computeInverseClassFrequencies(Map<String, Integer> vocabulary) {
    int languageCount = samplesByLanguage.size();
    int[] classFrequencies = new int[vocabulary.size()];
    for (List<Map<String, Integer>> samples : samplesByLanguage.values()) {
      Set<String> languageTerms = new HashSet<>();
      samples.forEach(sample -> languageTerms.addAll(sample.keySet()));
      languageTerms.forEach(token -> {
        Integer index = vocabulary.get(token);
        if (index != null) {
          classFrequencies[index]++;
        }
      });
    }

    double[] inverseClassFrequencies = new double[vocabulary.size()];
    for (int index = 0; index < inverseClassFrequencies.length; index++) {
      inverseClassFrequencies[index] = Math.log((double) languageCount / classFrequencies[index]) + 1.0;
    }
    return inverseClassFrequencies;
  }

  private Map<Integer, Map<Integer, Double>> computeCentroids(
    Map<String, Integer> vocabulary,
    double[] inverseClassFrequencies) {
    Map<Integer, Map<Integer, Double>> centroids = new LinkedHashMap<>();
    samplesByLanguage.forEach((languageId, samples) -> {
      Map<Integer, Double> centroid = new TreeMap<>();
      for (Map<String, Integer> sample : samples) {
        Map<Integer, Double> normalizedSample = normalizeSample(sample, vocabulary, inverseClassFrequencies);
        normalizedSample.forEach((index, value) -> centroid.merge(index, value, Double::sum));
      }
      centroid.replaceAll((index, value) -> value / samples.size());
      normalize(centroid);
      centroids.put(languageId, centroid);
    });
    return centroids;
  }

  private static Map<Integer, Double> normalizeSample(
    Map<String, Integer> sample,
    Map<String, Integer> vocabulary,
    double[] inverseClassFrequencies) {
    Map<Integer, Double> weightedSample = new TreeMap<>();
    sample.forEach((token, frequency) -> {
      Integer index = vocabulary.get(token);
      if (index != null) {
        double termFrequency = 1.0 + Math.log(frequency);
        weightedSample.put(index, termFrequency * inverseClassFrequencies[index]);
      }
    });
    normalize(weightedSample);
    return weightedSample;
  }

  private static void normalize(Map<Integer, Double> vector) {
    double norm = Math.sqrt(vector.values().stream().mapToDouble(value -> value * value).sum());
    if (norm != 0.0) {
      vector.replaceAll((index, value) -> value / norm);
    }
  }
}
