package com.inventory.plan.rest.dto.response;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/** The gross amount of a quote line is worked out here, not in the apps. */
class QuoteResponseTest {

  @Test
  void aLineCarriesItsGrossAmountInTheJson() throws Exception {
    QuoteResponse.QuoteItem item = QuoteResponse.QuoteItem.builder()
        .unitPrice(new BigDecimal("499.00")).quantity(3).discount(BigDecimal.ZERO).build();

    assertThat(item.getGrossAmount()).isEqualByComparingTo("1497.00");
    assertThat(new ObjectMapper().writeValueAsString(item)).contains("\"grossAmount\":1497.00");
  }

  @Test
  void aLineWithoutAPriceHasNoGrossAmount() {
    assertThat(QuoteResponse.QuoteItem.builder().quantity(1).build().getGrossAmount()).isNull();
  }
}
