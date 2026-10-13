/*
 * Copyright (C) SonarSource Sàrl
 * For more information, see https://sonarsource.com/legal/
 * mailto:info AT sonarsource DOT com
 */
package org.sonar.linguist;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

final class ClassifierModelVerifier {
  private static final Set<String> METADATA_FIELDS = Set.of(
    "modelSha256",
    "orderedSampleDigest",
    "sampleCount",
    "schemaVersion",
    "tokenizerSha256",
    "upstreamCommit",
    "upstreamOrigin");
  private static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");

  private ClassifierModelVerifier() {
  }

  public static void main(String[] arguments) throws IOException {
    if (arguments.length != 4) {
      throw new IllegalArgumentException("Expected upstream manifest, tokenizer source, model, and metadata arguments");
    }
    verify(Path.of(arguments[0]), Path.of(arguments[1]), Path.of(arguments[2]), Path.of(arguments[3]));
  }

  static void verify(Path manifestFile, Path tokenizerSource, Path modelFile, Path metadataFile) throws IOException {
    ClassifierModelUpdater.Provenance provenance =
      ClassifierModelUpdater.provenance(Files.readString(manifestFile, StandardCharsets.UTF_8));
    String metadataJson = Files.readString(metadataFile, StandardCharsets.UTF_8);
    ClassifierModelUpdater.Metadata metadata = parseMetadata(metadataJson);

    require(metadata.schemaVersion() == ClassifierModelCodec.SCHEMA_VERSION,
      "Classifier metadata schema does not match the model codec");
    require(metadata.upstreamOrigin().equals(provenance.origin()),
      "Classifier metadata upstream origin does not match the manifest");
    require(metadata.upstreamCommit().equals(provenance.commit()),
      "Classifier metadata upstream commit does not match the manifest");
    require(SHA_256.matcher(metadata.orderedSampleDigest()).matches(),
      "Classifier metadata sample digest is not SHA-256");
    require(metadata.sampleCount() > 0, "Classifier metadata sample count must be positive");
    require(metadata.modelSha256().equals(ClassifierModelUpdater.sha256(modelFile)),
      "Classifier model SHA-256 does not match metadata");
    require(metadata.tokenizerSha256().equals(ClassifierModelUpdater.sha256(tokenizerSource)),
      "Classifier tokenizer SHA-256 does not match metadata");

    ClassifierModel model;
    try (InputStream input = Files.newInputStream(modelFile)) {
      model = ClassifierModelCodec.read(input);
    }
    verifyCatalogMappings(model, LanguageCatalog.bundled());
  }

  static ClassifierModelUpdater.Metadata parseMetadata(String json) {
    Object yaml = LanguageCatalog.loadCustomYaml(
      new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)), "classifier metadata");
    if (!(yaml instanceof Map<?, ?> values) || !values.keySet().equals(METADATA_FIELDS)) {
      throw new IllegalArgumentException("Classifier metadata must contain exactly the required fields");
    }
    ClassifierModelUpdater.Metadata metadata = new ClassifierModelUpdater.Metadata(
      string(values, "modelSha256"),
      string(values, "orderedSampleDigest"),
      integer(values, "sampleCount"),
      integer(values, "schemaVersion"),
      string(values, "tokenizerSha256"),
      string(values, "upstreamCommit"),
      string(values, "upstreamOrigin"));
    require(json.equals(ClassifierModelUpdater.metadataJson(metadata)), "Classifier metadata is not canonical");
    return metadata;
  }

  private static void verifyCatalogMappings(ClassifierModel model, LanguageCatalog catalog) {
    require(!model.vocabulary().isEmpty(), "Classifier model vocabulary is empty");
    require(!model.centroids().isEmpty(), "Classifier model centroids are empty");
    require(!model.languageIdsByClassifierName().isEmpty(), "Classifier model language mappings are empty");
    Set<Integer> mappedLanguageIds = new HashSet<>();
    model.languageIdsByClassifierName().forEach((classifierName, languageId) -> {
      Language language = catalog.byClassifierName(classifierName)
        .orElseThrow(() -> new IllegalStateException("Unknown classifier language mapping: " + classifierName));
      require(language.languageId() == languageId,
        "Classifier language ID does not match the catalog: " + classifierName);
      mappedLanguageIds.add(languageId);
    });
    require(model.centroids().keySet().equals(mappedLanguageIds),
      "Classifier centroid language IDs do not match its mappings");
  }

  private static String string(Map<?, ?> values, String field) {
    Object value = values.get(field);
    if (!(value instanceof String string)) {
      throw new IllegalArgumentException("Classifier metadata field must be a string: " + field);
    }
    return string;
  }

  private static int integer(Map<?, ?> values, String field) {
    Object value = values.get(field);
    if (!(value instanceof Integer integer)) {
      throw new IllegalArgumentException("Classifier metadata field must be an integer: " + field);
    }
    return integer;
  }

  private static void require(boolean condition, String message) {
    if (!condition) {
      throw new IllegalStateException(message);
    }
  }
}
