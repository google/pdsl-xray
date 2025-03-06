package com.pdsl.xray.observers;

import com.pdsl.gherkin.GherkinObserver;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
