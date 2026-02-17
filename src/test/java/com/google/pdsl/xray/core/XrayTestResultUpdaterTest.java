package com.google.pdsl.xray.core;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

@ExtendWith(MockitoExtension.class)
class XrayTestResultUpdaterTest {

    @Mock
    private XrayAuth xrayAuth;

    private XrayTestResultUpdater.Builder xrayTestResultUpdaterBuilder;
    private Path tempDir;

    @BeforeEach
    void setUp() throws IOException {
        tempDir = Files.createTempDirectory("xray-test");
        Supplier<Map<Object, Object>> fieldSupplier = () -> new HashMap<>() {{
            put("summary", "Test Summary");
            put("project", Map.of("key", "PDSL"));
        }};
        xrayTestResultUpdaterBuilder = new XrayTestResultUpdater.Builder("Test Title", "Test Description", fieldSupplier)
                .withTempDirectory(tempDir);
    }

    @Test
    void build_withNullXrayAuth_throwsIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class, () -> xrayTestResultUpdaterBuilder.build());
    }

    @Test
    void build_withXrayAuth_doesNotThrowException() {
        assertDoesNotThrow(() -> xrayTestResultUpdaterBuilder.withXrayAuth(xrayAuth).build());
    }

    @Test
    void build_withXrayAuthProperties_doesNotThrowException() throws IOException {
        // Create a dummy properties file
        Properties properties = new Properties();
        properties.setProperty("xray.api.url", "http://localhost:8080");
        properties.setProperty("xray.client.id", "dummy-id");
        properties.setProperty("xray.client.secret", "dummy-secret");
        Path propertiesFile = tempDir.resolve("xray_new.properties");
        properties.store(Files.newOutputStream(propertiesFile), null);

        assertDoesNotThrow(() -> xrayTestResultUpdaterBuilder.withPropertiesPath(propertiesFile).build());
    }
}