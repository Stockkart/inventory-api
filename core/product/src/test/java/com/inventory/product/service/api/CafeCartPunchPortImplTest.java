package com.inventory.product.service.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.inventory.pluginengine.kot.CafeCartPunchPort.Cart;
import com.inventory.pluginengine.kot.CafeCartPunchPort.Delta;
import com.inventory.pluginengine.kot.CafeCartPunchPort.Punch;
import com.inventory.product.service.vertical.CafeCartPuncher;
import java.util.List;
import java.util.Optional;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.data.mongodb.core.query.UpdateDefinition;

/**
 * The cart side of a cafe punch, as {@code core/product} serves it to {@code plugins/cafe}: every
 * read and write is scoped by shop, and the stored punch record reaches the plugin unchanged.
 */
class CafeCartPunchPortImplTest {

  private MongoTemplate mongoTemplate;
  private CafeCartPuncher puncher;
  private CafeCartPunchPortImpl port;

  @BeforeEach
  void setUp() {
    mongoTemplate = mock(MongoTemplate.class);
    puncher = mock(CafeCartPuncher.class);
    port = new CafeCartPunchPortImpl(mongoTemplate, puncher);
  }

  private void stubPurchase(Document purchase) {
    when(mongoTemplate.findOne(any(Query.class), eq(Document.class), eq("purchases")))
        .thenReturn(purchase);
  }

  @Test
  void theCartReadIsScopedByShopAndPurchase() {
    stubPurchase(new Document("_id", "p1"));

    port.findCart("shop-1", "p1");

    ArgumentCaptor<Query> query = ArgumentCaptor.forClass(Query.class);
    verify(mongoTemplate).findOne(query.capture(), eq(Document.class), eq("purchases"));
    assertEquals("shop-1", query.getValue().getQueryObject().get("shopId"));
    assertEquals("p1", query.getValue().getQueryObject().get("_id"));
  }

  @Test
  void anAbsentCartIsEmpty() {
    stubPurchase(null);

    assertTrue(port.findCart("shop-1", "p1").isEmpty());
  }

  @Test
  void aCartWithNoPunchesHasAnEmptyList() {
    stubPurchase(new Document("_id", "p1"));

    Cart cart = port.findCart("shop-1", "p1").orElseThrow();

    assertEquals(List.of(), cart.punches());
  }

  @Test
  void theStoredPunchReachesThePluginFieldForField() {
    Document delta =
        new Document("sellableRef", "menu:m1")
            .append("name", "Biryani")
            .append("department", "KITCHEN")
            .append("note", "extra spicy")
            .append("quantity", -2);
    Document done =
        new Document("punchId", "punch-0").append("idempotencyKey", "key-0").append("status", "COMPLETE");
    Document pending =
        new Document("punchId", "punch-1")
            .append("idempotencyKey", "key-1")
            .append("status", "PENDING_KOT_CREATION")
            .append("kotIds", List.of("punch-1:KITCHEN:CANCEL"))
            .append("deltas", List.of(delta));
    stubPurchase(
        new Document("_id", "p1")
            .append("tableLabel", "T4")
            .append("tokenNo", "12")
            .append("cafeKotPunches", List.of(done, pending)));

    Cart cart = port.findCart("shop-1", "p1").orElseThrow();

    assertEquals("p1", cart.id());
    assertEquals("T4", cart.tableLabel());
    assertEquals("12", cart.tokenNo());
    assertEquals(2, cart.punches().size(), "every punch, in the order pressed");
    assertTrue(cart.punches().get(0).complete());
    Punch punch = cart.punches().get(1);
    assertFalse(punch.complete());
    assertEquals("punch-1", punch.punchId());
    assertEquals("key-1", punch.idempotencyKey());
    assertEquals(List.of("punch-1:KITCHEN:CANCEL"), punch.kotIds());
    assertEquals(
        List.of(new Delta("menu:m1", "Biryani", "KITCHEN", "extra spicy", -2)), punch.deltas());
  }

  @Test
  void aQuantityOfAnyBsonWidthIsHonoured() {
    // Mongo hands back whatever numeric type the pipeline produced; a Long must not be dropped.
    Document delta = new Document("sellableRef", "menu:m1").append("quantity", 2L);
    stubPurchase(
        new Document("_id", "p1")
            .append(
                "cafeKotPunches",
                List.of(new Document("punchId", "punch-1").append("deltas", List.of(delta)))));

    Cart cart = port.findCart("shop-1", "p1").orElseThrow();

    assertEquals(2, cart.punches().get(0).deltas().get(0).quantity());
  }

  @Test
  void aQuantityThatIsNotANumberFailsLoudly() {
    Document delta = new Document("sellableRef", "menu:m1").append("quantity", "two");
    stubPurchase(
        new Document("_id", "p1")
            .append(
                "cafeKotPunches",
                List.of(new Document("punchId", "punch-1").append("deltas", List.of(delta)))));

    assertThrows(IllegalStateException.class, () -> port.findCart("shop-1", "p1"));
  }

  @Test
  void markingCompleteSetsStatusAndTicketsOnThatPunchInThatShop() {
    port.markPunchComplete("shop-1", "p1", "punch-1", List.of("punch-1:KITCHEN:ISSUE"));

    ArgumentCaptor<Query> query = ArgumentCaptor.forClass(Query.class);
    ArgumentCaptor<UpdateDefinition> update = ArgumentCaptor.forClass(UpdateDefinition.class);
    verify(mongoTemplate).updateFirst(query.capture(), update.capture(), eq("purchases"));
    Document match = query.getValue().getQueryObject();
    assertEquals("p1", match.get("_id"));
    assertEquals("shop-1", match.get("shopId"));
    assertEquals("punch-1", match.get("cafeKotPunches.punchId"));
    Document set = ((Update) update.getValue()).getUpdateObject().get("$set", Document.class);
    assertEquals("COMPLETE", set.get("cafeKotPunches.$.status"));
    assertEquals(List.of("punch-1:KITCHEN:ISSUE"), set.get("cafeKotPunches.$.kotIds"));
  }

  @Test
  void aClaimReportsWhetherTheWriteLanded() {
    when(puncher.claimAndReconcile("shop-1", "p1", "punch-1", "key-1", "user-1"))
        .thenReturn(Optional.of(new Document("_id", "p1")), Optional.empty());

    assertTrue(port.claimAndReconcile("shop-1", "p1", "punch-1", "key-1", "user-1"));
    assertFalse(port.claimAndReconcile("shop-1", "p1", "punch-1", "key-1", "user-1"));
  }
}
