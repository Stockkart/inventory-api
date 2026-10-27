package com.inventory.accounting.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.inventory.accounting.domain.model.Account;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.test.util.ReflectionTestUtils;

class SystemAccountCodeConflictCheckTest {

  private static Account account(String shopId, String code) {
    Account a = new Account();
    a.setShopId(shopId);
    a.setCode(code);
    return a;
  }

  private static SystemAccountCodeConflictCheck check(MongoTemplate template, boolean enabled) {
    SystemAccountCodeConflictCheck check = new SystemAccountCodeConflictCheck();
    ReflectionTestUtils.setField(check, "mongoTemplate", template);
    ReflectionTestUtils.setField(check, "enabled", enabled);
    return check;
  }

  @Test
  void groupsTheShopsOwnAccountsOnTheHrCodesByShop() {
    MongoTemplate template = mock(MongoTemplate.class);
    when(template.find(any(Query.class), eq(Account.class)))
        .thenReturn(List.of(account("s2", "5700"), account("s1", "2400"), account("s2", "1250")));

    Map<String, Set<String>> conflicts = check(template, true).conflicts();

    assertThat(conflicts).containsOnlyKeys("s1", "s2");
    assertThat(conflicts.get("s2")).containsExactly("1250", "5700");
    ArgumentCaptor<Query> query = ArgumentCaptor.forClass(Query.class);
    verify(template).find(query.capture(), eq(Account.class));
    // only the shop's own accounts, never the seeded ones
    assertThat(query.getValue().getQueryObject().toJson()).contains("\"system\": {\"$ne\": true}");
    assertThat(query.getValue().getQueryObject().toJson()).contains("1250", "2400", "5700");
    // bounded scan (run B7)
    assertThat(query.getValue().getMeta().getMaxTimeMsec()).isEqualTo(30_000L);
  }

  @Test
  void theWarningNamesAtMostTheFirstTenShops() {
    Map<String, Set<String>> all = new TreeMap<>();
    for (int i = 10; i < 35; i++) {
      all.put("s" + i, Set.of("2400"));
    }

    Map<String, Set<String>> first = SystemAccountCodeConflictCheck.firstShops(all);

    assertThat(first).hasSize(10);
    assertThat(first.keySet()).containsExactly("s10", "s11", "s12", "s13", "s14", "s15", "s16",
        "s17", "s18", "s19");
    assertThat(SystemAccountCodeConflictCheck.firstShops(Map.of("s1", Set.of("1250"))))
        .containsOnlyKeys("s1");
  }

  @Test
  void runWritesNothing() {
    MongoTemplate template = mock(MongoTemplate.class);
    when(template.find(any(Query.class), eq(Account.class)))
        .thenReturn(List.of(account("s1", "2400")));

    check(template, true).run();

    verify(template, never()).updateMulti(any(Query.class), any(Update.class), eq(Account.class));
    verify(template, never()).remove(any(Query.class), eq(Account.class));
  }

  @Test
  void disabledCheckReadsNothing() {
    MongoTemplate template = mock(MongoTemplate.class);
    check(template, false).run();
    verifyNoInteractions(template);
  }

  @Test
  void aFailureIsLoggedAndBootContinues() {
    MongoTemplate template = mock(MongoTemplate.class);
    when(template.find(any(Query.class), eq(Account.class)))
        .thenThrow(new IllegalStateException("down"));
    check(template, true).run(); // no exception
  }
}
