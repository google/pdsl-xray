package com.google.pdsl.xray.core;

import com.google.pdsl.xray.constants.StepStatus;
import com.google.pdsl.xray.models.XrayTestExecution;
import com.google.pdsl.xray.models.XrayTestResult;
import com.pdsl.reports.TestResult;
import com.pdsl.specifications.Phrase;
import com.pdsl.testcases.TaggedTestCase;
import com.pdsl.testcases.TestCase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

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

    @Test
    void addResults_withStepLevelComments_allPassed() {
        XrayTestResultUpdater updater = xrayTestResultUpdaterBuilder.withXrayAuth(xrayAuth).build();

        Map<Integer, List<String>> stepComments = new HashMap<>();
        stepComments.put(1, List.of("@xray-test-case=STEP-KEY-1"));
        stepComments.put(3, List.of("@xray-test-case=STEP-KEY-3"));
        TaggedTestCase testCase = createMockTestCase(stepComments);

        // Mock TestResult
        TestResult result = org.mockito.Mockito.mock(TestResult.class);
        when(result.getTestCase()).thenReturn(testCase);
        when(result.getStatus()).thenReturn(com.pdsl.reports.proto.TechnicalReportData.Status.PASSED);
        when(result.getFailureReason()).thenReturn(Optional.empty());
        when(result.getFailingPhrase()).thenReturn(Optional.empty());

        updater.addResults(List.of(result));

        XrayTestExecution testExecution = getTestExecution(updater, "EXEC-123");

        assertEquals(3, testExecution.tests().size());
        assertEquals(StepStatus.PASSED.name(), getTestStatus(testExecution, "STEP-KEY-1"));
        assertEquals(StepStatus.PASSED.name(), getTestStatus(testExecution, "STEP-KEY-3"));
        assertEquals(StepStatus.PASSED.name(), getTestStatus(testExecution, "SCENARIO-KEY"));
    }

    @Test
    void addResults_withStepLevelComments_stepFailed() {
        XrayTestResultUpdater updater = xrayTestResultUpdaterBuilder.withXrayAuth(xrayAuth).build();

        // Setup step comments:
        // Step 1 (index 1) has STEP-KEY-1 -> should be PASSED
        // Step 2 (index 2) has STEP-KEY-2 -> should be FAILED
        // Step 3 (index 3) has STEP-KEY-3 -> should be BLOCKED
        Map<Integer, List<String>> stepComments = new HashMap<>();
        stepComments.put(1, List.of("@xray-test-case=STEP-KEY-1"));
        stepComments.put(2, List.of("@xray-test-case=STEP-KEY-2"));
        stepComments.put(3, List.of("@xray-test-case=STEP-KEY-3"));
        TaggedTestCase testCase = createMockTestCase(stepComments);

        Phrase failingPhrase = org.mockito.Mockito.mock(Phrase.class);
        when(failingPhrase.getPrefilteredIndex()).thenReturn(1);

        // Mock TestResult
        TestResult result = org.mockito.Mockito.mock(TestResult.class);
        when(result.getTestCase()).thenReturn(testCase);
        when(result.getStatus()).thenReturn(com.pdsl.reports.proto.TechnicalReportData.Status.FAILED);
        when(result.getFailureReason()).thenReturn(Optional.of(new RuntimeException("Test Failure")));
        when(result.getFailingPhrase()).thenReturn(Optional.of(failingPhrase));

        updater.addResults(List.of(result));

        XrayTestExecution testExecution = getTestExecution(updater, "EXEC-123");

        // SCENARIO-KEY, STEP-KEY-1, STEP-KEY-2, STEP-KEY-3
        assertEquals(4, testExecution.tests().size());
        assertEquals(StepStatus.FAILED.name(), getTestStatus(testExecution, "SCENARIO-KEY"));
        assertEquals(StepStatus.PASSED.name(), getTestStatus(testExecution, "STEP-KEY-1"));
        assertEquals(StepStatus.FAILED.name(), getTestStatus(testExecution, "STEP-KEY-2"));
        assertEquals(StepStatus.BLOCKED.name(), getTestStatus(testExecution, "STEP-KEY-3"));
    }


    private TaggedTestCase createMockTestCase(Map<Integer, List<String>> stepComments) {
        TaggedTestCase testCase = org.mockito.Mockito.mock(TaggedTestCase.class);
        when(testCase.getTags()).thenReturn(Set.of("@xray-test-plan=PLAN-123", "@xray-test-execution=EXEC-123", "@xray-test-case=SCENARIO-KEY"));
        when(testCase.getOriginalSource()).thenReturn(URI.create("file:/some/path?ruleIndex=1&ordinal=2&tableIndex=3"));
        when(testCase.getTestTitle()).thenReturn("My Scenario");
        when(testCase.getUnfilteredPhraseBody()).thenReturn(List.of("Given step one", "When step two", "Then step three"));

        Map<String, Object> metadata = new HashMap<>();
        metadata.put(TestCase.STEP_COMMENTS, stepComments);
        when(testCase.getMetadata()).thenReturn(metadata);
        return testCase;
    }

    private XrayTestExecution getTestExecution(XrayTestResultUpdater updater, String testExecutionKey) {

        Collection<XrayTestExecution> payloads = updater.getXrayPayload().stream().toList();
        assertEquals(1, payloads.size());
        XrayTestExecution testExecution = payloads.iterator().next();
        assertEquals(testExecutionKey, testExecution.testExecutionKey());
        return testExecution;
    }

    private String getTestStatus(XrayTestExecution testExecution, String testKey) {
        XrayTestResult result = testExecution.tests().stream()
                .filter(r -> r.testKey().equals(testKey))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Could not find test with key " + testKey));
        return result.status();
    }

}