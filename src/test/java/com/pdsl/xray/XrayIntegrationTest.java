package com.pdsl.xray;

import com.pdsl.executors.DefaultPolymorphicDslTestExecutor;
import com.pdsl.gherkin.DefaultGherkinTestSpecificationFactory;
import com.pdsl.gherkin.DefaultGherkinTestSpecificationFactoryGenerator;
import com.pdsl.gherkin.PickleJarFactory;
import com.pdsl.grammars.AllGrammarsLexer;
import com.pdsl.grammars.AllGrammarsParser;
import com.pdsl.grammars.AllGrammarsParserBaseListener;
import com.pdsl.specifications.FilteredPhrase;
import com.pdsl.transformers.PolymorphicDslPhraseFilter;
import com.pdsl.xray.core.XrayAuth;
import com.pdsl.xray.core.XrayTestResultUpdater;
import com.pdsl.xray.observers.PickleJarScenarioObserver;
import java.io.InputStream;
import java.nio.file.Paths;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.antlr.v4.runtime.tree.ParseTreeListener;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.TestTemplate;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.TestTemplateInvocationContext;
import org.junit.jupiter.engine.descriptor.PdslConfigParameter;
import org.junit.jupiter.engine.descriptor.PdslExecutable;
import org.junit.jupiter.engine.descriptor.PdslGherkinInvocationContextProvider;
import org.junit.jupiter.engine.descriptor.PdslTestParameter;

/**
 * This class contains JUnit Jupiter tests for Xray integration. It uses the Pdsl framework to
 * execute Gherkin scenarios and integrates with Xray for test management and reporting.
 */
public class XrayIntegrationTest {

  private int totalRunTests = 0;
  private static final XrayAuth xrayAuth = XrayAuth.fromPropertiesFile(
      "src/test/resources/xray.properties");

  private static final XrayTestResultUpdater updater = new XrayTestResultUpdater(xrayAuth);
  private static final PickleJarFactory PICKLE_JAR_FACTORY = PickleJarFactory.getDefaultPickleJarFactory();
  private static final PolymorphicDslPhraseFilter MY_CUSTOM_PDSL_PHRASE_FILTER = new MyCustomPDSLPhraseFilter();


  @TestTemplate
  @ExtendWith(IosExtension.class)
  public void iosTest(PdslExecutable executable) {

    executable.execute();
    totalRunTests++;
    assert (totalRunTests == 1);
  }

  @TestTemplate
  @ExtendWith(AndroidExtension.class)
  public void androidTest(PdslExecutable executable) {

    executable.execute();
    totalRunTests++;
    assert (totalRunTests == 1);
  }

  /**
   * A supplier that provides an instance of AllGrammarsParserBaseListener.
   */
  private static class IosExtension extends PdslGherkinInvocationContextProvider {

    private static final DefaultPolymorphicDslTestExecutor traceableTestRunExecutor = new DefaultPolymorphicDslTestExecutor();
    private static final PickleJarScenarioObserver PICKLE_JAR_SCENARIO_OBSERVER = new PickleJarScenarioObserver();

    @Override
    public Stream<TestTemplateInvocationContext> provideTestTemplateInvocationContexts(
        ExtensionContext context) {
      traceableTestRunExecutor.registerObserver(updater);
      PICKLE_JAR_FACTORY.registerObserver(PICKLE_JAR_SCENARIO_OBSERVER);
      return getInvocationContext(PdslConfigParameter.createGherkinPdslConfig(
              List.of(
                  new PdslTestParameter.Builder(parseTreeListenerSupplier,
                      AllGrammarsLexer.class, AllGrammarsParser.class)
                      .withTagExpression("@ios")
                      .withIncludedResources(new String[]{"xray_integration.feature"})
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
          .build())
          .stream();
    }
  }

  private static final Supplier<ParseTreeListener> parseTreeListenerSupplier = AllGrammarsParserBaseListener::new;

  private static class AndroidExtension extends PdslGherkinInvocationContextProvider {

    private static final DefaultPolymorphicDslTestExecutor traceableTestRunExecutor = new DefaultPolymorphicDslTestExecutor();


    @Override
    public Stream<TestTemplateInvocationContext> provideTestTemplateInvocationContexts(
        ExtensionContext context) {
      traceableTestRunExecutor.registerObserver(updater);
      return getInvocationContext(PdslConfigParameter.createGherkinPdslConfig(
              List.of(
                  new PdslTestParameter.Builder(parseTreeListenerSupplier,
                      AllGrammarsLexer.class, AllGrammarsParser.class)
                      .withTagExpression("@android")
                      .withIncludedResources(new String[]{"xray_integration.feature"})
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
          .build())
          .stream();
    }
  }

  /**
   * Publishes the test results to Xray after all tests have been executed.
   */
  @AfterAll
  public static void publishReportsToXray() {

    updater.publishReportsToXray();
  }

  public static class MyCustomPDSLPhraseFilter implements PolymorphicDslPhraseFilter {

    /**
     * Filters the given list of phrases based on custom logic.
     *
     * @param testInput A list of input streams for the test.
     * @return An optional list of filtered phrases, or Optional.empty() if no filtering is applied.
     */
    @Override
    public Optional<List<FilteredPhrase>> filterPhrases(List<InputStream> testInput) {
      return Optional.empty();
    }
  }


}



