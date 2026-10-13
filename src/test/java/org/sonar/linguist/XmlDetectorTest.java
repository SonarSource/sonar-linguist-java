/*
 * Copyright (C) SonarSource Sàrl
 * For more information, see https://sonarsource.com/legal/
 * mailto:info AT sonarsource DOT com
 */
package org.sonar.linguist;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class XmlDetectorTest {
  private final LanguageCatalog catalog = LanguageCatalog.bundled();

  @Test
  void detectsXmlDeclarationWithinFirstTwoLines() {
    assertThat(names(detect("<?xml version=\"1.0\"?>\n<root/>\n"))).containsExactly("XML");
    assertThat(names(detect("<!-- generated -->\r\n<?xml version=\"1.0\"?>\r\n<root/>\n")))
      .containsExactly("XML");
  }

  @Test
  void ignoresXmlDeclarationAfterFirstTwoLines() {
    assertThat(detect("<!-- first -->\n<!-- second -->\n<?xml version=\"1.0\"?>\n")).isEmpty();
  }

  @Test
  void preservesExistingCandidatesWithoutInspectingXml() {
    Language python = catalog.byAlias("Python").orElseThrow();

    assertThat(XmlDetector.detect(content("<?xml version=\"1.0\"?>"), catalog, List.of(python)))
      .containsExactly(python);
  }

  private List<Language> detect(String value) {
    return XmlDetector.detect(content(value), catalog, List.of());
  }

  private static FileContent content(String value) {
    return FileContent.copyOf("unknown", value.getBytes(StandardCharsets.UTF_8));
  }

  private static List<String> names(List<Language> languages) {
    return languages.stream().map(Language::name).toList();
  }
}
