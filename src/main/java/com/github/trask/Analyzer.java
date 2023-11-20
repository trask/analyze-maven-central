package com.github.trask;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.regex.Pattern;
import java.util.zip.ZipException;

// percentage of artifacts which had at least one version published in October 2023
public class Analyzer {

    private static final String MAVEN_CENTRAL_ROOT = "https://repo1.maven.org/maven2/";

    private static final Pattern HREFS = Pattern.compile("href=\"([^\"]*)\".*</a>\\s+([-0-9]+) ");

    private final CachingHttpClient httpClient = new CachingHttpClient();
    private final HttpClient jarHttpClient = HttpClient.newHttpClient();

    public Analyzer() throws SQLException {
    }

    public static void main(String[] args) throws Exception {
        new Analyzer().analyzePath("", 0, new AtomicInteger());
    }

    private void analyzePath(String path, int level, AtomicInteger numArtifactsAnalyzedInParentGroupId) throws Exception {
        String page;
        try {
            page = httpClient.get(MAVEN_CENTRAL_ROOT + path);
        } catch (Exception e) {
            return;
        }

        AtomicInteger numArtifactsAnalyzedInThisGroupId = new AtomicInteger();

        var matcher = HREFS.matcher(page);
        while (matcher.find()) {
            var href = matcher.group(1);
            if (href.startsWith("maven-metadata.xml")) {
                continue;
            }
            if (href.isEmpty()) {
                // e.g. https://repo1.maven.org/maven2/com/auryc/
                continue;
            }
            var date = matcher.group(2);
            if (date.equals("-")) {
                analyzePath(path + href, level + 1, numArtifactsAnalyzedInThisGroupId);
            } else if (numArtifactsAnalyzedInParentGroupId.get() < 5 && date.startsWith("2023-10-") && level > 0) {
                Result result = analyzeArtifact(path + href);
                if (result != null) {
                    numArtifactsAnalyzedInParentGroupId.getAndIncrement();
                }
            }
        }
        if (numArtifactsAnalyzedInThisGroupId.get() > 0) {
            System.out.println(path);
        }
    }

    private Result analyzeArtifact(String path) throws Exception {
        int versionSeparator = path.lastIndexOf('/', path.length() - 2);
        int artifactNameSeparator = path.lastIndexOf('/', versionSeparator - 1);

        var artifactName = path.substring(artifactNameSeparator + 1, versionSeparator);
        var version = path.substring(versionSeparator + 1, path.length() - 1);

        var primaryJarName = artifactName + "-" + version + ".jar";
        var primaryAarName = artifactName + "-" + version + ".aar";

        var page = httpClient.get(MAVEN_CENTRAL_ROOT + path);

        if (page.contains("href=\"" + primaryJarName + "\"")) {
            return analyzeJar(path + primaryJarName);
        } else if (page.contains("href=\"" + primaryAarName + "\"")) {
            return analyzeJar(path + primaryAarName);
        } else {
            return null;
        }
    }

    private Result analyzeJar(String path) throws Exception {
        Path localJar = Path.of("jars", path);

        if (!Files.exists(localJar)) {
            downloadJar(path, localJar);
        }

        try (JarFile jarFile = new JarFile(localJar.toFile())) {
            Manifest manifest = jarFile.getManifest();
            if (manifest != null && !manifest.getEntries().isEmpty()) {
                if (jarFile.stream().anyMatch(jarEntry -> jarEntry.getName().startsWith("META-INF/") && jarEntry.getName().endsWith(".SF"))) {
                    System.out.println("FOUND SIGNED JAR: " + path);
                    return Result.SIGNED;
                }
            }
            return Result.UNSIGNED;
        } catch (ZipException ignored) {
            return null;
        }
    }

    private void downloadJar(String path, Path localJar) throws IOException, InterruptedException {
        Files.createDirectories(localJar.getParent());
        var request =
                HttpRequest.newBuilder()
                        .uri(URI.create(MAVEN_CENTRAL_ROOT + path))
                        .method("GET", HttpRequest.BodyPublishers.noBody())
                        .build();
        jarHttpClient.send(request, HttpResponse.BodyHandlers.ofFile(localJar));
    }

    private enum Result {
        SIGNED,
        UNSIGNED
    }
}
