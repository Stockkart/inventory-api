package com.inventory.product.domain.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.List;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.convert.DbRefResolver;
import org.springframework.data.mongodb.core.convert.MappingMongoConverter;
import org.springframework.data.mongodb.core.convert.MongoCustomConversions;
import org.springframework.data.mongodb.core.convert.NoOpDbRefResolver;
import org.springframework.data.mongodb.core.mapping.MongoMappingContext;

/**
 * The cafe vertical writes onto a {@code purchases} document from {@code plugins/cafe}, which
 * cannot depend on this module and so reaches the collection as raw BSON. Every such key must
 * also be a mapped property here, because {@code MongoRepository.save} is a full-document
 * <b>replace</b>: an unmapped key is read into nothing and written back as nothing, so the very
 * next ordinary save from the Sell screen erases it silently.
 *
 * <p>These tests are therefore about the round trip and not about the getters — they read the
 * values back out of the written {@link Document} by key, which is exactly what the server would
 * store, and which is what an unmapped field fails.
 */
class PurchaseCafeFieldMappingTest {

  private MappingMongoConverter converter;

  @BeforeEach
  void setUp() {
    DbRefResolver dbRefResolver = NoOpDbRefResolver.INSTANCE;
    MongoCustomConversions conversions = new MongoCustomConversions(List.of());
    MongoMappingContext context = new MongoMappingContext();
    // Without this, BigDecimal is walked as an entity rather than stored as a value.
    context.setSimpleTypeHolder(conversions.getSimpleTypeHolder());
    context.afterPropertiesSet();
    converter = new MappingMongoConverter(dbRefResolver, context);
    converter.setCustomConversions(conversions);
    converter.afterPropertiesSet();
  }

  @Test
  void cafeKotPunchesSurvivesAnOrdinarySaveOfTheBill() {
    Document punch =
        new Document("punchId", "punch-1")
            .append("idempotencyKey", "idem-1")
            .append("status", "PENDING_KOT_CREATION")
            .append("kotIds", List.of("punch-1:KITCHEN:ISSUE"))
            .append(
                "deltas",
                List.of(
                    new Document("sellableRef", "menu:tea")
                        .append("name", "Tea")
                        .append("quantity", 2)));
    Document stored =
        new Document("_id", "bill-1")
            .append("shopId", "shop-1")
            .append("cafeKotPunches", List.of(punch));

    Document rewritten = roundTrip(stored);

    List<?> punches = (List<?>) rewritten.get("cafeKotPunches");
    assertNotNull(
        punches,
        "the bill's record of what the kitchen was sent: without it the claim's $ne idempotency "
            + "cannot hold, a replay has no deltas to finish from, and a ticket's round number is "
            + "derived from an empty list");
    assertEquals(1, punches.size());
    Document rewrittenPunch = (Document) punches.get(0);
    assertEquals("punch-1", rewrittenPunch.get("punchId"));
    assertEquals(
        2,
        ((Document) ((List<?>) rewrittenPunch.get("deltas")).get(0)).get("quantity"),
        "including the delta a cancellation is computed from");
  }

  @Test
  void aBillLinesLineRefSurvivesTheSameRoundTrip() {
    Document item =
        new Document("sellableRef", "menu:tea")
            .append("lineRef", "line-1")
            .append("name", "Tea");
    Document stored =
        new Document("_id", "bill-1").append("shopId", "shop-1").append("items", List.of(item));

    Document rewritten = roundTrip(stored);

    List<?> items = (List<?>) rewritten.get("items");
    assertEquals(
        "line-1",
        ((Document) items.get(0)).get("lineRef"),
        "two lines can share one sellableRef; only this tells the kitchen which was reduced");
  }

  /** Read as the mapper reads a stored document, then written as {@code save} would write it. */
  private Document roundTrip(Document stored) {
    Purchase read = converter.read(Purchase.class, stored);
    Document rewritten = new Document();
    converter.write(read, rewritten);
    return rewritten;
  }
}
