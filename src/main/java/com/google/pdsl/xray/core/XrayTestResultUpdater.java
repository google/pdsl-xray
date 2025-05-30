package com.google.pdsl.xray.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Preconditions;
import com.google.pdsl.xray.models.Info;
import com.google.pdsl.xray.models.XrayTestExecutionResult;
import com.google.pdsl.xray.models.XrayTestResult;
import com.pdsl.executors.ExecutorObserver;
import com.pdsl.gherkin.GherkinObserver;
import com.pdsl.gherkin.models.GherkinScenario;
import com.pdsl.reports.MetadataTestRunResults;
import com.pdsl.reports.TestResult;
import com.pdsl.reports.proto.TechnicalReportData;
import com.pdsl.testcases.SharedTestCase;
import com.pdsl.testcases.TaggedTestCase;
import com.pdsl.testcases.TestCase;
import org.antlr.v4.runtime.tree.ParseTreeListener;
import org.apache.commons.codec.Charsets;
import org.apache.http.HttpHeaders;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.mime.MultipartEntityBuilder;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.function.Supplier;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/*
Copyright 2025 Google LLC

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    https://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
*/

/**
 * XrayTestResultUpdater updates Xray with test execution results. It implements GherkinObserver and
 * ExecutorObserver to listen for test events.
 */
public class XrayTestResultUpdater implements GherkinObserver, ExecutorObserver {

    private final XrayAuth xrayAuth;
    private final ObjectMapper objectMapper; // Jackson ObjectMapper for JSON serialization
    private final Map<String, HierarchicalTestSuite> testCaseXrayTestExecutionResultMap = new HashMap<>();
    private final Logger logger = Logger.getLogger(this.getClass().getName());
    private Properties prop;
    private final Set<String> environments;
    private final String description;
    private final String title;
    private final Supplier<Map<Object, Object>> fieldSupplier;
    private final Path tempDirectory;
    private final List<String> xrayStatuses;

    private XrayTestResultUpdater(XrayAuth xrayAuth, String title, String description,
                                 Supplier<Map<Object, Object>> fieldSupplier, Path tempDirectoryPath,
                                 Set<String> environments, List<String> xrayStatuses) {
        validateTempDirectory(tempDirectoryPath);
        this.xrayAuth = xrayAuth;
        this.objectMapper = new ObjectMapper();
        prop = xrayAuth.getProperties();
        this.environments = environments;
        this.description = description;
        this.title = title;
        this.fieldSupplier = fieldSupplier;
        this.tempDirectory = tempDirectoryPath;
        this.xrayStatuses = xrayStatuses;
    }

    public static class Builder {
        private Optional<XrayAuth> xrayAuth = Optional.empty();
        private ObjectMapper objectMapper = new ObjectMapper();
        private Optional<Properties> prop = Optional.empty();
        private Optional<Set<String>> environments = Optional.empty();
        private String description;
        private String title;
        private Supplier<Map<Object, Object>> fieldSupplier;
        private Optional<Path> tempDirectory = Optional.empty();
        private List<String> xrayStatuses = List.of("EXECUTING", "FAILED", "PASSED", "TODO");

        public XrayTestResultUpdater build() {
            Preconditions.checkNotNull(fieldSupplier, "fieldSupplier must not be null");
            Preconditions.checkNotNull(description, "description must not be null");
            Preconditions.checkNotNull(title, "title must not be null");
            XrayAuth auth = xrayAuth.orElse(
                    XrayAuth.fromPropertiesFile("src/test/resources/xray.properties"));
            Set<String> envs = environments.orElseGet(() -> Arrays.stream(
                    auth.getProperties().get("xray.environments")
                            .toString()
                            .split(","))
                    .collect(Collectors.toSet()));
            return new XrayTestResultUpdater(xrayAuth.orElse(
                    XrayAuth.fromPropertiesFile("src/test/resources/xray.properties")),
                    title,
                    description,
                    fieldSupplier,
                    tempDirectory.orElseGet(() -> {
                        try {
                            return Files.createTempDirectory("xray");
                        } catch (IOException e) {
                            throw new IllegalStateException("""
                        No path for temporary files was provided in the builder,
                        so tried to create one in the standard temp directory.
                        
                        Recommended: Provide a viable path using this builder.
                        
                        Alternative: Fix the following IOException.
                        """, e);
                        }
                    }),
                    envs,
                    xrayStatuses);


        }


        public Builder(String title, String description, Supplier<Map<Object, Object>> fieldSupplier) {
            Preconditions.checkNotNull(fieldSupplier, "fieldSupplier must not be null");
            Preconditions.checkNotNull(title, "title must not be null");
            Preconditions.checkNotNull(description, "description must not be null");
            this.title = title;
            this.description = description;
            this.fieldSupplier = fieldSupplier;
        }

        public Builder withXrayStatuses(List<String> xrayStatuses) {
            this.xrayStatuses = xrayStatuses;
            return this;
        }

        public Builder withTempDirectory(Path tempDirectory) {
            this.tempDirectory = Optional.ofNullable(tempDirectory);
            return this;
        }
        public Builder withFieldSupplier(Supplier<Map<Object, Object>> fieldSupplier) {
            Preconditions.checkNotNull(fieldSupplier);
            this.fieldSupplier = fieldSupplier;
            return this;
        }
        public Builder withTitle(String title) {
            Preconditions.checkNotNull(title);
            this.title = title;
            return this;
        }

        public Builder withDescriptionS(String description) {
            Preconditions.checkNotNull(description);
            this.description = description;
            return this;
        }

        public Builder withEnvironments(Set<String> environments) {
            this.environments = Optional.of(environments);
            return this;
        }

        public Builder withProperties(Properties properties) {
            this.prop = Optional.of(properties);
            return this;
        }

        public Builder withObjectMapper(ObjectMapper objectMapper) {
            this.objectMapper = objectMapper;
            return this;
        }

        public Builder withXrayAuth(XrayAuth xrayAuth) {
            this.xrayAuth = Optional.ofNullable(xrayAuth);
            return this;
        }
    }

    private void validateTempDirectory(Path tempDirectoryPath) {
        if (!tempDirectoryPath.toFile().isDirectory()) {
            throw new IllegalArgumentException(String.format("""
                    A directory to put temporary files is required to create the multipart
                    request to the XRAY API. The provided parameter is not a directory:
                    %s
                    
                    To use the operating systems standard temp location, you can use this as a parameter:
                      Files.createTempDir()
                    
                    Be aware that in a CI/CD pipeline this script will need to have write access to this location.
                    """, tempDirectoryPath.toUri()));
        }
        if (!tempDirectoryPath.toFile().canWrite()) {
            throw new IllegalArgumentException(String.format("""
                    A directory to put temporary files is required to create the multipart
                    request to the XRAY API. The provided  directory is not writeable by this program in this environment:
                    %s
                    
                    To use the operating systems standard temp location, you can use this as a parameter:
                      Files.createTempDir()
                    
                    Be aware that in a CI/CD pipeline this script will need to have write access to this location.
                    """, tempDirectoryPath.toUri()));
        }
    }

    private record TestItem(String testKey, String status, List<String> stepDescription, Throwable throwable, Integer failedStepIndex){
        public Optional<Integer> getFailedStepIndex() { return Optional.ofNullable(failedStepIndex); }
        public Optional<Throwable> getThrowable() { return Optional.ofNullable(throwable); }
    }

    private final class HierarchicalTestSuite {

        private record TestGroup(String source, Info info, int groupNumber, Map<Integer, TestOrdinal> ordinals) {
        }

        private record TestOrdinal(int ordinal, String xrayTestCase, List<TestPermutation> permutations) {
        }

        private record TestPermutation(URI source, int permutationNumber, TestItem result) {
        }

        private final Map<Info, List<TestGroup>> info2TestGroups = new HashMap<>();

        void addTestResult(URI source, Info info, TestItem result, int groupNumber, int ordinal, int exampleNumber) {
            Collection<TestGroup> testGroups = info2TestGroups.computeIfAbsent(info, (k) -> new ArrayList<>());
            TestGroup group = testGroups.stream()
                    .filter(g -> g.groupNumber == groupNumber)
                    .findFirst()
                    .orElseGet(() -> {
                        var g = new TestGroup(source.getPath(), info, groupNumber, new HashMap<>());
                        info2TestGroups.get(info).add(g);
                        return g;
                    });
            TestOrdinal testOrdinal = group.ordinals.computeIfAbsent(ordinal, (k) -> new TestOrdinal(ordinal, result.testKey(), new ArrayList<>()));
            List<TestPermutation> permutations = testOrdinal.permutations();
            permutations.add(new TestPermutation(source, exampleNumber, result));
        }

        Collection<XrayTestExecutionResult> Info2Results() {
            List<XrayTestExecutionResult> results = new ArrayList<>();
            Map<Info, List<XrayTestResult>> info2Results = new HashMap<>();
            for (Map.Entry<Info, List<TestGroup>> entry : this.info2TestGroups.entrySet()) {
                List<XrayTestResult> testResults = info2Results.computeIfAbsent(entry.getKey(), (k) -> new ArrayList<>());
                for (TestGroup testGroup : entry.getValue()) {
                    for (TestOrdinal ordinal : testGroup.ordinals.values()) {
                        // Sort all of the ordinals to make the results show up the same way as they did in the file
                        ordinal.permutations.sort(Comparator.comparingInt(t -> t.permutationNumber));
                        List<String> examplesResults = new ArrayList<>();
                        List<XrayTestResult.Iteration> iterations = new ArrayList<>();
                        for (int i=0; i < ordinal.permutations.size(); i++) {
                            TestPermutation permutation = ordinal.permutations.get(i);
                            examplesResults.add(permutation.result.status);
                            iterations.add(new XrayTestResult.Iteration(
                                    String.valueOf(i),
                                    permutation.result.status,
                                    new HashMap<>(),
                                    iterationStepsFromPermutation(permutation)));
                        }
                        XrayTestResult consolidatedResult = new XrayTestResult(ordinal.permutations.getFirst().result.testKey(),
                                calculateOverallStatus(examplesResults), examplesResults
                                // TODO: determine if iterations can be supported propertly
                                //  , iterations
                                );
                        testResults.add(consolidatedResult);
                        info2Results.get(entry.getKey()).add(consolidatedResult);
                    }
                }
            }
            return info2Results.entrySet().stream()
                    .map(e -> new XrayTestExecutionResult(e.getKey(), new HashSet<>(e.getValue())))
                    .toList();
        }
    }

    private List<XrayTestResult.Iteration.XrayStep> iterationStepsFromPermutation(HierarchicalTestSuite.TestPermutation p) {
        List<XrayTestResult.Iteration.XrayStep> steps = new ArrayList<>();
        for (String step : p.result.stepDescription) {
            // TODO: We need to track the correct status by step rather than
            // just assign the overall status to it.
            // This is challenging to do unless we know what the custom statuses are.
            steps.add(new XrayTestResult.Iteration.XrayStep(p.result.status, step));
        }
        return steps;
    }

    /* Look at all the statuses we've gotten. Find the most significant status and use that to represent
       the overall status of the test.
     */
    private String calculateOverallStatus(List<String> statuses) {
        int index = statuses.stream()
                .mapToInt(xrayStatuses::indexOf)
                .sorted()
                .findFirst()
                .orElseThrow();
        return xrayStatuses.get(index);
    }


    /**
     * Called when a Gherkin scenario is converted.  Currently logs the Xray test key if found.
     *
     * @param title         The title of the scenario.
     * @param steps         The steps in the scenario.
     * @param tags          The tags associated with the scenario.
     * @param substitutions The substitutions used in the scenario.
     */
    @Override
    public void onScenarioConverted(String title, List<String> steps, Set<String> tags,
                                    Map<String, String> substitutions) {
        addTags(tags, substitutions, "<xray-test-plan>");
        addTags(tags, substitutions, "<xray-test-case>");
        addTags(tags, substitutions, "<xray-test-env>");
    }

    private static void addTags(Set<String> tags, Map<String, String> substitutions, String key) {
        for (Map.Entry<String, String> entry : substitutions.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(key)) {
                // Remove the < > from the key
                tags.add("@%s=%s".formatted(key.substring(1, key.length() - 1), entry.getValue()));
                return; // Exit after finding the first match
            }
        }
    }



    /**
     * Publishes the test execution reports to Xray.
     */
    public List<org.apache.http.HttpResponse> publishReportsToXray() {
        List<org.apache.http.HttpResponse> responses = new ArrayList<>();
        boolean debugging = false;
        if (debugging) {
            System.setProperty("org.apache.commons.logging.Log", "org.apache.commons.logging.impl.SimpleLog");
            System.setProperty("org.apache.commons.logging.simplelog.showdatetime", "true");
            System.setProperty("org.apache.commons.logging.simplelog.log.org.apache.http.wire", "DEBUG");
            System.setProperty("org.apache.commons.logging.simplelog.log.org.apache.http.impl.conn", "DEBUG");
            System.setProperty("org.apache.commons.logging.simplelog.log.org.apache.http.impl.client", "DEBUG");
            System.setProperty("org.apache.commons.logging.simplelog.log.org.apache.http.client", "DEBUG");
            System.setProperty("org.apache.commons.logging.simplelog.log.org.apache.http", "DEBUG");
        }

        try {
            Path info = Files.writeString(tempDirectory.resolve(String.format("info-%s.json", UUID.randomUUID())),
                    objectMapper.writeValueAsString(fieldSupplier.get()), Charsets.UTF_8,
                    StandardOpenOption.CREATE_NEW);
            info.toFile().deleteOnExit();
            for (Map.Entry<String, HierarchicalTestSuite> entry : testCaseXrayTestExecutionResultMap.entrySet()) {
                for (XrayTestExecutionResult executionResult : entry.getValue().Info2Results()) {
                    String requestBody = objectMapper.writeValueAsString(executionResult);
                    // Convert the request to files as per the xray API specification
                    Path results = Files.writeString(tempDirectory.resolve(Path.of(String.format("results-%s.json", UUID.randomUUID()))),
                            requestBody, Charsets.UTF_8,
                            StandardOpenOption.CREATE_NEW);
                    results.toFile().deleteOnExit();

                    HttpPost post = new HttpPost(getXrayReportUrl());
                    post.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + xrayAuth.getAuthToken());
                    post.addHeader(HttpHeaders.CONTENT_TYPE, String.format("%s; boundary=%s",
                            ContentType.MULTIPART_FORM_DATA.getMimeType(),
                            "X-PDSL-XRAY-PLUGIN-BOUNDARY"));
                    post.addHeader(HttpHeaders.ACCEPT, "*/*");
                    post.addHeader(HttpHeaders.ACCEPT_ENCODING, "*/*");

                    post.setEntity(MultipartEntityBuilder.create()
                            .addBinaryBody("results", results.toFile(), ContentType.APPLICATION_JSON, results.getFileName().toString())
                            .addBinaryBody("info", info.toFile(), ContentType.APPLICATION_JSON, info.getFileName().toString())
                            .setLaxMode()
                            .setBoundary("X-PDSL-XRAY-PLUGIN-BOUNDARY")
                            .setCharset(StandardCharsets.UTF_8)
                            .build());
                    try (CloseableHttpClient client = HttpClients.createDefault();
                         CloseableHttpResponse response = client
                                 .execute(post)) {
                        responses.add(response);
                        final int statusCode = response.getStatusLine().getStatusCode();
                        if (statusCode >= 200 && statusCode < 300) {
                            logger.info(String.format("Xray test execution results imported successfully\n%s%n",
                                    new String(response.getEntity().getContent().readAllBytes())));
                        } else {
                            logger.severe(String.format("Failed to import Xray test execution results: %s - %s%n",
                                    response.getStatusLine(), new String(response.getEntity().getContent().readAllBytes())));
                        }
                    }
                    @SuppressWarnings("unused") boolean unused = results.toFile().delete();
                }
            }
            @SuppressWarnings("unused") boolean unused = info.toFile().delete();
            testCaseXrayTestExecutionResultMap.clear();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return responses;
    }


    /**
     * Retrieves the Xray report URL from the properties file.
     *
     * @return The Xray report URL.
     * @throws RuntimeException If the properties file or URL is not found.
     */
    private String getXrayReportUrl() {
        prop = this.xrayAuth.getProperties();
        String apiUrl = prop.getProperty("xray.api.report.url");
        if (apiUrl == null) {
            throw new RuntimeException("xray.api.url must be defined in the properties file.");
        }
        return apiUrl;
    }

    /**
     * Adds test results to the internal map for later publishing to Xray.
     *
     * @param results The collection of test results.
     */
    public void addResults(Collection<TestResult> results) {

        for (TestResult result : results) {
            TestCase testCase = result.getTestCase();
            if (testCase instanceof TaggedTestCase taggedTestCase) {
                Collection<String> testPlanTags = extractTags(taggedTestCase.getTags(), "@xray-test-plan=");
                Collection<String> caseTags = extractTags(taggedTestCase.getTags(), "@xray-test-case=");
                Set<String> envTags = extractTags(taggedTestCase.getTags(), "@xray-test-env=").stream()
                        .map(s -> Arrays.asList(s.split(",")))
                        .flatMap(Collection::stream)
                        .collect(Collectors.toUnmodifiableSet());

                List<Info> listOfInfo = testPlanTags.stream().map(tag -> new Info(
                        title, // testCase.getTestTitle(),
                        description, //String.join("", taggedTestCase.getUnfilteredPhraseBody()),
                        tag,
                        envTags.isEmpty() ? environments : envTags
                )).toList();

                Set<TestItem> testItems = caseTags.stream()
                        .map(t -> new TestItem(t, result.getStatus().toString(),
                                testCase.getUnfilteredPhraseBody(),
                                result.getFailureReason().orElse(null),
                                result.getFailingPhrase().isPresent() ? result.getFailingPhrase().get().getPrefilteredIndex() : null
                                ))
                .collect(Collectors.toSet());

                try {
                    tryAddParams(testCase.getOriginalSource(), listOfInfo, testItems);
                } catch (NumberFormatException | IllegalStateException e) {
                    HierarchicalTestSuite hierarchicalTest = testCaseXrayTestExecutionResultMap.computeIfAbsent(
                            testCase.getOriginalSource().getPath(), k -> new HierarchicalTestSuite());
                    for (Info info : listOfInfo) {
                        testItems.forEach(testItem -> {
                            hierarchicalTest.addTestResult(
                                    testCase.getOriginalSource(),
                                    info,
                                    testItem,
                                    -1,
                                    -1,
                                    -1
                            );
                        });
                    }
                }
            }
        }
    }

    private void tryAddParams(URI uri, List<Info> listOfInfo, Set<TestItem> testItems) {
        HierarchicalTestSuite hierarchicalTest = testCaseXrayTestExecutionResultMap.computeIfAbsent(
                uri.getPath(), k -> new HierarchicalTestSuite());
        Map<String, String> params = Arrays.stream(uri.getQuery().split("&"))
                .filter(s -> s.split("=").length == 2)
                .collect(Collectors.toMap(s -> s.split("=")[0], s -> s.split("=")[1]));
        if (params.containsKey(GherkinScenario.ScenarioPosition.RULE_INDEX)
                && params.containsKey(GherkinScenario.ScenarioPosition.ORDINAL)
                && params.containsKey(GherkinScenario.ScenarioPosition.TABLE_INDEX)) {
            int tableIndex = Integer.parseInt(params.get(GherkinScenario.ScenarioPosition.TABLE_INDEX));
            for (Info info : listOfInfo) {
                testItems.forEach(testItem -> {
                    hierarchicalTest.addTestResult(
                            uri,
                            info,
                            testItem,
                            Integer.parseInt(params.get(GherkinScenario.ScenarioPosition.RULE_INDEX)),
                            Integer.parseInt(params.get(GherkinScenario.ScenarioPosition.ORDINAL)),
                            tableIndex);
                });

            }
            return;
        }
        throw new IllegalStateException("Could not find parameters for " + uri);
    }

    private static List<String> extractTags(Collection<String> tags, String prefix) {
        return tags.stream()
                .filter(tag -> tag.toLowerCase().startsWith(prefix.toLowerCase()))
                .map(tag -> {
                    String[] split = tag.split("=", 2);
                    if (split.length == 2) {
                        return Optional.of(split[1]);
                    } else {
                        return Optional.empty();
                    }
                })
                .filter(Optional::isPresent)
                .map(Optional::get)
                .map(Object::toString)
                .collect(Collectors.toList());
    }

    public Collection<XrayTestExecutionResult> getXrayPayload() {
        return testCaseXrayTestExecutionResultMap.entrySet().stream()
                .flatMap(e -> e.getValue().Info2Results().stream())
                .toList();
    }

    @Override
    public void onAfterTestSuite(Collection<? extends TestCase> testCases, ParseTreeListener listener,
                                 MetadataTestRunResults results, String context) {
        addResults(results.getTestResults());
    }

    @Override
    public void onAfterTestSuite(Collection<? extends SharedTestCase> testCases,
                                 MetadataTestRunResults results, String context) {
        addResults(results.getTestResults());
    }

    @Override
    public void onAfterTestSuite(Collection<? extends TestCase> testCases,
                                 org.antlr.v4.runtime.tree.ParseTreeVisitor<?> visitor, MetadataTestRunResults results,
                                 String context) {
        addResults(results.getTestResults());
    }
}
