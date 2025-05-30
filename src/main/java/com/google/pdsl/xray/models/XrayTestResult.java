package com.google.pdsl.xray.models;
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

import java.util.List;
import java.util.Map;

/**
 * A Data Transfer Object that represents the result of an XRAY test case.
 * <p>
 * The test case may in fact represent multiple results through the 'examples' or 'iterations'
 * feature XRAY has for tests.
 * <p>
 * This object is intended to be serialized and follows the JSON schema used for the v2
 * REST API.
 */
public record XrayTestResult(String testKey, String status, List<String> examples) {
    /**
     * Represents a single iteration for a "Manual" style test case in XRAY.
     * <p>
     * This is a permutation of a specific test. One test may have many permutations in an execution.
     * @param name The iteration name to distinguish it from others
     * @param status the execution status, e.g. PASSED or FAILED
     * @param parameters a map of parameter names and corresponding values
     * @param steps details about the sequence of test execution and validation
     */
    public record Iteration(String name, String status, Map<String, String> parameters, List<XrayStep> steps) {
        /**
         * A summary for a particular action taken in a XRAY manual test case.
         * @param status the execution status, e.g. PASSED or FAILED
         * @param actualResult a free-form description of what was observed in this step
         */
        public record XrayStep(String status, String actualResult) {}
    }
}
