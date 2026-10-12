package com.google.pdsl.xray.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.http.HttpHeaders;
import org.apache.http.HttpResponse;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.ByteArrayEntity;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.entity.mime.MultipartEntityBuilder;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.message.BasicHttpResponse;
import org.apache.http.util.EntityUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;
import com.google.pdsl.xray.core.model.ParseResult;

/**
 * XrayApiClient handles all HTTP communication with the Xray API.
 */
public class XrayApiClient {

    private final XrayAuth xrayAuth;
    private final ObjectMapper objectMapper;
    private final Logger logger = Logger.getLogger(this.getClass().getName());

    /**
     * Constructs a new {@code XrayApiClient}.
     *
     * @param xrayAuth     the authentication credentials for the Xray API
     * @param objectMapper the {@link ObjectMapper} used for JSON serialization and deserialization
     */
    public XrayApiClient(XrayAuth xrayAuth, ObjectMapper objectMapper) {
        this.xrayAuth = xrayAuth;
        this.objectMapper = objectMapper;
    }

    /**
     * Retrieves the Xray report Base URL used by the updater.
     *
     * @return The Xray report URL.
     */
    private String getXrayReportBaseUrl() {
        return "https://xray.cloud.getxray.app/api/v2";
    }

    /**
     * Posts test execution results to the Xray API using a multipart/form-data request.
     *
     * @param results   the path to the test results JSON file
     * @param info      the path to the execution info JSON file
     * @param responses a list to which the cloned HTTP response from the execution import will be added
     * @throws IOException if an I/O error occurs during the HTTP request or file reading
     */
    public void postXrayExecution(Path results, Path info, List<org.apache.http.HttpResponse> responses) throws IOException {
        HttpPost post = new HttpPost(getXrayReportBaseUrl() + "/import/execution/multipart");
        post.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + xrayAuth.getAuthToken());
        post.addHeader(HttpHeaders.CONTENT_TYPE, String.format("%s; boundary=%s",
                ContentType.MULTIPART_FORM_DATA.getMimeType(),
                "X-PDSL-XRAY-PLUGIN-BOUNDARY"));
        post.addHeader(HttpHeaders.ACCEPT, "*/*");
        post.addHeader(HttpHeaders.ACCEPT_ENCODING, "*/*");

        post.setEntity(MultipartEntityBuilder.create()
                .addBinaryBody("results", results.toFile(), ContentType.APPLICATION_JSON, results.getFileName().toString())
                .addBinaryBody("info", info.toFile(), ContentType.APPLICATION_JSON, info.getFileName().toString())
                .setLaxMode()
                .setBoundary("X-PDSL-XRAY-PLUGIN-BOUNDARY")
                .setCharset(StandardCharsets.UTF_8)
                .build());

        try (CloseableHttpClient client = HttpClients.createDefault();
             CloseableHttpResponse response = client.execute(post)) {
            HttpResponse clonedResponse = cloneResponseIntoMemory(response);
            responses.add(clonedResponse);
            final int statusCode = response.getStatusLine().getStatusCode();
            if (statusCode >= 200 && statusCode < 300) {
                logger.info(String.format("Xray test execution results imported successfully\n%s%n",
                        new String(clonedResponse.getEntity().getContent().readAllBytes())));
            } else {
                logger.severe(String.format("Failed to import Xray test execution results: %s - %s%n",
                        response.getStatusLine(), new String(clonedResponse.getEntity().getContent().readAllBytes())));
            }
        }
    }

    /**
     * Reads an active HttpResponse and clones it completely into memory
     * so it can be safely used after the original network stream is closed.
     *
     * @param originalResponse the original {@link HttpResponse}
     * @return a {@link BasicHttpResponse} whose entity has been fully buffered into memory
     * @throws IOException if an error occurs while reading the response entity
     */
    private BasicHttpResponse cloneResponseIntoMemory(HttpResponse originalResponse) throws IOException {
        BasicHttpResponse clonedResponse = new BasicHttpResponse(originalResponse.getStatusLine());

        clonedResponse.setHeaders(originalResponse.getAllHeaders());

        if (originalResponse.getEntity() != null) {
            ByteArrayEntity memoryEntity = new ByteArrayEntity(EntityUtils.toByteArray(originalResponse.getEntity()));

            if (originalResponse.getEntity().getContentType() != null) {
                memoryEntity.setContentType(originalResponse.getEntity().getContentType());
            }
            if (originalResponse.getEntity().getContentEncoding() != null) {
                memoryEntity.setContentEncoding(originalResponse.getEntity().getContentEncoding());
            }

            clonedResponse.setEntity(memoryEntity);
        }

        return clonedResponse;
    }


    /**
     * Fetches test keys associated with a specific test plan from the Xray GraphQL API.
     *
     * @param testPlanKey the key of the test plan issue in Jira
     * @return a set containing the extracted test keys
     */
    public Set<String> fetchTestsFromTestPlan(String testPlanKey) {
        if (testPlanKey == null || testPlanKey.isEmpty()) {
            return Collections.emptySet();
        }

        Set<String> testKeys = new HashSet<>();
        int start = 0;
        int limit = 100;
        boolean hasMore = true;
        XrayTestPlanParser parser = new XrayTestPlanParser(objectMapper);

        try (CloseableHttpClient client = HttpClients.createDefault()) {
            HttpPost post = new HttpPost(getXrayReportBaseUrl() + "/graphql");
            post.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + xrayAuth.getAuthToken());
            post.addHeader(HttpHeaders.CONTENT_TYPE, "application/json");

            while (hasMore) {
                String graphqlQuery = """
                        {
                            "query": "{ getTestPlans(jql: \\"issue = '%s'\\", limit: 1) { results { tests(limit: %d, start: %d) { total results { jira(fields: [\\"key\\"]) } } } } }"
                        }
                        """.formatted(testPlanKey, limit, start);
                
                post.setEntity(new StringEntity(graphqlQuery, StandardCharsets.UTF_8));
                
                try (CloseableHttpResponse response = client.execute(post)) {

                    if (response.getStatusLine().getStatusCode() >= 200 && response.getStatusLine().getStatusCode() < 300) {
                        String jsonResponse = EntityUtils.toString(response.getEntity());
                        ParseResult parseResult = parser.extractTests(jsonResponse);
                        
                        testKeys.addAll(parseResult.testKeys());
                        
                        if (parseResult.total() > 0) {
                            start += limit;
                            if (start >= parseResult.total()) {
                                hasMore = false;
                            }
                        } else {
                            hasMore = false;
                        }
                    } else {
                        logger.severe("Failed to fetch test plan tests from Xray: " + response.getStatusLine());
                        hasMore = false;
                    }
                }
            }
        } catch (IOException e) {
            logger.severe("Error fetching tests from test plan: " + e.getMessage());
        }
        
        return testKeys;
    }
}
