package com.inventory.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.inventory.common.exception.ValidationException;
import org.junit.jupiter.api.Test;

class AdminCredentialsTest {

  @Test
  void sessionTokensAreRandomAndHashDeterministically() {
    String a = AdminCredentials.newSessionToken();
    String b = AdminCredentials.newSessionToken();

    assertThat(a).isNotEqualTo(b).hasSize(43);
    assertThat(AdminCredentials.hashToken(a)).isEqualTo(AdminCredentials.hashToken(a)).hasSize(64);
  }

  @Test
  void temporaryPasswordsAvoidLookAlikeCharacters() {
    String password = AdminCredentials.newTemporaryPassword();

    assertThat(password).hasSize(16).doesNotContainPattern("[0O1lI]");
  }

  @Test
  void passwordLengthIsBounded() {
    AdminCredentials.requireAcceptablePassword("x".repeat(10));
    AdminCredentials.requireAcceptablePassword("x".repeat(128));
    assertThatThrownBy(() -> AdminCredentials.requireAcceptablePassword("x".repeat(9)))
        .isInstanceOf(ValidationException.class);
    assertThatThrownBy(() -> AdminCredentials.requireAcceptablePassword("x".repeat(129)))
        .isInstanceOf(ValidationException.class);
    assertThatThrownBy(() -> AdminCredentials.requireAcceptablePassword(null))
        .isInstanceOf(ValidationException.class);
  }
}
