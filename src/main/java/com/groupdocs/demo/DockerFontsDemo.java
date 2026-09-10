package com.groupdocs.demo;

// Topic: Signing documents inside a Linux container - font provisioning, resolving a family that exists, and a working Dockerfile.
// Most container base images ship zero fonts, and GroupDocs.Signature does NOT substitute a missing one: naming a family that is
// not installed throws "Specified font file was not found<name>" and produces no document. (The .NET build words the same error
// differently - "Sign document error: Font <name> was not found" - so do not match on the message text.) Leaving the font unset does not
// help either - GroupDocs then requests its own default (Times New Roman) and fails identically - so a fontless image cannot apply
// a text signature at all. This sample inventories the fonts on disk, asks the library which candidate families it can actually
// use, signs a PDF with Latin and CJK text signatures, reads both back, and shows the missing-font exception inside a catch.
// Run it against Dockerfile (fonts installed) and Dockerfile.nofonts (none) to see both outcomes.

import com.groupdocs.signature.Signature;
import com.groupdocs.signature.domain.SignResult;
import com.groupdocs.signature.domain.SignatureFont;
import com.groupdocs.signature.domain.signatures.TextSignature;
import com.groupdocs.signature.licensing.License;
import com.groupdocs.signature.options.search.TextSearchOptions;
import com.groupdocs.signature.options.sign.SignOptions;
import com.groupdocs.signature.options.sign.TextSignOptions;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public final class DockerFontsDemo {

    private static final String DOCS = "documents";
    private static final String RESULT = "Result";
    private static final String SOURCE_PDF = DOCS + File.separator + "sample.pdf";
    private static final String SIGNED_PDF = RESULT + File.separator + "signed.pdf";

    // Preference order, most portable first. The container installs the first entry of each list;
    // the trailing entries are what a Windows or macOS developer box is likely to have instead.
    private static final String[] LATIN_CANDIDATES = { "DejaVu Sans", "Liberation Sans", "Arial", "Verdana" };
    private static final String[] CJK_CANDIDATES = {
        "Noto Sans CJK JP", "Noto Sans CJK SC", "Noto Sans CJK", "Noto Sans JP",
        "MS Gothic", "Yu Gothic", "SimSun", "Malgun Gothic",
    };

    // A family that exists nowhere, used to show the failure mode on purpose.
    private static final String ABSENT_FAMILY = "No Such Font Family";

    private static final String LATIN_TEXT = "Approved by GroupDocs";

    // Japanese for "approved". Kept as escapes so this file stays ASCII: the build compiles without an
    // explicit -encoding flag, so a UTF-8 literal would be misread under a non-UTF-8 default charset.
    // Never written to stdout either - the Windows console codepage cannot encode it.
    private static final String CJK_TEXT = "\u627F\u8A8D\u6E08\u307F";

    private static final String[] FONT_EXTENSIONS = { ".ttf", ".otf", ".ttc", ".pfb" };

    public static void main(String[] args) throws Exception {
        new File(DOCS).mkdirs();
        new File(RESULT).mkdirs();
        applyLicense();

        System.out.println("=== GroupDocs.Signature - signing in a container: font report ===");
        System.out.println("[env] os        : " + System.getProperty("os.name") + " " + System.getProperty("os.version"));
        System.out.println("[env] container : " + (isContainer() ? "yes" : "no"));

        List<Path> fontFiles = findFontFiles();
        System.out.println("[fonts] font files on disk: " + fontFiles.size());
        System.out.println("[fonts] sample: " + summarise(fontFiles, 6));

        if (!new File(SOURCE_PDF).exists()) {
            System.err.println("Missing source document: " + new File(SOURCE_PDF).getAbsolutePath());
            System.exit(1);
        }

        String latinFamily = resolveUsableFamily(SOURCE_PDF, LATIN_CANDIDATES);
        String cjkFamily = resolveUsableFamily(SOURCE_PDF, CJK_CANDIDATES);
        System.out.println("[fonts] latin family resolved: "
                + (latinFamily == null ? "(none - falling back to the platform default)" : latinFamily));
        System.out.println("[fonts] cjk family resolved  : "
                + (cjkFamily == null ? "(none - CJK signature will be skipped)" : cjkFamily));

        // The teaching moment: what actually happens when the image has no fonts.
        System.out.println("[demo] signing with '" + ABSENT_FAMILY + "' on purpose...");
        System.out.println("[demo] -> " + describeMissingFontFailure(SOURCE_PDF));

        int applied;
        try {
            applied = signWithResolvedFonts(SOURCE_PDF, SIGNED_PDF, latinFamily, cjkFamily);
        } catch (Exception ex) {
            // Reached when the image has no usable font at all. Omitting the font does not help:
            // GroupDocs then asks for its own default family (Times New Roman) and fails the same way.
            System.err.println("[sign] FAILED: " + ex.getMessage());
            System.err.println("[sign] this image cannot render text signatures - it has no usable font.");
            System.err.println("[sign] there is no code-level workaround: install at least one font in the image.");
            System.err.println("[sign] minimum fix: apt-get install -y fonts-dejavu-core (add fonts-noto-cjk for CJK).");
            System.exit(3);
            return;
        }
        System.out.println("[sign] text signatures applied: " + applied);

        List<String> recovered = searchTextSignatures(SIGNED_PDF);
        System.out.println("[search] text signatures found: " + recovered.size());
        System.out.println("[search] latin text recovered : " + (recovered.contains(LATIN_TEXT) ? "yes" : "no"));
        System.out.println("[search] cjk text recovered   : " + (recovered.contains(CJK_TEXT) ? "yes" : "no"));
        if (cjkFamily == null) {
            System.out.println("[warn] no CJK font in this image - install fonts-noto-cjk (see Dockerfile) to sign CJK text.");
        }

        System.out.println("[result] " + new File(SIGNED_PDF).getAbsolutePath());
        System.exit(recovered.isEmpty() ? 2 : 0);
    }

    private static void applyLicense() {
        // Point this at your .lic file to remove evaluation limits.
        // Get a free temporary licence: https://purchase.groupdocs.com/temporary-license
        final String licensePath = "REPLACE_WITH_YOUR_LICENSE_PATH";

        // In a container the path above is baked in at build time, which is rarely what you want.
        // LIC_PATH lets the licence be mounted and named at run time instead:
        //   docker run --rm -v "/path/to/licences:/lic:ro" -e LIC_PATH=/lic/GroupDocs.Total.lic <image>
        String fromEnv = System.getenv("LIC_PATH");

        String resolved = null;
        if (new File(licensePath).exists()) {
            resolved = licensePath;
        } else if (fromEnv != null && !fromEnv.isEmpty() && new File(fromEnv).exists()) {
            resolved = fromEnv;
        }

        if (resolved != null) {
            new License().setLicense(resolved);
            System.out.println("[license] applied");
        } else {
            // Evaluation mode still signs, but it adds its own trial text to the page - which the
            // search below will report alongside (or instead of) yours. Licence it for a clean run.
            System.out.println("[license] no licence set - running in evaluation mode");
        }
    }

    /**
     * Detects whether the process is running inside a container.
     *
     * <p>Checks for the Docker marker file, then for a container runtime named in PID 1's cgroup
     * entry, which also catches containerd, podman and Kubernetes. Always false on Windows.
     */
    private static boolean isContainer() {
        if (new File("/.dockerenv").exists()) {
            return true;
        }
        Path cgroup = Paths.get("/proc/1/cgroup");
        if (!Files.exists(cgroup)) {
            return false;
        }
        try {
            String text = new String(Files.readAllBytes(cgroup), StandardCharsets.UTF_8);
            return text.contains("docker") || text.contains("containerd") || text.contains("kubepods");
        } catch (IOException ex) {
            return false;
        }
    }

    /**
     * Returns the font files visible in the standard system and per-user font directories.
     *
     * <p>Probes the Linux, Windows and macOS locations in one pass and ignores directories that do
     * not exist, so the same call is meaningful on a developer laptop and inside a slim base image.
     * Uses the filesystem rather than {@code GraphicsEnvironment}, which needs a usable AWT/headless
     * toolkit and is itself a common container failure.
     */
    private static List<Path> findFontFiles() {
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
    }

    private static boolean isFontFile(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        for (String ext : FONT_EXTENSIONS) {
            if (name.endsWith(ext)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Builds a short, ASCII-safe sample of the font files found, for logging.
     *
     * <p>Prints distinct file stems rather than resolved family names, and says how many were not
     * shown, so a container with two fonts and a laptop with four hundred both give one readable line.
     */
    private static String summarise(List<Path> fontFiles, int max) {
        if (fontFiles.isEmpty()) {
            return "(none - this image has no fonts installed)";
        }
        Set<String> names = new LinkedHashSet<>();
        for (Path file : fontFiles) {
            String name = file.getFileName().toString();
            int dot = name.lastIndexOf('.');
            names.add(dot > 0 ? name.substring(0, dot) : name);
            if (names.size() == max) {
                break;
            }
        }
        String head = String.join(", ", names);
        int remaining = fontFiles.size() - names.size();
        return remaining > 0 ? head + " (+" + remaining + " more)" : head;
    }

    /**
     * Returns the first candidate family GroupDocs can actually use, or {@code null} if none work.
     *
     * <p>Resolution asks the library rather than guessing from file names. Font files rarely carry
     * the family string a caller must pass - Debian's {@code fonts-noto-cjk} installs
     * {@code NotoSansCJK-Regular.ttc}, whose family is "Noto Sans CJK JP" - so a filename match both
     * misses real fonts and claims fonts that will not resolve.
     */
    private static String resolveUsableFamily(String sourcePath, String[] candidates) {
        for (String candidate : candidates) {
            if (tryFamily(sourcePath, candidate) == null) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * Attempts a throwaway signature with one font family.
     *
     * <p>Returns {@code null} when the family works, otherwise the error message. Writes to a
     * temporary file that is always deleted, so probing never touches {@code Result/}.
     */
    private static String tryFamily(String sourcePath, String familyName) {
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
        } catch (Exception ex) {
            return ex.getMessage();
        } finally {
            if (scratch.exists() && !scratch.delete()) {
                scratch.deleteOnExit();
            }
        }
    }

    private static String describeMissingFontFailure(String sourcePath) {
        String message = tryFamily(sourcePath, ABSENT_FAMILY);
        return message == null ? "no exception - this platform substituted a font instead of failing" : message;
    }

    /**
     * Signs the document with a text signature per resolved family, skipping CJK when none exists.
     *
     * <p>Passing {@code null} for a family omits {@link SignatureFont} entirely so GroupDocs uses its
     * own default rather than a name it cannot resolve. Returns the number of signatures written.
     */
    private static int signWithResolvedFonts(String sourcePath, String outputPath,
                                             String latinFamily, String cjkFamily) throws Exception {
        Signature signature = new Signature(sourcePath);

        List<SignOptions> options = new ArrayList<>();
        options.add(buildTextOptions(LATIN_TEXT, latinFamily, 50));

        // Without a CJK-capable font the glyphs cannot be embedded at all, so skip rather than throw.
        if (cjkFamily != null) {
            options.add(buildTextOptions(CJK_TEXT, cjkFamily, 120));
        }

        SignResult result = signature.sign(outputPath, options);
        return result.getSucceeded().size();
    }

    /**
     * Builds a text signature option set, attaching a font only when a family was resolved.
     *
     * <p>The font is left unset when {@code familyName} is {@code null}; naming a family that is not
     * installed is what raises the "Font ... was not found" exception.
     */
    private static TextSignOptions buildTextOptions(String text, String familyName, int top) {
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
    }

    /**
     * Reads every text signature back out of the signed document.
     *
     * <p>Searches all pages and returns the recovered strings, which is how the sample proves the CJK
     * text survived the round-trip rather than merely appearing to.
     */
    private static List<String> searchTextSignatures(String signedPath) throws Exception {
        Signature signature = new Signature(signedPath);
        TextSearchOptions options = new TextSearchOptions();
        options.setAllPages(true);

        List<TextSignature> found = signature.search(TextSignature.class, options);
        List<String> texts = new ArrayList<>();
        for (TextSignature item : found) {
            texts.add(item.getText());
        }
        return texts;
    }
}
