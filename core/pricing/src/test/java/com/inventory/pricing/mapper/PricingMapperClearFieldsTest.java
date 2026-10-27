package com.inventory.pricing.mapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.inventory.common.exception.ValidationException;
import com.inventory.pricing.domain.model.Pricing;
import com.inventory.pricing.domain.model.Scheme;
import com.inventory.pricing.rest.dto.request.PricingUpdateCommand;
import com.inventory.pricing.rest.dto.request.UpdatePricingRequest;
import com.inventory.pricing.utils.constants.PricingConstants;
import com.inventory.pricing.validation.PricingValidator;
import java.math.BigDecimal;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A null field in a pricing update keeps the stored value, so clearing a discount or a scheme
 * needs its name in clearFields. Each name travels from the command to the stored pricing.
 */
class PricingMapperClearFieldsTest {

  private final PricingMapper mapper = new PricingMapperImpl();
  private Pricing pricing;

  @BeforeEach
  void storedPricing() {
    pricing = new Pricing();
    pricing.setPriceToRetail(new BigDecimal("67.8"));
    pricing.setSaleAdditionalDiscount(new BigDecimal("3"));
    pricing.setPurchaseAdditionalDiscount(new BigDecimal("9"));
    pricing.setSaleScheme(new Scheme("PERCENTAGE", null, null, new BigDecimal("15")));
    pricing.setPurchaseScheme(new Scheme("FIXED_UNITS", 19, 1, null));
  }

  private void update(Set<String> clearFields) {
    PricingUpdateCommand command = PricingUpdateCommand.builder().clearFields(clearFields).build();
    UpdatePricingRequest request = mapper.toUpdatePricingRequest(command);
    mapper.updateEntity(request, pricing);
  }

  @Test
  void namedDiscountsAreCleared() {
    update(Set.of(
        PricingConstants.CLEAR_SALE_ADDITIONAL_DISCOUNT,
        PricingConstants.CLEAR_PURCHASE_ADDITIONAL_DISCOUNT));

    assertNull(pricing.getSaleAdditionalDiscount());
    assertNull(pricing.getPurchaseAdditionalDiscount());
    assertNotNull(pricing.getPurchaseScheme());
  }

  @Test
  void namedSchemesAreCleared() {
    update(Set.of(PricingConstants.CLEAR_PURCHASE_SCHEME, PricingConstants.CLEAR_SALE_SCHEME));

    assertNull(pricing.getPurchaseScheme());
    assertNull(pricing.getSaleScheme());
    assertEquals(0, new BigDecimal("9").compareTo(pricing.getPurchaseAdditionalDiscount()));
  }

  @Test
  void withoutClearFieldsEveryStoredValueIsKept() {
    update(null);

    assertEquals(0, new BigDecimal("3").compareTo(pricing.getSaleAdditionalDiscount()));
    assertEquals(19, pricing.getPurchaseScheme().getSchemePayFor());
    assertEquals(0, new BigDecimal("67.8").compareTo(pricing.getPriceToRetail()));
  }

  @Test
  void anUnknownNameIsRefused() {
    PricingUpdateCommand command =
        PricingUpdateCommand.builder().clearFields(Set.of("costPrice")).build();

    ValidationException e = assertThrows(ValidationException.class,
        () -> new PricingValidator().validateUpdateCommand(command));
    assertEquals("Pricing field cannot be cleared: costPrice", e.getMessage());
  }
}
