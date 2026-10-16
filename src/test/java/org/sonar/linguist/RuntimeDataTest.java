/*
 * Copyright (C) SonarSource Sàrl
 * For more information, see https://sonarsource.com/legal/
 * mailto:info AT sonarsource DOT com
 */
package org.sonar.linguist;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RuntimeDataTest {
  @Test
  void packagesVersionedRuntimeDataWithPinnedContent() throws Exception {
    Map<String, Object> manifest = map(LanguageCatalog.loadYaml("runtime-data.json"));
    List<Map<String, Object>> resources = maps(manifest.get("resources"));

    assertThat(manifest).containsEntry("schemaVersion", 1);
    assertThat(resources).extracting(resource -> resource.get("path")).containsExactly(
      "documentation.yml",
      "generic.yml",
      "heuristics.yml",
      "languages.yml",
      "popular.yml",
      "vendor.yml");
    for (Map<String, Object> resource : resources) {
      assertThat(resource).containsEntry("schemaVersion", 1);
      assertThat(resourceSha256((String) resource.get("path"))).isEqualTo(resource.get("sha256"));
    }

    Map<String, Object> sources = map(manifest.get("sources"));
    assertThat(map(sources.get("github-linguist")))
      .containsEntry("commit", "73ee9a3b277f398c37ca4ab0dea0193849de6460")
      .containsEntry("version", "9.7.0");
  }

  private static String resourceSha256(String name) throws IOException, NoSuchAlgorithmException {
    try (InputStream input = Objects.requireNonNull(
      RuntimeDataTest.class.getResourceAsStream(name), "Missing runtime resource: " + name)) {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.readAllBytes()));
    }
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> map(Object value) {
    return (Map<String, Object>) value;
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> maps(Object value) {
    return (List<Map<String, Object>>) value;
  }
}
