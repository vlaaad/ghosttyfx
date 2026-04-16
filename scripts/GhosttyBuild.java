import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public final class GhosttyBuild {
    private static final String GHOSTTY_SUBMODULE = "ghostty";
    private static final String JEXTRACT_TARGET_PACKAGE = "io.github.vlaaad.ghostty.bindings";
    private static final String JEXTRACT_HEADER_CLASS = "ghostty_vt_h";
    private static final HttpClient HTTP = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build();
    private static final Map<String, PlatformSpec> PLATFORMS = Map.of(
        "linux-x86_64",
        new PlatformSpec(
            "linux-x86_64",
            new DownloadSpec(
                "zig",
                "https://ziglang.org/download/0.15.2/zig-x86_64-linux-0.15.2.tar.xz",
                "02aa270f183da276e5b5920b1dac44a63f1a49e55050ebde3aecc9eb82f93239",
                "zig-x86_64-linux-0.15.2.tar.xz",
                "zig-x86_64-linux-0.15.2",
                "zig",
                ArchiveType.TAR
            ),
            new DownloadSpec(
                "jextract",
                "https://download.java.net/java/early_access/jextract/25/2/openjdk-25-jextract+2-4_linux-x64_bin.tar.gz",
                "d0cc481abc1adb16fb9514e1c5e0bfc08d38c29228bece667fb5054ceaffaa42",
                "openjdk-25-jextract+2-4_linux-x64_bin.tar.gz",
                "jextract-25",
                "bin/jextract",
                ArchiveType.TAR
            ),
            "ghostty/zig-out/lib/libghostty-vt.so.0.1.0",
            "libghostty-vt-linux-x86_64.so"
        ),
        "macos-x86_64",
        new PlatformSpec(
            "macos-x86_64",
            new DownloadSpec(
                "zig",
                "https://ziglang.org/download/0.15.2/zig-x86_64-macos-0.15.2.tar.xz",
                "375b6909fc1495d16fc2c7db9538f707456bfc3373b14ee83fdd3e22b3d43f7f",
                "zig-x86_64-macos-0.15.2.tar.xz",
                "zig-x86_64-macos-0.15.2",
                "zig",
                ArchiveType.TAR
            ),
            new DownloadSpec(
                "jextract",
                "https://download.java.net/java/early_access/jextract/25/2/openjdk-25-jextract+2-4_macos-x64_bin.tar.gz",
                "6ae7a46e7e7b56f077ab72623c0a894a8d525d5b698c90785b97c241f95a99b1",
                "openjdk-25-jextract+2-4_macos-x64_bin.tar.gz",
                "jextract-25",
                "bin/jextract",
                ArchiveType.TAR
            ),
            "ghostty/zig-out/lib/libghostty-vt.0.1.0.dylib",
            "libghostty-vt-macos-x86_64.dylib"
        ),
        "macos-aarch64",
        new PlatformSpec(
            "macos-aarch64",
            new DownloadSpec(
                "zig",
                "https://ziglang.org/download/0.15.2/zig-aarch64-macos-0.15.2.tar.xz",
                "3cc2bab367e185cdfb27501c4b30b1b0653c28d9f73df8dc91488e66ece5fa6b",
                "zig-aarch64-macos-0.15.2.tar.xz",
                "zig-aarch64-macos-0.15.2",
                "zig",
                ArchiveType.TAR
            ),
            new DownloadSpec(
                "jextract",
                "https://download.java.net/java/early_access/jextract/25/2/openjdk-25-jextract+2-4_macos-aarch64_bin.tar.gz",
                "3dd1dd1bde059d271739e2cc2290c64f93f85488c86c01e566c0e374eece798f",
                "openjdk-25-jextract+2-4_macos-aarch64_bin.tar.gz",
                "jextract-25",
                "bin/jextract",
                ArchiveType.TAR
            ),
            "ghostty/zig-out/lib/libghostty-vt.0.1.0.dylib",
            "libghostty-vt-macos-aarch64.dylib"
        ),
        "windows-x86_64",
        new PlatformSpec(
            "windows-x86_64",
            new DownloadSpec(
                "zig",
                "https://ziglang.org/download/0.15.2/zig-x86_64-windows-0.15.2.zip",
                "3a0ed1e8799a2f8ce2a6e6290a9ff22e6906f8227865911fb7ddedc3cc14cb0c",
                "zig-x86_64-windows-0.15.2.zip",
                "zig-x86_64-windows-0.15.2",
                "zig.exe",
                ArchiveType.ZIP
            ),
            new DownloadSpec(
                "jextract",
                "https://download.java.net/java/early_access/jextract/25/2/openjdk-25-jextract+2-4_windows-x64_bin.tar.gz",
                "b03533eb6b249a154752b7c7bf93cdb8c147cf2f9699422e615e84b7fb652872",
                "openjdk-25-jextract+2-4_windows-x64_bin.tar.gz",
                "jextract-25",
                "bin/jextract.bat",
                ArchiveType.TAR
            ),
            "ghostty/zig-out/bin/ghostty-vt.dll",
            "libghostty-vt-windows-x86_64.dll"
        )
    );

    private GhosttyBuild() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 4 || !"generate-platform".equals(args[0])) {
            throw new IllegalArgumentException("usage: generate-platform <platform-id> <artifact-id> <build-dir>");
        }

        var repo = Path.of("").toAbsolutePath().normalize();
        var platform = platform(args[1]);
        var artifactId = args[2];
        var buildDir = Path.of(args[3]).toAbsolutePath().normalize();
        var hostPlatform = hostPlatformId();
        if (!platform.id.equals(hostPlatform)) {
            throw new IllegalStateException(
                "requested platform '" + platform.id + "' does not match host '" + hostPlatform + "'"
            );
        }

        ensureGhosttySubmodule(repo);
        var ghosttyCommit = capture(repo.resolve(GHOSTTY_SUBMODULE), "git", "rev-parse", "HEAD").trim();
        var outputs = outputs(buildDir);
        if (isUpToDate(outputs, platform, artifactId, ghosttyCommit)) {
            System.out.println("ghosttyfx artifact is up to date: " + outputs.artifactDir);
            return;
        }

        var zigHome = ensureTool(repo, platform.zig);
        var jextractHome = ensureTool(repo, platform.jextract);
        buildGhostty(repo, platform, zigHome);
        generateArtifact(repo, outputs, platform, artifactId, ghosttyCommit, jextractHome);
    }

    private static PlatformSpec platform(String id) {
        var platform = PLATFORMS.get(id);
        if (platform == null) {
            throw new IllegalArgumentException("unsupported platform: " + id);
        }
        return platform;
    }

    private static String hostPlatformId() {
        var os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        var arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        arch = switch (arch) {
            case "x8664", "amd64", "x86_64" -> "x86_64";
            case "aarch64", "arm64" -> "aarch64";
            default -> arch;
        };
        if (os.contains("win")) {
            return "windows-" + arch;
        }
        if (os.contains("mac") || os.contains("darwin")) {
            return "macos-" + arch;
        }
        if (os.contains("linux")) {
            return "linux-" + arch;
        }
        throw new IllegalStateException("unsupported host os: " + System.getProperty("os.name"));
    }

    private static void ensureGhosttySubmodule(Path repo) throws Exception {
        run(repo, Map.of(), "git", "submodule", "sync", "--recursive", GHOSTTY_SUBMODULE);
        var status = capture(repo, "git", "submodule", "status", "--", GHOSTTY_SUBMODULE).strip();
        if (status.isEmpty() || status.charAt(0) == '-') {
            run(repo, Map.of(), "git", "submodule", "update", "--init", "--recursive", GHOSTTY_SUBMODULE);
            return;
        }

        var ghostty = repo.resolve(GHOSTTY_SUBMODULE);
        run(ghostty, Map.of(), "git", "submodule", "sync", "--recursive");
        run(ghostty, Map.of(), "git", "submodule", "update", "--init", "--recursive");
    }

    private static Path ensureTool(Path repo, DownloadSpec spec) throws Exception {
        var toolsRoot = repo.resolve(".tools").resolve(spec.kind);
        var toolHome = toolsRoot.resolve(spec.rootDirName);
        var executable = toolHome.resolve(spec.executableRelativePath.replace("/", java.io.File.separator));
        if (Files.isRegularFile(executable)) {
            return toolHome;
        }

        Files.createDirectories(toolsRoot);
        var downloads = repo.resolve(".tools").resolve("downloads");
        Files.createDirectories(downloads);
        var archive = downloads.resolve(spec.archiveFileName);
        downloadIfNeeded(spec, archive);

        deleteDirectory(toolHome);
        extractArchive(spec, archive, toolsRoot);
        if (!Files.isRegularFile(executable)) {
            throw new IllegalStateException("missing executable after extraction: " + executable);
        }
        return toolHome;
    }

    private static void downloadIfNeeded(DownloadSpec spec, Path archive) throws Exception {
        if (Files.isRegularFile(archive) && spec.sha256.equalsIgnoreCase(sha256(archive))) {
            return;
        }

        Files.deleteIfExists(archive);
        System.out.println("downloading " + spec.url);
        var request = HttpRequest.newBuilder(URI.create(spec.url)).GET().build();
        var response = HTTP.send(request, HttpResponse.BodyHandlers.ofFile(archive));
        if (response.statusCode() != 200) {
            Files.deleteIfExists(archive);
            throw new IOException("download failed for " + spec.url + ": HTTP " + response.statusCode());
        }

        var actual = sha256(archive);
        if (!spec.sha256.equalsIgnoreCase(actual)) {
            Files.deleteIfExists(archive);
            throw new IOException("sha256 mismatch for " + archive + ": expected " + spec.sha256 + ", got " + actual);
        }
    }

    private static void extractArchive(DownloadSpec spec, Path archive, Path destination) throws Exception {
        System.out.println("extracting " + archive.getFileName());
        if (spec.archiveType == ArchiveType.ZIP) {
            extractZip(archive, destination);
            return;
        }
        run(destination, Map.of(), "tar", "-xf", archive.toString(), "-C", destination.toString());
    }

    private static void extractZip(Path archive, Path destination) throws IOException {
        try (var input = Files.newInputStream(archive);
             var zip = new ZipInputStream(input)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                var resolved = destination.resolve(entry.getName()).normalize();
                if (!resolved.startsWith(destination)) {
                    throw new IOException("zip entry escapes destination: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(resolved);
                    continue;
                }
                Files.createDirectories(Objects.requireNonNull(resolved.getParent()));
                Files.copy(zip, resolved, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    private static void buildGhostty(Path repo, PlatformSpec platform, Path zigHome) throws Exception {
        ensureHostToolchain();
        var environment = buildEnvironment(repo, zigHome);
        run(repo.resolve(GHOSTTY_SUBMODULE), environment, zigExecutable(zigHome).toString(), "build", "-Dapp-runtime=none", "-Demit-lib-vt=true");

        var builtLibrary = repo.resolve(platform.builtLibraryRelativePath);
        if (!Files.isRegularFile(builtLibrary)) {
            throw new IllegalStateException("expected built library at " + builtLibrary);
        }
    }

    private static Map<String, String> buildEnvironment(Path repo, Path zigHome) throws Exception {
        var environment = new java.util.LinkedHashMap<String, String>();
        if (isWindows()) {
            environment.putAll(windowsToolchainEnvironment());
        }
        var basePath = environment.getOrDefault("PATH", System.getenv().getOrDefault("PATH", ""));
        environment.put("PATH", zigHome + java.io.File.pathSeparator + basePath);
        var localCache = repo.resolve(".zig-local-cache");
        var globalCache = repo.resolve(".zig-global-cache");
        var tmpDir = repo.resolve(".tmp");
        Files.createDirectories(localCache);
        Files.createDirectories(globalCache);
        Files.createDirectories(tmpDir);
        environment.put("ZIG_LOCAL_CACHE_DIR", localCache.toString());
        environment.put("ZIG_GLOBAL_CACHE_DIR", globalCache.toString());
        environment.put("TEMP", tmpDir.toString());
        environment.put("TMP", tmpDir.toString());
        return environment;
    }

    private static void ensureHostToolchain() {
        if (!isWindows()) {
            return;
        }

        if (findWindowsToolchainScript().isPresent()) {
            return;
        }

        throw new IllegalStateException(
            "Windows Ghostty builds require Visual Studio Build Tools and the Windows SDK. "
                + "CI runners provide them, but this local machine does not appear to have them installed."
        );
    }

    private static Map<String, String> windowsToolchainEnvironment() throws Exception {
        var script = findWindowsToolchainScript().orElseThrow(() -> new IllegalStateException(
            "Windows Ghostty builds require Visual Studio Build Tools and the Windows SDK. "
                + "CI runners provide them, but this local machine does not appear to have them installed."
        ));
        var output = capture(
            Path.of("").toAbsolutePath().normalize(),
            "cmd.exe",
            "/d",
            "/s",
            "/c",
            toolchainSetupCommand(script)
        );
        var environment = new java.util.LinkedHashMap<String, String>();
        for (var line : output.split("\\R")) {
            if (line.isBlank() || line.startsWith("=")) {
                continue;
            }
            var separator = line.indexOf('=');
            if (separator <= 0) {
                continue;
            }
            environment.put(line.substring(0, separator), line.substring(separator + 1));
        }
        if (!environment.containsKey("PATH")) {
            throw new IllegalStateException("failed to capture Windows toolchain environment from " + script);
        }
        return environment;
    }

    private static Optional<Path> findWindowsToolchainScript() {
        var vsWhere = Path.of("C:/Program Files (x86)/Microsoft Visual Studio/Installer/vswhere.exe");
        if (Files.isRegularFile(vsWhere)) {
            try {
                var installationPath = capture(
                    Path.of("").toAbsolutePath().normalize(),
                    vsWhere.toString(),
                    "-latest",
                    "-products",
                    "*",
                    "-requires",
                    "Microsoft.VisualStudio.Component.VC.Tools.x86.x64",
                    "-property",
                    "installationPath"
                ).strip();
                if (!installationPath.isEmpty()) {
                    var installation = Path.of(installationPath);
                    var vcvars = installation.resolve("VC").resolve("Auxiliary").resolve("Build").resolve("vcvars64.bat");
                    if (Files.isRegularFile(vcvars)) {
                        return Optional.of(vcvars);
                    }
                    var vsDevCmd = installation.resolve("Common7").resolve("Tools").resolve("VsDevCmd.bat");
                    if (Files.isRegularFile(vsDevCmd)) {
                        return Optional.of(vsDevCmd);
                    }
                }
            } catch (Exception ignored) {
                // Fall back to scanning the standard installation roots.
            }
        }

        for (var root : java.util.List.of(
            Path.of("C:/Program Files/Microsoft Visual Studio"),
            Path.of("C:/Program Files (x86)/Microsoft Visual Studio")
        )) {
            var toolchain = findWindowsToolchainScript(root);
            if (toolchain.isPresent()) {
                return toolchain;
            }
        }
        return Optional.empty();
    }

    private static Optional<Path> findWindowsToolchainScript(Path root) {
        if (!Files.isDirectory(root)) {
            return Optional.empty();
        }

        try (var paths = Files.walk(root, 6)) {
            return paths
                .filter(Files::isRegularFile)
                .filter(path -> {
                    var name = path.getFileName();
                    if (name == null) {
                        return false;
                    }
                    return "vcvars64.bat".equalsIgnoreCase(name.toString())
                        || "VsDevCmd.bat".equalsIgnoreCase(name.toString());
                })
                .sorted(java.util.Comparator.comparingInt(GhosttyBuild::toolchainScriptPriority))
                .findFirst();
        } catch (IOException exception) {
            throw new IllegalStateException("failed to inspect Windows toolchain under " + root, exception);
        }
    }

    private static int toolchainScriptPriority(Path path) {
        var name = path.getFileName();
        if (name == null) {
            return Integer.MAX_VALUE;
        }
        return "vcvars64.bat".equalsIgnoreCase(name.toString()) ? 0 : 1;
    }

    private static String toolchainSetupCommand(Path script) {
        var quoted = "\"" + script + "\"";
        if ("VsDevCmd.bat".equalsIgnoreCase(String.valueOf(script.getFileName()))) {
            return "call " + quoted + " -arch=amd64 -host_arch=amd64 >nul && set";
        }
        return "call " + quoted + " >nul && set";
    }

    private static Path zigExecutable(Path zigHome) {
        var executable = zigHome.resolve(isWindows() ? "zig.exe" : "zig");
        if (!Files.isRegularFile(executable)) {
            throw new IllegalStateException("missing zig executable: " + executable);
        }
        return executable;
    }

    private static void generateArtifact(
        Path repo,
        OutputDirs outputs,
        PlatformSpec platform,
        String artifactId,
        String ghosttyCommit,
        Path jextractHome
    ) throws Exception {
        deleteDirectory(outputs.generatedSourcesDir);
        deleteDirectory(outputs.generatedResourcesDir);
        deleteDirectory(outputs.artifactDir);
        var artifactSrcDir = outputs.artifactDir.resolve("src");
        var artifactResourcesDir = outputs.artifactDir.resolve("resources");
        var artifactNativeDir = artifactResourcesDir.resolve("native").resolve(platform.id);
        var generatedNativeDir = outputs.generatedResourcesDir.resolve("native").resolve(platform.id);
        Files.createDirectories(outputs.generatedSourcesDir);
        Files.createDirectories(generatedNativeDir);
        Files.createDirectories(artifactSrcDir);
        Files.createDirectories(artifactNativeDir);

        var ghosttyRoot = repo.resolve(GHOSTTY_SUBMODULE);
        var includeDir = ghosttyRoot.resolve("zig-out").resolve("include");
        var header = includeDir.resolve("ghostty").resolve("vt.h");
        if (!Files.isRegularFile(header)) {
            throw new IllegalStateException("missing jextract header: " + header);
        }

        runJextract(repo, jextractHome, includeDir, outputs.generatedSourcesDir, header);

        var builtLibrary = repo.resolve(platform.builtLibraryRelativePath);
        var generatedLibrary = generatedNativeDir.resolve(platform.packagedLibraryFileName);
        Files.copy(builtLibrary, generatedLibrary, StandardCopyOption.REPLACE_EXISTING);
        copyDirectory(outputs.generatedSourcesDir, artifactSrcDir);
        Files.copy(generatedLibrary, artifactNativeDir.resolve(platform.packagedLibraryFileName), StandardCopyOption.REPLACE_EXISTING);
        writeMetadata(outputs.generatedResourcesDir.resolve("metadata.properties"), platform, artifactId, ghosttyCommit);
        Files.copy(
            outputs.generatedResourcesDir.resolve("metadata.properties"),
            artifactResourcesDir.resolve("metadata.properties"),
            StandardCopyOption.REPLACE_EXISTING
        );
    }

    private static void runJextract(Path repo, Path jextractHome, Path includeDir, Path srcDir, Path header) throws Exception {
        var command = new java.util.ArrayList<String>();
        if (isWindows()) {
            command.add("cmd.exe");
            command.add("/c");
        }
        command.add(jextractExecutable(jextractHome).toString());
        command.add("-I");
        command.add(includeDir.toString());
        command.add("-t");
        command.add(JEXTRACT_TARGET_PACKAGE);
        command.add("--header-class-name");
        command.add(JEXTRACT_HEADER_CLASS);
        command.add("--output");
        command.add(srcDir.toString());
        command.add(header.toString());
        run(repo, Map.of(), command.toArray(String[]::new));
    }

    private static Path jextractExecutable(Path jextractHome) {
        var executable = jextractHome.resolve("bin").resolve(isWindows() ? "jextract.bat" : "jextract");
        if (!Files.isRegularFile(executable)) {
            throw new IllegalStateException("missing jextract executable: " + executable);
        }
        return executable;
    }

    private static void writeMetadata(Path metadataPath, PlatformSpec platform, String artifactId, String ghosttyCommit) throws IOException {
        var metadata = new Properties();
        metadata.setProperty("artifactId", artifactId);
        metadata.setProperty("platform", platform.id);
        metadata.setProperty("ghostty.commit", ghosttyCommit);
        metadata.setProperty("jextract.package", JEXTRACT_TARGET_PACKAGE);
        metadata.setProperty("jextract.headerClass", JEXTRACT_HEADER_CLASS);
        metadata.setProperty("jextract.version", platform.jextract.rootDirName);
        metadata.setProperty("zig.version", platform.zig.rootDirName);
        Files.createDirectories(Objects.requireNonNull(metadataPath.getParent()));
        try (var output = Files.newOutputStream(metadataPath)) {
            metadata.store(output, "generated by scripts/GhosttyBuild.java");
        }
    }

    private static boolean isUpToDate(OutputDirs outputs, PlatformSpec platform, String artifactId, String ghosttyCommit) throws IOException {
        var metadataFile = outputs.generatedResourcesDir.resolve("metadata.properties");
        var generatedHeader = outputs.generatedSourcesDir
            .resolve(JEXTRACT_TARGET_PACKAGE.replace('.', java.io.File.separatorChar))
            .resolve(JEXTRACT_HEADER_CLASS + ".java");
        var nativeLibrary = outputs.generatedResourcesDir.resolve("native").resolve(platform.id).resolve(platform.packagedLibraryFileName);
        var artifactLibrary = outputs.artifactDir.resolve("resources")
            .resolve("native")
            .resolve(platform.id)
            .resolve(platform.packagedLibraryFileName);
        var artifactHeader = outputs.artifactDir.resolve("src")
            .resolve(JEXTRACT_TARGET_PACKAGE.replace('.', java.io.File.separatorChar))
            .resolve(JEXTRACT_HEADER_CLASS + ".java");
        var artifactMetadata = outputs.artifactDir.resolve("resources").resolve("metadata.properties");
        if (!Files.isRegularFile(metadataFile) || !Files.isRegularFile(generatedHeader) || !Files.isRegularFile(nativeLibrary)) {
            return false;
        }
        if (!Files.isRegularFile(artifactLibrary) || !Files.isRegularFile(artifactHeader) || !Files.isRegularFile(artifactMetadata)) {
            return false;
        }

        var metadata = new Properties();
        try (var input = Files.newInputStream(metadataFile)) {
            metadata.load(input);
        }
        return artifactId.equals(metadata.getProperty("artifactId"))
            && platform.id.equals(metadata.getProperty("platform"))
            && ghosttyCommit.equals(metadata.getProperty("ghostty.commit"))
            && JEXTRACT_TARGET_PACKAGE.equals(metadata.getProperty("jextract.package"))
            && JEXTRACT_HEADER_CLASS.equals(metadata.getProperty("jextract.headerClass"))
            && platform.jextract.rootDirName.equals(metadata.getProperty("jextract.version"))
            && platform.zig.rootDirName.equals(metadata.getProperty("zig.version"));
    }

    private static OutputDirs outputs(Path buildDir) {
        return new OutputDirs(
            buildDir.resolve("generated-sources").resolve("jextract"),
            buildDir.resolve("generated-resources").resolve("ghosttyfx"),
            buildDir.resolve("ghosttyfx-artifact")
        );
    }

    private static void run(Path workingDirectory, Map<String, String> extraEnvironment, String... command) throws Exception {
        System.out.println(String.join(" ", command));
        var processBuilder = new ProcessBuilder(command);
        processBuilder.directory(workingDirectory.toFile());
        processBuilder.inheritIO();
        if (!extraEnvironment.isEmpty()) {
            processBuilder.environment().putAll(extraEnvironment);
        }
        var process = processBuilder.start();
        var exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new IllegalStateException("command failed with exit code " + exitCode + ": " + String.join(" ", command));
        }
    }

    private static String capture(Path workingDirectory, String... command) throws Exception {
        var processBuilder = new ProcessBuilder(command);
        processBuilder.directory(workingDirectory.toFile());
        processBuilder.redirectErrorStream(true);
        var process = processBuilder.start();
        try (InputStream input = process.getInputStream()) {
            var output = new String(input.readAllBytes());
            var exitCode = process.waitFor();
            if (exitCode != 0) {
                throw new IllegalStateException("command failed with exit code " + exitCode + ": " + String.join(" ", command));
            }
            return output;
        }
    }

    private static String sha256(Path path) throws Exception {
        var digest = MessageDigest.getInstance("SHA-256");
        try (var input = Files.newInputStream(path)) {
            var buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read == 0) {
                    continue;
                }
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void deleteDirectory(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        Files.walkFileTree(path, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                if (exc != null) {
                    throw exc;
                }
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void copyDirectory(Path source, Path target) throws IOException {
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Files.createDirectories(target.resolve(source.relativize(dir)));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.copy(
                    file,
                    target.resolve(source.relativize(file)),
                    StandardCopyOption.REPLACE_EXISTING
                );
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private enum ArchiveType {
        TAR,
        ZIP
    }

    private record DownloadSpec(
        String kind,
        String url,
        String sha256,
        String archiveFileName,
        String rootDirName,
        String executableRelativePath,
        ArchiveType archiveType
    ) {}

    private record PlatformSpec(
        String id,
        DownloadSpec zig,
        DownloadSpec jextract,
        String builtLibraryRelativePath,
        String packagedLibraryFileName
    ) {}

    private record OutputDirs(
        Path generatedSourcesDir,
        Path generatedResourcesDir,
        Path artifactDir
    ) {}
}
