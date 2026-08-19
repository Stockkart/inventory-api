package com.inventory.product.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.inventory.pluginengine.ref.SellableRef;
import com.inventory.product.domain.model.Inventory;
import com.inventory.product.domain.model.Purchase;
import com.inventory.product.domain.model.PurchaseItem;
import com.inventory.product.domain.model.enums.PurchaseStatus;
import com.inventory.product.domain.model.enums.SchemeType;
import com.inventory.product.domain.repository.InventoryRepository;
import com.inventory.product.domain.repository.ProductRepository;
import com.inventory.product.rest.dto.response.CustomerProductHistoryResponse;
import com.inventory.product.rest.dto.response.CustomerProductSaleEntryDto;
import com.inventory.user.service.CustomerService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;

@ExtendWith(MockitoExtension.class)
class CustomerProductHistoryServiceTest {

  private static final String SHOP = "shop-1";
  private static final String CUSTOMER = "cust-1";
  private static final String PRODUCT = "prod-liv52";
  private static final String CURRENT_LOT = "lot-current";
  private static final String OLD_LOT = "lot-old";
  private static final String NAME = "LIV. 52 SYRUP 100ML 1X56";
  private static final String COMPANY = "HIMALAYA WELLNESS COMPANY - ZEAL";

  @Mock private MongoTemplate mongoTemplate;
  @Mock private CustomerService customerService;
  @Mock private InventoryRepository inventoryRepository;
  @Mock private ProductRepository productRepository;

  @InjectMocks private CustomerProductHistoryService service;

  private final List<Inventory> knownLots = new ArrayList<>();

  @BeforeEach
  void setUp() {
    knownLots.clear();
    when(inventoryRepository.findByIdIn(any())).thenAnswer(invocation -> {
      List<String> ids = invocation.getArgument(0);
      return knownLots.stream().filter(inv -> ids.contains(inv.getId())).toList();
    });
    when(productRepository.findByShopIdAndNormalizedName(eq(SHOP), any()))
        .thenReturn(List.of());
    when(inventoryRepository.findByShopIdAndProductIdIn(eq(SHOP), anyCollection()))
        .thenAnswer(invocation -> knownLots.stream()
            .filter(inv -> PRODUCT.equals(inv.getProductId()))
            .toList());
  }

  @Test
  void matchesFinishedLotOfSameProductOnANewInventoryId() {
    knownLots.add(lot(CURRENT_LOT, PRODUCT, NAME, COMPANY));
    knownLots.add(lot(OLD_LOT, PRODUCT, NAME, COMPANY));

    Purchase past = sale("p1", "INV-00005", item(OLD_LOT, NAME, "5.5", SchemeType.FIXED_UNITS, 10, 2, null));
    when(mongoTemplate.find(any(Query.class), eq(Purchase.class))).thenReturn(List.of(past));

    CustomerProductHistoryResponse response = service.getHistory(
        SHOP, CUSTOMER, null, List.of(SellableRef.inventory(CURRENT_LOT).encode()), 3, null);

    CustomerProductSaleEntryDto entry = response.getBySellableRef()
        .get(SellableRef.inventory(CURRENT_LOT).encode())
        .getLastSale();
    assertNotNull(entry);
    assertEquals("INV-00005", entry.getInvoiceNo());
    assertEquals(new BigDecimal("5.5"), entry.getSaleAdditionalDiscount());
    assertEquals(SchemeType.FIXED_UNITS, entry.getSchemeType());
    assertEquals(10, entry.getSchemePayFor());
    assertEquals(2, entry.getSchemeFree());
  }

  @Test
  void matchesByNameWhenOldInventoryDocumentIsGone() {
    knownLots.add(lot(CURRENT_LOT, PRODUCT, NAME, COMPANY));
    Purchase past = sale("p2", "INV-00006", item("lot-deleted", NAME, "0", SchemeType.PERCENTAGE, null, null, new BigDecimal("8")));
    when(mongoTemplate.find(any(Query.class), eq(Purchase.class))).thenReturn(List.of(past));

    CustomerProductHistoryResponse response = service.getHistory(
        SHOP, CUSTOMER, null, List.of(SellableRef.inventory(CURRENT_LOT).encode()), 3, null);

    CustomerProductSaleEntryDto entry = response.getBySellableRef()
        .get(SellableRef.inventory(CURRENT_LOT).encode())
        .getLastSale();
    assertNotNull(entry);
    assertEquals("INV-00006", entry.getInvoiceNo());
    assertEquals(SchemeType.PERCENTAGE, entry.getSchemeType());
    assertEquals(new BigDecimal("8"), entry.getSchemePercentage());
  }

  @Test
  void doesNotMatchSameNameFromADifferentCompanyWhenThatLotStillExists() {
    knownLots.add(lot(CURRENT_LOT, PRODUCT, NAME, COMPANY));
    knownLots.add(lot("lot-other", "prod-other", NAME, "OTHER LABS"));
    Purchase past = sale("p3", "INV-00999", item("lot-other", NAME, "2", null, null, null, null));
    when(mongoTemplate.find(any(Query.class), eq(Purchase.class))).thenReturn(List.of(past));

    CustomerProductHistoryResponse response = service.getHistory(
        SHOP, CUSTOMER, null, List.of(SellableRef.inventory(CURRENT_LOT).encode()), 3, null);

    assertNull(response.getBySellableRef()
        .get(SellableRef.inventory(CURRENT_LOT).encode())
        .getLastSale());
  }

  private static Inventory lot(String id, String productId, String name, String company) {
    Inventory inv = new Inventory();
    inv.setId(id);
    inv.setProductId(productId);
    inv.setName(name);
    inv.setCompanyName(company);
    inv.setShopId(SHOP);
    return inv;
  }

  private static PurchaseItem item(
      String lotId,
      String name,
      String discount,
      SchemeType schemeType,
      Integer payFor,
      Integer free,
      BigDecimal schemePercentage) {
    PurchaseItem item = new PurchaseItem();
    item.setSellableRef(SellableRef.inventory(lotId).encode());
    item.setName(name);
    item.setQuantity(BigDecimal.ONE);
    item.setPriceToRetail(new BigDecimal("85.93"));
    item.setSaleAdditionalDiscount(new BigDecimal(discount));
    item.setSchemeType(schemeType);
    item.setSchemePayFor(payFor);
    item.setSchemeFree(free);
    item.setSchemePercentage(schemePercentage);
    return item;
  }

  private static Purchase sale(String id, String invoiceNo, PurchaseItem line) {
    Purchase purchase = new Purchase();
    purchase.setId(id);
    purchase.setInvoiceNo(invoiceNo);
    purchase.setShopId(SHOP);
    purchase.setCustomerId(CUSTOMER);
    purchase.setStatus(PurchaseStatus.COMPLETED);
    purchase.setSoldAt(Instant.parse("2026-08-19T10:00:00Z"));
    purchase.setItems(List.of(line));
    return purchase;
  }
}
