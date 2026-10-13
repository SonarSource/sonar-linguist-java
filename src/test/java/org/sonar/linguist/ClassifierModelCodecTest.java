/*
 * Copyright (C) SonarSource Sàrl
 * For more information, see https://sonarsource.com/legal/
 * mailto:info AT sonarsource DOT com
 */
package org.sonar.linguist;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ClassifierModelCodecTest {
  @Test
  void roundTripsVersionedModelDeterministically() throws IOException {
    ClassifierModel first = model(
      orderedMap("beta", 1, "alpha", 0),
      orderedCentroids(20, orderedVector(1, 0.8), 10, orderedVector(0, 1.0)),
      orderedMap("Fstar", 10, "Beta", 20));
    ClassifierModel second = model(
      orderedMap("alpha", 0, "beta", 1),
      orderedCentroids(10, orderedVector(0, 1.0), 20, orderedVector(1, 0.8)),
      orderedMap("Beta", 20, "Fstar", 10));

    byte[] firstBytes = write(first);
    byte[] secondBytes = write(second);
    ClassifierModel restored = ClassifierModelCodec.read(new ByteArrayInputStream(firstBytes));

    assertThat(firstBytes).isEqualTo(secondBytes);
    assertThat(restored.vocabulary()).containsExactlyEntriesOf(orderedMap("alpha", 0, "beta", 1));
    assertThat(restored.inverseClassFrequency(0)).isEqualTo(1.25);
    assertThat(restored.inverseClassFrequency(1)).isEqualTo(2.5);
    assertThat(restored.centroids()).containsExactlyEntriesOf(
      orderedCentroids(10, orderedVector(0, 1.0), 20, orderedVector(1, 0.8)));
    assertThat(restored.languageIdsByClassifierName()).containsExactlyEntriesOf(
      orderedMap("Beta", 20, "Fstar", 10));
  }

  @Test
  void rejectsUnknownSchemaAndTrailingData() throws IOException {
    byte[] valid = write(model(Map.of(), Map.of(), Map.of()));
    byte[] unknownSchema = valid.clone();
    unknownSchema[11] = 2;
    byte[] trailing = java.util.Arrays.copyOf(valid, valid.length + 1);

    assertThatThrownBy(() -> ClassifierModelCodec.read(new ByteArrayInputStream(unknownSchema)))
      .isInstanceOf(IOException.class)
      .hasMessageContaining("schema version");
    assertThatThrownBy(() -> ClassifierModelCodec.read(new ByteArrayInputStream(trailing)))
      .isInstanceOf(IOException.class)
      .hasMessageContaining("trailing");
  }

  private static ClassifierModel model(
    Map<String, Integer> vocabulary,
    Map<Integer, Map<Integer, Double>> centroids,
    Map<String, Integer> mappings) {
    double[] inverseClassFrequencies = vocabulary.isEmpty() ? new double[0] : new double[] {1.25, 2.5};
    return new ClassifierModel(vocabulary, inverseClassFrequencies, centroids, mappings);
  }

  private static byte[] write(ClassifierModel model) throws IOException {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    ClassifierModelCodec.write(model, output);
    return output.toByteArray();
  }

  private static <K, V> Map<K, V> orderedMap(K firstKey, V firstValue, K secondKey, V secondValue) {
    Map<K, V> result = new LinkedHashMap<>();
    result.put(firstKey, firstValue);
    result.put(secondKey, secondValue);
    return result;
  }

  private static Map<Integer, Map<Integer, Double>> orderedCentroids(
    int firstId,
    Map<Integer, Double> first,
    int secondId,
    Map<Integer, Double> second) {
    return orderedMap(firstId, first, secondId, second);
  }

  private static Map<Integer, Double> orderedVector(int index, double value) {
    return Map.of(index, value);
  }
}
