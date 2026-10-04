package com.inventory.product.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyIterable;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.inventory.pricing.domain.model.Pricing;
import com.inventory.pricing.domain.repository.PricingRepository;
import com.inventory.product.domain.model.Inventory;
import com.inventory.product.domain.model.Product;
import com.inventory.product.domain.repository.InventoryRepository;
import com.inventory.product.domain.repository.ProductRepository;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class HsnRateConsistencyTest {

  @Spy
  private HsnGstRateMaster hsnGstRateMaster =
      new HsnGstRateMaster(Map.of(
          "3401", new HsnGstRateMaster.Entry(List.of(new BigDecimal("18")), "verified"),
          "3306", new HsnGstRateMaster.Entry(
              List.of(new BigDecimal("5"), new BigDecimal("18")), "verified"),
          "3304", new HsnGstRateMaster.Entry(List.of(new BigDecimal("12")), "unchecked")));

  @Mock private ProductRepository productRepository;
  @Mock private InventoryRepository inventoryRepository;
  @Mock private PricingRepository pricingRepository;
  @InjectMocks private HsnRateConsistency consistency;

  private final List<Product> products = new ArrayList<>();
  private final List<Inventory> lots = new ArrayList<>();
  private final List<Pricing> pricings = new ArrayList<>();

  @BeforeEach
  void setUp() {
    when(productRepository.findByShopIdAndHsn(eq("s1"), any())).thenReturn(products);
    when(inventoryRepository.findByShopIdAndProductIdIn(eq("s1"), anyList())).thenReturn(lots);
    when(pricingRepository.findAllById(anyIterable())).thenReturn(pricings);
  }

  /** A product the shop holds under the HSN, priced at {@code halfGst} each side. */
  private void held(String halfGst) {
    String n = String.valueOf(products.size());
    Product product = new Product();
    product.setId("p" + n);
    products.add(product);
    Inventory lot = new Inventory();
    lot.setProductId("p" + n);
    lot.setPricingId("pr" + n);
    lots.add(lot);
    Pricing pricing = new Pricing();
    pricing.setId("pr" + n);
    pricing.setCgst(halfGst);
    pricing.setSgst(halfGst);
    pricings.add(pricing);
  }

  @Test
  void aVerifiedRateOverrulesAShopThatAgreesWithItself() {
    held("6");
    held("6");

    Optional<HsnRateConsistency.Conflict> conflict =
        consistency.check("s1", "34011190", new BigDecimal("12"));

    assertTrue(conflict.isPresent());
    assertEquals(0, new BigDecimal("18").compareTo(conflict.get().expectedRates().get(0)));
  }

  /** Toothpaste 5% and other oral care 18% share 3306; either is right, a third is not. */
  @Test
  void anyRateTheScheduleAllowsIsFineAndOthersAreFlagged() {
    held("6");
    held("6");

    assertTrue(consistency.check("s1", "33061020", new BigDecimal("5")).isEmpty());
    assertTrue(consistency.check("s1", "33061020", new BigDecimal("18")).isEmpty());
    Optional<HsnRateConsistency.Conflict> conflict =
        consistency.check("s1", "33061020", new BigDecimal("12"));
    assertTrue(conflict.isPresent());
    assertEquals("Paste is recorded at 12% GST, but HSN 33061020 is rated at 5% or 18%.",
        conflict.get().describe("Paste"));
  }

  @Test
  void anUnverifiedEntryCannotOverruleTheShop() {
    held("9");
    held("9");

    assertTrue(consistency.check("s1", "33049910", new BigDecimal("18")).isEmpty());
  }

  @Test
  void aRateTheShopAlreadyUsesIsFine() {
    held("9");
    held("9");
    held("2.5");

    assertTrue(consistency.check("s1", "30049099", new BigDecimal("5")).isEmpty());
  }

  @Test
  void theOddOneOutAgainstTheShopsOwnRecordsIsFlagged() {
    held("6");
    held("6");

    Optional<HsnRateConsistency.Conflict> conflict =
        consistency.check("s1", "30049099", new BigDecimal("18"));

    assertTrue(conflict.isPresent());
    assertEquals(0, new BigDecimal("12").compareTo(conflict.get().expectedRates().get(0)));
  }

  @Test
  void oneProductIsNotEnoughToFlagAnything() {
    held("6");

    assertTrue(consistency.check("s1", "30049099", new BigDecimal("18")).isEmpty());
  }
}
