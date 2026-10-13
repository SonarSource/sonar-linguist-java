import java.util.zip.ZipFile

plugins {
  `java-library`
  application
  `maven-publish`
  jacoco
  signing
  alias(libs.plugins.sonarqube)
  alias(libs.plugins.artifactory)
}

group = "org.sonarsource.linguist"
version = providers.gradleProperty("version").get()

repositories {
  mavenCentral()
}

java {
  toolchain.languageVersion = JavaLanguageVersion.of(21)
  withSourcesJar()
  withJavadocJar()
}

tasks.withType<JavaCompile>().configureEach {
  options.encoding = "UTF-8"
  options.release = 21
}

tasks.withType<Javadoc>().configureEach {
  (options as StandardJavadocDocletOptions).apply {
    encoding = "UTF-8"
    addBooleanOption("Xdoclint:all,-missing", true)
  }
}

tasks.test {
  useJUnitPlatform()
  systemProperty("file.encoding", "UTF-8")
  systemProperty("user.language", "en")
  systemProperty("user.country", "US")
  testLogging.events("skipped", "failed")
}

tasks.jacocoTestReport {
  reports {
    xml.required = true
    html.required = true
  }
}

sonar {
  properties {
    property("sonar.projectKey", "SonarSource_sonar-linguist-java")
    property("sonar.organization", "sonarsource")
    property("sonar.projectName", "Linguist Java")
  }
}

tasks.sonar {
  dependsOn(tasks.jacocoTestReport)
}

description = "Java port of GitHub Linguist: file language detection and repository language statistics"

val tokenizerImplementation = layout.projectDirectory.file("src/main/java/org/sonar/linguist/DirectTokenizerLexer.java")
val classifierModelArtifacts = layout.projectDirectory.dir("src/main/resources/org/sonar/linguist")
val classifierModelGenerator = sourceSets.create("classifierModelGenerator")
configurations[classifierModelGenerator.implementationConfigurationName]
  .extendsFrom(configurations.implementation.get())
classifierModelGenerator.compileClasspath += sourceSets.main.get().output
classifierModelGenerator.runtimeClasspath += sourceSets.main.get().output
sourceSets.test {
  compileClasspath += classifierModelGenerator.output
  runtimeClasspath += classifierModelGenerator.output
}

dependencies {
  implementation(libs.icu4j)
  implementation(libs.jgit)
  implementation(libs.snakeyaml)

  compileOnly(libs.jsr305)

  testImplementation(platform(libs.junit.bom))
  testImplementation(libs.junit.jupiter)
  testRuntimeOnly(libs.junit.platform.launcher)
  testImplementation(libs.assertj.core)
}

tasks.compileTestJava {
  dependsOn(tasks.named(classifierModelGenerator.classesTaskName))
}

tasks.register<JavaExec>("updateClassifierModel") {
  group = "linguist model"
  description = "Downloads pinned upstream samples and regenerates the committed classifier model"

  inputs.file(layout.projectDirectory.file("upstream/manifest.json"))
  inputs.file(tokenizerImplementation)
  outputs.file(classifierModelArtifacts.file("classifier-model.bin"))
  outputs.file(classifierModelArtifacts.file("classifier-model.json"))

  classpath = classifierModelGenerator.runtimeClasspath
  mainClass = "org.sonar.linguist.ClassifierModelUpdater"
  args(
    layout.projectDirectory.file("upstream/manifest.json").asFile.absolutePath,
    layout.buildDirectory.dir("classifier-model").get().asFile.absolutePath,
    tokenizerImplementation.asFile.absolutePath,
    classifierModelArtifacts.asFile.absolutePath)

  doFirst {
    check(!gradle.startParameter.isOffline) {
      "updateClassifierModel requires network access and cannot run with --offline"
    }
  }
}

val verifyClassifierModel = tasks.register<JavaExec>("verifyClassifierModel") {
  group = "verification"
  description = "Verifies the committed classifier model without network access"

  val manifest = layout.projectDirectory.file("upstream/manifest.json")
  val model = classifierModelArtifacts.file("classifier-model.bin")
  val metadata = classifierModelArtifacts.file("classifier-model.json")
  dependsOn(tasks.named(classifierModelGenerator.classesTaskName))
  inputs.files(manifest, tokenizerImplementation, model, metadata)

  classpath = classifierModelGenerator.runtimeClasspath
  mainClass = "org.sonar.linguist.ClassifierModelVerifier"
  args(
    manifest.asFile.absolutePath,
    tokenizerImplementation.asFile.absolutePath,
    model.asFile.absolutePath,
    metadata.asFile.absolutePath)
}

tasks.check {
  dependsOn(verifyClassifierModel)
}

val verifyRuntimeJarContents = tasks.register("verifyRuntimeJarContents") {
  group = "verification"
  description = "Verifies required and excluded contents of the runtime JAR"

  val runtimeJar = tasks.named<Jar>("jar")
  dependsOn(runtimeJar)
  inputs.file(runtimeJar.flatMap { it.archiveFile })

  doLast {
    val entries = ZipFile(runtimeJar.get().archiveFile.get().asFile).use { jar ->
      jar.entries().asSequence().map { it.name }.toSet()
    }
    val packagePath = "org/sonar/linguist/"
    val required = setOf(
      "META-INF/LICENSE",
      "META-INF/LICENSE-GPL",
      "META-INF/NOTICE.txt",
      "META-INF/LICENSE-UPSTREAM",
      "META-INF/THIRD-PARTY-NOTICES.md",
      packagePath + "classifier-model.bin",
      packagePath + "classifier-model.json",
      packagePath + "documentation.yml",
      packagePath + "generic.yml",
      packagePath + "heuristics.yml",
      packagePath + "languages.yml",
      packagePath + "popular.yml",
      packagePath + "runtime-data.json",
      packagePath + "vendor.yml")
    val missing = required - entries
    check(missing.isEmpty()) { "Runtime JAR is missing required entries: ${missing.sorted()}" }

    val excludedClasses = setOf(
      packagePath + "ClassifierModelUpdater.class",
      packagePath + "ClassifierModelVerifier.class")
    val excluded = entries.filter { entry ->
      entry in excludedClasses
        || entry.startsWith("upstream/")
        || entry.startsWith("schemas/")
        || entry.startsWith("samples/")
        || entry.contains("/samples/")
        || entry.startsWith("training-samples/")
        || entry.contains("/training-samples/")
    }
    check(excluded.isEmpty()) { "Runtime JAR contains excluded entries: ${excluded.sorted()}" }
  }
}

tasks.check {
  dependsOn(verifyRuntimeJarContents)
}

application {
  mainClass = "org.sonar.linguist.Main"
}

// Upstream Linguist data is used under the MIT license: ship the license and the attribution with the JAR
tasks.withType<Jar>().configureEach {
  into("META-INF") {
    from("LICENSE", "LICENSE-GPL", "NOTICE.txt", "LICENSE-UPSTREAM", "THIRD-PARTY-NOTICES.md")
  }
}

publishing {
  publications {
    create<MavenPublication>("mavenJava") {
      from(components["java"])
      pom {
        name = "Linguist Java"
        description = project.description
        url = "https://github.com/SonarSource/sonar-linguist-java"
        licenses {
          license {
            name = "GNU Lesser General Public License, Version 3"
            url = "https://www.gnu.org/licenses/lgpl-3.0.html"
            distribution = "repo"
          }
        }
        developers {
          developer {
            id = "sonarsource"
            name = "SonarSource"
            organization = "SonarSource"
            organizationUrl = "https://www.sonarsource.com"
          }
        }
        scm {
          connection = "scm:git:https://github.com/SonarSource/sonar-linguist-java.git"
          developerConnection = "scm:git:ssh://git@github.com/SonarSource/sonar-linguist-java.git"
          url = "https://github.com/SonarSource/sonar-linguist-java"
        }
      }
    }
  }
}

// Signing keys are provided by the CI (ORG_GRADLE_PROJECT_signing*), only needed when deploying to Repox
signing {
  useInMemoryPgpKeys(
    providers.gradleProperty("signingKeyId").orNull,
    providers.gradleProperty("signingKey").orNull,
    providers.gradleProperty("signingPassword").orNull)
  setRequired { gradle.taskGraph.hasTask(":artifactoryPublish") }
  sign(publishing.publications)
}

tasks.withType<Sign>().configureEach {
  onlyIf { gradle.taskGraph.hasTask(":artifactoryPublish") }
}

artifactory {
  clientConfig.isIncludeEnvVars = true
  clientConfig.setEnvVarsExcludePatterns("*password*,*PASSWORD*,*secret*,*MAVEN_CMD_LINE_ARGS*,sun.java.command,*token*,*TOKEN*,*LOGIN*,*login*,*key*,*KEY*,*signing*")
  setContextUrl(System.getenv("ARTIFACTORY_URL"))
  publish {
    repository {
      repoKey = System.getenv("ARTIFACTORY_DEPLOY_REPO")
      username = System.getenv("ARTIFACTORY_DEPLOY_USERNAME")
      password = System.getenv("ARTIFACTORY_DEPLOY_ACCESS_TOKEN")
    }
    defaults {
      setProperties(mapOf<String, String?>(
        "build.name" to "sonar-linguist-java",
        "build.number" to System.getenv("BUILD_NUMBER"),
        "pr.branch.target" to System.getenv("GITHUB_BASE_REF"),
        "pr.number" to System.getenv("PULL_REQUEST"),
        "vcs.branch" to System.getenv("GITHUB_REF_NAME"),
        "vcs.revision" to System.getenv("GITHUB_SHA"),
        "version" to version.toString()))
      publications("mavenJava")
      setPublishPom(true)
      setPublishIvy(false)
    }
  }
  clientConfig.info.buildName = "sonar-linguist-java"
  clientConfig.info.buildNumber = System.getenv("BUILD_NUMBER")
  // The name of this variable is important because it's used by the delivery process when extracting version from Artifactory build info.
  clientConfig.info.addEnvironmentProperty("PROJECT_VERSION", version.toString())
}
