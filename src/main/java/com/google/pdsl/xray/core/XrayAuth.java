package com.google.pdsl.xray.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDateTime;
import java.util.Properties;
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
 * XrayAuth fetches the authentication token from jira
 */
public class XrayAuth {

  private static final HttpClient client = HttpClient.newHttpClient();
  private final String xrayUrl;
  private Long tokenValidityHours = 23L;
  private String authToken;
  private LocalDateTime tokenCreationDate;
  private final String clientId;
  private final String clientSecret;

    /**
   * Constructor for XrayAuth.
   *
   * @param xrayUrl The URL of the Xray API endpoint for authentication.
   * @param clientId The client ID for Xray authentication.
   * @param clientSecret The client secret for Xray authentication.
   */
  public XrayAuth(String xrayUrl, String clientId, String clientSecret) {
    this.xrayUrl = xrayUrl;
    this.clientId = clientId;
    this.clientSecret = clientSecret;
  }

  /**
   * Retrieves the authentication token. If a valid token exists, it is returned. Otherwise, a new
   * token is fetched.
   *
   * @return The Xray authentication token.
   */
  public String getAuthToken() {
      if (authToken == null || tokenCreationDate == null
              || LocalDateTime.now().minusHours(tokenValidityHours).isAfter(tokenCreationDate)) {
        fetchAuthToken();
      }
      return authToken;
  }

  /**
    * Set token validity hours value. Default value is 23 hours
    *
    * @param tokenValidityHours token validity value in hours
    */
  public void setTokenValidityHours(long tokenValidityHours) {
      this.tokenValidityHours = tokenValidityHours;
  }

  /**
   * Fetches a new authentication token from the Xray API.
   *
   * @throws IllegalStateException If an error occurs during token retrieval.
   */
  private void fetchAuthToken() {
    try {
      ObjectMapper objectMapper = new ObjectMapper();
      JsonNode requestBody = objectMapper.createObjectNode()
          .put("client_id", this.clientId)
          .put("client_secret", this.clientSecret);

      HttpRequest request = HttpRequest.newBuilder()
          .uri(URI.create(this.xrayUrl))
          .header("Content-Type", "application/json")
          .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(requestBody)))
          .build();

      HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
      String responseBody = response.body().replaceAll("\"", "");

      if (response.statusCode() >= 200 && response.statusCode() < 300) {
        tokenCreationDate = LocalDateTime.now();
        this.authToken = responseBody;
      } else {
        throw new IllegalStateException(
            "Failed to fetch Xray auth token: %d - %s".formatted(response.statusCode(),
                responseBody));
      }
    } catch (IOException | InterruptedException e) {
      throw new IllegalStateException(
          "Error fetching Xray auth token: %s".formatted(e.getMessage()), e);
    }
  }

  /**
   * Creates an XrayAuth instance from a properties file.
   *
   * @param propertiesFilePath The path to the Xray properties file.
   * @return An XrayAuth instance.
   * @throws RuntimeException If required properties are missing from the file.
   */
  public static XrayAuth fromPropertiesFile(String propertiesFilePath) {
    Properties properties = new Properties();
    try {
      properties.load(new FileInputStream(propertiesFilePath));
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
    String xrayUrl = properties.getProperty("xray.api.url");
    String clientId = properties.getProperty("xray.client.id");
    String clientSecret = properties.getProperty("xray.client.secret");
    if (clientId == null || clientSecret == null || xrayUrl == null) {
      throw new RuntimeException(
          "xray.client.id, xray.client.secret and xray.api.url must be defined in the properties file.");
    }
    return new XrayAuth(xrayUrl, clientId, clientSecret);
  }
}
