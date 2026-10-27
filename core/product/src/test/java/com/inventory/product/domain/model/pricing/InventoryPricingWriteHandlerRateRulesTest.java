package com.inventory.product.domain.model.pricing;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.inventory.common.constants.ErrorCode;
import com.inventory.common.exception.BaseException;
import com.inventory.common.exception.ValidationException;
import com.inventory.pricing.api.ShopRateRule;
import com.inventory.pricing.api.port.ShopRateRulesPort;
import com.inventory.pricing.api.PricingCreate;
import com.inventory.pricing.api.PricingUpdate;
import com.inventory.pricing.api.RateLine;
import com.inventory.pricing.api.InventoryPricing;
import com.inventory.pricing.utils.constants.PricingConstants;
import com.inventory.product.domain.model.Inventory;
import com.inventory.product.domain.model.enums.ShopType;
import com.inventory.product.domain.repository.ShopRepository;
import com.inventory.product.rest.dto.request.UpdateInventoryRequest;
import com.inventory.product.service.RateRulesTestFixtures;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class InventoryPricingWriteHandlerRateRulesTest {

  @Mock private InventoryPricing pricingPort;
  @Mock private ShopRepository shopRepository;
  @Mock private ShopRateRulesPort rateRulesPort;

  private final InventoryPricingWriteHandler handler = new InventoryPricingWriteHandler();

  @BeforeEach
  void setUp() {
    ReflectionTestUtils.setField(handler, "pricingPort", pricingPort);
    ReflectionTestUtils.setField(handler, "shopRepository", shopRepository);
    ReflectionTestUtils.setField(handler, "shopRateRulesPort", rateRulesPort);
    when(shopRepository.findById(anyString()))
        .thenReturn(Optional.of(RateRulesTestFixtures.shop(ShopType.RETAILER, "pharmacy")));
  }

  private Inventory newInventory() {
    Inventory inventory = new Inventory();
    inventory.setShopId(RateRulesTestFixtures.SHOP);
    inventory.setSellingPrice(new BigDecimal("100"));
    inventory.setCostPrice(new BigDecimal("60"));
    inventory.setRates(List.of(RateLine.of("Rate-S", new BigDecimal("95"))));
    return inventory;
  }

  private PricingCreate created() {
    ArgumentCaptor<PricingCreate> captor = ArgumentCaptor.forClass(PricingCreate.class);
    verify(pricingPort).create(captor.capture());
    return captor.getValue();
  }

  /** An inventory update of a saved retailer batch that sends only the selling price. */
  private PricingUpdate updatedWithOnlySellingPrice(String sellingPrice) {
    Inventory inventory = newInventory();
    inventory.setId("inv-1");
    inventory.setPricingId("p-1");
    UpdateInventoryRequest req = new UpdateInventoryRequest();
    req.setSellingPrice(new BigDecimal(sellingPrice));
    InventoryPricingContext.setUpdate(req, "owner-1");
    try {
      handler.persistOnSave(inventory);
    } finally {
      InventoryPricingContext.clear();
    }
    ArgumentCaptor<PricingUpdate> captor = ArgumentCaptor.forClass(PricingUpdate.class);
    verify(pricingPort).update(eq("p-1"), captor.capture(), eq("owner-1"));
    return captor.getValue();
  }

  private void updateRatesWhilePricingThrows(RuntimeException failure) {
    doThrow(failure).when(pricingPort).update(anyString(), any(PricingUpdate.class), any());
    Inventory inventory = newInventory();
    inventory.setId("inv-1");
    inventory.setPricingId("p-1");
    UpdateInventoryRequest req = new UpdateInventoryRequest();
    req.setPriceToRetail(new BigDecimal("110"));
    InventoryPricingContext.setUpdate(req, "owner-1");
    try {
      handler.persistOnSave(inventory);
    } finally {
      InventoryPricingContext.clear();
    }
  }

  /**
   * A rejected rate edit (case-only clash with a rule, override on a missing base) must reach
   * the caller as 400 before the inventory is saved, not be dropped with the pricing update.
   */
  @Test
  void pricingValidationFailureOnUpdateReachesTheCaller() {
    ValidationException clash = new ValidationException("Rate rate-a differs from rate rule Rate-A only in case");

    ValidationException e = assertThrows(ValidationException.class,
        () -> updateRatesWhilePricingThrows(clash));

    assertSame(clash, e);
  }

  /** Other pricing failures are still logged and swallowed, as before. */
  @Test
  void otherPricingFailuresOnUpdateAreStillSwallowed() {
    assertDoesNotThrow(() -> updateRatesWhilePricingThrows(
        new BaseException(ErrorCode.INTERNAL_SERVER_ERROR, "Error updating pricing")));
    assertDoesNotThrow(() -> updateRatesWhilePricingThrows(new IllegalStateException("boom")));
  }

  /** T3: a retailer with rules gets MRP and PTR mirroring a new selling price, as on create. */
  @Test
  void retailerWithRulesSellingPriceUpdateMirrorsIntoMrpAndPtr() {
    when(rateRulesPort.rulesFor(RateRulesTestFixtures.SHOP)).thenReturn(List.of(
        new ShopRateRule("Rate-S", PricingConstants.RATE_BASE_SELLING_PRICE, new BigDecimal("5"), null)));

    PricingUpdate cmd = updatedWithOnlySellingPrice("120");

    assertEquals(new BigDecimal("120"), cmd.sellingPrice());
    assertEquals(new BigDecimal("120"), cmd.priceToRetail());
    assertEquals(new BigDecimal("120"), cmd.maximumRetailPrice());
  }

  /** A retailer without rules keeps today's edit: only the selling price is sent on. */
  @Test
  void retailerWithoutRulesSellingPriceUpdateIsUnchanged() {
    when(rateRulesPort.rulesFor(RateRulesTestFixtures.SHOP)).thenReturn(List.of());

    PricingUpdate cmd = updatedWithOnlySellingPrice("120");

    assertEquals(new BigDecimal("120"), cmd.sellingPrice());
    assertNull(cmd.priceToRetail());
    assertNull(cmd.maximumRetailPrice());
  }

  @Test
  void retailerWithRulesKeepsGeneratedRates() {
    when(rateRulesPort.rulesFor(RateRulesTestFixtures.SHOP)).thenReturn(List.of(
        new ShopRateRule("Rate-S", PricingConstants.RATE_BASE_SELLING_PRICE, new BigDecimal("5"), null)));

    handler.persistOnSave(newInventory());

    PricingCreate cmd = created();
    assertEquals(1, cmd.rates().size());
    assertEquals("Rate-S", cmd.rates().get(0).name());
    assertEquals(new BigDecimal("100"), cmd.priceToRetail());
    assertEquals(new BigDecimal("100"), cmd.maximumRetailPrice());
  }

  @Test
  void retailerWithoutRulesStillNullsRates() {
    when(rateRulesPort.rulesFor(RateRulesTestFixtures.SHOP)).thenReturn(List.of());

    handler.persistOnSave(newInventory());

    assertNull(created().rates());
  }
}
