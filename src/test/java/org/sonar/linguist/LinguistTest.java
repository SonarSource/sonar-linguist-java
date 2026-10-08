/*
 * Copyright (C) SonarSource Sàrl
 * For more information, see https://sonarsource.com/legal/
 * mailto:info AT sonarsource DOT com
 */
package org.sonar.linguist;

import java.io.ByteArrayInputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LinguistTest {
  private final Linguist linguist = Linguist.create();
  @TempDir
  Path temporary;

  @ParameterizedTest
  @CsvSource({
    "Main.java, Java", "main.py, Python", "lib.rs, Rust", "app.js, JavaScript",
    "main.go, Go", "main.rb, Ruby", "main.cs, C#", "main.cpp, C++",
    "main.c, C", "main.C, C", "main.JAVA, Java", "main.zig, Zig",
    "main.cob, COBOL", "main.bicep, Bicep", "main.tf, HCL",
    "index.html, HTML", "app.tsx, TSX", "notebook.ipynb, Jupyter Notebook",
    "build.gradle.kts, Gradle Kotlin DSL", "main.kts, Kotlin",
    "Dockerfile, Dockerfile", "nested/Containerfile, Dockerfile",
    "Makefile, Makefile", "Rakefile, Ruby", ".bashrc, Shell"
  })
  void recognizesLanguagesAndCompoundExtensions(String path, String expected) {
    assertThat(languageName(detect(path))).isEqualTo(expected);
  }

  @Test
  void bundlesExtensiveCatalogAndAllPathPatternsCompile() {
    assertThat((Map<?, ?>) LanguageCatalog.loadYaml("languages.yml")).hasSizeGreaterThan(700);
    assertThat(detect("src/Main.java").vendored()).isFalse();
    assertThat(detect("src/Main.java").documentation()).isFalse();
  }

  @Test
  void tiesAreStableAndUseExplicitDefaults() {
    assertThat(languageName(detect("schema.sql"))).isEqualTo("SQL");
    assertThat(languageName(detect("header.h"))).isEqualTo("C");
    assertThat(languageName(detect("script.pl"))).isEqualTo("Perl");
    assertThat(Linguist.create().detect("ambiguous.m", text(" "))).isEqualTo(detect("ambiguous.m"));
  }

  @Test
  void resolvesAmbiguousFilenameCandidatesWithHeuristics() {
    Detection detection = detect("config/.eslintrc");

    assertThat(detection.language()).map(Language::name).contains("JSON with Comments");
    assertThat(detection.strategy()).isEqualTo(DetectionStrategy.HEURISTICS);
    assertThat(detection.candidates()).extracting(Language::name).containsExactly("JSON with Comments");
  }

  @Test
  void unknownAndExtensionlessFilesHaveNoLanguage() {
    Detection unknown = detect("file.unknown-extension");

    assertThat(unknown.language()).isEmpty();
    assertThat(unknown.strategy()).isEqualTo(DetectionStrategy.NO_MATCH);
    assertThat(unknown.candidates()).isEmpty();
    assertThat(unknown.classifierScores()).isEmpty();
    assertThat(detect("script").language()).isEmpty();
  }

  @Test
  void filteringFlagsDoNotSuppressDetectedLanguage() {
    Detection vendor = detect("node_modules/package/index.js");
    assertThat(vendor.vendored()).isTrue();
    assertThat(languageName(vendor)).isEqualTo("JavaScript");
    assertThat(detect("docs/sample.java").documentation()).isTrue();
    assertThat(detect("README.md").documentation()).isTrue();
    assertThat(detect("build/generated/Main.java").generated()).isFalse();
    assertThat(detect("web/app.min.js").generated()).isFalse();
    assertThat(linguist.detect("web/app.min.js", text("x".repeat(111))).generated()).isTrue();
    assertThat(detect("web/app.js.map").generated()).isTrue();
    assertThat(detect("package-lock.json").generated()).isTrue();
    assertThat(detect("gradle/wrapper/gradle-wrapper.properties").generated()).isFalse();
    assertThat(detect("gradlew").generated()).isTrue();
    assertThat(detect("src/Main.java").generated()).isFalse();
    assertThat(languageName(detect("src\\Main.java"))).isEqualTo("Java");
    assertThat(detect("docs\\sample.java").documentation()).isTrue();
  }

  @Test
  void reportsGeneratedVendoredAndDocumentationFactsIndependently() {
    Detection generated = detect("package-lock.json");
    Detection vendored = detect("dist/app.js");
    Detection documentation = detect("docs/Main.java");
    Detection combined = detect("docs/node_modules/package-lock.json");

    assertThat(generated.generated()).isTrue();
    assertThat(generated.vendored()).isFalse();
    assertThat(generated.documentation()).isFalse();
    assertThat(vendored.generated()).isFalse();
    assertThat(vendored.vendored()).isTrue();
    assertThat(vendored.documentation()).isFalse();
    assertThat(documentation.generated()).isFalse();
    assertThat(documentation.vendored()).isFalse();
    assertThat(documentation.documentation()).isTrue();
    assertThat(combined.generated()).isTrue();
    assertThat(combined.vendored()).isTrue();
    assertThat(combined.documentation()).isTrue();
  }

  @Test
  void detectsBinaryContentAndRespectsTextBoms() {
    Detection binary = linguist.detect("Main.java", new byte[] {65, 0, 66});
    assertThat(binary.binary()).isTrue();
    assertThat(binary.language()).isEmpty();
    assertThat(linguist.detect("x.java", new byte[] {1, 2, 3, 4}).binary()).isTrue();
    assertThat(linguist.detect("Main.java", "class Main {}".getBytes(StandardCharsets.UTF_16)).binary()).isFalse();
    assertThat(linguist.detect(
      "Main.java", new byte[] {0, 0, (byte) 0xFE, (byte) 0xFF, 0, 0, 0, 65}).binary()).isFalse();
    assertThat(linguist.detect("Main.java", new byte[0]).binary()).isFalse();
  }

  @Test
  void exposesFileKindFacts() {
    assertThat(linguist.detect("image.PNG", text("content")).image()).isTrue();
    assertThat(linguist.detect("model.stl", text("content")).solid()).isTrue();
    assertThat(linguist.detect("data.csv", text("a,b\n")).csv()).isTrue();
    assertThat(linguist.detect("document.pdf", new byte[] {'a', 0, 'b'}).pdf()).isTrue();
    assertThat(linguist.detect("pointer", text("""
      version https://git-lfs.github.com/spec/v1
      oid sha256:0123456789abcdef
      size 123
      """)).lfsPointer()).isTrue();
  }

  @Test
  void exposesLineMetrics() {
    Detection detection = linguist.detect("lines.txt", text("first\n\nthird\n"));

    assertThat(detection.lineCount()).isEqualTo(3);
    assertThat(detection.sourceLineCount()).isEqualTo(2);
  }

  @Test
  void modelineWinsBeforeFilenameAndExtensionDetection() {
    Detection detection = linguist.detect("script.pl", text("# vim: ft=javascript\ncontent\n"));

    assertThat(detection.language()).map(Language::name).contains("JavaScript");
    assertThat(detection.strategy()).isEqualTo(DetectionStrategy.MODELINE);
    assertThat(detection.candidates()).extracting(Language::name).containsExactly("JavaScript");
  }

  @Test
  void shebangWinsBeforeExtensionAndPreservesAmbiguousCandidates() {
    Detection ruby = linguist.detect("script.py", text("#!/usr/bin/env ruby\nputs 'hello'\n"));
    Detection node = linguist.detect("script", text("#!/usr/bin/env node\nconsole.log('hello')\n"));
    Detection typescript = linguist.detect("script.ts", text("#!/usr/bin/env node\nconsole.log('hello')\n"));

    assertThat(ruby.language()).map(Language::name).contains("Ruby");
    assertThat(ruby.strategy()).isEqualTo(DetectionStrategy.SHEBANG);
    assertThat(ruby.candidates()).extracting(Language::name).containsExactly("Ruby");
    assertThat(ruby.classifierScores()).isEmpty();
    assertThat(node.language()).map(Language::name).contains("TypeScript");
    assertThat(node.strategy()).isEqualTo(DetectionStrategy.CLASSIFIER);
    assertThat(node.candidates()).extracting(Language::name).containsExactly("TypeScript", "JavaScript");
    assertThat(node.classifierScores()).isNotEmpty();
    assertThat(typescript.language()).map(Language::name).contains("TypeScript");
    assertThat(typescript.strategy()).isEqualTo(DetectionStrategy.EXTENSION);
    assertThat(typescript.candidates()).extracting(Language::name).containsExactly("TypeScript");
    assertThat(typescript.classifierScores()).isEmpty();
  }

  @Test
  void resolvesAmbiguousExtensionCandidatesAndDefersGenericExtensions() {
    Detection sql = linguist.detect("query.sql", text("<?xml version=\"1.0\"?>\n<query/>\n"));
    Detection generic = detect("model.stl");

    assertThat(sql.language()).map(Language::name).contains("SQL");
    assertThat(sql.strategy()).isEqualTo(DetectionStrategy.HEURISTICS);
    assertThat(sql.candidates()).extracting(Language::name).containsExactly("SQL");
    assertThat(generic.language()).isEmpty();
    assertThat(generic.strategy()).isEqualTo(DetectionStrategy.NO_MATCH);
  }

  @Test
  void detectsXmlAfterExtensionWhenNoCandidatesExist() {
    Detection xml = linguist.detect("document.unknown", text("<?xml version=\"1.0\"?>\n<root/>\n"));
    Detection python = linguist.detect("script.py", text("<?xml version=\"1.0\"?>\n<root/>\n"));

    assertThat(xml.language()).map(Language::name).contains("XML");
    assertThat(xml.strategy()).isEqualTo(DetectionStrategy.XML);
    assertThat(xml.candidates()).extracting(Language::name).containsExactly("XML");
    assertThat(python.language()).map(Language::name).contains("Python");
    assertThat(python.strategy()).isEqualTo(DetectionStrategy.EXTENSION);
  }

  @Test
  void detectsManpagesWhenEarlierStrategiesProduceNoCandidates() {
    Detection manpage = linguist.detect("docs/bsdmalloc.3malloc", text(".Dd January 1, 2026\n"));

    assertThat(manpage.language()).map(Language::name).contains("Roff Manpage");
    assertThat(manpage.strategy()).isEqualTo(DetectionStrategy.CLASSIFIER);
    assertThat(manpage.candidates()).extracting(Language::name).containsExactly("Roff Manpage", "Roff");
    assertThat(manpage.classifierScores()).isNotEmpty();
  }

  @Test
  void preservesManpageCandidatesAndWinningStrategyWithoutClassifierScores() {
    Linguist withoutClassifier = new Linguist(LanguageCatalog.bundled(), new PathRules(), null);

    Detection detection = withoutClassifier.detect("docs/bsdmalloc.3malloc", text(".Dd January 1, 2026\n"));

    assertThat(detection.language()).map(Language::name).contains("Roff Manpage");
    assertThat(detection.strategy()).isEqualTo(DetectionStrategy.MANPAGE);
    assertThat(detection.candidates()).extracting(Language::name).containsExactly("Roff Manpage", "Roff");
    assertThat(detection.classifierScores()).isEmpty();
  }

  @Test
  void appliesHeuristicsAfterExtensionCandidates() {
    Detection objectiveC = linguist.detect("header.h", text("@interface Example\n"));
    Detection sql = linguist.detect("query.sql", text("select * from example;\n"));
    Detection unresolved = linguist.detect("module.mod", text("unrecognized content\n"));

    assertThat(objectiveC.language()).map(Language::name).contains("Objective-C");
    assertThat(objectiveC.strategy()).isEqualTo(DetectionStrategy.HEURISTICS);
    assertThat(objectiveC.candidates()).extracting(Language::name).containsExactly("Objective-C");
    assertThat(sql.language()).map(Language::name).contains("SQL");
    assertThat(sql.strategy()).isEqualTo(DetectionStrategy.HEURISTICS);
    assertThat(unresolved.strategy()).isEqualTo(DetectionStrategy.HEURISTICS);
    assertThat(unresolved.candidates()).extracting(Language::name)
      .containsExactly("Linux Kernel Module", "AMPL");
  }

  @Test
  void retainsExtensionCandidatesWhenHeuristicsDoNotMatch() {
    Detection detection = linguist.detect("ambiguous.m", text("unrecognized content\n"));

    assertThat(detection.language()).map(Language::name).contains("Limbo");
    assertThat(detection.strategy()).isEqualTo(DetectionStrategy.EXTENSION);
    assertThat(detection.candidates()).extracting(Language::name)
      .containsExactly("Limbo", "M", "MATLAB", "MUF", "Mercury", "Objective-C", "Wolfram Language");
    assertThat(detection.classifierScores()).isEmpty();
  }

  @Test
  void executesStrategiesInUpstreamOrderAndStopsOnOneCandidate() {
    Detection modeline = linguist.detect("Dockerfile", text("# vim: ft=python\n#!/usr/bin/env ruby\n"));
    Detection filename = linguist.detect("Dockerfile", text("#!/usr/bin/env ruby\n"));
    Detection xml = linguist.detect("manual.3malloc", text("<?xml version=\"1.0\"?>\n<root/>\n"));

    assertThat(modeline.strategy()).isEqualTo(DetectionStrategy.MODELINE);
    assertThat(modeline.language()).map(Language::name).contains("Python");
    assertThat(modeline.candidates()).extracting(Language::name).containsExactly("Python");
    assertThat(modeline.classifierScores()).isEmpty();
    assertThat(filename.strategy()).isEqualTo(DetectionStrategy.FILENAME);
    assertThat(filename.language()).map(Language::name).contains("Dockerfile");
    assertThat(filename.candidates()).extracting(Language::name).containsExactly("Dockerfile");
    assertThat(filename.classifierScores()).isEmpty();
    assertThat(xml.strategy()).isEqualTo(DetectionStrategy.XML);
    assertThat(xml.language()).map(Language::name).contains("XML");
    assertThat(xml.candidates()).extracting(Language::name).containsExactly("XML");
    assertThat(xml.classifierScores()).isEmpty();
  }

  @Test
  void exposesDetectedEncodingAndHandlesMalformedInput() {
    Detection utf8 = linguist.detect("Main.java", text("foo\n"));
    assertThat(utf8.encoding()).contains("UTF-8");
    assertThat(utf8.rubyEncoding()).contains("UTF-8");
    assertThat(utf8.encodingConfidence()).isPresent();
    assertThat(utf8.encodingType()).contains("text");

    Detection malformedUtf16 = linguist.detect("Main.java", new byte[] {(byte) 0xFF, (byte) 0xFE, 'a'});
    assertThat(malformedUtf16.encoding()).isEmpty();
    assertThat(malformedUtf16.binary()).isFalse();
  }

  @Test
  void rejectsEmptyFilesByDefaultAndAllowsThemExplicitly() {
    Detection rejected = linguist.detect("Main.java", new byte[0]);
    Detection allowed = linguist.detect("Main.java", new byte[0], new DetectionOptions(true));

    assertThat(rejected.empty()).isTrue();
    assertThat(rejected.language()).isEmpty();
    assertThat(rejected.strategy()).isEqualTo(DetectionStrategy.NO_MATCH);
    assertThat(allowed.empty()).isTrue();
    assertThat(allowed.language()).map(Language::name).contains("Java");
    assertThat(allowed.strategy()).isEqualTo(DetectionStrategy.EXTENSION);
  }

  @Test
  void appliesAllowEmptyToContentLoadedFromAPath() {
    ContentLoader emptyContent = () -> new ByteArrayInputStream(new byte[0]);

    assertThat(linguist.detect(Path.of("Main.java"), emptyContent).language()).isEmpty();
    assertThat(linguist.detect(Path.of("Main.java"), emptyContent, new DetectionOptions(true)).language())
      .map(Language::name)
      .contains("Java");
  }

  @Test
  void existingOverloadsUseDefaultOptions() {
    byte[] content = text("class Main {}");
    ContentLoader loader = () -> new ByteArrayInputStream(content);

    assertThat(linguist.detect("Main.java", content))
      .isEqualTo(linguist.detect("Main.java", content, DetectionOptions.defaults()));
    assertThat(linguist.detect(Path.of("Main.java"), loader))
      .isEqualTo(linguist.detect(Path.of("Main.java"), loader, DetectionOptions.defaults()));
  }

  @Test
  void validatesExistingAndOptionAwareDetectionArguments() {
    ContentLoader content = () -> new ByteArrayInputStream(text("content"));
    Path mainJava = Path.of("Main.java");
    byte[] bytes = text("content");

    assertThatThrownBy(() -> linguist.detect((String) null, new byte[0]))
      .isInstanceOf(NullPointerException.class)
      .hasMessage("filename");
    assertThatThrownBy(() -> linguist.detect("Main.java", null))
      .isInstanceOf(NullPointerException.class)
      .hasMessage("content");
    assertThatThrownBy(() -> linguist.detect((Path) null, content))
      .isInstanceOf(NullPointerException.class)
      .hasMessage("relativePath");
    assertThatThrownBy(() -> linguist.detect(mainJava, null))
      .isInstanceOf(NullPointerException.class)
      .hasMessage("contentLoader");
    assertThatThrownBy(() -> linguist.detect("Main.java", bytes, null))
      .isInstanceOf(NullPointerException.class)
      .hasMessage("options");
    assertThatThrownBy(() -> linguist.detect(mainJava, content, null))
      .isInstanceOf(NullPointerException.class)
      .hasMessage("options");
  }

  @Test
  void readsStandaloneFilesAndReportsReadFailures() throws Exception {
    Path file = temporary.resolve("Main.java");
    Files.writeString(file, "class Main {}\n");
    assertThat(languageName(detect(Path.of("Main.java"), file))).isEqualTo("Java");
    Path missing = temporary.resolve("missing.java");
    Path missingRelativePath = Path.of("missing.java");
    assertThatThrownBy(() -> detect(missingRelativePath, missing)).isInstanceOf(UncheckedIOException.class);
  }

  @Test
  void opensContentOnceAndSharesTheCompleteBytes() {
    AtomicInteger opened = new AtomicInteger();
    byte[] bytes = new byte[100_000];
    Arrays.fill(bytes, (byte) 'a');
    ContentLoader content = () -> {
      opened.incrementAndGet();
      return new ByteArrayInputStream(bytes);
    };

    Detection unknown = linguist.detect(Path.of("image.unknown-extension"), content);
    assertThat(unknown.language()).isEmpty();
    assertThat(unknown.binary()).isFalse();
    assertThat(unknown.byteSize()).isEqualTo(bytes.length);
    assertThat(opened).hasValue(1);
  }

  @Test
  void appliesRootRelativeRulesToTheRelativePath() throws Exception {
    Path file = temporary.resolve("index.js");
    Files.writeString(file, "module.exports = {};\n");
    assertThat(detect(Path.of("node_modules", "lib", "index.js"), file).vendored()).isTrue();
    assertThat(detect(Path.of("index.js"), file).vendored()).isFalse();
  }

  @Test
  void exposesUpstreamLanguageIds() {
    assertThat(detect("Main.java").language()).map(Language::languageId).contains(181);
    assertThat(detect("main.py").language()).map(Language::languageId).contains(303);
  }

  @Test
  void checksOnlyBoundedBinarySample() throws Exception {
    byte[] bytes = new byte[100_000];
    Arrays.fill(bytes, (byte) 'a');
    bytes[90_000] = 0;
    Path file = temporary.resolve("large.java");
    Files.write(file, bytes);
    assertThat(detect(Path.of("large.java"), file).binary()).isFalse();
    assertThat(detect(Path.of("large.java"), file)).isEqualTo(linguist.detect("large.java", bytes));
  }

  private Detection detect(Path relativePath, Path file) {
    return linguist.detect(relativePath, () -> Files.newInputStream(file));
  }

  private Detection detect(String path) {
    return linguist.detect(path, text(" "));
  }

  private static String languageName(Detection detection) {
    return detection.language().orElseThrow().name();
  }

  private static byte[] text(String value) {
    return value.getBytes(StandardCharsets.UTF_8);
  }
}
