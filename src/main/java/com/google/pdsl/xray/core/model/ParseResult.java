package com.google.pdsl.xray.core.model;

import java.util.Set;

/**
 * Container for the result of parsing a test plan GraphQL response.
 *
 * @param testKeys the set of extracted test keys
 * @param total    the total number of tests reported in the test plan
 */
public record ParseResult(Set<String> testKeys, int total) {
}
