package com.inventory.user.service;

import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.inventory.common.constants.ErrorCode;
import com.inventory.common.exception.AuthenticationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
@Slf4j
public class GoogleTokenVerifier {

  private final GoogleIdTokenVerifier verifier;

  public GoogleTokenVerifier() {
    // Initialize verifier without client ID check (for now)
    // In production, you should verify the audience (client ID) as well
    this.verifier = new GoogleIdTokenVerifier.Builder(new NetHttpTransport(), new GsonFactory())
        .build();
  }

  /**
   * Verifies a Google ID token and extracts user information.
   *
   * @param idTokenString The Google ID token string to verify
   * @return Map containing user information (email, name, etc.)
   * @throws AuthenticationException if token verification fails
   */
  public Map<String, Object> verifyToken(String idTokenString) {
    try {
      log.debug("Verifying Google ID token");

      // Verify the token
      GoogleIdToken idToken = verifier.verify(idTokenString);

      if (idToken == null) {
        throw new AuthenticationException(ErrorCode.INVALID_CREDENTIALS,
            "Google ID token verification failed - token is invalid or expired");
      }

      // Get payload
      GoogleIdToken.Payload payload = idToken.getPayload();

      // Convert to Map for easier access
      java.util.HashMap<String, Object> claims = new java.util.HashMap<>();
      if (payload.getEmail() != null) {
        claims.put("email", payload.getEmail());
      }
      if (payload.get("name") != null) {
        claims.put("name", payload.get("name"));
      }
      if (payload.get("given_name") != null) {
        claims.put("given_name", payload.get("given_name"));
      }
      if (payload.get("family_name") != null) {
        claims.put("family_name", payload.get("family_name"));
      }
      if (payload.getSubject() != null) {
        claims.put("sub", payload.getSubject());
      }
      if (payload.getIssuer() != null) {
        claims.put("iss", payload.getIssuer());
      }

      log.debug("Google ID token verified successfully for email: {}", payload.getEmail());

      return claims;

    } catch (AuthenticationException e) {
      throw e;
    } catch (Exception e) {
      log.warn("Google ID token verification failed: {}", e.getMessage());
      throw new AuthenticationException(ErrorCode.INVALID_CREDENTIALS,
          "Invalid Google ID token: " + e.getMessage());
    }
  }

  /**
   * Extracts email from verified token payload.
   */
  public String getEmail(Map<String, Object> payload) {
    Object email = payload.get("email");
    if (email == null) {
      throw new AuthenticationException(ErrorCode.INVALID_CREDENTIALS,
          "Email not found in Google ID token");
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
    // Fallback to given_name and family_name
    Object givenName = payload.get("given_name");
    Object familyName = payload.get("family_name");
    if (givenName != null || familyName != null) {
      return ((givenName != null ? givenName.toString() : "") + " " +
          (familyName != null ? familyName.toString() : "")).trim();
    }
    return null;
  }
}

