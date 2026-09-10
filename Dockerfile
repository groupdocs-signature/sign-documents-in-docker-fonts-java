# Signing documents with GroupDocs.Signature for Java inside a Linux container.
#
# The point of this file is the font layer. Build this image and Dockerfile.nofonts and compare:
# without it the sample cannot sign at all. GroupDocs.Signature does not substitute a missing
# family - it raises "Specified font file was not found<name>" - and dropping the font does not
# rescue you either, because it then asks for its own default and fails identically. On a fontless
# base image every text signature fails, so the font layer is required, not an optimisation.
#
# Run on Java 8-17. GroupDocs.Signature for Java is Java-8 bytecode and its imaging breaks on
# JDK 25 with "Cannot open an image. The image size can not be 0!".

# ---- build ----
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /src

# Resolve dependencies first so edits to the source do not re-download the GroupDocs jar.
COPY pom.xml ./
RUN mvn -B -q dependency:go-offline

COPY src ./src
RUN mvn -B -q package -DskipTests

# Strip the jar signature. The GroupDocs Java artifact is a signed fat jar whose signature manifest
# is large enough that the JVM refuses to load classes from it:
#   java.lang.NoClassDefFoundError: com/groupdocs/signature/options/search/SearchOptions
# Removing META-INF/*.SF, *.RSA and *.DSA fixes it. This bites in a container specifically, because
# a local Maven/IDE run often resolves an already-unpacked or differently-verified copy.
# Deleting META-INF/*.SF|RSA|DSA is NOT enough on its own: MANIFEST.MF still carries a per-entry
# SHA digest for every class (~19 MB of it), and that is what the loader chokes on. The manifest
# must also be truncated to its main section - everything up to the first blank line.
RUN apt-get update && apt-get install -y --no-install-recommends zip unzip \
    && for j in /src/target/dependency/*.jar; do \
         zip -d "$j" 'META-INF/*.SF' 'META-INF/*.RSA' 'META-INF/*.DSA' >/dev/null 2>&1 || true; \
         work=$(mktemp -d); \
         ( cd "$work" \
           && unzip -o -q "$j" META-INF/MANIFEST.MF \
           && sed -n '1,/^[[:space:]]*$/p' META-INF/MANIFEST.MF > META-INF/MANIFEST.trimmed \
           && mv META-INF/MANIFEST.trimmed META-INF/MANIFEST.MF \
           && zip -q "$j" META-INF/MANIFEST.MF ); \
         rm -rf "$work"; \
       done \
    && apt-get clean && rm -rf /var/lib/apt/lists/*

# ---- runtime ----
FROM eclipse-temurin:17-jre

# Fonts. The base image ships none, so every text signature fails until these are installed.
#   fontconfig            the resolver itself, plus fc-cache / fc-list for debugging
#   fonts-dejavu-core     Latin/Greek/Cyrillic workhorse; the sample resolves "DejaVu Sans"
#   fonts-liberation      metric-compatible stand-ins for Arial / Times New Roman / Courier New,
#                         which is what documents authored on Windows actually reference
#   fonts-noto-cjk        Chinese, Japanese and Korean; the sample resolves "Noto Sans CJK JP"
RUN apt-get update && apt-get install -y --no-install-recommends \
        fontconfig \
        fonts-dejavu-core \
        fonts-liberation \
        fonts-noto-cjk \
    && fc-cache -f \
    && apt-get clean \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY --from=build /src/target/docker-fonts-demo.jar ./docker-fonts-demo.jar
COPY --from=build /src/target/dependency ./dependency
COPY documents ./documents

# documents/ and Result/ are resolved relative to the working directory.
# Mount a volume over /app/Result to keep the signed file after the container exits, and mount a
# licence rather than baking one in:
#   docker run --rm -v "$PWD/Result:/app/Result" \
#              -v "/path/to/licences:/lic:ro" -e LIC_PATH=/lic/GroupDocs.Total.lic \
#              groupdocs-signature-fonts-java
ENTRYPOINT ["java", "-cp", "/app/docker-fonts-demo.jar:/app/dependency/*", "com.groupdocs.demo.DockerFontsDemo"]
