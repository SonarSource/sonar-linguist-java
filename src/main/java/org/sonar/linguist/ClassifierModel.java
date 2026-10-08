/*
 * Copyright (C) SonarSource Sàrl
 * For more information, see https://sonarsource.com/legal/
 * mailto:info AT sonarsource DOT com
 */
package org.sonar.linguist;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

final class ClassifierModel {
  private final Map<String, Integer> vocabulary;
  private final double[] inverseClassFrequencies;
  private final Map<Integer, Map<Integer, Double>> centroids;
  private final Map<String, Integer> languageIdsByClassifierName;

  ClassifierModel(
    Map<String, Integer> vocabulary,
    double[] inverseClassFrequencies,
    Map<Integer, Map<Integer, Double>> centroids) {
    this(vocabulary, inverseClassFrequencies, centroids, Map.of());
  }

  ClassifierModel(
    Map<String, Integer> vocabulary,
    double[] inverseClassFrequencies,
    Map<Integer, Map<Integer, Double>> centroids,
    Map<String, Integer> languageIdsByClassifierName) {
    this.vocabulary = immutableMap(vocabulary);
    this.inverseClassFrequencies = inverseClassFrequencies.clone();
    LinkedHashMap<Integer, Map<Integer, Double>> immutableCentroids = new LinkedHashMap<>();
    centroids.forEach((languageId, centroid) -> immutableCentroids.put(languageId, immutableMap(centroid)));
    this.centroids = Collections.unmodifiableMap(immutableCentroids);
    this.languageIdsByClassifierName = immutableMap(languageIdsByClassifierName);
  }

  Map<String, Integer> vocabulary() {
    return vocabulary;
  }

  double inverseClassFrequency(int vocabularyIndex) {
    return inverseClassFrequencies[vocabularyIndex];
  }

  Map<Integer, Map<Integer, Double>> centroids() {
    return centroids;
  }

  Map<String, Integer> languageIdsByClassifierName() {
    return languageIdsByClassifierName;
  }

  ClassifierModel withLanguageIdsByClassifierName(Map<String, Integer> mappings) {
    return new ClassifierModel(vocabulary, inverseClassFrequencies, centroids, mappings);
  }

  private static <K, V> Map<K, V> immutableMap(Map<K, V> source) {
    return Collections.unmodifiableMap(new LinkedHashMap<>(source));
  }
}
