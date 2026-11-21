package com.inventory.user.service;

import static org.assertj.core.api.Assertions.assertThat;

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
}
