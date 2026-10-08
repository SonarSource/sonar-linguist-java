/*
 * Copyright (C) SonarSource Sàrl
 * For more information, see https://sonarsource.com/legal/
 * mailto:info AT sonarsource DOT com
 */
package org.sonar.linguist;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LanguageTest {
  private final LanguageCatalog catalog = LanguageCatalog.bundled();

  @Test
  void loadsAllRetainedMetadataFromPinnedResources() {
    Map<String, Map<String, Object>> definitions = languageDefinitions();
    Set<String> popularNames = Set.copyOf(strings(LanguageCatalog.loadYaml("popular.yml")));

    for (var entry : definitions.entrySet()) {
      String name = entry.getKey();
      Map<String, Object> fields = entry.getValue();
      Language language = language(name);
      List<String> aliases = new ArrayList<>();
      aliases.add(name.toLowerCase(Locale.ROOT).replaceAll("\\s", "-"));
      aliases.addAll(strings(fields.get("aliases")));

      assertThat(language.languageId()).isEqualTo(fields.get("language_id"));
      assertThat(language.type().name()).isEqualTo(((String) fields.get("type")).toUpperCase(Locale.ROOT));
      assertThat(language.aliases()).containsExactlyElementsOf(aliases);
      assertThat(language.extensions()).containsExactlyElementsOf(strings(fields.get("extensions")));
      assertThat(language.filenames()).containsExactlyElementsOf(strings(fields.get("filenames")));
      assertThat(language.interpreters()).containsExactlyInAnyOrderElementsOf(strings(fields.get("interpreters")));
      assertThat(language.filesystemName()).isEqualTo(Optional.ofNullable((String) fields.get("fs_name")));
      assertThat(language.color()).isEqualTo(Optional.ofNullable((String) fields.get("color")));
      assertThat(language.group().name()).isEqualTo(fields.getOrDefault("group", name));
      assertThat(language.tmScope()).isEqualTo(fields.get("tm_scope"));
      assertThat(language.aceMode()).isEqualTo(fields.get("ace_mode"));
      assertThat(language.codeMirrorMode()).isEqualTo(Optional.ofNullable((String) fields.get("codemirror_mode")));
      assertThat(language.codeMirrorMimeType())
        .isEqualTo(Optional.ofNullable((String) fields.get("codemirror_mime_type")));
      assertThat(language.wrap()).isEqualTo(Boolean.TRUE.equals(fields.get("wrap")));
      assertThat(language.popular()).isEqualTo(popularNames.contains(name));
    }
  }

  @Test
  void exposesCatalogMetadata() {
    Language cpp = language("C++");

    assertThat(cpp.languageId()).isEqualTo(43);
    assertThat(cpp.name()).isEqualTo("C++");
    assertThat(cpp.escapedName()).isEqualTo("C%2B%2B");
    assertThat(cpp.type()).isEqualTo(LanguageType.PROGRAMMING);
    assertThat(cpp.defaultAlias()).isEqualTo("c++");
    assertThat(cpp.defaultAliasName()).isEqualTo(cpp.defaultAlias());
    assertThat(cpp.aliases()).containsExactly("c++", "cpp");
    assertThat(cpp.extensions()).startsWith(".cpp", ".c++", ".cc");
    assertThat(cpp.filenames()).isEmpty();
    assertThat(cpp.interpreters()).isEmpty();
    assertThat(cpp.filesystemName()).isEmpty();
    assertThat(cpp.color()).contains("#f34b7d");
    assertThat(cpp.group()).isSameAs(cpp);
    assertThat(cpp.tmScope()).isEqualTo("source.c++");
    assertThat(cpp.aceMode()).isEqualTo("c_cpp");
    assertThat(cpp.codeMirrorMode()).contains("clike");
    assertThat(cpp.codeMirrorMimeType()).contains("text/x-c++src");
    assertThat(cpp.wrap()).isFalse();
    assertThat(cpp.popular()).isTrue();
  }

  @Test
  void exposesDerivedAndOptionalMetadata() {
    Language commonLisp = language("Common Lisp");
    Language fstar = language("F*");
    Language roffManpage = language("Roff Manpage");

    assertThat(language("C#").escapedName()).isEqualTo("C%23");
    assertThat(commonLisp.escapedName()).isEqualTo("Common%20Lisp");
    assertThat(fstar.escapedName()).isEqualTo("F%2A");
    assertThat(language("Ren'Py").escapedName()).isEqualTo("Ren%27Py");
    assertThat(commonLisp.defaultAlias()).isEqualTo("common-lisp");
    assertThat(commonLisp.interpreters()).containsExactly("ccl", "clisp", "ecl", "lisp", "sbcl");
    assertThat(fstar.filesystemName()).contains("Fstar");
    assertThat(fstar.codeMirrorMode()).isEmpty();
    assertThat(fstar.codeMirrorMimeType()).isEmpty();
    assertThat(language("ABNF").color()).isEmpty();
    assertThat(roffManpage.group().name()).isEqualTo("Roff");
    assertThat(roffManpage.wrap()).isTrue();
    assertThat(roffManpage.popular()).isFalse();
  }

  @Test
  void exposesImmutableMetadataLists() {
    Language cpp = language("C++");
    Language commonLisp = language("Common Lisp");
    Language roff = language("Roff");
    List<String> aliases = cpp.aliases();
    List<String> extensions = cpp.extensions();
    List<String> filenames = roff.filenames();
    List<String> interpreters = commonLisp.interpreters();

    assertThatThrownBy(() -> aliases.add("other")).isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(extensions::clear).isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> filenames.add("other")).isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(interpreters::removeFirst)
      .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void preservesFieldSpecificListOrdering() {
    assertThat(language("C#").aliases()).containsExactly("c#", "csharp", "cake", "cakescript");
    assertThat(language("C++").extensions()).startsWith(".cpp", ".c++", ".cc");
    assertThat(language("Roff").filenames()).containsExactly("eqnrc", "mmn", "mmt", "troffrc", "troffrc-end");
    assertThat(language("Wolfram Language").interpreters()).containsExactly(
      "MathKernel",
      "Mathematica",
      "MathematicaScript",
      "WolframKernel",
      "WolframNB",
      "math",
      "wolfram",
      "wolframscript");
  }

  @Test
  void usesCanonicalNameAndIdForStableIdentity() {
    Language java = new Language(181, "Java", LanguageType.PROGRAMMING);
    Language sameIdentity = new Language(181, "Java", LanguageType.DATA);

    assertThat(sameIdentity).isEqualTo(java).hasSameHashCodeAs(java);
    assertThat(new Language(181, "Other", LanguageType.PROGRAMMING)).isNotEqualTo(java);
    assertThat(new Language(999, "Java", LanguageType.PROGRAMMING)).isNotEqualTo(java);
  }

  @Test
  void preservesExistingConstructionAndAccessors() {
    Language language = new Language(-1, "Example", LanguageType.PROGRAMMING);

    assertThat(language.languageId()).isEqualTo(-1);
    assertThat(language.name()).isEqualTo("Example");
    assertThat(language.type()).isEqualTo(LanguageType.PROGRAMMING);
    assertThat(language.group()).isSameAs(language);
    assertThat(language).hasToString("Example");
  }

  private Language language(String name) {
    return catalog.byAlias(name).orElseThrow();
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Map<String, Object>> languageDefinitions() {
    return (Map<String, Map<String, Object>>) LanguageCatalog.loadYaml("languages.yml");
  }

  @SuppressWarnings("unchecked")
  private static List<String> strings(Object value) {
    return value == null ? List.of() : (List<String>) value;
  }
}
