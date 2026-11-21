package com.inventory.product.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.inventory.product.domain.model.Inventory;
import com.inventory.product.domain.model.Purchase;
import com.inventory.product.domain.model.PurchaseItem;
import com.inventory.product.domain.model.Shop;
import com.inventory.product.domain.model.enums.BillingMode;
import com.inventory.product.domain.model.enums.PurchaseStatus;
import com.inventory.product.domain.repository.InventoryRepository;
import com.inventory.product.domain.repository.ShopRepository;
import com.inventory.product.mapper.PurchaseMapper;
import com.inventory.product.util.PurchaseItemRefs;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * A stock line tapped a second time is rebuilt, not patched. The rebuild must keep what the kitchen
 * side owns on the line — the station frozen when it was first added, the quantity already sent,
 * the cashier's note — or a "No ticket" Coke falls back to KITCHEN and prints.
 */
class CheckoutServiceStockLineStationTest {

  private static final String SHOP_ID = "shop-1";
  private static final String BILL_ID = "bill-7";

  private InMemoryPurchases purchases;
  private CheckoutService checkoutService;

  @BeforeEach
  void setUp() {
    purchases = new InMemoryPurchases();
    checkoutService = new CheckoutService();
    ShopRepository shopRepository = mock(ShopRepository.class);
    when(shopRepository.findById(anyString())).thenReturn(Optional.<Shop>empty());
    InventoryRepository inventoryRepository = mock(InventoryRepository.class);
    Inventory lot = new Inventory();
    lot.setId("inv-1");
    lot.setShopId(SHOP_ID);
    lot.setBaseUnit("BTL");
    when(inventoryRepository.findById("inv-1")).thenReturn(Optional.of(lot));
    PurchaseMapper purchaseMapper = mock(PurchaseMapper.class);
    when(purchaseMapper.createPurchaseItem(anyString(), any(), any(), any(), any(), any()))
        .thenCallRealMethod();
    ReflectionTestUtils.setField(checkoutService, "shopRepository", shopRepository);
    ReflectionTestUtils.setField(checkoutService, "inventoryRepository", inventoryRepository);
    ReflectionTestUtils.setField(checkoutService, "purchaseMapper", purchaseMapper);
    ReflectionTestUtils.setField(
        checkoutService, "packagingUnitService", mock(PackagingUnitService.class));
    ReflectionTestUtils.setField(checkoutService, "purchaseRepository", purchases.repository());
    ReflectionTestUtils.setField(
        checkoutService,
        "purchaseTargetedWriter",
        new PurchaseTargetedWriter(purchases.mongoTemplate()));
  }

  @Test
  void aSecondTapKeepsTheStationTheSentQuantityAndTheNote() {
    Purchase bill = openBill();
    PurchaseItem coke = stockLine(1);
    coke.setDepartment("NONE");
    coke.setKotSentQuantity(1);
    coke.setNote("no ice");
    bill.getItems().add(coke);
    Purchase cart = purchases.seed(bill);

    checkoutService.updateCart(
        cart, purchases.stored(BILL_ID), List.of(stockLine(1)), null, null, null, BillingMode.REGULAR);

    PurchaseItem after = purchases.read(BILL_ID).getItems().get(0);
    assertEquals(2, after.getBaseQuantity());
    assertEquals("NONE", after.getDepartment(), "the station frozen on first add survives");
    assertEquals(1, after.getKotSentQuantity(), "what the kitchen was sent is not forgotten");
    assertEquals("no ice", after.getNote());
  }

  private static PurchaseItem stockLine(int baseQuantity) {
    PurchaseItem item = new PurchaseItem();
    PurchaseItemRefs.applyInventoryLine(item, "inv-1");
    item.setName("Coca-Cola");
    item.setBaseQuantity(baseQuantity);
    item.setQuantity(BigDecimal.valueOf(baseQuantity));
    item.setSaleUnit("BTL");
    item.setUnitFactor(1);
    item.setPriceToRetail(new BigDecimal("20.00"));
    item.setMaximumRetailPrice(new BigDecimal("20.00"));
    item.setTotalAmount(new BigDecimal("20.00").multiply(BigDecimal.valueOf(baseQuantity)));
    return item;
  }

  private static Purchase openBill() {
    Purchase bill = new Purchase();
    bill.setId(BILL_ID);
    bill.setShopId(SHOP_ID);
    bill.setUserId("user-1");
    bill.setStatus(PurchaseStatus.CREATED);
    bill.setBillingMode(BillingMode.REGULAR);
    bill.setItems(new ArrayList<>());
    bill.setCreatedAt(Instant.now());
    bill.setUpdatedAt(Instant.now());
    return bill;
  }
}
