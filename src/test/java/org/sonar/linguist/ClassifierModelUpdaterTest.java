/*
 * Copyright (C) SonarSource Sàrl
 * For more information, see https://sonarsource.com/legal/
 * mailto:info AT sonarsource DOT com
 */
package org.sonar.linguist;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ClassifierModelUpdaterTest {
  private static final String ROOT = "linguist-0123456789abcdef0123456789abcdef01234567";

  @TempDir
  Path temporary;

  @Test
  void readsExactlyOnePinnedCommit() {
    String commit = "0123456789abcdef0123456789abcdef01234567";

    assertThat(ClassifierModelUpdater.pinnedCommit("{\"commit\":\"" + commit + "\"}"))
      .isEqualTo(commit);
    assertThatThrownBy(() -> ClassifierModelUpdater.pinnedCommit("{}"))
      .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> ClassifierModelUpdater.pinnedCommit(
      "{\"commit\":\"" + commit + "\",\"nested\":{\"commit\":\"" + commit + "\"}}"))
      .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void readsUpstreamProvenance() {
    String commit = "0123456789abcdef0123456789abcdef01234567";

    assertThat(ClassifierModelUpdater.provenance(
      "{\"origin\":\"https://example.com/upstream.git\",\"commit\":\"" + commit + "\"}"))
      .isEqualTo(new ClassifierModelUpdater.Provenance("https://example.com/upstream.git", commit));
    assertThatThrownBy(() -> ClassifierModelUpdater.provenance("{\"commit\":\"" + commit + "\"}"))
      .isInstanceOf(IllegalArgumentException.class)
      .hasMessageContaining("origin");
  }

  @ParameterizedTest
  @MethodSource("unsafePaths")
  void rejectsUnsafeOrUnexpectedArchivePaths(String path) {
    assertThatThrownBy(() -> ClassifierModelUpdater.validateEntryPath(path, ROOT))
      .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void validatesEveryEntryBeforeExtractingSamples() throws IOException {
    Path archive = archive(
      entry(ROOT + "/samples/Ruby/example.rb", "puts :ok"),
      entry(ROOT + "/../outside", "unsafe"));
    Path destination = temporary.resolve("extracted");

    assertThatThrownBy(() -> ClassifierModelUpdater.extractSamples(archive, destination, ROOT))
      .isInstanceOf(IllegalArgumentException.class)
      .hasMessageContaining("Unsafe archive entry");
    assertThat(destination).doesNotExist();
  }

  @Test
  void extractsOnlyTheExpectedSamplesDirectory() throws IOException {
    Path archive = archive(
      entry(ROOT + "/README.md", "ignored"),
      entry(ROOT + "/samples/Ruby/example.rb", "puts :ok"));
    Path destination = temporary.resolve("extracted");

    ClassifierModelUpdater.extractSamples(archive, destination, ROOT);

    assertThat(destination.resolve("samples/Ruby/example.rb")).hasContent("puts :ok");
    assertThat(destination.resolve("README.md")).doesNotExist();
  }

  @Test
  void discoversSamplesInDeterministicPathOrderAndMapsFilesystemNames() throws IOException {
    Path samples = temporary.resolve("samples");
    write(samples.resolve("Ruby/z.rb"));
    write(samples.resolve("Fstar/example.fst"));
    write(samples.resolve("Ruby/filenames/Gemfile"));

    List<ClassifierModelUpdater.Sample> discovered =
      ClassifierModelUpdater.discoverSamples(samples, LanguageCatalog.bundled());

    assertThat(discovered).extracting(ClassifierModelUpdater.Sample::relativePath)
      .containsExactly("Fstar/example.fst", "Ruby/filenames/Gemfile", "Ruby/z.rb");
    assertThat(discovered).extracting(sample -> sample.language().name())
      .containsExactly("F*", "Ruby", "Ruby");
  }

  @Test
  void trainsFromCompleteSampleFileContents() throws IOException {
    Path samples = temporary.resolve("training-samples");
    String content = " ".repeat(60 * 1024) + "latetoken";
    Files.createDirectories(samples.resolve("Ruby"));
    Files.writeString(samples.resolve("Ruby/one.rb"), content);
    Files.writeString(samples.resolve("Ruby/two.rb"), content);

    List<ClassifierModelUpdater.Sample> discovered =
      ClassifierModelUpdater.discoverSamples(samples, LanguageCatalog.bundled());
    ClassifierModel model = ClassifierModelUpdater.train(discovered);

    assertThat(model.vocabulary()).containsEntry("latetoken", 0);
    int rubyId = LanguageCatalog.bundled().byName("Ruby").orElseThrow().languageId();
    assertThat(model.centroids()).containsKey(rubyId);
    assertThat(ClassifierModelUpdater.classifierMappings(discovered)).containsExactlyEntriesOf(Map.of("Ruby", rubyId));
  }

  @Test
  void computesDigestFromOrderedSamplePathsAndContents() throws IOException {
    Path samples = temporary.resolve("digest-samples");
    write(samples.resolve("Ruby/one.rb"), "first");
    write(samples.resolve("Ruby/two.rb"), "second");
    List<ClassifierModelUpdater.Sample> discovered =
      ClassifierModelUpdater.discoverSamples(samples, LanguageCatalog.bundled());

    String digest = ClassifierModelUpdater.orderedSampleDigest(discovered);

    assertThat(ClassifierModelUpdater.orderedSampleDigest(discovered)).isEqualTo(digest);
    assertThat(ClassifierModelUpdater.orderedSampleDigest(discovered.reversed())).isNotEqualTo(digest);
    write(samples.resolve("Ruby/two.rb"), "changed");
    assertThat(ClassifierModelUpdater.orderedSampleDigest(discovered)).isNotEqualTo(digest);
  }

  @Test
  void writesCanonicalMetadataJson() {
    ClassifierModelUpdater.Metadata metadata = new ClassifierModelUpdater.Metadata(
      "model", "samples", 42, 1, "tokenizer", "commit", "https://example.com/a\\b\"c");

    assertThat(ClassifierModelUpdater.metadataJson(metadata)).isEqualTo("""
      {
        "modelSha256": "model",
        "orderedSampleDigest": "samples",
        "sampleCount": 42,
        "schemaVersion": 1,
        "tokenizerSha256": "tokenizer",
        "upstreamCommit": "commit",
        "upstreamOrigin": "https://example.com/a\\\\b\\\"c"
      }
      """);
  }

  @Test
  void buildsMetadataFromInputs() throws IOException {
    Path tokenizer = temporary.resolve("tokenizer.flex");
    Path model = temporary.resolve("model.bin");
    write(tokenizer, "tokenizer");
    write(model, "model");
    Path sampleFile = temporary.resolve("Ruby/example.rb");
    write(sampleFile, "sample");
    Language ruby = LanguageCatalog.bundled().byName("Ruby").orElseThrow();
    List<ClassifierModelUpdater.Sample> samples = List.of(
      new ClassifierModelUpdater.Sample(sampleFile, "Ruby/example.rb", ruby));

    ClassifierModelUpdater.Metadata metadata = ClassifierModelUpdater.metadata(
      new ClassifierModelUpdater.Provenance("https://example.com/upstream.git", ROOT.substring("linguist-".length())),
      tokenizer,
      samples,
      model);

    assertThat(metadata.modelSha256()).hasSize(64);
    assertThat(metadata.orderedSampleDigest()).hasSize(64);
    assertThat(metadata.tokenizerSha256()).hasSize(64);
    assertThat(metadata.sampleCount()).isOne();
    assertThat(metadata.schemaVersion()).isEqualTo(ClassifierModelCodec.SCHEMA_VERSION);
    assertThat(metadata.upstreamOrigin()).isEqualTo("https://example.com/upstream.git");
  }

  @Test
  void doesNotPublishArtifactsWhenGenerationsDiffer() throws IOException {
    Path first = artifactSet("first", "model", "metadata");
    Path second = artifactSet("second", "different model", "metadata");
    Path destination = artifactSet("destination", "old model", "old metadata");

    assertThatThrownBy(() -> ClassifierModelUpdater.verifyAndPublish(first, second, destination))
      .isInstanceOf(IllegalStateException.class)
      .hasMessageContaining("classifier-model.bin");
    assertThat(destination.resolve("classifier-model.bin")).hasContent("old model");
    assertThat(destination.resolve("classifier-model.json")).hasContent("old metadata");
  }

  @Test
  void atomicallyPublishesMatchingArtifacts() throws IOException {
    Path first = artifactSet("first", "new model", "new metadata");
    Path second = artifactSet("second", "new model", "new metadata");
    Path destination = artifactSet("destination", "old model", "old metadata");

    ClassifierModelUpdater.verifyAndPublish(first, second, destination);

    assertThat(destination.resolve("classifier-model.bin")).hasContent("new model");
    assertThat(destination.resolve("classifier-model.json")).hasContent("new metadata");
    assertThat(destination.resolve(".classifier-model.bin.tmp")).doesNotExist();
    assertThat(destination.resolve(".classifier-model.json.tmp")).doesNotExist();
  }

  @Test
  void allowsOnlyDocumentedCAndCppClassificationExceptions() {
    Language c = LanguageCatalog.bundled().byName("C").orElseThrow();
    Language cpp = LanguageCatalog.bundled().byName("C++").orElseThrow();

    assertThat(ClassifierModelUpdater.classificationMatches(sample("C/rpc.h", c), cpp)).isTrue();
    assertThat(ClassifierModelUpdater.classificationMatches(sample("C/syscalldefs.h", c), cpp)).isTrue();
    assertThat(ClassifierModelUpdater.classificationMatches(sample("C++/Types.h", cpp), c)).isTrue();
    assertThat(ClassifierModelUpdater.classificationMatches(sample("C/other.h", c), cpp)).isFalse();
    assertThat(ClassifierModelUpdater.classificationMatches(sample("C/other.h", c), c)).isTrue();
  }

  @Test
  void deletesDownloadedAndExtractedTrainingInputs() throws IOException {
    Path downloads = temporary.resolve("downloads");
    Path extracted = temporary.resolve("upstream");
    write(downloads.resolve("linguist.zip"), "archive");
    write(downloads.resolve("linguist.zip.part"), "partial archive");
    write(extracted.resolve("samples/Ruby/example.rb"), "sample");

    ClassifierModelUpdater.cleanupTrainingInputs(downloads, extracted);

    assertThat(downloads).doesNotExist();
    assertThat(extracted).doesNotExist();
    ClassifierModelUpdater.cleanupTrainingInputs(downloads, extracted);
  }

  @Test
  void rejectsUnknownLanguageDirectoriesAndUnexpectedRootFiles() throws IOException {
    LanguageCatalog catalog = LanguageCatalog.bundled();
    Path unknownSamples = temporary.resolve("unknown-samples");
    write(unknownSamples.resolve("Unknown Language/example"));
    assertThatThrownBy(() -> ClassifierModelUpdater.discoverSamples(unknownSamples, catalog))
      .isInstanceOf(IllegalArgumentException.class)
      .hasMessage("Unknown sample language directory: Unknown Language");

    Path invalidSamples = temporary.resolve("invalid-samples");
    write(invalidSamples.resolve("orphan"));
    assertThatThrownBy(() -> ClassifierModelUpdater.discoverSamples(invalidSamples, catalog))
      .isInstanceOf(IllegalArgumentException.class)
      .hasMessageContaining("Unexpected entry in samples root");
  }

  private Path archive(ArchiveEntry... entries) throws IOException {
    Path archive = temporary.resolve("archive-" + System.nanoTime() + ".zip");
    try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(archive))) {
      for (ArchiveEntry entry : entries) {
        output.putNextEntry(new ZipEntry(entry.path()));
        output.write(entry.content().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        output.closeEntry();
      }
    }
    return archive;
  }

  private Path artifactSet(String directory, String model, String metadata) throws IOException {
    Path artifactSet = temporary.resolve(directory);
    write(artifactSet.resolve("classifier-model.bin"), model);
    write(artifactSet.resolve("classifier-model.json"), metadata);
    return artifactSet;
  }

  private ClassifierModelUpdater.Sample sample(String relativePath, Language language) {
    return new ClassifierModelUpdater.Sample(temporary.resolve(relativePath), relativePath, language);
  }

  private static ArchiveEntry entry(String path, String content) {
    return new ArchiveEntry(path, content);
  }

  private static void write(Path path) throws IOException {
    write(path, "sample");
  }

  private static void write(Path path, String content) throws IOException {
    Files.createDirectories(path.getParent());
    Files.writeString(path, content);
  }

  private static Stream<String> unsafePaths() {
    return Stream.of(
      "",
      "/" + ROOT + "/samples/file",
      "C:/" + ROOT + "/samples/file",
      ROOT + "\\samples\\file",
      ROOT + "/../outside",
      ROOT + "/./samples/file",
      ROOT + "//samples/file",
      "unexpected-root/samples/file");
  }

  private record ArchiveEntry(String path, String content) {
  }
}
