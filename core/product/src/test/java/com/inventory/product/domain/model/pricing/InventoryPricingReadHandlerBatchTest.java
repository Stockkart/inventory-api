package com.inventory.product.domain.model.pricing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.inventory.pricing.api.LotPricing;
import com.inventory.pricing.api.InventoryPricing;
import com.inventory.product.domain.model.Inventory;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;

/** Enriching a list of lots costs a fixed number of queries, whatever the number of lots. */
@ExtendWith(MockitoExtension.class)
class InventoryPricingReadHandlerBatchTest {

  @Mock private InventoryPricing pricingPort;
  @Mock private MongoTemplate mongoTemplate;
  @InjectMocks private InventoryPricingReadHandler handler;

  private static Inventory lot(String id, String pricingId, String shopId) {
    Inventory inv = new Inventory();
    inv.setId(id);
    inv.setPricingId(pricingId);
    inv.setShopId(shopId);
    return inv;
  }

  private static LotPricing priced(String mrp) {
    return new LotPricing(new BigDecimal(mrp), null, null, null, null, null, null, null, null,
        null, null, "2.5", "2.5", null);
  }

  @Test
  void legacyLotsAreReadInOneQueryNotOnePerLot() {
    String a = new ObjectId().toHexString();
    String b = new ObjectId().toHexString();
    String c = new ObjectId().toHexString();
    when(mongoTemplate.find(any(Query.class), eq(Document.class), eq("inventory"))).thenReturn(List.of(
        new Document("_id", new ObjectId(a)).append("maximumRetailPrice", new BigDecimal("10")),
        new Document("_id", new ObjectId(b)).append("maximumRetailPrice", new BigDecimal("20")),
        new Document("_id", new ObjectId(c)).append("costPrice", new BigDecimal("5"))));

    Map<String, LotPricing> out =
        handler.resolveBatch(List.of(lot(a, null, "s"), lot(b, null, "s"), lot(c, "", "s")));

    assertThat(out).hasSize(3);
    assertThat(out.get(a).maximumRetailPrice()).isEqualByComparingTo("10");
    assertThat(out.get(b).maximumRetailPrice()).isEqualByComparingTo("20");
    assertThat(out.get(c).costPrice()).isEqualByComparingTo("5");
    verify(mongoTemplate, times(1)).find(any(Query.class), eq(Document.class), eq("inventory"));
    verify(mongoTemplate, never()).findOne(any(Query.class), eq(Document.class), eq("inventory"));
    verify(pricingPort, never()).findByIdIn(anyList());
  }

  @Test
  void mixedListUsesOnePricingQueryAndOneLegacyQuery() {
    String legacy = new ObjectId().toHexString();
    when(pricingPort.findByIdIn(List.of("p1", "p2"))).thenReturn(Map.of("p1", priced("100"), "p2", priced("200")));
    when(mongoTemplate.find(any(Query.class), eq(Document.class), eq("inventory"))).thenReturn(List.of(
        new Document("_id", new ObjectId(legacy)).append("sellingPrice", new BigDecimal("7"))));

    Map<String, LotPricing> out = handler.resolveBatch(List.of(
        lot("i1", "p1", "s"), lot("i2", "p2", "s"), lot("i3", "p1", "s"), lot(legacy, null, "s")));

    assertThat(out.get("i1").maximumRetailPrice()).isEqualByComparingTo("100");
    assertThat(out.get("i3").maximumRetailPrice()).isEqualByComparingTo("100");
    assertThat(out.get("i2").maximumRetailPrice()).isEqualByComparingTo("200");
    // legacy rule kept: sellingPrice stands in for priceToRetail
    assertThat(out.get(legacy).priceToRetail()).isEqualByComparingTo("7");
    verify(pricingPort, times(1)).findByIdIn(anyList());

    ArgumentCaptor<Query> q = ArgumentCaptor.forClass(Query.class);
    verify(mongoTemplate, times(1)).find(q.capture(), eq(Document.class), eq("inventory"));
    // only the legacy lot is fetched, and only its price fields
    assertThat(q.getValue().getQueryObject().toJson()).contains(legacy).doesNotContain("i1");
    assertThat(q.getValue().getFieldsObject().keySet())
        .contains("maximumRetailPrice", "sellingPrice", "sgst", "cgst", "schemeType", "purchaseSchemeType");
  }

  @Test
  void legacyLotWithNoPriceFieldsResolvesToNothing() {
    String a = new ObjectId().toHexString();
    when(mongoTemplate.find(any(Query.class), eq(Document.class), eq("inventory")))
        .thenReturn(List.of(new Document("_id", new ObjectId(a))));

    assertThat(handler.resolveBatch(List.of(lot(a, null, "s")))).isEmpty();
  }

  @Test
  void gstComesFromTheLotsPricingOnlyNeverFromTheShop() {
    LotPricing noTax = new LotPricing(BigDecimal.TEN, null, null, null, null, null, null, null,
        null, null, null, null, null, null);
    when(pricingPort.findByIdIn(anyList())).thenReturn(Map.of("p", noTax, "q", priced("50")));

    List<Inventory> lots = List.of(lot("a", "p", "s1"), lot("b", "q", "s1"));
    handler.enrich(lots);

    assertThat(lots.get(0).getSgst()).isNull();
    assertThat(lots.get(0).getCgst()).isNull();
    assertThat(lots.get(0).getMaximumRetailPrice()).isEqualByComparingTo("10");
    assertThat(lots.get(1).getSgst()).isEqualTo("2.5");
    assertThat(lots.get(1).getCgst()).isEqualTo("2.5");
  }

  @Test
  void emptyListMakesNoQueries() {
    assertThat(handler.resolveBatch(List.of())).isEmpty();
    verify(pricingPort, never()).findByIdIn(anyList());
    verify(mongoTemplate, never()).find(any(Query.class), eq(Document.class), eq("inventory"));
  }
}
