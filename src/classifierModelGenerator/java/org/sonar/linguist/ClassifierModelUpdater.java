/*
 * Copyright (C) SonarSource Sàrl
 * For more information, see https://sonarsource.com/legal/
 * mailto:info AT sonarsource DOT com
 */
package org.sonar.linguist;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

final class ClassifierModelUpdater {
  private static final Pattern PINNED_COMMIT = Pattern.compile("\\\"commit\\\"\\s*:\\s*\\\"([0-9a-f]{40})\\\"");
  private static final Pattern UPSTREAM_ORIGIN = Pattern.compile("\\\"origin\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"");
  private static final String ARCHIVE_URL = "https://codeload.github.com/github-linguist/linguist/zip/";
  private static final List<String> ARTIFACT_NAMES = List.of("classifier-model.bin", "classifier-model.json");
  private static final Map<String, Set<String>> ALLOWED_CLASSIFICATION_RESULTS = Map.of(
    "C/rpc.h", Set.of("C", "C++"),
    "C/syscalldefs.h", Set.of("C", "C++"),
    "C++/Types.h", Set.of("C", "C++"));

  private ClassifierModelUpdater() {
  }

  public static void main(String[] arguments) throws IOException, InterruptedException {
    if (arguments.length != 4) {
      throw new IllegalArgumentException(
        "Expected upstream manifest, working directory, tokenizer source, and artifact directory arguments");
    }
    Path manifest = Path.of(arguments[0]);
    Path workingDirectory = Path.of(arguments[1]);
    Path tokenizerSource = Path.of(arguments[2]);
    Path artifactDirectory = Path.of(arguments[3]);
    Provenance provenance = provenance(Files.readString(manifest));
    String commit = provenance.commit();
    Path downloads = workingDirectory.resolve("downloads");
    Path archive = downloads.resolve("linguist.zip");
    Path extracted = workingDirectory.resolve("upstream");

    try {
      download(URI.create(ARCHIVE_URL + commit), archive);
      extractSamples(archive, extracted, "linguist-" + commit);
      List<Sample> samples = discoverSamples(extracted.resolve("samples"), LanguageCatalog.bundled());
      Path firstGeneration = workingDirectory.resolve("generated/first");
      Path secondGeneration = workingDirectory.resolve("generated/second");
      generateArtifacts(provenance, tokenizerSource, samples, firstGeneration);
      generateArtifacts(provenance, tokenizerSource, samples, secondGeneration);
      verifyMatchingArtifacts(firstGeneration, secondGeneration);
      try (InputStream input = Files.newInputStream(firstGeneration.resolve("classifier-model.bin"))) {
        evaluateAmbiguousSamples(samples, LanguageCatalog.bundled(), ClassifierModelCodec.read(input));
      }
      publishArtifacts(firstGeneration, artifactDirectory);
    } finally {
      cleanupTrainingInputs(downloads, extracted);
    }
  }

  static void generateArtifacts(Provenance provenance, Path tokenizerSource, List<Sample> samples, Path outputDirectory)
    throws IOException {
    deleteTree(outputDirectory);
    Files.createDirectories(outputDirectory);
    ClassifierModel model = train(samples).withLanguageIdsByClassifierName(classifierMappings(samples));
    Path modelFile = outputDirectory.resolve("classifier-model.bin");
    Files.createDirectories(modelFile.getParent());
    try (OutputStream output = Files.newOutputStream(modelFile)) {
      ClassifierModelCodec.write(model, output);
    }
    Metadata metadata = metadata(provenance, tokenizerSource, samples, modelFile);
    Files.writeString(outputDirectory.resolve("classifier-model.json"), metadataJson(metadata), StandardCharsets.UTF_8);
  }

  static String pinnedCommit(String manifest) {
    return exactlyOneMatch(PINNED_COMMIT, manifest,
      "Upstream manifest must contain exactly one lowercase 40-character commit");
  }

  static Provenance provenance(String manifest) {
    String origin = exactlyOneMatch(UPSTREAM_ORIGIN, manifest,
      "Upstream manifest must contain exactly one origin");
    return new Provenance(origin, pinnedCommit(manifest));
  }

  private static String exactlyOneMatch(Pattern pattern, String value, String errorMessage) {
    Matcher matcher = pattern.matcher(value);
    if (!matcher.find()) {
      throw new IllegalArgumentException(errorMessage);
    }
    String match = matcher.group(1);
    if (matcher.find()) {
      throw new IllegalArgumentException(errorMessage);
    }
    return match;
  }

  static Path validateEntryPath(String entryName, String expectedRoot) {
    if (entryName.isEmpty() || entryName.indexOf('\\') >= 0 || entryName.startsWith("/")
      || entryName.matches("^[A-Za-z]:.*")) {
      throw unsafeEntry(entryName);
    }
    String withoutTrailingSlash = entryName.endsWith("/") ? entryName.substring(0, entryName.length() - 1) : entryName;
    String[] components = withoutTrailingSlash.split("/", -1);
    if (components.length == 0 || !components[0].equals(expectedRoot)) {
      throw new IllegalArgumentException("Unexpected archive entry: " + entryName);
    }
    for (String component : components) {
      if (component.isEmpty() || component.equals(".") || component.equals("..")) {
        throw unsafeEntry(entryName);
      }
    }
    try {
      Path path = Path.of(withoutTrailingSlash);
      if (path.isAbsolute()) {
        throw unsafeEntry(entryName);
      }
      return path;
    } catch (InvalidPathException e) {
      throw new IllegalArgumentException("Unsafe archive entry: " + entryName, e);
    }
  }

  static void extractSamples(Path archive, Path destination, String expectedRoot) throws IOException {
    Set<Path> entries = new HashSet<>();
    boolean foundSamples = false;
    try (ZipFile zip = new ZipFile(archive.toFile())) {
      for (ZipEntry entry : zip.stream().toList()) {
        Path entryPath = validateEntryPath(entry.getName(), expectedRoot);
        if (!entries.add(entryPath)) {
          throw new IllegalArgumentException("Duplicate archive entry: " + entry.getName());
        }
        foundSamples |= entryPath.getNameCount() > 1 && entryPath.getName(1).toString().equals("samples");
      }
      if (!foundSamples) {
        throw new IllegalArgumentException("Archive does not contain the expected samples directory");
      }

      deleteTree(destination);
      Path normalizedDestination = destination.toAbsolutePath().normalize();
      for (ZipEntry entry : zip.stream().toList()) {
        Path entryPath = validateEntryPath(entry.getName(), expectedRoot);
        if (entryPath.getNameCount() < 2 || !entryPath.getName(1).toString().equals("samples")) {
          continue;
        }
        Path relative = entryPath.subpath(1, entryPath.getNameCount());
        Path target = normalizedDestination.resolve(relative).normalize();
        if (!target.startsWith(normalizedDestination)) {
          throw unsafeEntry(entry.getName());
        }
        if (entry.isDirectory()) {
          Files.createDirectories(target);
        } else {
          Files.createDirectories(target.getParent());
          try (InputStream input = zip.getInputStream(entry)) {
            Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING);
          }
        }
      }
    }
  }

  static List<Sample> discoverSamples(Path samplesRoot, LanguageCatalog catalog) throws IOException {
    if (!Files.isDirectory(samplesRoot)) {
      throw new IllegalArgumentException("Samples root is not a directory: " + samplesRoot);
    }
    Map<String, Language> languagesByDirectory = new TreeMap<>();
    try (var entries = Files.list(samplesRoot)) {
      for (Path entry : entries.toList()) {
        if (!Files.isDirectory(entry)) {
          throw new IllegalArgumentException("Unexpected entry in samples root: " + entry.getFileName());
        }
        String directory = entry.getFileName().toString();
        Language language = catalog.byClassifierName(directory)
          .orElseThrow(() -> new IllegalArgumentException("Unknown sample language directory: " + directory));
        languagesByDirectory.put(directory, language);
      }
    }

    List<Path> paths;
    try (var entries = Files.walk(samplesRoot)) {
      paths = entries.filter(Files::isRegularFile)
        .sorted(Comparator.comparing(path -> relativePath(samplesRoot, path)))
        .toList();
    }
    return paths.stream()
      .map(path -> {
        String relativePath = relativePath(samplesRoot, path);
        String languageDirectory = samplesRoot.relativize(path).getName(0).toString();
        return new Sample(path, relativePath, languagesByDirectory.get(languageDirectory));
      })
      .toList();
  }

  static ClassifierModel train(List<Sample> samples) throws IOException {
    ClassifierTrainer trainer = new ClassifierTrainer();
    for (Sample sample : samples) {
      try {
        trainer.addSample(sample.language().languageId(), Files.readAllBytes(sample.path()));
      } catch (IOException e) {
        throw new IOException("Cannot read classifier sample: " + sample.relativePath(), e);
      }
    }
    return trainer.build();
  }

  static Map<String, Integer> classifierMappings(List<Sample> samples) {
    Map<String, Integer> mappings = new TreeMap<>();
    for (Sample sample : samples) {
      String classifierName = sample.relativePath().substring(0, sample.relativePath().indexOf('/'));
      Integer conflict = mappings.putIfAbsent(classifierName, sample.language().languageId());
      if (conflict != null && conflict != sample.language().languageId()) {
        throw new IllegalArgumentException("Conflicting classifier language mapping: " + classifierName);
      }
    }
    return mappings;
  }

  static Metadata metadata(
    Provenance provenance,
    Path tokenizerSource,
    List<Sample> samples,
    Path modelFile) throws IOException {
    return new Metadata(
      sha256(modelFile),
      orderedSampleDigest(samples),
      samples.size(),
      ClassifierModelCodec.SCHEMA_VERSION,
      sha256(tokenizerSource),
      provenance.commit(),
      provenance.origin());
  }

  static String orderedSampleDigest(List<Sample> samples) throws IOException {
    MessageDigest digest = sha256Digest();
    for (Sample sample : samples) {
      byte[] path = sample.relativePath().getBytes(StandardCharsets.UTF_8);
      digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(path.length).array());
      digest.update(path);
      digest.update(sha256Bytes(sample.path()));
    }
    return HexFormat.of().formatHex(digest.digest());
  }

  static String metadataJson(Metadata metadata) {
    return "{\n"
      + "  \"modelSha256\": " + jsonString(metadata.modelSha256()) + ",\n"
      + "  \"orderedSampleDigest\": " + jsonString(metadata.orderedSampleDigest()) + ",\n"
      + "  \"sampleCount\": " + metadata.sampleCount() + ",\n"
      + "  \"schemaVersion\": " + metadata.schemaVersion() + ",\n"
      + "  \"tokenizerSha256\": " + jsonString(metadata.tokenizerSha256()) + ",\n"
      + "  \"upstreamCommit\": " + jsonString(metadata.upstreamCommit()) + ",\n"
      + "  \"upstreamOrigin\": " + jsonString(metadata.upstreamOrigin()) + "\n"
      + "}\n";
  }

  static void verifyAndPublish(Path firstGeneration, Path secondGeneration, Path artifactDirectory) throws IOException {
    verifyMatchingArtifacts(firstGeneration, secondGeneration);
    publishArtifacts(firstGeneration, artifactDirectory);
  }

  private static void verifyMatchingArtifacts(Path firstGeneration, Path secondGeneration) throws IOException {
    for (String artifactName : ARTIFACT_NAMES) {
      Path first = firstGeneration.resolve(artifactName);
      Path second = secondGeneration.resolve(artifactName);
      if (Files.mismatch(first, second) != -1) {
        throw new IllegalStateException("Classifier model generation is not reproducible: " + artifactName);
      }
    }
  }

  private static void publishArtifacts(Path generation, Path artifactDirectory) throws IOException {
    for (String artifactName : ARTIFACT_NAMES) {
      atomicReplace(generation.resolve(artifactName), artifactDirectory.resolve(artifactName));
    }
  }

  static void evaluateAmbiguousSamples(
    List<Sample> samples,
    LanguageCatalog catalog,
    ClassifierModel model) throws IOException {
    Set<String> skippedExtensions = HeuristicRules.bundled().disambiguations().stream()
      .filter(disambiguation -> !disambiguation.rules().isEmpty()
        && disambiguation.rules().getLast().condition() instanceof HeuristicRules.Always)
      .flatMap(disambiguation -> disambiguation.extensions().stream())
      .map(extension -> extension.toLowerCase(Locale.ROOT))
      .collect(Collectors.toUnmodifiableSet());
    Classifier classifier = new Classifier(model);
    Map<String, String> failures = new LinkedHashMap<>();
    for (Sample sample : samples) {
      String extension = extension(sample.relativePath());
      if (skippedExtensions.contains(extension) || catalog.findByFilename(sample.relativePath()).size() == 1) {
        continue;
      }
      List<Language> candidates = catalog.findByExtension(sample.relativePath());
      if (candidates.size() <= 1) {
        continue;
      }
      Classifier.Result result = classifier.classify(Files.readAllBytes(sample.path()), candidates);
      Language actual = result.scores().keySet().stream().findFirst().orElse(null);
      if (actual == null || !classificationMatches(sample, actual)) {
        failures.put(sample.relativePath(), actual == null ? "no result" : actual.name());
      }
    }
    if (!failures.isEmpty()) {
      throw new IllegalStateException("Classifier failed ambiguous samples: " + failures);
    }
  }

  static boolean classificationMatches(Sample sample, Language actual) {
    Set<String> allowed = ALLOWED_CLASSIFICATION_RESULTS.get(sample.relativePath());
    return allowed == null ? actual.equals(sample.language()) : allowed.contains(actual.name());
  }

  private static String extension(String path) {
    String filename = path.substring(path.lastIndexOf('/') + 1);
    int dot = filename.lastIndexOf('.');
    return dot < 0 ? "" : filename.substring(dot).toLowerCase(Locale.ROOT);
  }

  private static void atomicReplace(Path source, Path destination) throws IOException {
    Files.createDirectories(destination.getParent());
    Path temporary = destination.resolveSibling("." + destination.getFileName() + ".tmp");
    Files.deleteIfExists(temporary);
    try {
      Files.copy(source, temporary);
      Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    } finally {
      Files.deleteIfExists(temporary);
    }
  }

  static String sha256(Path path) throws IOException {
    return HexFormat.of().formatHex(sha256Bytes(path));
  }

  private static byte[] sha256Bytes(Path path) throws IOException {
    MessageDigest digest = sha256Digest();
    try (InputStream input = Files.newInputStream(path)) {
      byte[] buffer = new byte[8192];
      int read;
      while ((read = input.read(buffer)) != -1) {
        digest.update(buffer, 0, read);
      }
    }
    return digest.digest();
  }

  private static MessageDigest sha256Digest() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is not available", e);
    }
  }

  private static String jsonString(String value) {
    StringBuilder escaped = new StringBuilder(value.length() + 2).append('"');
    for (int index = 0; index < value.length(); index++) {
      char character = value.charAt(index);
      switch (character) {
        case '"' -> escaped.append("\\\"");
        case '\\' -> escaped.append("\\\\");
        case '\b' -> escaped.append("\\b");
        case '\f' -> escaped.append("\\f");
        case '\n' -> escaped.append("\\n");
        case '\r' -> escaped.append("\\r");
        case '\t' -> escaped.append("\\t");
        default -> {
          if (character < 0x20) {
            escaped.append(String.format("\\u%04x", (int) character));
          } else {
            escaped.append(character);
          }
        }
      }
    }
    return escaped.append('"').toString();
  }

  private static void download(URI source, Path destination) throws IOException, InterruptedException {
    Files.createDirectories(destination.getParent());
    Path temporary = destination.resolveSibling(destination.getFileName() + ".part");
    Files.deleteIfExists(temporary);
    HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();
    HttpRequest request = HttpRequest.newBuilder(source).GET().build();
    HttpResponse<Path> response = client.send(request, HttpResponse.BodyHandlers.ofFile(temporary));
    if (response.statusCode() != 200) {
      Files.deleteIfExists(temporary);
      throw new IOException("Cannot download pinned Linguist archive: HTTP " + response.statusCode());
    }
    try {
      Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    } catch (AtomicMoveNotSupportedException e) {
      Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
    }
  }

  private static void deleteTree(Path path) throws IOException {
    if (!Files.exists(path)) {
      return;
    }
    try (var paths = Files.walk(path)) {
      for (Path entry : paths.sorted(Comparator.reverseOrder()).toList()) {
        Files.delete(entry);
      }
    }
  }

  static void cleanupTrainingInputs(Path... paths) throws IOException {
    IOException failure = null;
    for (Path path : paths) {
      try {
        deleteTree(path);
      } catch (IOException e) {
        if (failure == null) {
          failure = e;
        } else {
          failure.addSuppressed(e);
        }
      }
    }
    if (failure != null) {
      throw failure;
    }
  }

  private static IllegalArgumentException unsafeEntry(String entryName) {
    return new IllegalArgumentException("Unsafe archive entry: " + entryName);
  }

  private static String relativePath(Path root, Path path) {
    return root.relativize(path).toString().replace(path.getFileSystem().getSeparator(), "/");
  }

  record Sample(Path path, String relativePath, Language language) {
  }

  record Provenance(String origin, String commit) {
  }

  record Metadata(
    String modelSha256,
    String orderedSampleDigest,
    int sampleCount,
    int schemaVersion,
    String tokenizerSha256,
    String upstreamCommit,
    String upstreamOrigin) {
  }
}
