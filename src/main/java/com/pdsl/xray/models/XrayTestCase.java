package com.pdsl.xray.models;

import com.pdsl.testcases.TestCase;
import com.pdsl.specifications.FilteredPhrase;
import com.pdsl.testcases.TestSection;
import java.net.URI;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
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
public final class XrayTestCase implements TestCase {

  private final URI originalSource;
  private final List<String> unfilteredPhraseBody;
  private final List<String> contextFilteredPhraseBody;
  private final String testTitle;
  private final List<FilteredPhrase> filteredPhrases;

  public XrayTestCase(URI originalSource, List<String> unfilteredPhraseBody,
      List<String> contextFilteredPhraseBody, String testTitle,
      List<FilteredPhrase> filteredPhrases) {
    this.originalSource = originalSource;
    this.unfilteredPhraseBody = unfilteredPhraseBody;
    this.contextFilteredPhraseBody = contextFilteredPhraseBody;
    this.testTitle = testTitle;
    this.filteredPhrases = filteredPhrases;
  }

  @Override
  public URI getOriginalSource() {
    return originalSource;
  }

  @Override
  public List<String> getUnfilteredPhraseBody() {
    return unfilteredPhraseBody;
  }

  @Override
  public List<String> getContextFilteredPhraseBody() {
    return contextFilteredPhraseBody;
  }

  @Override
  public String getTestTitle() {
    return testTitle;
  }

  @Override
  public Iterator<TestSection> getContextFilteredTestSectionIterator() {
    return null;
  }

  @Override
  public List<FilteredPhrase> getFilteredPhrases() {
    return filteredPhrases;
  }

  public String getTestResult() {
    return contextFilteredPhraseBody.isEmpty() ? "PASS" : "FAIL";
  }

  public Throwable getFailureException() {
    return contextFilteredPhraseBody.isEmpty() ? null : new Throwable("Test Case Failed");
  }

  @Override
  public boolean equals(Object obj) {
    if (obj == this) {
      return true;
    }
    if (obj == null || obj.getClass() != this.getClass()) {
      return false;
    }
    var that = (XrayTestCase) obj;
    return Objects.equals(this.originalSource, that.originalSource) &&
        Objects.equals(this.unfilteredPhraseBody, that.unfilteredPhraseBody) &&
        Objects.equals(this.contextFilteredPhraseBody, that.contextFilteredPhraseBody) &&
        Objects.equals(this.testTitle, that.testTitle) &&
        Objects.equals(this.filteredPhrases, that.filteredPhrases);
  }

  @Override
  public int hashCode() {
    return Objects.hash(originalSource, unfilteredPhraseBody, contextFilteredPhraseBody, testTitle,
        filteredPhrases);
  }

  @Override
  public String toString() {
    return "XrayTestCase[" +
        "originalSource=" + originalSource + ", " +
        "unfilteredPhraseBody=" + unfilteredPhraseBody + ", " +
        "contextFilteredPhraseBody=" + contextFilteredPhraseBody + ", " +
        "testTitle=" + testTitle + ", " +
        "filteredPhrases=" + filteredPhrases + ']';
  }


}
