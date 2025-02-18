package com.pdsl.xray.observers;

import com.pdsl.gherkin.GherkinObserver;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * This class observes Gherkin scenarios and adds Xray-related tags to them.
 *
 * The `onScenarioConverted` method is called when a Gherkin scenario is successfully converted into
 * a testable format. This method adds Xray-specific tags to the scenario's tag list.
 *
 * These tags can be used later for filtering, grouping, and reporting in Xray or other test
 * management systems.
 */
public class PickleJarScenarioObserver implements GherkinObserver {


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
   * substitutions map.
   *
   * @param tags The set of tags to which the new tag will be added.
   * @param substitutions A map containing key-value pairs for substitutions.
   * @param key The key for the tag to be added.
   */
  private void addTag(Set<String> tags, Map<String, String> substitutions, String key) {
    String value = substitutions.get(key);
    if (value != null) {
      tags.add("@%s=%s".formatted(key, value));
    }
  }
}
