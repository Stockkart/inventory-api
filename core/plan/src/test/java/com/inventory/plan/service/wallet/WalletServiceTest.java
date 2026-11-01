package com.inventory.plan.service.wallet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.inventory.plan.domain.model.ShopCredit;
import com.inventory.plan.domain.model.ShopCreditEntry;
import com.inventory.plan.domain.model.ShopCreditSource;
import com.inventory.plan.domain.repository.ShopCreditEntryRepository;
import com.inventory.plan.domain.repository.ShopCreditRepository;
import com.inventory.plan.mapper.WalletMapper;
import com.mongodb.client.result.UpdateResult;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class WalletServiceTest {

  private static final Instant NOW = Instant.parse("2026-09-01T10:00:00Z");

  @Mock private ShopCreditRepository creditRepository;
  @Mock private ShopCreditEntryRepository entryRepository;
  @Mock private MongoTemplate mongoTemplate;
  @Spy private WalletMapper walletMapper = new WalletMapper() {
    @Override
    public com.inventory.plan.rest.dto.response.WalletEntryResponse toEntryResponse(ShopCreditEntry entry) {
      return null;
    }

    @Override
    public List<com.inventory.plan.rest.dto.response.WalletEntryResponse> toEntryResponses(List<ShopCreditEntry> e) {
      return List.of();
    }
  };

  @InjectMocks
  private WalletService service;

  @BeforeEach
  void setUp() {
    service.clock = Clock.fixed(NOW, ZoneOffset.UTC);
  }

  private static ShopCredit wallet(String available, String reserved, long version, String... references) {
    return ShopCredit.builder().shopId("shop-1")
        .availableBalance(new BigDecimal(available))
        .reservedBalance(new BigDecimal(reserved))
        .outstandingClawback(BigDecimal.ZERO)
        .version(version)
        .recentReferences(new ArrayList<>(List.of(references)))
        .build();
  }

  private void writes(long... modified) {
    var stub = when(mongoTemplate.updateFirst(any(Query.class), any(Update.class), eq(ShopCredit.class)));
    for (long count : modified) {
      stub = stub.thenReturn(UpdateResult.acknowledged(count, count, null));
    }
  }

  @Test
  void reservingMovesAvailableToReservedAndWritesTheLedger() {
    when(creditRepository.findById("shop-1")).thenReturn(Optional.of(wallet("500", "0", 3)));
    writes(1);

    assertThat(service.reserve("shop-1", "order-1", new BigDecimal("200"))).isTrue();

    ArgumentCaptor<Query> query = ArgumentCaptor.forClass(Query.class);
    ArgumentCaptor<Update> update = ArgumentCaptor.forClass(Update.class);
    verify(mongoTemplate).updateFirst(query.capture(), update.capture(), eq(ShopCredit.class));
    assertThat(query.getValue().getQueryObject().get("version")).isEqualTo(3L);
    Document set = (Document) update.getValue().getUpdateObject().get("$set");
    assertThat((BigDecimal) set.get("availableBalance")).isEqualByComparingTo("300");
    assertThat((BigDecimal) set.get("reservedBalance")).isEqualByComparingTo("200");

    ArgumentCaptor<ShopCreditEntry> entry = ArgumentCaptor.forClass(ShopCreditEntry.class);
    verify(entryRepository).insert(entry.capture());
    assertThat(entry.getValue().getReferenceId()).isEqualTo("order-reserve:order-1");
    assertThat(entry.getValue().getSource()).isEqualTo(ShopCreditSource.ORDER_RESERVATION);
    assertThat(entry.getValue().getAvailableDelta()).isEqualByComparingTo("-200");
    assertThat(entry.getValue().getAvailableAfter()).isEqualByComparingTo("300");
  }

  @Test
  void notEnoughBalanceRejectsWithoutWriting() {
    when(creditRepository.findById("shop-1")).thenReturn(Optional.of(wallet("100", "0", 0)));

    assertThat(service.reserve("shop-1", "order-1", new BigDecimal("200"))).isFalse();
    verify(mongoTemplate, never()).updateFirst(any(Query.class), any(Update.class), eq(ShopCredit.class));
  }

  @Test
  void aConcurrentChangeIsRetriedAgainstTheFreshBalance() {
    when(creditRepository.findById("shop-1"))
        .thenReturn(Optional.of(wallet("500", "0", 1)), Optional.of(wallet("250", "250", 2)));
    writes(0, 1);

    assertThat(service.reserve("shop-1", "order-1", new BigDecimal("200"))).isTrue();

    verify(mongoTemplate, times(2)).updateFirst(any(Query.class), any(Update.class), eq(ShopCredit.class));
  }

  @Test
  void aRepeatedChangeIsNotAppliedTwice() {
    when(creditRepository.findById("shop-1"))
        .thenReturn(Optional.of(wallet("300", "200", 4, "order-reserve:order-1")));
    when(entryRepository.existsByReferenceId("order-reserve:order-1")).thenReturn(true);

    assertThat(service.reserve("shop-1", "order-1", new BigDecimal("200"))).isTrue();
    verify(mongoTemplate, never()).updateFirst(any(Query.class), any(Update.class), eq(ShopCredit.class));
  }

  @Test
  void consumingMoreThanIsReservedFailsLoudly() {
    when(creditRepository.findById("shop-1")).thenReturn(Optional.of(wallet("0", "50", 0)));

    assertThatThrownBy(() -> service.consume("shop-1", "order-1", new BigDecimal("200")))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void aShopWithoutAWalletGetsOneOnItsFirstCredit() {
    when(creditRepository.findById("shop-1")).thenReturn(Optional.empty());
    when(creditRepository.insert(any(ShopCredit.class))).thenAnswer(inv -> inv.getArgument(0));
    writes(1);

    assertThat(service.credit("shop-1", new BigDecimal("150"), ShopCreditSource.REFERRAL_REWARD, "reward-1",
        null, null)).isEqualTo(WalletService.Outcome.APPLIED);

    ArgumentCaptor<ShopCreditEntry> entry = ArgumentCaptor.forClass(ShopCreditEntry.class);
    verify(entryRepository).insert(entry.capture());
    assertThat(entry.getValue().getReferenceId()).isEqualTo("referral_reward:reward-1");
    assertThat(entry.getValue().getAvailableAfter()).isEqualByComparingTo("150");
  }

  @Test
  void balanceWithoutAWalletIsZero() {
    when(creditRepository.findById("shop-1")).thenReturn(Optional.empty());
    assertThat(service.available("shop-1")).isEqualByComparingTo("0");
  }
}
