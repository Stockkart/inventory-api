package com.inventory.product.domain.model.pricing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.inventory.pricing.domain.model.RateRuleSpec;
import com.inventory.pricing.rest.dto.request.PricingCreateCommand;
import com.inventory.pricing.rest.dto.request.PricingUpdateCommand;
import com.inventory.pricing.rest.dto.response.RateDto;
import com.inventory.pricing.service.InventoryPricingAdapter;
import com.inventory.pricing.service.ShopRateRulesPort;
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

  @Mock private InventoryPricingAdapter pricingPort;
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
    RateDto rate = new RateDto();
    rate.setName("Rate-S");
    rate.setPrice(new BigDecimal("95"));
    inventory.setRates(List.of(rate));
    return inventory;
  }

  private PricingCreateCommand created() {
    ArgumentCaptor<PricingCreateCommand> captor = ArgumentCaptor.forClass(PricingCreateCommand.class);
    verify(pricingPort).create(captor.capture());
    return captor.getValue();
  }

  /** An inventory update of a saved retailer batch that sends only the selling price. */
  private PricingUpdateCommand updatedWithOnlySellingPrice(String sellingPrice) {
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
    ArgumentCaptor<PricingUpdateCommand> captor = ArgumentCaptor.forClass(PricingUpdateCommand.class);
    verify(pricingPort).update(eq("p-1"), captor.capture(), eq("owner-1"));
    return captor.getValue();
  }

  /** T3: a retailer with rules gets MRP and PTR mirroring a new selling price, as on create. */
  @Test
  void retailerWithRulesSellingPriceUpdateMirrorsIntoMrpAndPtr() {
    when(rateRulesPort.rulesFor(RateRulesTestFixtures.SHOP)).thenReturn(List.of(
        new RateRuleSpec("Rate-S", PricingConstants.RATE_BASE_SELLING_PRICE, new BigDecimal("5"), null)));

    PricingUpdateCommand cmd = updatedWithOnlySellingPrice("120");

    assertEquals(new BigDecimal("120"), cmd.getSellingPrice());
    assertEquals(new BigDecimal("120"), cmd.getPriceToRetail());
    assertEquals(new BigDecimal("120"), cmd.getMaximumRetailPrice());
  }

  /** A retailer without rules keeps today's edit: only the selling price is sent on. */
  @Test
  void retailerWithoutRulesSellingPriceUpdateIsUnchanged() {
    when(rateRulesPort.rulesFor(RateRulesTestFixtures.SHOP)).thenReturn(List.of());

    PricingUpdateCommand cmd = updatedWithOnlySellingPrice("120");

    assertEquals(new BigDecimal("120"), cmd.getSellingPrice());
    assertNull(cmd.getPriceToRetail());
    assertNull(cmd.getMaximumRetailPrice());
  }

  @Test
  void retailerWithRulesKeepsGeneratedRates() {
    when(rateRulesPort.rulesFor(RateRulesTestFixtures.SHOP)).thenReturn(List.of(
        new RateRuleSpec("Rate-S", PricingConstants.RATE_BASE_SELLING_PRICE, new BigDecimal("5"), null)));

    handler.persistOnSave(newInventory());

    PricingCreateCommand cmd = created();
    assertEquals(1, cmd.getRates().size());
    assertEquals("Rate-S", cmd.getRates().get(0).getName());
    assertEquals(new BigDecimal("100"), cmd.getPriceToRetail());
    assertEquals(new BigDecimal("100"), cmd.getMaximumRetailPrice());
  }

  @Test
  void retailerWithoutRulesStillNullsRates() {
    when(rateRulesPort.rulesFor(RateRulesTestFixtures.SHOP)).thenReturn(List.of());

    handler.persistOnSave(newInventory());

    assertNull(created().getRates());
  }

  @Test
  void retailerWithNoPortStillNullsRates() {
    ReflectionTestUtils.setField(handler, "shopRateRulesPort", null);

    handler.persistOnSave(newInventory());

    assertNull(created().getRates());
  }
}
