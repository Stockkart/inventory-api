package com.inventory.plan.service.wallet;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class WalletBalancesTest {

  private static WalletBalances balances(String available, String reserved, String outstanding) {
    return new WalletBalances(new BigDecimal(available), new BigDecimal(reserved), new BigDecimal(outstanding));
  }

  private static BigDecimal amount(String value) {
    return new BigDecimal(value);
  }

  @Test
  void reservingNeedsEnoughAvailable() {
    assertThat(balances("500", "0", "0").reserve(amount("200")))
        .contains(balances("300", "200", "0"));
    assertThat(balances("100", "0", "0").reserve(amount("200"))).isEmpty();
  }

  @Test
  void consumingSpendsOnlyWhatIsReserved() {
    assertThat(balances("300", "200", "0").consume(amount("200"))).contains(balances("300", "0", "0"));
    assertThat(balances("300", "100", "0").consume(amount("200"))).isEmpty();
  }

  @Test
  void releasedAndCreditedMoneyPaysOffOutstandingClawbackFirst() {
    assertThat(balances("0", "200", "150").release(amount("200"))).contains(balances("50", "0", "0"));
    assertThat(balances("0", "0", "150").credit(amount("100"))).isEqualTo(balances("0", "0", "50"));
    assertThat(balances("10", "0", "0").credit(amount("100"))).isEqualTo(balances("110", "0", "0"));
  }

  @Test
  void amountsAreKeptToPaise() {
    assertThat(balances("1.005", "0", "0").available()).isEqualByComparingTo("1.01");
  }
}
