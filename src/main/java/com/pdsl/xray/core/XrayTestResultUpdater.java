package com.pdsl.xray.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pdsl.executors.ExecutorObserver;
import com.pdsl.gherkin.GherkinObserver;
import com.pdsl.reports.TestResult;
import com.pdsl.xray.models.Info;
import com.pdsl.xray.models.XrayTestCase;
import com.pdsl.xray.models.XrayTestExecutionResult;
import com.pdsl.xray.models.XrayTestResult;
import com.pdsl.reports.MetadataTestRunResults;
import com.pdsl.specifications.Phrase;
import com.pdsl.testcases.TaggedTestCase;
import com.pdsl.testcases.TestCase;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import org.antlr.v4.runtime.tree.ParseTreeListener;
import org.antlr.v4.runtime.tree.ParseTreeVisitor;
import org.antlr.v4.runtime.tree.ParseTreeWalker;

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
  private final Map<TestCase, List<XrayTestExecutionResult>> testCaseXrayTestExecutionResultMap = new HashMap<>();
  private final Logger logger = Logger.getLogger(this.getClass().getName());
  private final Properties prop = new Properties();
  private final List<String> environments;
  private final String user;

  /**
   * Constructor for XrayTestResultUpdater.
   *
   * @param xrayAuth The Xray authentication object.
   */
  public XrayTestResultUpdater(XrayAuth xrayAuth) {
    this.xrayAuth = xrayAuth;
    this.objectMapper = new ObjectMapper();
    loadXrayProperties();
    String environmentsStr = prop.getProperty("xray.environments");
    this.environments = Arrays.asList(environmentsStr.split(","));
    this.user = prop.getProperty("xray.user");
  }

  /**
   * Loads Xray properties from the xray.properties file.
   *
   * @throws RuntimeException If an error occurs while loading the properties.
   */
  private void loadXrayProperties() {
    try {
      prop.load(new FileInputStream("src/test/resources/xray.properties"));
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  /**
   * Called when a Gherkin scenario is converted.  Currently logs the Xray test key if found.
   *
   * @param title The title of the scenario.
   * @param steps The steps in the scenario.
   * @param tags The tags associated with the scenario.
   * @param substitutions The substitutions used in the scenario.
   */
  @Override
  public void onScenarioConverted(String title, List<String> steps, Set<String> tags,
      Map<String, String> substitutions) {
    String xrayTestKey = extractXrayTestKey(tags, "@xray-test-case");
    if (xrayTestKey != null) {
      logger.info("Found Xray Test Key: %s for scenario: %s".formatted(xrayTestKey, title));
    } else {
      logger.info("No Xray Test Key found for scenario: %s".formatted(title));
    }
  }

  /**
   * Called after the test suite execution.  Adds the test results.
   *
   * @param testCases The collection of test cases.
   * @param visitor The parse tree visitor.
   * @param results The metadata containing the test results.
   * @param context The context of the test suite.
   */

  @Override
  public void onAfterTestSuite(Collection<? extends TestCase> testCases,
      org.antlr.v4.runtime.tree.ParseTreeVisitor<?> visitor, MetadataTestRunResults results,
      String context) {
    addResults(results.getTestResults());
  }

  /**
   * Publishes the test execution reports to Xray.
   */
  public void publishReportsToXray() {

    List<XrayTestResult> tests = new ArrayList<>();
    for (List<XrayTestExecutionResult> results : testCaseXrayTestExecutionResultMap.values()) {

      for (XrayTestExecutionResult result : results) {
        try {
          String requestBody = objectMapper.writeValueAsString(result);

          HttpClient client = HttpClient.newHttpClient();
          HttpRequest request = HttpRequest.newBuilder()
              .uri(URI.create(getXrayReportUrl()))
              .header("Authorization", "Bearer " + xrayAuth.getAuthToken())
              .header("Content-Type", "application/json")
              .POST(HttpRequest.BodyPublishers.ofString(requestBody))
              .build();

          HttpResponse<String> response = client.send(request,
              HttpResponse.BodyHandlers.ofString());

          if (response.statusCode() >= 200 && response.statusCode() < 300) {
            System.out.printf("Xray test execution results imported successfully\n%s%n",
                response.body());
          } else {
            System.err.printf("Failed to import Xray test execution results: %d - %s%n",
                response.statusCode(), response.body());
          }
        } catch (IOException | InterruptedException e) {
          System.err.printf("Error importing Xray test execution results: %s%n", e.getMessage());
        } finally {
          testCaseXrayTestExecutionResultMap.clear();
        }
      }
    }
  }

  /**
   * Retrieves the Xray report URL from the properties file.
   *
   * @return The Xray report URL.
   * @throws RuntimeException If the properties file or URL is not found.
   */
  private String getXrayReportUrl() {
    Properties properties = new Properties();
    try (InputStream input = XrayAuth.class.getClassLoader()
        .getResourceAsStream("xray.properties")) {
      if (input == null) {
        throw new RuntimeException("Unable to find properties file: xray.properties");
      }
      properties.load(input);
      String apiUrl = properties.getProperty("xray.api.report.url");
      if (apiUrl == null) {
        throw new RuntimeException("xray.api.url must be defined in the properties file.");
      }
      return apiUrl;
    } catch (IOException ex) {
      throw new RuntimeException("Error loading properties file: " + ex.getMessage(), ex);
    }
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
        Collection<String> tags = extractTags(taggedTestCase.getTags(), "@xray-test-plan=");
        Collection<String> caseTags = extractTags(taggedTestCase.getTags(), "@xray-test-case=");

        List<Info> listOfInfo = tags.stream().map(tag -> new Info(
            testCase.getTestTitle(),
            String.join("", taggedTestCase.getUnfilteredPhraseBody()),
            tag,
            environments,
            user
        )).collect(Collectors.toList());

        List<XrayTestResult> xrayTestResults = caseTags.stream().map(t -> new XrayTestResult(
            t, result.getStatus().toString()
        )).collect(Collectors.toList());

        List<XrayTestExecutionResult> resultsList = testCaseXrayTestExecutionResultMap.computeIfAbsent(
            testCase, k -> new ArrayList<>());
        for (Info info : listOfInfo) {
          resultsList.add(new XrayTestExecutionResult(info, xrayTestResults));
        }
      }
    }

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

  /**
   * Extracts the Xray test key from the test case tags.
   *
   * @param testCase The test case.
   * @param tagName The tag name to look for.
   * @return The Xray test key, or null if not found.
   */
  private String extractXrayTestKey(TestCase testCase, String tagName) {
    if (testCase instanceof TaggedTestCase taggedTestCase) {
      return extractXrayTestKey(taggedTestCase.getTags(), tagName);
    }
    return null;
  }

  /**
   * Extracts the Xray test key from a collection of tags.
   *
   * @param tags The collection of tags.
   * @param tagName The tag name to look for.
   * @return The Xray test key, or null if not found.
   */
  private String extractXrayTestKey(Collection<String> tags, String tagName) {
    Optional<String> xrayKey = tags.stream()
        .filter(tag -> tag.startsWith(tagName))
        .map(tag -> tag.substring(tagName.length()))
        .findFirst();
    return xrayKey.orElse(null);
  }

  private String extractTestStatus(TestCase testCase) {

    if (testCase instanceof XrayTestCase) {
      return ((XrayTestCase) testCase).getTestResult();
    } else {
      //TODO: decide default
      return "PASSED";
    }
  }

  private Throwable extractFailureReason(TestCase testCase) {
    if (testCase instanceof XrayTestCase) {
      return ((XrayTestCase) testCase).getFailureException();
    } else {
      return null;
    }
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
  public void onAfterPhrase(ParseTreeVisitor<?> visitor, Phrase activePhrase) {

  }

  @Override
  public void onPhraseFailure(ParseTreeListener listener, Phrase activePhrase, TestCase testCase,
      Throwable exception) {

  }

  @Override
  public void onPhraseFailure(ParseTreeVisitor<?> visitor, Phrase activePhrase, TestCase testCase,
      Throwable exception) {

  }


  @Override
  public void onBeforeTestSuite(Collection<? extends TestCase> testCases,
      ParseTreeListener listener, String context) {

  }

  @Override
  public void onBeforeTestSuite(Collection<? extends TestCase> testCases, String context) {

  }


  @Override
  public void onAfterTestSuite(Collection<? extends TestCase> testCases, ParseTreeListener listener,
      MetadataTestRunResults results, String context) {
    addResults(results.getTestResults());
  }

  @Override
  public void onAfterTestSuite(Collection<? extends TestCase> testCases,
      MetadataTestRunResults results, String context) {
    addResults(results.getTestResults());
  }

  @Override
  public void onBeforeTestSuite(Collection<? extends TestCase> testCases,
      ParseTreeVisitor<?> visitor,
      String context) {

  }


}
