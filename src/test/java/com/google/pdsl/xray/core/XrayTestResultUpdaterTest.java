package com.google.pdsl.xray.core;

import com.google.pdsl.xray.constants.StepStatus;
import com.google.pdsl.xray.models.XrayTestExecution;
import com.google.pdsl.xray.models.XrayTestResult;
import com.pdsl.reports.TestResult;
import com.pdsl.reports.proto.TechnicalReportData;
import com.pdsl.specifications.Phrase;
import com.pdsl.testcases.TaggedTestCase;
import com.pdsl.testcases.TestCase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class XrayTestResultUpdaterTest {

    private static final List<String> STEP_DESCRIPTIONS_LIST = List.of(
            "Given step one",
            "When step two",
            "Then step three",
            "Then step four"
    );

    private static final String TEST_PLAN_KEY = "PLAN-123";
    private static final String TEST_EXECUTION_KEY = "EXEC-123";
    private static final String DEFAULT_SCENARIO_KEY = "SCENARIO-KEY";

    private static final Set<String> DEFAULT_TEST_CASE_TAGS = Set.of(
            "@xray-test-plan=" + TEST_PLAN_KEY,
            "@xray-test-execution=" + TEST_EXECUTION_KEY,
            "@xray-test-case=" + DEFAULT_SCENARIO_KEY
    );

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
        TestResult result = Mockito.mock(TestResult.class);
        when(result.getTestCase()).thenReturn(testCase);
        when(result.getStatus()).thenReturn(TechnicalReportData.Status.PASSED);

        updater.addResults(List.of(result));

        XrayTestExecution testExecution = getTestExecution(updater, TEST_EXECUTION_KEY);

        assertEquals(3, testExecution.tests().size());
        assertEquals(StepStatus.PASSED.name(), getTestStatus(testExecution, "STEP-KEY-1"));
        assertEquals(StepStatus.PASSED.name(), getTestStatus(testExecution, "STEP-KEY-3"));
        assertEquals(StepStatus.PASSED.name(), getTestStatus(testExecution, DEFAULT_SCENARIO_KEY));
    }

    @Test
    void addResults_withStepLevelComments_stepFailed() {
        XrayTestResultUpdater updater = xrayTestResultUpdaterBuilder.withXrayAuth(xrayAuth).build();

        // Setup step comments:
        // Step 1 (index 1) has STEP-KEY-1 -> should be PASSED
        // Step 2 (index 2) has STEP-KEY-2 -> should be FAILED
        // Step 3 (index 3) has STEP-KEY-2 -> should be FAILED
        // Step 4 (index 4) has STEP-KEY-3 -> should be BLOCKED
        Map<Integer, List<String>> stepComments = new HashMap<>();
        stepComments.put(1, List.of("@xray-test-case=STEP-KEY-1"));
        stepComments.put(2, List.of("@xray-test-case=STEP-KEY-2"));
        stepComments.put(4, List.of("@xray-test-case=STEP-KEY-3"));
        TaggedTestCase testCase = createMockTestCase(stepComments);

        Phrase failingPhrase = Mockito.mock(Phrase.class);
        when(failingPhrase.getPrefilteredIndex()).thenReturn(2);

        // Mock TestResult
        TestResult result = Mockito.mock(TestResult.class);
        when(result.getTestCase()).thenReturn(testCase);
        when(result.getStatus()).thenReturn(TechnicalReportData.Status.FAILED);
        when(result.getFailureReason()).thenReturn(Optional.of(new RuntimeException("Test Failure")));
        when(result.getFailingPhrase()).thenReturn(Optional.of(failingPhrase));

        updater.addResults(List.of(result));

        XrayTestExecution testExecution = getTestExecution(updater, TEST_EXECUTION_KEY);

        // SCENARIO-KEY, STEP-KEY-1, STEP-KEY-2, STEP-KEY-3
        assertEquals(4, testExecution.tests().size());
        assertEquals(StepStatus.FAILED.name(), getTestStatus(testExecution, DEFAULT_SCENARIO_KEY));
        assertEquals(StepStatus.PASSED.name(), getTestStatus(testExecution, "STEP-KEY-1"));
        assertEquals(StepStatus.FAILED.name(), getTestStatus(testExecution, "STEP-KEY-2"));
        assertEquals(StepStatus.BLOCKED.name(), getTestStatus(testExecution, "STEP-KEY-3"));
    }

    @Test
    void addResults_withDuplicateStepLevelComments_avoidsDuplicatesAndConsolidatesStatus() {
        XrayTestResultUpdater updater = xrayTestResultUpdaterBuilder.withXrayAuth(xrayAuth).build();

        // given
        // Step 1 (index 1) has STEP-KEY-1 -> should be PASSED
        // Step 2 (index 2) has STEP-KEY-1 (duplicate) -> should be FAILED
        // Step 3 (index 3) has STEP-KEY-1 (duplicate) -> should be BLOCKED
        Map<Integer, List<String>> stepComments = new HashMap<>();
        stepComments.put(1, List.of("@xray-test-case=STEP-KEY-1"));
        stepComments.put(2, List.of("@xray-test-case=STEP-KEY-1"));
        stepComments.put(3, List.of("@xray-test-case=STEP-KEY-1"));
        TaggedTestCase testCase = createMockTestCase(stepComments);

        Phrase failingPhrase = Mockito.mock(Phrase.class);
        when(failingPhrase.getPrefilteredIndex()).thenReturn(1);

        // when
        TestResult result = Mockito.mock(TestResult.class);
        when(result.getTestCase()).thenReturn(testCase);
        when(result.getStatus()).thenReturn(TechnicalReportData.Status.FAILED);
        when(result.getFailingPhrase()).thenReturn(Optional.of(failingPhrase));

        updater.addResults(List.of(result));

        XrayTestExecution testExecution = getTestExecution(updater, TEST_EXECUTION_KEY);

        // then
        // Only 2 distinct tests: SCENARIO-KEY, STEP-KEY-1 (no duplicates)
        assertEquals(2, testExecution.tests().size());
        assertEquals(StepStatus.FAILED.name(), getTestStatus(testExecution, DEFAULT_SCENARIO_KEY));
        // Overall status of STEP-KEY-1 should be FAILED since one of the steps failed
        assertEquals(StepStatus.FAILED.name(), getTestStatus(testExecution, "STEP-KEY-1"));
    }

    @Test
    void addResults_withStepLevelComments_stepPopulation() {
        XrayTestResultUpdater updater = xrayTestResultUpdaterBuilder.withXrayAuth(xrayAuth).build();

        Map<Integer, List<String>> stepComments = new HashMap<>();
        stepComments.put(0, List.of("@xray-test-case=BEFORE-KEY"));
        stepComments.put(1, List.of("@xray-test-case=STEP-1-KEY"));
        stepComments.put(2, List.of("@xray-test-case=STEP-2-KEY"));
        stepComments.put(3, List.of("@xray-test-case=STEP-3-KEY"));
        TaggedTestCase testCase = createMockTestCase(stepComments);

        // Mock TestResult
        TestResult result = Mockito.mock(TestResult.class);
        when(result.getTestCase()).thenReturn(testCase);
        when(result.getStatus()).thenReturn(TechnicalReportData.Status.PASSED);

        updater.addResults(List.of(result));

        // Let's assert on the TestItems stepDescriptions using our new testing helper
        List<XrayTestResultUpdater.TestItem> testItems = updater.getTestItemsForTestPlan(TEST_PLAN_KEY);

        XrayTestResultUpdater.TestItem beforeTestItem = testItems.stream()
                .filter(item -> item.testKey().equals("BEFORE-KEY"))
                .findFirst()
                .orElseThrow();
        assertEquals(STEP_DESCRIPTIONS_LIST, beforeTestItem.stepDescription());

        XrayTestResultUpdater.TestItem step1TestItem = testItems.stream()
                .filter(item -> item.testKey().equals("STEP-1-KEY"))
                .findFirst()
                .orElseThrow();
        assertEquals(STEP_DESCRIPTIONS_LIST.subList(0, 1), step1TestItem.stepDescription());

        XrayTestResultUpdater.TestItem step2TestItem = testItems.stream()
                .filter(item -> item.testKey().equals("STEP-2-KEY"))
                .findFirst()
                .orElseThrow();
        assertEquals(STEP_DESCRIPTIONS_LIST.subList(1, 2), step2TestItem.stepDescription());

        XrayTestResultUpdater.TestItem step3TestItem = testItems.stream()
                .filter(item -> item.testKey().equals("STEP-3-KEY"))
                .findFirst()
                .orElseThrow();
        assertEquals(STEP_DESCRIPTIONS_LIST.subList(2, STEP_DESCRIPTIONS_LIST.size()), step3TestItem.stepDescription());
    }

    private TaggedTestCase createMockTestCase(Map<Integer, List<String>> stepComments) {
        TaggedTestCase testCase = Mockito.mock(TaggedTestCase.class);
        when(testCase.getTags()).thenReturn(DEFAULT_TEST_CASE_TAGS);
        when(testCase.getOriginalSource()).thenReturn(URI.create("file:/some/path?ruleIndex=1&ordinal=2&tableIndex=3"));
        when(testCase.getTestTitle()).thenReturn("My Scenario");
        when(testCase.getUnfilteredPhraseBody()).thenReturn(STEP_DESCRIPTIONS_LIST);

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