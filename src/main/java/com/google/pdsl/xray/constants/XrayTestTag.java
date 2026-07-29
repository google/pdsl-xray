package com.google.pdsl.xray.constants;

/**
 * XrayTags enum representing the supported Xray tags with their prefix values.
 */
public enum XrayTestTag {

    CASE("xray-test-case"),
    ENV("xray-test-env"),
    EXECUTION("xray-test-execution"),
    PLAN("xray-test-plan");

    // Defined locally within the enum
    private static final String GHERKIN_TAG_PREFIX = "@";
    private static final String GHERKIN_EQUAL = "=";

    private final String value;

    XrayTestTag(String value) {
        this.value = value;
    }

    /**
     * Gets the full Gherkin tag string including the prefix symbol.
     *
     * @return the complete tag string (e.g. "@xray-test-env=")
     */
    public String getTagValue() {
        return GHERKIN_TAG_PREFIX + value + GHERKIN_EQUAL;
    }

    /**
     * Wraps the tag value in angle brackets as an HTML or XML tag.
     *
     * @return the tag value wrapped in angle brackets (e.g., "&lt;xray-test-case&gt;")
     */
    public String toHtmlTag() {
        return "<" + value + ">";
    }

    @Override
    public String toString() {
        return value;
    }
}
