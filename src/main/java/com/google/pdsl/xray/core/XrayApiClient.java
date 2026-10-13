package com.google.pdsl.xray.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.pdsl.xray.core.model.ParseResult;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.entity.EntityBuilder;
import org.apache.hc.client5.http.entity.mime.MultipartEntityBuilder;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.HttpHeaders;
import org.apache.hc.core5.http.HttpResponse;
import org.apache.hc.core5.http.ParseException;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.apache.hc.core5.http.message.BasicClassicHttpResponse;
import org.apache.hc.core5.http.message.BasicHttpResponse;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;

/**
 * XrayApiClient handles all HTTP communication with the Xray API.
 */
public class XrayApiClient {

    public static final String IMPORT_MULTIPART_ENDPOINT = "/import/execution/multipart";
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
    public void postXrayExecution(Path results, Path info, List<HttpResponse> responses) throws IOException {
        HttpPost post = new HttpPost(getXrayReportBaseUrl() + IMPORT_MULTIPART_ENDPOINT);
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
            ClassicHttpResponse clonedResponse = cloneResponseIntoMemory(response);
            responses.add(clonedResponse);
            final int statusCode = response.getCode();
            if (statusCode >= 200 && statusCode < 300) {
                logger.info(String.format("Xray test execution results imported successfully\n%s%n",
                        new String(clonedResponse.getEntity().getContent().readAllBytes())));
            } else {
                logger.severe(String.format("Failed to import Xray test execution results: %d %s - %s%n",
                        response.getCode(),
                        response.getReasonPhrase(),
                        new String(clonedResponse.getEntity().getContent().readAllBytes(), StandardCharsets.UTF_8)));
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
    private BasicClassicHttpResponse cloneResponseIntoMemory(ClassicHttpResponse originalResponse) throws IOException {
        BasicClassicHttpResponse clonedResponse =
                new BasicClassicHttpResponse(originalResponse.getCode(), originalResponse.getReasonPhrase());

        clonedResponse.setHeaders(originalResponse.getHeaders());

        if (originalResponse.getEntity() != null) {
            byte[] bytes = EntityUtils.toByteArray(originalResponse.getEntity());
            ContentType contentType = null;
            if (originalResponse.getEntity().getContentType() != null) {
                contentType = ContentType.parse(originalResponse.getEntity().getContentType());
            }

            clonedResponse.setEntity(EntityBuilder.create()
                    .setBinary(bytes)
                    .setContentType(contentType)
                    .setContentEncoding(originalResponse.getEntity().getContentEncoding())
                    .build());
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

                    final int statusCode = response.getCode();
                    if (statusCode >= 200 && statusCode < 300) {
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
                        logger.severe("Failed to fetch test plan tests from Xray: " + statusCode);
                        hasMore = false;
                    }
                } catch (ParseException e) {
                    throw new RuntimeException(e);
                }
            }
        } catch (IOException e) {
            logger.severe("Error fetching tests from test plan: " + e.getMessage());
        }

        return testKeys;
    }
}
