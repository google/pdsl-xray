package com.google.pdsl.xray.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.HashSet;
import java.util.Set;
import java.util.logging.Logger;
import com.google.pdsl.xray.core.model.ParseResult;

/**
 * Parses Xray GraphQL API responses to extract test plan information.
 */
public class XrayTestPlanParser {

    private final ObjectMapper objectMapper;
    private final Logger logger = Logger.getLogger(this.getClass().getName());

    /**
     * Constructs a new {@code XrayTestPlanParser}.
     *
     * @param objectMapper the {@link ObjectMapper} used to parse JSON responses
     */
    public XrayTestPlanParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }


    /**
     * Extracts test keys and the total count of tests from a JSON GraphQL response.
     *
     * @param jsonResponse the raw JSON response string
     * @return a {@link ParseResult} containing the extracted test keys and total count
     */
    public ParseResult extractTests(String jsonResponse) {
        Set<String> testKeys = new HashSet<>();
        int total = 0;

        try {
            JsonNode root = objectMapper.readTree(jsonResponse);
            JsonNode results = root.at("/data/getTestPlans/results");
            
            if (results.isArray() && !results.isEmpty()) {
                JsonNode testsConnection = results.get(0).at("/tests");
                JsonNode testsResults = testsConnection.get("results");

                if (testsResults != null && testsResults.isArray()) {
                    for (JsonNode testNode : testsResults) {
                        JsonNode keyNode = testNode.at("/jira/key");
                        if (!keyNode.isMissingNode() && !keyNode.isNull()) {
                            testKeys.add(keyNode.asText());
                        }
                    }
                }

                JsonNode totalNode = testsConnection.get("total");
                if (totalNode != null && totalNode.isInt()) {
                    total = totalNode.asInt();
                }
            }
        } catch (Exception e) {
            logger.severe("Error parsing test plan response: " + e.getMessage());
        }

        return new ParseResult(testKeys, total);
    }
}
