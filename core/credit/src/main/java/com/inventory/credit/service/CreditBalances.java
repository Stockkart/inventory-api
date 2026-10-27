package com.inventory.credit.service;

import com.inventory.credit.domain.model.CreditAccount;
import com.inventory.credit.domain.model.CreditBalanceStatus;
import com.inventory.credit.domain.model.CreditPartyType;
import com.mongodb.MongoWriteException;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.ReturnDocument;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.bson.types.Decimal128;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

/**
 * A party's due ({@code credit_accounts.currentBalance}) moved in one atomic update: the amount is
 * added, the status (due / clear / advance) set from the new balance, and the account created if
 * it is the party's first entry — all in the same operation. Reading the account, adding in Java and
 * saving it back let two entries at the same moment both start from the same balance, and one of
 * them was lost.
 */
@Component
public class CreditBalances {

  private static final int MONEY_SCALE = 4;
  private static final int DUPLICATE_KEY = 11000;

  private final MongoTemplate mongoTemplate;

  public CreditBalances(MongoTemplate mongoTemplate) {
    this.mongoTemplate = mongoTemplate;
  }

  /** The account after the change: its id and its new balance. */
  public record Applied(String accountId, BigDecimal balance) {}

  /**
   * Adds {@code delta} to the party's due (creating the account at zero first if it has none) and
   * stamps the display name, phone and times as the old read-modify-save did.
   */
  public Applied add(
      String shopId,
      CreditPartyType partyType,
      String partyRefId,
      String partyDisplayName,
      String partyPhone,
      BigDecimal delta,
      Instant now) {
    List<Bson> pipeline = update(delta, partyDisplayName, partyPhone, now, true);
    FindOneAndUpdateOptions options =
        new FindOneAndUpdateOptions().upsert(true).returnDocument(ReturnDocument.AFTER);
    Document after;
    try {
      after = accounts().findOneAndUpdate(byParty(shopId, partyType, partyRefId), pipeline, options);
    } catch (MongoWriteException | DuplicateKeyException e) {
      if (!isDuplicateKey(e)) {
        throw e;
      }
      // two first entries at once: the other one created the account; add to it
      after = accounts().findOneAndUpdate(byParty(shopId, partyType, partyRefId), pipeline, options);
    }
    return new Applied(String.valueOf(after.get("_id")), scale(decimal(after.get("currentBalance"))));
  }

  /** Takes back an {@link #add} whose entry could not be saved (there are no transactions). */
  public void undo(String shopId, CreditPartyType partyType, String partyRefId, BigDecimal delta, Instant now) {
    accounts()
        .updateOne(byParty(shopId, partyType, partyRefId), update(delta.negate(), null, null, now, false));
  }

  /**
   * {@code currentBalance += delta}, then {@code status} from the new balance. Values from the
   * request are wrapped in {@code $literal} so a name starting with {@code $} is not read as a field.
   */
  private static List<Bson> update(BigDecimal delta, String displayName, String phone, Instant now, boolean stamp) {
    Document balance =
        new Document("$add", Arrays.asList(new Document("$ifNull", Arrays.asList("$currentBalance", zero())), dec(delta)));
    Document set = new Document("currentBalance", balance).append("updatedAt", Date.from(now));
    if (stamp) {
      set.append("partyDisplayName", literal(displayName))
          .append("partyPhone", literal(phone))
          .append("lastEntryAt", Date.from(now))
          .append("createdAt", new Document("$ifNull", Arrays.asList("$createdAt", Date.from(now))))
          .append("_class", new Document("$ifNull", Arrays.asList("$_class", CreditAccount.class.getName())));
    }
    Document status =
        new Document(
            "$switch",
            new Document(
                    "branches",
                    List.of(
                        new Document("case", new Document("$gt", Arrays.asList("$currentBalance", 0)))
                            .append("then", CreditBalanceStatus.DUE.name()),
                        new Document("case", new Document("$lt", Arrays.asList("$currentBalance", 0)))
                            .append("then", CreditBalanceStatus.ADVANCE.name())))
                .append("default", CreditBalanceStatus.CLEAR.name()));
    return List.of(new Document("$set", set), new Document("$set", new Document("status", status)));
  }

  private MongoCollection<Document> accounts() {
    return mongoTemplate.getCollection(mongoTemplate.getCollectionName(CreditAccount.class));
  }

  private static Bson byParty(String shopId, CreditPartyType partyType, String partyRefId) {
    return Filters.and(
        Filters.eq("shopId", shopId), Filters.eq("partyType", partyType.name()), Filters.eq("partyRefId", partyRefId));
  }

  private static boolean isDuplicateKey(RuntimeException e) {
    if (e instanceof DuplicateKeyException) {
      return true;
    }
    return e instanceof MongoWriteException w && w.getError().getCode() == DUPLICATE_KEY;
  }

  private static Document literal(Object v) {
    return new Document("$literal", v);
  }

  private static Decimal128 dec(BigDecimal v) {
    return new Decimal128(scale(v));
  }

  private static Decimal128 zero() {
    return dec(BigDecimal.ZERO);
  }

  private static BigDecimal scale(BigDecimal v) {
    return v.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
  }

  private static BigDecimal decimal(Object v) {
    if (v instanceof Decimal128 d) {
      return d.bigDecimalValue();
    }
    if (v instanceof Number n) {
      return new BigDecimal(n.toString());
    }
    return BigDecimal.ZERO;
  }
}
