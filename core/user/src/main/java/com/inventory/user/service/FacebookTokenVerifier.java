package com.inventory.user.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.inventory.common.constants.ErrorCode;
import com.inventory.common.exception.AuthenticationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

@Service
@Slf4j
public class FacebookTokenVerifier {

  private static final String FACEBOOK_GRAPH_API_URL = "https://graph.facebook.com/me";
  private static final String FIELDS_PARAM = "fields=id,name,email";
  private final HttpClient httpClient;
  private final ObjectMapper objectMapper;

  public FacebookTokenVerifier() {
    this.httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build();
    this.objectMapper = new ObjectMapper();
  }

  /**
   * Verifies a Facebook access token and extracts user information.
   *
   * @param accessToken The Facebook access token to verify
   * @return Map containing user information (email, name, id, etc.)
   * @throws AuthenticationException if token verification fails
   */
  public Map<String, Object> verifyToken(String accessToken) {
    try {
      log.debug("Verifying Facebook access token");

      // Build the request URL
      String url = FACEBOOK_GRAPH_API_URL + "?access_token=" + accessToken + "&" + FIELDS_PARAM;

      // Create HTTP request
      HttpRequest request = HttpRequest.newBuilder()
          .uri(URI.create(url))
          .timeout(Duration.ofSeconds(10))
          .GET()
          .build();

      // Send request and get response
      HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

      // Check response status
      if (response.statusCode() != 200) {
        log.warn("Facebook token verification failed with status code: {}", response.statusCode());
        throw new AuthenticationException(ErrorCode.INVALID_CREDENTIALS,
            "Facebook access token verification failed - token is invalid or expired");
      }

      // Parse JSON response
      JsonNode jsonNode = objectMapper.readTree(response.body());

      // Check for error in response
      if (jsonNode.has("error")) {
        JsonNode errorNode = jsonNode.get("error");
        String errorMessage = errorNode.has("message") ? errorNode.get("message").asText() : "Unknown error";
        log.warn("Facebook API returned error: {}", errorMessage);
        throw new AuthenticationException(ErrorCode.INVALID_CREDENTIALS,
            "Facebook access token verification failed: " + errorMessage);
      }

      // Extract user information
      Map<String, Object> claims = new HashMap<>();
      if (jsonNode.has("id")) {
        claims.put("id", jsonNode.get("id").asText());
      }
      if (jsonNode.has("email")) {
        claims.put("email", jsonNode.get("email").asText());
      }
      if (jsonNode.has("name")) {
        claims.put("name", jsonNode.get("name").asText());
      }

      String email = jsonNode.has("email") ? jsonNode.get("email").asText() : null;
      log.debug("Facebook access token verified successfully for email: {}", email);

      if (email == null || email.trim().isEmpty()) {
        throw new AuthenticationException(ErrorCode.INVALID_CREDENTIALS,
            "Email not found in Facebook token. Please ensure email permission is granted.");
      }

      return claims;

    } catch (AuthenticationException e) {
      throw e;
    } catch (IOException | InterruptedException e) {
      log.warn("Facebook token verification failed: {}", e.getMessage());
      throw new AuthenticationException(ErrorCode.INVALID_CREDENTIALS,
          "Invalid Facebook access token: " + e.getMessage());
    }
  }

  /**
   * Extracts email from verified token payload.
   */
  public String getEmail(Map<String, Object> payload) {
    Object email = payload.get("email");
    if (email == null) {
      throw new AuthenticationException(ErrorCode.INVALID_CREDENTIALS,
          "Email not found in Facebook token");
    }
    return email.toString();
  }

  /**
   * Extracts name from verified token payload.
   */
  public String getName(Map<String, Object> payload) {
    Object name = payload.get("name");
    if (name != null) {
      return name.toString();
    }
    return null;
  }
}

