package com.google.pdsl.xray;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.pdsl.xray.core.XrayAuth;
import com.google.pdsl.xray.core.XrayTestResultUpdater;
import org.junit.Assert;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.mock;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.function.Supplier;

public class XrayComponentTest {

    @Test
    public void builder_allParametersPositive() throws IOException {
        // ARRANGE
        String title = "unit test";
        String description = "description";
        Supplier<Map<Object, Object>> supplier = () -> new HashMap<>(0);
        Set<String> envs = Set.of("stub");
        ObjectMapper objectMapper = mock(ObjectMapper.class);
        Path path = Files.createTempDirectory("xray-component-test");
        Properties properties = mock(Properties.class);
        XrayAuth xrayAuth = mock(XrayAuth.class);

        XrayTestResultUpdater.Builder builder = new XrayTestResultUpdater.Builder(title, description, supplier)
                .withEnvironments(envs)
                .withObjectMapper(objectMapper)
                .withTempDirectory(path)
                .withProperties(properties)
                .withXrayAuth(xrayAuth);

        XrayTestResultUpdater updater = builder.build();
        Assertions.assertNotNull(updater);
    }
}
