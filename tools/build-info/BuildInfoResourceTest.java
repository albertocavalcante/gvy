// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.build;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.io.StringReader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;

/** The same declared metadata must reach the classloader and the assembled server. */
public final class BuildInfoResourceTest {
    private static final int MAX_METADATA_BYTES = 65_536;

    @Test
    public void generatedStampIsIdenticalOnRuntimeClasspathAndInDeployJar() throws Exception {
        byte[] expected = Files.readAllBytes(resolve("gvy.test.properties"));
        assertTrue(expected.length <= MAX_METADATA_BYTES);
        List<URL> resources =
                Collections.list(getClass().getClassLoader().getResources("version.properties"));
        assertEquals(1, resources.size(), "One unambiguous GVY root resource");
        try (InputStream input = resources.get(0).openStream()) {
            assertArrayEquals(expected, readMetadata(input));
        }
        try (ZipFile jar = new ZipFile(resolve("gvy.test.jar").toFile())) {
            assertEquals(
                    1,
                    jar.stream().filter(entry -> entry.getName().equals("version.properties")).count());
            try (InputStream input = jar.getInputStream(jar.getEntry("version.properties"))) {
                assertArrayEquals(expected, readMetadata(input));
            }
        }
    }

    @Test
    public void actualLoadedBuildInfoUsesTheDeclaredSourceStamp() throws Exception {
        Properties expected = new Properties();
        expected.load(
                new StringReader(
                        Files.readString(resolve("gvy.test.properties"), StandardCharsets.UTF_8)));
        String source = expected.getProperty("gitSha");
        String dirty = expected.getProperty("gitDirty");
        assertNotNull(source);
        assertNotNull(dirty);
        assertTrue(source.equals("unknown") || source.matches("[a-f0-9]{40}"));
        assertTrue(List.of("true", "false", "unknown").contains(dirty));
        assertEquals(source.equals("unknown"), dirty.equals("unknown"));
        String qualifiedSource = System.getenv("GVY_EXPECTED_SOURCE_SHA");
        if (qualifiedSource != null) {
            requireQualifiedSource(expected, qualifiedSource);
        }
        Class<?> version = Class.forName("com.github.albertocavalcante.gvy.gls.Version");
        Object singleton = version.getField("INSTANCE").get(null);
        Object actual = version.getMethod("getInfo").invoke(singleton);
        assertEquals(
                expected.getProperty("gitSha"), actual.getClass().getMethod("getGitSha").invoke(actual));
        Boolean expectedDirty = dirty.equals("unknown") ? null : Boolean.valueOf(dirty);
        assertEquals(expectedDirty, actual.getClass().getMethod("getGitDirty").invoke(actual));
        assertEquals(
                expected.getProperty("displayVersion"),
                actual.getClass().getMethod("getDisplayVersion").invoke(actual));
    }

    @Test
    public void qualificationRejectsMismatchedUnknownAndDirtySources() {
        String source = "a".repeat(40);
        requireQualifiedSource(stamp(source, "false", ""), source);
        assertThrows(
                AssertionError.class,
                () -> requireQualifiedSource(stamp("b".repeat(40), "false", ""), source));
        assertThrows(
                AssertionError.class,
                () -> requireQualifiedSource(stamp("unknown", "unknown", ""), source));
        assertThrows(
                AssertionError.class,
                () -> requireQualifiedSource(stamp(source, "true", "123456789abc"), source));
        assertThrows(
                AssertionError.class,
                () -> requireQualifiedSource(stamp(source, "false", "123456789abc"), source));
        assertThrows(
                AssertionError.class,
                () -> requireQualifiedSource(stamp(source, "false", ""), "unknown"));
    }

    private static Properties stamp(String source, String dirty, String dirtyHash) {
        Properties properties = new Properties();
        properties.setProperty("gitSha", source);
        properties.setProperty("gitDirty", dirty);
        properties.setProperty("gitDirtyHash", dirtyHash);
        return properties;
    }

    private static void requireQualifiedSource(Properties properties, String qualifiedSource) {
        assertTrue(qualifiedSource.matches("[a-f0-9]{40}"), "Qualification needs a canonical source SHA");
        assertEquals(qualifiedSource, properties.getProperty("gitSha"), "Qualified checkout identity");
        assertEquals("false", properties.getProperty("gitDirty"), "Qualification needs a clean tree");
        assertEquals("", properties.getProperty("gitDirtyHash"), "Clean metadata has no dirty hash");
    }

    private static byte[] readMetadata(InputStream input) throws Exception {
        byte[] bytes = input.readNBytes(MAX_METADATA_BYTES + 1);
        assertTrue(bytes.length <= MAX_METADATA_BYTES);
        return bytes;
    }

    private static Path resolve(String property) throws Exception {
        String configured = System.getProperty(property);
        assertNotNull(configured, property);
        Path path = Path.of(configured);
        if (path.isAbsolute()) {
            return path.toRealPath();
        }
        String runfiles = System.getenv("RUNFILES_DIR");
        if (runfiles == null) {
            runfiles = System.getenv("TEST_SRCDIR");
        }
        assertNotNull(runfiles, "Bazel runfiles are required for relative inputs");
        String workspace = System.getenv().getOrDefault("TEST_WORKSPACE", "_main");
        return Path.of(runfiles, workspace).resolve(path).toRealPath();
    }
}
