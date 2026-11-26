package com.inventory.user.externalservice;

import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.inventory.common.constants.ErrorCode;
import com.inventory.common.exception.AuthenticationException;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Verifies Google ID tokens for sign-up and sign-in.
 *
 * <p>Signature, issuer and expiry are checked by Google's verifier. The audience ({@code aud}) is
 * checked here against {@code google.oauth.client-ids}: the web OAuth client id and the Android
 * client id of Stockkart Team (worker-app DD8). A token minted for any other client, or any
 * token when the list is not configured, is refused with {@link ErrorCode#INVALID_CREDENTIALS}; the
 * list is the only thing that makes a Google token "ours".
 */
@Service
@Slf4j
public class GoogleTokenVerifier {

  /** Property holding the comma-separated OAuth client ids whose tokens are accepted. */
  public static final String CLIENT_IDS_PROPERTY = "google.oauth.client-ids";

  private final GoogleIdTokenVerifier verifier;
  private final Set<String> allowedClientIds;

  @Autowired
  public GoogleTokenVerifier(@Value("${" + CLIENT_IDS_PROPERTY + ":}") List<String> clientIds) {
    this(clientIds, new GoogleIdTokenVerifier.Builder(new NetHttpTransport(), new GsonFactory()).build());
  }

  /** For tests: the audience rule over a verifier that does not call Google. */
  GoogleTokenVerifier(Collection<String> clientIds, GoogleIdTokenVerifier verifier) {
    this.verifier = verifier;
    this.allowedClientIds = allowList(clientIds);
    if (this.allowedClientIds.isEmpty()) {
      log.warn(
          "{} is empty: every Google ID token will be refused until the web and Android client ids"
              + " are configured",
          CLIENT_IDS_PROPERTY);
    }
  }

  /** Trimmed, non-blank ids, in a set; {@code null} becomes empty. */
  static Set<String> allowList(Collection<String> clientIds) {
    if (clientIds == null) {
      return Set.of();
    }
    return clientIds.stream()
        .filter(id -> id != null && !id.isBlank())
        .map(String::trim)
        .collect(Collectors.toUnmodifiableSet());
  }

  /**
   * The audience rule on its own (Property-test friendly): a token is for us iff at least one of
   * its {@code aud} values is in the allow-list. An empty allow-list or an empty audience accepts
   * nothing.
   */
  static boolean audienceAllowed(Set<String> allowList, Collection<String> audience) {
    if (allowList == null || allowList.isEmpty() || audience == null) {
      return false;
    }
    return audience.stream().anyMatch(allowList::contains);
  }

  /** The configured allow-list, read-only. */
  public Set<String> allowedClientIds() {
    return allowedClientIds;
  }

  /**
   * Verifies a Google ID token and extracts user information.
   *
   * @param idTokenString The Google ID token string to verify
   * @return Map containing user information (email, name, etc.)
   * @throws AuthenticationException if token verification fails or the token is for another client
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

      if (!audienceAllowed(allowedClientIds, payload.getAudienceAsList())) {
        log.warn(
            "Google ID token refused: audience {} is not in {}",
            payload.getAudienceAsList(),
            CLIENT_IDS_PROPERTY);
        throw new AuthenticationException(ErrorCode.INVALID_CREDENTIALS,
            "Google ID token was not issued for this application");
      }

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
      if (payload.getAudience() != null) {
        claims.put("aud", payload.getAudienceAsList());
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
