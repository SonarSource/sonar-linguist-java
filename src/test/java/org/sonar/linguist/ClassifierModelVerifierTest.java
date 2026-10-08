/*
 * Copyright (C) SonarSource Sàrl
 * For more information, see https://sonarsource.com/legal/
 * mailto:info AT sonarsource DOT com
 */
package org.sonar.linguist;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ClassifierModelVerifierTest {
  private static final String COMMIT = "0123456789abcdef0123456789abcdef01234567";
  private static final String ORIGIN = "https://example.com/upstream.git";

  @TempDir
  Path temporary;

  @Test
  void verifiesCommittedInputsAndRejectsChangedTokenizer() throws IOException {
    Path manifest = write("manifest.json", "{\"commit\":\"" + COMMIT + "\",\"origin\":\"" + ORIGIN + "\"}");
    Path tokenizer = write("tokenizer.flex", "tokenizer");
    Path sampleFile = write("samples/Ruby/example.rb", "sample");
    Path modelFile = temporary.resolve("classifier-model.bin");
    Language ruby = LanguageCatalog.bundled().byName("Ruby").orElseThrow();
    ClassifierModel model = new ClassifierModel(
      Map.of("token", 0),
      new double[] {1.0},
      Map.of(ruby.languageId(), Map.of(0, 1.0)),
      Map.of("Ruby", ruby.languageId()));
    try (OutputStream output = Files.newOutputStream(modelFile)) {
      ClassifierModelCodec.write(model, output);
    }
    ClassifierModelUpdater.Sample sample = new ClassifierModelUpdater.Sample(sampleFile, "Ruby/example.rb", ruby);
    ClassifierModelUpdater.Metadata metadata = ClassifierModelUpdater.metadata(
      new ClassifierModelUpdater.Provenance(ORIGIN, COMMIT), tokenizer, List.of(sample), modelFile);
    Path metadataFile = write("classifier-model.json", ClassifierModelUpdater.metadataJson(metadata));

    ClassifierModelVerifier.verify(manifest, tokenizer, modelFile, metadataFile);

    Files.writeString(tokenizer, "changed");
    assertThatThrownBy(() -> ClassifierModelVerifier.verify(manifest, tokenizer, modelFile, metadataFile))
      .isInstanceOf(IllegalStateException.class)
      .hasMessageContaining("tokenizer SHA-256");
  }

  @Test
  void rejectsNonCanonicalOrUnexpectedMetadata() {
    String valid = ClassifierModelUpdater.metadataJson(new ClassifierModelUpdater.Metadata(
      "a".repeat(64), "b".repeat(64), 1, 1, "c".repeat(64), COMMIT, ORIGIN));
    String unexpectedField = valid.replace("{", "{\n  \"extra\": true,");
    String nonCanonical = valid.replace("  \"modelSha256\"", " \"modelSha256\"");

    assertThatThrownBy(() -> ClassifierModelVerifier.parseMetadata(unexpectedField))
      .isInstanceOf(IllegalArgumentException.class)
      .hasMessageContaining("required fields");
    assertThatThrownBy(() -> ClassifierModelVerifier.parseMetadata(nonCanonical))
      .isInstanceOf(IllegalStateException.class)
      .hasMessageContaining("canonical");
  }

  private Path write(String relativePath, String content) throws IOException {
    Path path = temporary.resolve(relativePath);
    Files.createDirectories(path.getParent());
    Files.writeString(path, content);
    return path;
  }
}
