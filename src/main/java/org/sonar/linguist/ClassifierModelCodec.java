/*
 * Copyright (C) SonarSource Sàrl
 * For more information, see https://sonarsource.com/legal/
 * mailto:info AT sonarsource DOT com
 */
package org.sonar.linguist;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

final class ClassifierModelCodec {
  private static final byte[] MAGIC = {'L', 'I', 'N', 'G', 'C', 'L', 'F', 0};
  static final int SCHEMA_VERSION = 1;
  private static final int MAXIMUM_ENTRIES = 1_000_000;

  private ClassifierModelCodec() {
  }

  static void write(ClassifierModel model, OutputStream output) throws IOException {
    DataOutputStream data = new DataOutputStream(output);
    data.write(MAGIC);
    data.writeInt(SCHEMA_VERSION);

    TreeMap<Integer, String> termsByIndex = new TreeMap<>();
    model.vocabulary().forEach((term, index) -> {
      if (index < 0 || termsByIndex.put(index, term) != null) {
        throw new IllegalArgumentException("Vocabulary indexes must be unique and non-negative");
      }
    });
    if (termsByIndex.size() != model.vocabulary().size()
      || (!termsByIndex.isEmpty() && termsByIndex.lastKey() != termsByIndex.size() - 1)) {
      throw new IllegalArgumentException("Vocabulary indexes must be contiguous");
    }
    data.writeInt(termsByIndex.size());
    for (var entry : termsByIndex.entrySet()) {
      writeString(data, entry.getValue());
      data.writeDouble(model.inverseClassFrequency(entry.getKey()));
    }

    Map<String, Integer> mappings = new TreeMap<>(model.languageIdsByClassifierName());
    data.writeInt(mappings.size());
    for (var entry : mappings.entrySet()) {
      writeString(data, entry.getKey());
      data.writeInt(entry.getValue());
    }

    Map<Integer, Map<Integer, Double>> centroids = new TreeMap<>(model.centroids());
    data.writeInt(centroids.size());
    for (var centroid : centroids.entrySet()) {
      data.writeInt(centroid.getKey());
      Map<Integer, Double> values = new TreeMap<>(centroid.getValue());
      data.writeInt(values.size());
      for (var value : values.entrySet()) {
        data.writeInt(value.getKey());
        data.writeDouble(value.getValue());
      }
    }
    data.flush();
  }

  static ClassifierModel read(InputStream input) throws IOException {
    DataInputStream data = new DataInputStream(input);
    validateHeader(data);

    int vocabularySize = readCount(data, "vocabulary");
    double[] inverseClassFrequencies = new double[vocabularySize];
    Map<String, Integer> vocabulary = readVocabulary(data, inverseClassFrequencies);
    Map<String, Integer> mappings = readMappings(data);
    Map<Integer, Map<Integer, Double>> centroids = readCentroids(data, vocabularySize);
    if (data.read() != -1) {
      throw new IOException("Unexpected trailing classifier model data");
    }
    return new ClassifierModel(vocabulary, inverseClassFrequencies, centroids, mappings);
  }

  private static void validateHeader(DataInputStream data) throws IOException {
    byte[] magic = data.readNBytes(MAGIC.length);
    if (!Arrays.equals(magic, MAGIC)) {
      throw new IOException("Invalid classifier model magic");
    }
    int version = data.readInt();
    if (version != SCHEMA_VERSION) {
      throw new IOException("Unsupported classifier model schema version: " + version);
    }
  }

  private static Map<String, Integer> readVocabulary(
    DataInputStream data,
    double[] inverseClassFrequencies) throws IOException {
    Map<String, Integer> vocabulary = new LinkedHashMap<>();
    for (int index = 0; index < inverseClassFrequencies.length; index++) {
      String term = readString(data);
      if (vocabulary.put(term, index) != null) {
        throw new IOException("Duplicate classifier vocabulary term: " + term);
      }
      inverseClassFrequencies[index] = readFiniteDouble(data, "inverse class frequency");
    }
    return vocabulary;
  }

  private static Map<String, Integer> readMappings(DataInputStream data) throws IOException {
    int mappingCount = readCount(data, "language mapping");
    Map<String, Integer> mappings = new LinkedHashMap<>();
    for (int index = 0; index < mappingCount; index++) {
      String name = readString(data);
      if (mappings.put(name, data.readInt()) != null) {
        throw new IOException("Duplicate classifier language mapping: " + name);
      }
    }
    return mappings;
  }

  private static Map<Integer, Map<Integer, Double>> readCentroids(
    DataInputStream data,
    int vocabularySize) throws IOException {
    int centroidCount = readCount(data, "centroid");
    Map<Integer, Map<Integer, Double>> centroids = new LinkedHashMap<>();
    for (int index = 0; index < centroidCount; index++) {
      int languageId = data.readInt();
      int valueCount = readCount(data, "centroid value");
      Map<Integer, Double> centroid = new LinkedHashMap<>();
      for (int valueIndex = 0; valueIndex < valueCount; valueIndex++) {
        int vocabularyIndex = data.readInt();
        if (vocabularyIndex < 0 || vocabularyIndex >= vocabularySize) {
          throw new IOException("Invalid centroid vocabulary index: " + vocabularyIndex);
        }
        if (centroid.put(vocabularyIndex, readFiniteDouble(data, "centroid value")) != null) {
          throw new IOException("Duplicate centroid vocabulary index: " + vocabularyIndex);
        }
      }
      if (centroids.put(languageId, centroid) != null) {
        throw new IOException("Duplicate classifier centroid language ID: " + languageId);
      }
    }
    return centroids;
  }

  private static void writeString(DataOutputStream output, String value) throws IOException {
    byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
    output.writeInt(bytes.length);
    output.write(bytes);
  }

  private static String readString(DataInputStream input) throws IOException {
    int length = readCount(input, "string byte");
    byte[] bytes = input.readNBytes(length);
    if (bytes.length != length) {
      throw new IOException("Truncated classifier model string");
    }
    return new String(bytes, StandardCharsets.UTF_8);
  }

  private static int readCount(DataInputStream input, String description) throws IOException {
    int count = input.readInt();
    if (count < 0 || count > MAXIMUM_ENTRIES) {
      throw new IOException("Invalid classifier model " + description + " count: " + count);
    }
    return count;
  }

  private static double readFiniteDouble(DataInputStream input, String description) throws IOException {
    double value = input.readDouble();
    if (!Double.isFinite(value)) {
      throw new IOException("Invalid classifier model " + description);
    }
    return value;
  }
}
