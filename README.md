# Java Container Signing and Font Provisioning

[![Product Page](https://img.shields.io/badge/Product%20Page-2865E0?style=for-the-badge&logo=appveyor&logoColor=white)](https://github.com/groupdocs-signature/GroupDocs.Signature-Docs)
[![Docs](https://img.shields.io/badge/Docs-2865E0?style=for-the-badge&logo=Hugo&logoColor=white)](https://docs.groupdocs.com/signature/java/)
[![Blog](https://img.shields.io/badge/Blog-2865E0?style=for-the-badge&logo=WordPress&logoColor=white)](https://blog.groupdocs.com/categories/groupdocs.signature-product-family/)
[![Free Support](https://img.shields.io/badge/Free%20Support-2865E0?style=for-the-badge&logo=Discourse&logoColor=white)](https://forum.groupdocs.com/c/signature/13)
[![Temporary License](https://img.shields.io/badge/Temporary%20License-2865E0?style=for-the-badge&logo=rocket&logoColor=white)](https://purchase.groupdocs.com/temp-license/100142)

## Overview

`sign-documents-in-docker-fonts-java` is a runnable Maven project that applies Latin and CJK text signatures to a PDF inside a Linux container and reports which fonts it could actually use. Two Dockerfiles ship with it: `Dockerfile` installs a font layer, `Dockerfile.nofonts` deliberately does not.

The rule the whole project exists to demonstrate: GroupDocs.Signature does not substitute a missing font. On Java the message reads `Specified font file was not found<name>`, and the call writes no document. The .NET build words the same failure as `Sign document error: Font <name> was not found`, so if you are handling this across platforms, do not match on the message text.

## Technology Stack

Java 11 or newer (the sample compiles at source and target 11 and runs on 8 through 17), GroupDocs.Signature for Java 26.5 from the GroupDocs Maven repository, and `eclipse-temurin:17-jre` as the runtime image. Font provisioning is Debian packages plus `fc-cache`; nothing in the code embeds a font file.

## Problem Statement

`eclipse-temurin:17-jre` ships 8 font files, all DejaVu, bundled for AWT. That is enough to make Latin signing work and not enough to notice the problem, which is what makes the JVM case awkward: the container signs English text quietly for months, then a Japanese customer name arrives and the job fails in production rather than in test.

Picking a font by scanning `/usr/share/fonts` for a filename does not fix it either. Debian's `fonts-noto-cjk` installs `NotoSansCJK-Regular.ttc`, whose family name is `Noto Sans CJK JP`. Matching filenames misses fonts that are present and reports families that will not resolve when passed to `SignatureFont.setFamilyName`.

There is also a JVM-specific trap on the way in. The GroupDocs Maven artifact is a signed fat jar, and loading it from a shaded or repackaged jar fails with `NoClassDefFoundError: com/groupdocs/signature/options/search/SearchOptions` unless the signature entries are removed - and removing `META-INF/*.SF|RSA|DSA` is not enough, because `MANIFEST.MF` carries roughly 19 MB of per-entry digests that must be truncated to the main section too. This sample avoids the whole issue by running against a plain classpath with a `dependency/` directory.

## Solution Overview

The sample asks the library which families work instead of assuming. For each candidate it attempts a throwaway signature into the temp directory, catches the failure, and keeps the first family that succeeds. Latin resolution is required; CJK resolution is optional and its absence downgrades to a warning and a skipped signature. After signing, the output is reopened and searched, so a claim that the CJK text is present is a read-back rather than an assumption.

## Prerequisites

- **JDK 11+** for the build; the runtime image is `eclipse-temurin:17-jre` and the sample runs on Java 8 through 17
- **Maven** with access to `https://releases.groupdocs.com/java/repo/`
- **Docker** to reproduce the two-image comparison
- **Licence (optional)** - set the path in the source or mount one and pass `LIC_PATH`; without it the run signs in evaluation mode and adds trial text the search will report

## Getting Started

### Installation

**Using Package Manager:**

```xml
<dependency>
    <groupId>com.groupdocs</groupId>
    <artifactId>groupdocs-signature</artifactId>
    <version>26.5</version>
</dependency>
```

The repository declaration matters as much as the dependency, since the artifact is not on Maven Central:

```xml
<repository>
    <id>groupdocs-artifact-repository</id>
    <url>https://releases.groupdocs.com/java/repo/</url>
</repository>
```

**Manual Installation:**

Clone the repository, run `mvn package`, and the build produces `target/docker-fonts-demo.jar` plus a `target/dependency` directory that the container entry point puts on the classpath.

### Configuration

Nothing needs configuring for a first run. To use a licence in a container, mount it rather than baking a path into the image:

```bash
docker run --rm -v "$PWD/Result:/app/Result" \
           -v "/path/to/licences:/lic:ro" -e LIC_PATH=/lic/GroupDocs.Total.lic \
           groupdocs-signature-fonts-java
```

## Repository Structure

```
sign-documents-in-docker-fonts-java/
│
├── pom.xml
├── Dockerfile
├── Dockerfile.nofonts
├── .dockerignore
├── documents/
│   └── sample.pdf
└── src/main/java/com/groupdocs/demo/
    └── DockerFontsDemo.java
```

### File Descriptions

- **DockerFontsDemo.java** - the entire sample: inventory, resolution, signing, read-back, and the deliberate failure
- **pom.xml** - pins GroupDocs.Signature 26.5, sets compiler level 11, and copies dependencies for the container classpath
- **Dockerfile** - multi-stage build with the font layer in the runtime image
- **Dockerfile.nofonts** - the same image minus the font layer, kept so the failure stays reproducible
- **documents/sample.pdf** - the input the sample signs

## Code Implementation

### Implementation: Returns the font files visible in the standard system and per-user font directories

The inventory runs first so the log answers "what did this image actually have" without opening a shell in the container. It probes Linux, Windows and macOS locations in one pass and skips what is not there.

```java
String home = System.getProperty("user.home", "");
String windir = System.getenv("WINDIR");
List<String> roots = new ArrayList<>(Arrays.asList(
        "/usr/share/fonts",
        "/usr/local/share/fonts",
        home + "/.fonts",
        home + "/.local/share/fonts",
        "/System/Library/Fonts",
        "/Library/Fonts"));
if (windir != null && !windir.isEmpty()) {
    roots.add(windir + File.separator + "Fonts");
}
```

The walk itself tolerates unreadable directories rather than aborting the scan:

```java
List<Path> files = new ArrayList<>();
for (String root : roots) {
    Path dir = Paths.get(root);
    if (!Files.isDirectory(dir)) {
        continue;
    }
    try (Stream<Path> walk = Files.walk(dir)) {
        files.addAll(walk.filter(Files::isRegularFile)
                .filter(DockerFontsDemo::isFontFile)
                .collect(Collectors.toList()));
    } catch (IOException ex) {
        // A font directory we may not read tells us nothing; keep scanning the rest.
    }
}
return files;
```

Key components: `Files.walk` over a fixed root list, filtered by extension.

Output: a count and a short sample line, for example `font files on disk: 8`.

The filesystem is used on purpose rather than `GraphicsEnvironment`, which needs a working AWT or headless toolkit and is itself a common container failure.

### Implementation: Attempts a throwaway signature with one font family

This is the probe every resolution decision rests on. It signs into the temp directory, always deletes the scratch file, and returns the error message instead of throwing.

```java
File scratch = new File(System.getProperty("java.io.tmpdir"),
        "gd-font-probe-" + UUID.randomUUID().toString().replace("-", "") + ".pdf");
try {
    Signature signature = new Signature(sourcePath);
    TextSignOptions options = new TextSignOptions("probe");
    options.setLeft(10);
    options.setTop(10);
    options.setWidth(60);
    options.setHeight(20);
    SignatureFont font = new SignatureFont();
    font.setFamilyName(familyName);
    font.setSize(10);
    options.setFont(font);
    signature.sign(scratch.getAbsolutePath(), options);
    return null;
}
```

The cleanup falls back to `deleteOnExit` when the delete fails, which matters on Windows where the file may still be held:

```java
catch (Exception ex) {
    return ex.getMessage();
} finally {
    if (scratch.exists() && !scratch.delete()) {
        scratch.deleteOnExit();
    }
}
```

Key components: `Signature.sign` into a scratch path, `SignatureFont.setFamilyName`.

Output: `null` when the family works, otherwise the library's error message.

### Implementation: Returns the first candidate family GroupDocs can actually use

Resolution is a loop over the probe. The Latin list starts with `DejaVu Sans` because that is what the container installs, and ends with `Arial` and `Verdana` for developer machines.

```java
for (String candidate : candidates) {
    if (tryFamily(sourcePath, candidate) == null) {
        return candidate;
    }
}
return null;
```

Key components: ordered candidate arrays, most portable first.

Output: the resolved family name, or `null` when nothing works.

### Implementation: Builds a text signature option set, attaching a font only when a family was resolved

The conditional is the load-bearing part: naming an absent family is what raises the exception, so when nothing resolved the font is simply left unset.

```java
TextSignOptions options = new TextSignOptions(text);
options.setLeft(50);
options.setTop(top);
options.setWidth(280);
options.setHeight(40);
if (familyName != null) {
    SignatureFont font = new SignatureFont();
    font.setFamilyName(familyName);
    font.setSize(16);
    options.setFont(font);
}
return options;
```

Key components: `TextSignOptions` geometry plus an optional `SignatureFont`.

Output: options ready to pass to `sign`.

Leaving the font unset is not a rescue on a fontless image - GroupDocs then requests Times New Roman and fails the same way.

### Implementation: Signs the document with a text signature per resolved family

Both signatures go through one `sign` call, and the CJK one is added only when a CJK family resolved.

```java
Signature signature = new Signature(sourcePath);

List<SignOptions> options = new ArrayList<>();
options.add(buildTextOptions(LATIN_TEXT, latinFamily, 50));

// Without a CJK-capable font the glyphs cannot be embedded, so skip rather than throw.
if (cjkFamily != null) {
    options.add(buildTextOptions(CJK_TEXT, cjkFamily, 120));
}

SignResult result = signature.sign(outputPath, options);
return result.getSucceeded().size();
```

Key components: `Signature.sign(String, List<SignOptions>)`, `SignResult.getSucceeded()`.

Output: the number of signatures written - 2 on the font image, 1 when only Latin resolved.

### Implementation: Reads every text signature back out of the signed document

CJK rendered as empty boxes does not raise anything, so the read-back is what separates "signed" from "signed correctly".

```java
Signature signature = new Signature(signedPath);
TextSearchOptions options = new TextSearchOptions();
options.setAllPages(true);

List<TextSignature> found = signature.search(TextSignature.class, options);
List<String> texts = new ArrayList<>();
for (TextSignature item : found) {
    texts.add(item.getText());
}
return texts;
```

Key components: `TextSearchOptions.setAllPages`, `Signature.search(Class, options)`.

Output: the recovered strings, compared against what was signed.

### Why not embed a font file with the application?

Because the family still has to resolve through the platform. Shipping a TTF next to the jar does nothing unless the file lands in a directory fontconfig indexes and `fc-cache` has run over it. Installing the Debian packages is the shortest path to that, and it keeps the font licence question with the distribution rather than with your build.

## Best Practices

I kept a hard-coded `Arial` in the first version of this and it survived every test on my Windows machine, then failed on the first container run. Resolve once at startup and cache the two family names; each probe writes a real PDF, so doing it per document is waste rather than diligence. Log the affected count and the resolved families together, because a count of zero with a resolved family means something different from a count of zero with none. Keep `Dockerfile.nofonts` in the repository after the first fix - it is the fastest way to reproduce the failure when someone changes base images later. And when you run this on Java 8, remember the sample compiles at level 11: drop the compiler properties before assuming the code is at fault.

## Additional Resources

- [**Step-by-step use case guide in the documentation**](https://docs.groupdocs.com/signature/java/use-cases/signing-documents-linux-container-fonts/) - the six methods in order, with the base-image font counts
- [**In-depth blog article about this project**](https://blog.groupdocs.com/signature/signing-documents-linux-container-fonts-java/) - why a JVM image fails later than a .NET one, and what that costs
- [**Signing documents**](https://docs.groupdocs.com/signature/java/signing/) - the option surface behind `TextSignOptions` and `SignatureFont`
- [**Searching for signatures**](https://docs.groupdocs.com/signature/java/searching/) - reference for the read-back step
- [**System requirements**](https://docs.groupdocs.com/signature/java/system-requirements/) - supported JDKs and platforms

## Keywords

`linux`, `sign`, `documents`, `pdf`, `docker`, `fonts`, `groupdocs signature`, `java signing`, `text signature`, `container fonts`, `fontconfig`, `fonts-dejavu-core`, `fonts-noto-cjk`, `liberation fonts`, `cjk signature`, `SignatureFont`, `TextSignOptions`, `eclipse-temurin`, `maven`, `font resolution`, `signed fat jar`, `jvm container`, `pdf signing linux`, `dockerfile`

## Support

[Free Support Forum](https://forum.groupdocs.com/c/signature/13) | [Temporary License](https://purchase.groupdocs.com/temp-license/100142) | [API Reference](https://reference.groupdocs.com/signature/java/)
