package com.inventory.user.externalservice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier;
import com.google.api.client.json.webtoken.JsonWebSignature;
import com.inventory.common.constants.ErrorCode;
import com.inventory.common.exception.AuthenticationException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Worker-app DD8: a Google ID token is ours only when its audience is a configured client id. */
class GoogleTokenVerifierTest {

  private static final String WEB_CLIENT = "1234-web.apps.googleusercontent.com";
  private static final String ANDROID_CLIENT = "1234-android.apps.googleusercontent.com";
  private static final String FOREIGN_CLIENT = "9999-someone-else.apps.googleusercontent.com";
  private static final String TOKEN = "an.id.token";

  private static GoogleIdToken tokenFor(Object audience) {
    GoogleIdToken.Payload payload = new GoogleIdToken.Payload();
    payload.setAudience(audience);
    payload.setEmail("priya@example.com");
    payload.setSubject("sub-1");
    payload.setIssuer("https://accounts.google.com");
    payload.set("name", "Priya Sharma");
    return new GoogleIdToken(new JsonWebSignature.Header(), payload, new byte[0], new byte[0]);
  }

  private static GoogleTokenVerifier verifierAnswering(List<String> allowed, GoogleIdToken answer)
      throws Exception {
    GoogleIdTokenVerifier google = mock(GoogleIdTokenVerifier.class);
    when(google.verify(TOKEN)).thenReturn(answer);
    return new GoogleTokenVerifier(allowed, google);
  }

  @Test
  void aTokenForTheWebClientIsAccepted() throws Exception {
    GoogleTokenVerifier verifier =
        verifierAnswering(List.of(WEB_CLIENT, ANDROID_CLIENT), tokenFor(WEB_CLIENT));

    Map<String, Object> claims = verifier.verifyToken(TOKEN);

    assertEquals("priya@example.com", verifier.getEmail(claims));
    assertEquals("Priya Sharma", verifier.getName(claims));
    assertEquals(List.of(WEB_CLIENT), claims.get("aud"));
  }

  @Test
  void aTokenForTheAndroidClientIsAccepted() throws Exception {
    GoogleTokenVerifier verifier =
        verifierAnswering(List.of(WEB_CLIENT, ANDROID_CLIENT), tokenFor(ANDROID_CLIENT));

    assertEquals("priya@example.com", verifier.getEmail(verifier.verifyToken(TOKEN)));
  }

  @Test
  void aTokenForAnotherClientIsRefusedAsInvalidCredentials() throws Exception {
    GoogleTokenVerifier verifier =
        verifierAnswering(List.of(WEB_CLIENT, ANDROID_CLIENT), tokenFor(FOREIGN_CLIENT));

    AuthenticationException e =
        assertThrows(AuthenticationException.class, () -> verifier.verifyToken(TOKEN));

    assertEquals(ErrorCode.INVALID_CREDENTIALS, e.getErrorCode());
  }

  @Test
  void aTokenWithNoAudienceIsRefused() throws Exception {
    GoogleTokenVerifier verifier =
        verifierAnswering(List.of(WEB_CLIENT), tokenFor(null));

    AuthenticationException e =
        assertThrows(AuthenticationException.class, () -> verifier.verifyToken(TOKEN));
    assertEquals(ErrorCode.INVALID_CREDENTIALS, e.getErrorCode());
  }

  @Test
  void anEmptyAllowListRefusesEveryToken() throws Exception {
    GoogleTokenVerifier verifier = verifierAnswering(List.of(), tokenFor(WEB_CLIENT));

    AuthenticationException e =
        assertThrows(AuthenticationException.class, () -> verifier.verifyToken(TOKEN));
    assertEquals(ErrorCode.INVALID_CREDENTIALS, e.getErrorCode());
    assertTrue(verifier.allowedClientIds().isEmpty());
  }

  @Test
  void aTokenGoogleRejectsIsStillInvalidCredentials() throws Exception {
    GoogleTokenVerifier verifier = verifierAnswering(List.of(WEB_CLIENT), null);

    AuthenticationException e =
        assertThrows(AuthenticationException.class, () -> verifier.verifyToken(TOKEN));
    assertEquals(ErrorCode.INVALID_CREDENTIALS, e.getErrorCode());
  }

  @Test
  void aMultiValuedAudienceIsAcceptedWhenOneValueIsOurs() throws Exception {
    GoogleTokenVerifier verifier =
        verifierAnswering(List.of(WEB_CLIENT), tokenFor(Arrays.asList(FOREIGN_CLIENT, WEB_CLIENT)));

    assertEquals("priya@example.com", verifier.getEmail(verifier.verifyToken(TOKEN)));
  }

  @Test
  void theAllowListIsTrimmedAndBlanksAreDropped() {
    Set<String> list =
        GoogleTokenVerifier.allowList(Arrays.asList(" " + WEB_CLIENT + " ", "", null, ANDROID_CLIENT));
    assertEquals(Set.of(WEB_CLIENT, ANDROID_CLIENT), list);
    assertTrue(GoogleTokenVerifier.allowList(null).isEmpty());
  }

  @Test
  void audienceRule() {
    Set<String> ours = Set.of(WEB_CLIENT, ANDROID_CLIENT);
    assertTrue(GoogleTokenVerifier.audienceAllowed(ours, List.of(WEB_CLIENT)));
    assertTrue(GoogleTokenVerifier.audienceAllowed(ours, List.of(FOREIGN_CLIENT, ANDROID_CLIENT)));
    assertFalse(GoogleTokenVerifier.audienceAllowed(ours, List.of(FOREIGN_CLIENT)));
    assertFalse(GoogleTokenVerifier.audienceAllowed(ours, List.of()));
    assertFalse(GoogleTokenVerifier.audienceAllowed(ours, null));
    assertFalse(GoogleTokenVerifier.audienceAllowed(Set.of(), List.of(WEB_CLIENT)));
    assertFalse(GoogleTokenVerifier.audienceAllowed(null, List.of(WEB_CLIENT)));
  }
}
