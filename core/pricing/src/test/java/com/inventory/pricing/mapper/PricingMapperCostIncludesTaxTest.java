package com.inventory.pricing.mapper;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.inventory.pricing.domain.model.Pricing;
import com.inventory.pricing.rest.dto.request.PricingCreateCommand;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/**
 * Whether a cost includes GST decides the landed cost, so the flag has to survive every hop from
 * stock-in to the read that margin uses.
 */
class PricingMapperCostIncludesTaxTest {

  private final PricingMapper mapper = new PricingMapperImpl();

  @Test
  void flagTravelsFromTheCreateCommandToTheReadDto() {
    PricingCreateCommand command = PricingCreateCommand.builder()
        .costPrice(new BigDecimal("99"))
        .sgst("2.5")
        .cgst("2.5")
        .costPriceIncludesTax(Boolean.TRUE)
        .build();

    Pricing pricing = mapper.toEntity(mapper.toCreatePricingRequest(command));

    assertEquals(Boolean.TRUE, pricing.getCostPriceIncludesTax());
    assertEquals(Boolean.TRUE, mapper.toPricingReadDto(pricing).getCostPriceIncludesTax());
    assertEquals(Boolean.TRUE, mapper.toResponse(pricing).getCostPriceIncludesTax());
  }
}
