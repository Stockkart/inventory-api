package com.inventory.user.domain.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Logging in clears the user's expired tokens and keeps at most 10 live sessions. */
class UserAccountAddTokenTest {

  private static final Instant NOW = Instant.parse("2026-10-19T10:00:00Z");

  private static UserToken token(String id, Instant created, Instant expires) {
    return new UserToken("at-" + id, "rt-" + id, "dev-" + id, created, expires);
  }

  private static List<String> ids(UserAccount a) {
    return a.getTokens().stream().map(UserToken::getAccessToken).toList();
  }

  @Test
  void expiredTokensAreDroppedAndLiveOnesKept() {
    UserAccount a = new UserAccount();
    a.setTokens(new ArrayList<>(List.of(
        token("old1", NOW.minus(Duration.ofDays(20)), NOW.minus(Duration.ofDays(13))),
        token("live", NOW.minus(Duration.ofDays(1)), NOW.plus(Duration.ofDays(6))),
        token("justExpired", NOW.minus(Duration.ofDays(7)), NOW),
        token("noExpiry", NOW.minus(Duration.ofDays(30)), null))));

    a.addToken(token("new", NOW, NOW.plus(Duration.ofDays(7))), NOW);

    assertThat(ids(a)).containsExactly("at-live", "at-noExpiry", "at-new");
  }

  @Test
  void eleventhSessionLogsOutTheOldest() {
    UserAccount a = new UserAccount();
    List<UserToken> live = new ArrayList<>();
    for (int i = 0; i < 10; i++) {
      live.add(token("s" + i, NOW.minus(Duration.ofHours(10 - i)), NOW.plus(Duration.ofDays(5))));
    }
    java.util.Collections.shuffle(live, new java.util.Random(7));
    a.setTokens(live);

    a.addToken(token("new", NOW, NOW.plus(Duration.ofDays(7))), NOW);

    assertThat(a.getTokens()).hasSize(UserAccount.MAX_LIVE_TOKENS);
    assertThat(ids(a)).doesNotContain("at-s0").contains("at-s1", "at-s9", "at-new");
  }

  @Test
  void manyExpiredAndTooManyLiveTogether() {
    UserAccount a = new UserAccount();
    List<UserToken> list = new ArrayList<>();
    for (int i = 0; i < 30; i++) {
      list.add(token("x" + i, NOW.minus(Duration.ofDays(40)), NOW.minus(Duration.ofDays(33))));
    }
    for (int i = 0; i < 12; i++) {
      list.add(token("l" + i, NOW.minus(Duration.ofMinutes(100 - i)), NOW.plus(Duration.ofDays(3))));
    }
    a.setTokens(list);

    a.addToken(token("new", NOW, NOW.plus(Duration.ofDays(7))), NOW);

    assertThat(a.getTokens()).hasSize(10);
    assertThat(ids(a)).noneMatch(s -> s.startsWith("at-x"));
    assertThat(ids(a)).doesNotContain("at-l0", "at-l1", "at-l2").contains("at-l3", "at-l11", "at-new");
  }

  @Test
  void firstLoginOnAnEmptyAccount() {
    UserAccount a = new UserAccount();
    a.addToken(token("new", NOW, NOW.plus(Duration.ofDays(7))), NOW);
    assertThat(ids(a)).containsExactly("at-new");
  }
}
