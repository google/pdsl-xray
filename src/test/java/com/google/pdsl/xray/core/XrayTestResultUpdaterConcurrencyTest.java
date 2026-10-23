package com.google.pdsl.xray.core;

import com.google.pdsl.xray.constants.StepStatus;
import com.google.pdsl.xray.core.model.TestItem;
import com.google.pdsl.xray.models.XrayTestExecution;
import com.google.pdsl.xray.models.XrayTestResult;
import com.pdsl.gherkin.DefaultGherkinTestSpecificationFactory;
import com.pdsl.gherkin.PickleJarFactory;
import com.pdsl.grammars.AllGrammarsLexer;
import com.pdsl.grammars.AllGrammarsParser;
import com.pdsl.reports.TestResult;
import com.pdsl.reports.proto.TechnicalReportData;
import com.pdsl.specifications.TestSpecification;
import com.pdsl.testcases.PreorderTestCaseFactory;
import com.pdsl.testcases.TaggedTestCase;
import com.pdsl.testcases.TestCase;
import com.pdsl.transformers.DefaultPolymorphicDslPhraseFilter;
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
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class XrayTestResultUpdaterConcurrencyTest {

    private static final String FEATURE_PATH = "src/test/resources/features/XrayConcurrency.feature";

    @Mock
    private XrayAuth xrayAuth;

    private XrayTestResultUpdater.Builder xrayTestResultUpdaterBuilder;
    private Path tempDir;

    @BeforeEach
    void setUp() throws IOException {
        tempDir = Files.createTempDirectory("xray-concurrency-test");
        Supplier<Map<Object, Object>> fieldSupplier = () -> new HashMap<>() {{
            put("summary", "Test Summary");
            put("project", Map.of("key", "PDSL"));
        }};
        xrayTestResultUpdaterBuilder = new XrayTestResultUpdater.Builder("Test Title", "Test Description", fieldSupplier)
                .withTempDirectory(tempDir);
    }

    @Test
    void addResults_concurrentCalls_populatesTestCaseXrayTestExecutionResultMapSafely() throws InterruptedException, ExecutionException {
        // given
        XrayTestResultUpdater updater = xrayTestResultUpdaterBuilder.withXrayAuth(xrayAuth).build();
        int threadCount = 50;
        ExecutorService executorService = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        var futures = new ArrayList<Future<?>>();

        URI featureUri = Paths.get(FEATURE_PATH).toUri();
        List<TaggedTestCase> testCases = loadTestCasesFromFeature(featureUri);

        Map<String, List<TaggedTestCase>> testCasesByPlan = testCases.stream()
                .collect(Collectors.groupingBy(tc -> tc.getTags().stream()
                        .filter(t -> t.startsWith("@xray-test-plan="))
                        .map(t -> t.substring("@xray-test-plan=".length()))
                        .findFirst()
                        .orElseThrow()));

        // Pre-create test results to isolate concurrency testing to updater.addResults()
        List<List<TestResult>> resultsPerThread = new ArrayList<>();
        for (int i = 0; i < threadCount; i++) {
            String planKey = "PLAN-" + i;
            List<TaggedTestCase> planCases = Objects.requireNonNull(testCasesByPlan.get(planKey), "Missing test cases for " + planKey);
            assertEquals(2, planCases.size());

            // Sort to ensure deterministic ordering (CASE-i-1 before CASE-i-2)
            planCases.sort((a, b) -> a.getTestTitle().compareTo(b.getTestTitle()));

            TestResult result1 = createMockTestResult(planCases.get(0), TechnicalReportData.Status.PASSED);
            TestResult result2 = createMockTestResult(planCases.get(1), TechnicalReportData.Status.PASSED);
            resultsPerThread.add(List.of(result1, result2));
        }

        // when
        for (int i = 0; i < threadCount; i++) {
            List<TestResult> results = resultsPerThread.get(i);
            futures.add(executorService.submit(() -> {
                startLatch.await();
                // Test both adding initial results (absent key) and adding subsequent results (existing key)
                updater.addResults(List.of(results.get(0)));
                updater.addResults(List.of(results.get(1)));
                return null;
            }));
        }

        startLatch.countDown();
        for (var future : futures) {
            future.get();
        }
        executorService.shutdown();
        assertTrue(executorService.awaitTermination(5, TimeUnit.SECONDS));

        // then
        Collection<XrayTestExecution> payloads = updater.getXrayPayload();
        assertEquals(threadCount, payloads.size());

        var executionByPlan = payloads.stream()
                .collect(Collectors.toMap(e -> e.info().testPlanKey(), e -> e));

        for (int i = 0; i < threadCount; i++) {
            String planKey = "PLAN-" + i;
            String execKey = "EXEC-" + i;
            String case1 = "CASE-" + i + "-1";
            String case2 = "CASE-" + i + "-2";

            assertTrue(executionByPlan.containsKey(planKey));
            XrayTestExecution execution = executionByPlan.get(planKey);
            assertEquals(execKey, execution.testExecutionKey());
            assertEquals(2, execution.tests().size());
            assertEquals(StepStatus.PASSED.name(), getTestStatus(execution, case1));
            assertEquals(StepStatus.PASSED.name(), getTestStatus(execution, case2));

            List<TestItem> testItems = updater.getTestItemsForTestPlan(planKey);
            assertEquals(2, testItems.size());
            Set<String> itemKeys = testItems.stream().map(TestItem::testKey).collect(Collectors.toSet());
            assertTrue(itemKeys.contains(case1));
            assertTrue(itemKeys.contains(case2));
        }
    }

    private static List<TaggedTestCase> loadTestCasesFromFeature(URI featureUri) {
        DefaultPolymorphicDslPhraseFilter phraseFilter = new DefaultPolymorphicDslPhraseFilter(
                AllGrammarsParser.class, AllGrammarsLexer.class,
                AllGrammarsParser.class, AllGrammarsLexer.class,
                "polymorphicDslAllRules", "polymorphicDslAllRules");
        PickleJarFactory pickleJarFactory = PickleJarFactory.getDefaultPickleJarFactory();
        DefaultGherkinTestSpecificationFactory specFactory = new DefaultGherkinTestSpecificationFactory.Builder(phraseFilter)
                .withPickleJarFactory(pickleJarFactory)
                .build();
        Collection<TestSpecification> specs = specFactory.getTestSpecifications(Set.of(featureUri))
                .orElseThrow(() -> new IllegalStateException("Failed to load test specifications from " + featureUri));
        PreorderTestCaseFactory testCaseFactory = new PreorderTestCaseFactory();
        Collection<TestCase> testCases = testCaseFactory.processTestSpecification(specs);
        return testCases.stream()
                .filter(TaggedTestCase.class::isInstance)
                .map(TaggedTestCase.class::cast)
                .collect(Collectors.toCollection(ArrayList::new));
    }

    private TestResult createMockTestResult(TaggedTestCase testCase, TechnicalReportData.Status status) {
        TestResult result = Mockito.mock(TestResult.class);
        when(result.getTestCase()).thenReturn(testCase);
        when(result.getStatus()).thenReturn(status);
        when(result.getFailureReason()).thenReturn(Optional.empty());
        when(result.getFailingPhrase()).thenReturn(Optional.empty());
        return result;
    }

    private String getTestStatus(XrayTestExecution testExecution, String testKey) {
        XrayTestResult result = testExecution.tests().stream()
                .filter(r -> r.testKey().equals(testKey))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Could not find test with key " + testKey));
        return result.status();
    }
}
