package com.google.pdsl.xray.core.model;

import java.net.URI;
import java.util.List;
import java.util.Set;

public record TestPlan(String key, List<XrayTestCase> testCases) {
    public record XrayTestCase(String key, Set<String> environments, URI uri) {
    }
}
