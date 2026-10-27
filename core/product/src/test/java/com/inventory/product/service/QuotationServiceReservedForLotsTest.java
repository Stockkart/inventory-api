package com.inventory.product.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.inventory.product.domain.model.Purchase;
import com.inventory.product.domain.model.PurchaseItem;
import com.inventory.product.domain.model.enums.DocumentType;
import com.inventory.product.domain.model.enums.EstimateState;
import com.inventory.product.domain.model.enums.PurchaseStatus;
import com.inventory.product.domain.repository.PurchaseRepository;
import com.inventory.product.util.PurchaseItemRefs;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;

/**
 * Reserved stock for a page of lots reads only the open documents holding those lots, and sums
 * them exactly as the whole-shop version does.
 */
class QuotationServiceReservedForLotsTest {

  private static final String SHOP = "shop-1";

  private PurchaseRepository purchases;
  private MongoTemplate template;
  private QuotationService service;

  @BeforeEach
  void setUp() {
    purchases = mock(PurchaseRepository.class);
    template = mock(MongoTemplate.class);
    service = new QuotationService(purchases, template, null, null, null, null);
  }

  private static PurchaseItem line(String lotId, int base) {
    PurchaseItem i = new PurchaseItem();
    PurchaseItemRefs.applyInventoryLine(i, lotId);
    i.setBaseQuantity(base);
    i.setQuantity(BigDecimal.valueOf(base));
    return i;
  }

  private static PurchaseItem legacyLine(String lotId, int base) {
    PurchaseItem i = new PurchaseItem();
    i.setMongoInventoryId(lotId);
    i.setBaseQuantity(base);
    return i;
  }

  private static PurchaseItem menuLine() {
    PurchaseItem i = new PurchaseItem();
    i.setSellMode("menu");
    i.setSellableRef("menu:m1");
    i.setBaseQuantity(9);
    return i;
  }

  private static Purchase doc(String id, DocumentType type, EstimateState state, PurchaseItem... items) {
    Purchase p = new Purchase();
    p.setId(id);
    p.setShopId(SHOP);
    p.setStatus(PurchaseStatus.CREATED);
    p.setDocumentType(type);
    p.setEstimateState(state);
    p.setItems(new ArrayList<>(List.of(items)));
    return p;
  }

  /** The same open documents, as the whole-shop read and as the lot-scoped read would get them. */
  private List<Purchase> open() {
    return List.of(
        doc("q1", null, null, line("L1", 2), line("L2", 5), line("other", 7)),
        doc("q2", DocumentType.SALE, null, line("L1", 3), menuLine()),
        doc("e-open", DocumentType.ESTIMATE, EstimateState.OPEN, line("L2", 1)),
        doc("e-converted", DocumentType.ESTIMATE, EstimateState.CONVERTED, line("L1", 100)),
        doc("legacy", null, null, legacyLine("L2", 4)),
        doc("mine", null, null, line("L1", 50)));
  }

  @Test
  void sameNumbersAsTheWholeShopReadForTheAskedLots() {
    when(purchases.findByShopIdAndStatus(SHOP, PurchaseStatus.CREATED)).thenReturn(open());
    when(template.find(any(Query.class), eq(Purchase.class))).thenReturn(open());

    Map<String, Integer> whole = service.quotedBaseQuantitiesByLot(SHOP, "mine");
    Map<String, Integer> scoped = service.quotedBaseQuantitiesForLots(SHOP, "mine", List.of("L1", "L2"));

    assertThat(scoped).containsExactlyInAnyOrderEntriesOf(Map.of("L1", 5, "L2", 10));
    assertThat(scoped.get("L1")).isEqualTo(whole.get("L1"));
    assertThat(scoped.get("L2")).isEqualTo(whole.get("L2"));
    assertThat(scoped).doesNotContainKey("other");
  }

  @Test
  void readsOnlyOpenDocumentsHoldingTheLotsAndOnlyTheFieldsNeeded() {
    when(template.find(any(Query.class), eq(Purchase.class))).thenReturn(List.of());

    service.quotedBaseQuantitiesForLots(SHOP, null, List.of("L1", " L2 ", ""));

    ArgumentCaptor<Query> q = ArgumentCaptor.forClass(Query.class);
    verify(template).find(q.capture(), eq(Purchase.class));
    Document filter = q.getValue().getQueryObject();
    assertThat(filter.get("shopId")).isEqualTo(SHOP);
    assertThat(filter.get("status")).isEqualTo(PurchaseStatus.CREATED);
    String or = filter.get("$or").toString();
    assertThat(or).contains("items.stockRef", "items.sellableRef", "items.inventoryId", "inventory:L1", "inventory:L2");
    assertThat(q.getValue().getFieldsObject().keySet())
        .contains("items.stockRef", "items.sellableRef", "items.inventoryId", "items.baseQuantity", "documentType", "estimateState")
        .doesNotContain("items.name", "customerName");
    verify(purchases, never()).findByShopIdAndStatus(any(), any());
  }

  @Test
  void noLotsMeansNoRead() {
    assertThat(service.quotedBaseQuantitiesForLots(SHOP, null, List.of())).isEmpty();
    verify(template, never()).find(any(Query.class), eq(Purchase.class));
  }
}
