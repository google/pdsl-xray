package com.pdsl.xray.observers;

import com.pdsl.gherkin.GherkinObserver;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class TabularScenarioObserver implements GherkinObserver {

  /**
   * Observes Gherkin scenarios during conversion and adds Xray-specific tags based on
   * substitutions. This observer integrates PickleJar with Xray by dynamically adding tags that
   * Xray recognizes.
   *
   * <p>When a Gherkin scenario is converted, this observer checks for specific substitution keys
   * ({@code xray-test-plan}, {@code xray-test-case}, {@code xray-test-platform}, and
   * {@code xray-test-env}) in the provided substitutions map. If a key is found, it adds a tag in
   * the format {@code @key=value} to the scenario's tags. These tags are used by Xray to associate
   * the scenario with specific test plans, test cases, platforms, and environments.
   *
   * <p>Valid Tags:
   * <ul>
   * <li>{@code @xray-test-plan=PLAN-123}: Associates the scenario with the Xray test plan "PLAN-123".</li>
   * <li>{@code @xray-test-case=TC-456}: Associates the scenario with the Xray test case "TC-456".</li>
   * <li>{@code @xray-test-platform=Chrome}: Specifies the platform on which the scenario should be tested.</li>
   * <li>{@code @xray-test-env=Staging}: Specifies the environment in which the scenario should be tested.</li>
   * </ul>
   *
   * <p>Example usage in a Gherkin feature file with substitutions:
   *
   * <pre>{@code
   * Feature: My Feature
   *
   * Scenario: My Scenario
   * Given a step
   * When an action
   * Then a result
   *
   * # Substitutions (typically provided via configuration or command-line arguments)
   * # xray-test-plan=PLAN-123
   * # XRAY-TEST-case=TC-456
   * # xray-Test-Platform=Chrome
   * # XRAY-TEST-ENV=Staging
   * }</pre>
   *
   * <p>After conversion, the scenario will have the following tags:
   *
   * <pre>{@code
   * @xray-test-plan=PLAN-123
   * @xray-test-case=TC-456
   * @xray-test-platform=Chrome
   * @xray-test-env=Staging
   * }</pre>
   *
   * <p>These tags are then used by Xray during test execution and result reporting.
   *
   * @param title The title of the converted scenario.
   * @param steps The list of steps in the scenario.
   * @param tags The set of tags associated with the scenario.
   * @param substitutions The map of substitutions provided during conversion.
   */
  @Override
  public void onScenarioConverted(String title, List<String> steps, Set<String> tags,
      Map<String, String> substitutions) {
    addTag(tags, substitutions, "xray-test-plan");
    addTag(tags, substitutions, "xray-test-case");
    addTag(tags, substitutions, "xray-test-platform");
    addTag(tags, substitutions, "xray-test-env");
  }

  /**
   * Adds a tag to the given set of tags if a value for the specified key exists in the
   * substitutions map. This method is case-insensitive when checking for the key.
   *
   * @param tags The set of tags to which the new tag will be added.
   * @param substitutions A map containing key-value pairs for substitutions.
   * @param key The key for the tag to be added.
   */
  private void addTag(Set<String> tags, Map<String, String> substitutions, String key) {
    for (Map.Entry<String, String> entry : substitutions.entrySet()) {
      if (entry.getKey().equalsIgnoreCase(key)) {
        tags.add("@%s=%s".formatted(key, entry.getValue()));
        return; // Exit after finding the first match
      }
    }
  }
}