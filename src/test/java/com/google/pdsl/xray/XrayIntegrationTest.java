package com.google.pdsl.xray;

import com.google.pdsl.xray.core.XrayAuth;
import com.google.pdsl.xray.core.XrayTestResultUpdater;
import com.pdsl.executors.DefaultPolymorphicDslTestExecutor;
import com.pdsl.gherkin.DefaultGherkinTestSpecificationFactory;
import com.pdsl.gherkin.DefaultGherkinTestSpecificationFactoryGenerator;
import com.pdsl.gherkin.PickleJarFactory;
import com.pdsl.grammars.AllGrammarsLexer;
import com.pdsl.grammars.AllGrammarsParser;
import com.pdsl.grammars.AllGrammarsParserBaseListener;
import com.pdsl.specifications.FilteredPhrase;
import com.pdsl.transformers.PolymorphicDslPhraseFilter;
import org.antlr.v4.runtime.tree.ParseTreeListener;
import org.apache.http.HttpResponse;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.TestTemplate;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.TestTemplateInvocationContext;
import org.junit.jupiter.engine.descriptor.PdslConfigParameter;
import org.junit.jupiter.engine.descriptor.PdslExecutable;
import org.junit.jupiter.engine.descriptor.PdslGherkinInvocationContextProvider;
import org.junit.jupiter.engine.descriptor.PdslTestParameter;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
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
 * This class contains JUnit Jupiter tests for Xray integration. It uses the Pdsl framework to
 * execute Gherkin scenarios and integrates with Xray for test management and reporting.
 */
public class XrayIntegrationTest {


  private static final XrayAuth xrayAuth =  XrayAuth.fromPropertiesFile("src/test/resources/xray.properties");
  private static final XrayTestResultUpdater updater = new XrayTestResultUpdater.Builder(

          "PDSL-XRAY Plugin E2E Tests",
          """
                  End to end tests for the pdsl-xray plugin.
                  These tests support the gherkin protocol both through special fields in 
                  the examples table or tags directly above scenarios:
                  |xray-test-plan | xray-test-case |  xray-test-platform  | xray-test-env |
                  """,
          () -> Map.of(
                  "fields", Map.of(
                         "project", Map.of("key", xrayAuth.getProperties().getProperty("xray.project.key")),
                         "summary", "Automated test run by Polymorphic DSL Test Framework",
                          "issuetype", Map.of("name", "Test Execution"),
                          "assignee", Map.of("accountId", xrayAuth.getProperties().getProperty("xray.reporter.accountId")),
                          "reporter", Map.of("accountId", xrayAuth.getProperties().getProperty("xray.reporter.accountId"))
                          )
          )).withXrayAuth(xrayAuth)
      .build();

  private static final DefaultPolymorphicDslTestExecutor traceableTestRunExecutor = new DefaultPolymorphicDslTestExecutor();
  private static final PolymorphicDslPhraseFilter MY_CUSTOM_PDSL_PHRASE_FILTER = new MyCustomPDSLPhraseFilter();
  private static final PickleJarFactory PICKLE_JAR_FACTORY = init();
  private static final Supplier<ParseTreeListener> parseTreeListenerSupplier = AllGrammarsParserBaseListener::new;

  private static PickleJarFactory init() {

    traceableTestRunExecutor.registerObserver(updater);

    PickleJarFactory PICKLE_JAR_FACTORY = PickleJarFactory.getDefaultPickleJarFactory();
    PICKLE_JAR_FACTORY.registerObserver(updater);
    return PICKLE_JAR_FACTORY;
  }
  @TestTemplate
  @ExtendWith(IosExtension.class)
  public void iosTest(PdslExecutable executable) {
    executable.execute();
  }

  @TestTemplate
  @ExtendWith(AndroidExtension.class)
  public void androidTest(PdslExecutable executable) {
    executable.execute();
  }

  private static PdslConfigParameter createParameterWithTag(String tag) {
    return PdslConfigParameter.createGherkinPdslConfig(
                    List.of(
                            new PdslTestParameter.Builder(parseTreeListenerSupplier,
                                    AllGrammarsLexer.class, AllGrammarsParser.class)
                                    .withTagExpression(tag)
                                    .withIncludedResources(new String[]{"XRayIntegration.feature", "PdslXrayTabular.feature"})
                                    .build()
                    )
            )
            .withApplicationName("Polymorphic DSL Framework")
            .withContext("User Acceptance Test")
            .withResourceRoot(Paths.get("src/test/resources/features").toUri())
            .withRecognizerRule("polymorphicDslAllRules")
            .withTestRunExecutor(() -> traceableTestRunExecutor)
            .withTestSpecificationFactoryGenerator(
                    () -> new DefaultGherkinTestSpecificationFactoryGenerator(
                            new DefaultGherkinTestSpecificationFactory.Builder((MY_CUSTOM_PDSL_PHRASE_FILTER))
                                    .withPickleJarFactory(PICKLE_JAR_FACTORY)))
            .build();
  }
  /**
   * A supplier that provides an instance of AllGrammarsParserBaseListener.
   */
  private static class IosExtension extends PdslGherkinInvocationContextProvider {

    @Override
    public Stream<TestTemplateInvocationContext> provideTestTemplateInvocationContexts(ExtensionContext context) {
      if (true) {
        return new ArrayList<TestTemplateInvocationContext>().stream();
      }
      return getInvocationContext(createParameterWithTag("@ios")).stream();
    }
  }


  private static class AndroidExtension extends PdslGherkinInvocationContextProvider {
    @Override
    public Stream<TestTemplateInvocationContext> provideTestTemplateInvocationContexts(ExtensionContext context) {
      return getInvocationContext(createParameterWithTag("@wip")).stream();
    }
  }

  /**
   * Publishes the test results to Xray after all tests have been executed.
   */
  @AfterAll
  public static void publishReportsToXray() {
    // Validation: Check if the updater has created a valid Xray payload.
    assertNotNull(updater.getXrayPayload(), "Xray payload is null.");
    List<HttpResponse> responses = updater.publishReportsToXray();
    assertFalse(responses.isEmpty());
  }

  private static class MyCustomPDSLPhraseFilter implements PolymorphicDslPhraseFilter {
    @Override
    public Optional<List<FilteredPhrase>> filterPhrases(List<InputStream> testInput) {
      return Optional.empty();
    }
  }
}
