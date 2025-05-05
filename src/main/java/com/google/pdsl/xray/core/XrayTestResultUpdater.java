package com.google.pdsl.xray.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.pdsl.xray.models.Info;
import com.google.pdsl.xray.models.XrayTestExecutionResult;
import com.google.pdsl.xray.models.XrayTestResult;
import com.pdsl.executors.ExecutorObserver;
import com.pdsl.gherkin.GherkinObserver;
import com.pdsl.reports.MetadataTestRunResults;
import com.pdsl.reports.TestResult;
import com.pdsl.specifications.Phrase;
import com.pdsl.testcases.TaggedTestCase;
import com.pdsl.testcases.TestCase;
import org.antlr.v4.runtime.tree.ParseTreeListener;
import org.antlr.v4.runtime.tree.ParseTreeVisitor;
import org.antlr.v4.runtime.tree.ParseTreeWalker;
import org.apache.commons.codec.Charsets;
import org.apache.http.HttpHeaders;
import org.apache.http.HttpResponse;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.mime.MultipartEntityBuilder;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;

import java.io.IOException;
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
    private final Map<TestCase, Set<XrayTestExecutionResult>> testCaseXrayTestExecutionResultMap = new HashMap<>();
    private final Logger logger = Logger.getLogger(this.getClass().getName());
    private Properties prop;
    private final Set<String> environments;
    private boolean hasResults = false;
    private final String description;
    private final String title;
    private final Supplier<Map<Object, Object>> fieldSupplier;
    private final Path tempDirectory;
    private XrayResultObserver observer = new DefaultXrayObserver(List.of(
            "ABORTED", "EXECUTING", "TODO", "BLOCKED", "FAIL", "PENDING_VALIDATION", "PASS"));

    public static class DefaultXrayObserver implements XrayResultObserver {

        private final List<String> xrayStatusHierarchy;

        public DefaultXrayObserver(List<String> xrayStatusHierarchy) {
            this.xrayStatusHierarchy = xrayStatusHierarchy;
        }

        @Override
        public Set<XrayTestExecutionResult> processResults(TestCase testCase, Set<XrayTestExecutionResult> results) {
            Set<XrayTestExecutionResult> resultsWithModifiedStatus = new HashSet<>(results.size());
            for (XrayTestExecutionResult execution : results) {
                Set<XrayTestResult> modifiedTests = new HashSet<>(execution.tests().size());
                for (XrayTestResult t : execution.tests()) {
                    Optional<String> newStatus = Optional.empty();
                    for (String status : xrayStatusHierarchy) {
                        if (t.examples().stream()
                                .anyMatch(test -> test.status().equalsIgnoreCase(status))) {
                            newStatus = Optional.of(status);
                            break;
                        }
                    }
                    modifiedTests.add(newStatus.map(s -> new XrayTestResult(t.testKey(), s, t.examples()))
                            .orElse(t));
                }
                resultsWithModifiedStatus.add(new XrayTestExecutionResult(execution.info(), modifiedTests));
            }
            return resultsWithModifiedStatus;
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

    public XrayTestResultUpdater(XrayAuth xrayAuth, String title, String description,
                                 Supplier<Map<Object, Object>> fieldSupplier, Path tempDirectoryPath) {
        validateTempDirectory(tempDirectoryPath);
        this.xrayAuth = xrayAuth;
        this.objectMapper = new ObjectMapper();
        prop = xrayAuth.getProperties();
        String environmentsStr = prop.getProperty("xray.environments");
        this.environments = new HashSet<>(Arrays.asList(environmentsStr.split(",")));
        this.description = description;
        this.title = title;
        this.fieldSupplier = fieldSupplier;
        this.tempDirectory = tempDirectoryPath;
    }

    public void setResultProcessorObserver(XrayResultObserver observer) {
        this.observer = observer;
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
        addTags(tags, substitutions, "<xray-iteration>");
    }

    private static void addTags(Set<String> tags, Map<String, String> substitutions, String key) {
        for (Map.Entry<String, String> entry : substitutions.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(key)) {
                // Remove the < > from the key
                tags.add("@%s=%s".formatted(key.substring(1, key.length() - 1), entry.getValue().strip()));
                return; // Exit after finding the first match
            }
        }
    }

    /**
     * Called after the test suite execution.  Adds the test results.
     *
     * @param testCases The collection of test cases.
     * @param visitor   The parse tree visitor.
     * @param results   The metadata containing the test results.
     * @param context   The context of the test suite.
     */

    @Override
    public void onAfterTestSuite(Collection<? extends TestCase> testCases,
                                 ParseTreeVisitor<?> visitor, MetadataTestRunResults results,
                                 String context) {
        addResults(results.getTestResults());
    }

    /**
     * Publishes the test execution reports to Xray.
     */
    public List<HttpResponse> publishReportsToXray() {
        List<HttpResponse> responses = new ArrayList<>();
        boolean debugging = true;
        if (debugging) {
            System.setProperty("org.apache.commons.logging.Log", "org.apache.commons.logging.impl.SimpleLog");
            System.setProperty("org.apache.commons.logging.simplelog.showdatetime", "true");
            System.setProperty("org.apache.commons.logging.simplelog.log.org.apache.http.wire", "DEBUG");
            System.setProperty("org.apache.commons.logging.simplelog.log.org.apache.http.impl.conn", "DEBUG");
            System.setProperty("org.apache.commons.logging.simplelog.log.org.apache.http.impl.client", "DEBUG");
            System.setProperty("org.apache.commons.logging.simplelog.log.org.apache.http.client", "DEBUG");
            System.setProperty("org.apache.commons.logging.simplelog.log.org.apache.http", "DEBUG");
        }
        // Consolodate results so that we don't create one execution per test case
        Map<Info, Set<XrayTestResult>> info2Results = new HashMap<>();
        testCaseXrayTestExecutionResultMap.values().stream()
                .flatMap(Collection::stream)
                .forEach(r -> {
                    Set<XrayTestResult> results = info2Results.computeIfAbsent(r.info(), k -> new HashSet<>());
                    results.addAll(r.tests());
                });
        // If a test had iterations each iteration at this point might be a distinct test case.
        // Consolodate the iteraitons
        Map<Info, Set<XrayTestResult>> consolodatedResults = new HashMap<>();
        for (Map.Entry<Info, Set<XrayTestResult>> entry : info2Results.entrySet()) {
            // Associate each iteration with it's respective test case
           Set<XrayTestResult> testResultsWithConsolodatedIteraitons = associateTestCasesWithIterations(entry.getValue());
           consolodatedResults.put(entry.getKey(), testResultsWithConsolodatedIteraitons);
        }

        try {
        Path info = Files.writeString(tempDirectory.resolve(String.format("info-%s.json", UUID.randomUUID())),
                objectMapper.writeValueAsString(fieldSupplier.get()), Charsets.UTF_8,
                StandardOpenOption.CREATE_NEW);
        info.toFile().deleteOnExit();
        for (Map.Entry<Info, Set<XrayTestResult>> entry : consolodatedResults.entrySet()) {

            String requestBody = objectMapper.writeValueAsString(new XrayTestExecutionResult(entry.getKey(), entry.getValue()));

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

private Set<XrayTestResult> associateTestCasesWithIterations(Collection<XrayTestResult> results) {
    Map<String, XrayTestResult> testCaseToIterations = new HashMap<>();
    results.forEach(r -> {
                XrayTestResult consolodatedResult = testCaseToIterations.computeIfAbsent(r.testKey(), (k) -> new XrayTestResult(r.testKey(), r.status(), new ArrayList<>()));
                consolodatedResult.examples().addAll(r.examples());
            });
    return new HashSet<>(testCaseToIterations.values());
}
/**
 * Adds test results to the internal map for later publishing to Xray.
 *
 * @param results The collection of test results.
 */
public void addResults(Collection<TestResult> results) {
    // It is possible that the results for a test suite will come one at a time
    // We cannot assume we will have all of the tabular scenarios in the colleciton, for example
    for (TestResult result : results) {


        String resultString = switch (result.getStatus()) {
            case STATUS_UNSPECIFIED -> "BLOCKED";
            case PASSED, DUPLICATE -> "PASS";
            case  FAILED, UNRECOGNIZED -> result.getFailureReason().map(e ->
                    e.getMessage()
                            .contains(("An operation is not implemented")) ? "TODO" : "FAIL").orElse("FAIL");
        };
        TestCase testCase = result.getTestCase();
        if (testCase instanceof TaggedTestCase taggedTestCase) {
            Collection<String> testPlanTags = extractTags(taggedTestCase.getTags(), "@xray-test-plan=");
            Collection<String> caseTags = extractTags(taggedTestCase.getTags(), "@xray-test-case=");
            Set<String> envTags = extractTags(taggedTestCase.getTags(), "@xray-test-env=").stream()
                    .map(s -> Arrays.asList(s.split(",")))
                    .flatMap(Collection::stream)
                    .collect(Collectors.toUnmodifiableSet());
            Optional<String> iterationTag = extractTags(taggedTestCase.getTags(), "@xray-iteration=").stream()
                    .findFirst();
            List<Info> listOfInfo = testPlanTags.stream().map(tag -> new Info(
                    title, // testCase.getTestTitle(),
                    description, //String.join("", taggedTestCase.getUnfilteredPhraseBody()),
                    tag,
                    envTags.isEmpty() ? environments : envTags
            )).toList();

            Set<XrayTestResult> xrayResultsByPotentialIteration = caseTags.stream().map(t ->
                    iterationTag.map(
                            s -> new XrayTestResult(t, result.getStatus().toString(),
                                        new ArrayList<>(List.<XrayTestResult.XrayIteration>of(new XrayTestResult.XrayIteration(Long.parseLong(s),
                                                resultString))
                            )))
                            .orElseGet(() -> new XrayTestResult(t, resultString, new ArrayList<>()))
            ).collect(Collectors.toSet());

            // Associate the results with each respective test suite it was associated with
            var resultsPriorToObserverModification = listOfInfo.stream()
                            .map(i -> new XrayTestExecutionResult(i, xrayResultsByPotentialIteration))
                    .collect(Collectors.toSet());
            var finalResults = observer.processResults(testCase, resultsPriorToObserverModification);
            var resultsSet = testCaseXrayTestExecutionResultMap.computeIfAbsent(testCase, (k) -> new HashSet<>());
            resultsSet.addAll(finalResults);
        }
    }
}

public interface XrayResultObserver {

    Set<XrayTestExecutionResult> processResults(TestCase testCase, Set<XrayTestExecutionResult> results);
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


    public boolean hasTestResults() {
    return hasResults;
}

public Object getXrayPayload() {
    return testCaseXrayTestExecutionResultMap;
}


@Override
public void onBeforePhrase(ParseTreeListener listener, ParseTreeWalker walker,
                           Phrase activePhrase) {

}

@Override
public void onBeforePhrase(ParseTreeVisitor<?> visitor, Phrase activePhrase) {

}

@Override
public void onAfterPhrase(ParseTreeListener listener, ParseTreeWalker walker,
                          Phrase activePhrase) {

}

@Override
public void onAfterPhrase(ParseTreeVisitor<?> visitor, Phrase activePhrase) {}

@Override
public void onPhraseFailure(ParseTreeListener listener, Phrase activePhrase, TestCase testCase,
                            Throwable exception) {}

@Override
public void onPhraseFailure(ParseTreeVisitor<?> visitor, Phrase activePhrase, TestCase testCase,
                            Throwable exception) {}


@Override
public void onBeforeTestSuite(Collection<? extends TestCase> testCases,
                              ParseTreeListener listener, String context) {}

@Override
public void onBeforeTestSuite(Collection<? extends TestCase> testCases, String context) {}


@Override
public void onAfterTestSuite(Collection<? extends TestCase> testCases, ParseTreeListener listener,
                             MetadataTestRunResults results, String context) {
    addResults(results.getTestResults());
    this.hasResults = !this.testCaseXrayTestExecutionResultMap.isEmpty();
}

@Override
public void onAfterTestSuite(Collection<? extends TestCase> testCases,
                             MetadataTestRunResults results, String context) {
    addResults(results.getTestResults());
    this.hasResults = !this.testCaseXrayTestExecutionResultMap.isEmpty();
}

@Override
public void onBeforeTestSuite(Collection<? extends TestCase> testCases,
                              ParseTreeVisitor<?> visitor,
                              String context) {
}

}
