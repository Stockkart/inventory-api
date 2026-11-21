package com.inventory.accounting.service;

import static com.inventory.accounting.service.MoneyUtil.scale;
import static com.inventory.accounting.service.MoneyUtil.zero;

import com.inventory.accounting.domain.model.Account;
import com.inventory.accounting.domain.model.LedgerEntry;
import com.inventory.accounting.domain.repository.LedgerEntryRepository;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.Projections;
import com.mongodb.client.model.ReturnDocument;
import com.mongodb.client.model.Updates;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.bson.types.Decimal128;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

/**
 * Each account's running ledger balance, kept on the account document and moved with one atomic
 * update per posting. Two journals posted at the same moment each get their own, consecutive
 * balance; reading the newest ledger row and adding to it let both start from the same row, and one
 * of them was lost.
 *
 * <p>Two fields on {@code coa_accounts}:
 * <ul>
 *   <li>{@code ledgerBalance} — the balance, signed positive on the account's normal side, like
 *       {@link LedgerEntry#getBalanceAfter()};
 *   <li>{@code ledgerAt} — when the balance last moved, from the database's clock, strictly
 *       increasing per account. It becomes the ledger rows' {@code postedAt}, so rows list in the
 *       order their balances were applied (a time taken before the update could list two
 *       near-simultaneous bills the other way round, and their balances would look swapped).
 * </ul>
 *
 * <p>Neither is mapped on {@link Account}: account writes go through targeted {@code $set}s
 * ({@link AccountService#update}), so no whole-document save can drop or rewind them. An account
 * without them (every account before this change, or one rewritten whole) starts from its newest
 * ledger row — the balance posting used to read — set only if still missing, so two first postings
 * at once cannot both set it.
 */
@Component
public class AccountLedgerBalances {

  static final String FIELD = "ledgerBalance";
  static final String AT = "ledgerAt";

  private final MongoTemplate mongoTemplate;
  private final LedgerEntryRepository ledgerEntryRepository;

  public AccountLedgerBalances(MongoTemplate mongoTemplate, LedgerEntryRepository ledgerEntryRepository) {
    this.mongoTemplate = mongoTemplate;
    this.ledgerEntryRepository = ledgerEntryRepository;
  }

  /** The account's balance after a move, and the moment it moved (the rows' {@code postedAt}). */
  public record Moved(BigDecimal balance, Instant at) {}

  /** Adds {@code delta} to the account's balance and returns the balance after it, and when. */
  public Moved add(String shopId, String accountId, BigDecimal delta) {
    Document after = move(shopId, accountId, delta, true);
    if (after == null) {
      startFromNewestRow(shopId, accountId);
      after = move(shopId, accountId, delta, true);
    }
    if (after == null) {
      throw new IllegalStateException("Account " + accountId + " not found in shop " + shopId);
    }
    Date at = after.getDate(AT);
    return new Moved(scale(decimal(after.get(FIELD))), at != null ? at.toInstant() : Instant.now());
  }

  /** Takes back an {@link #add} whose ledger rows could not be saved (there are no transactions). */
  public void undo(String shopId, String accountId, BigDecimal delta) {
    move(shopId, accountId, delta.negate(), false);
  }

  /**
   * {@code ledgerBalance += delta} and, when {@code stamp}, {@code ledgerAt = max($$NOW, ledgerAt + 1 ms)}
   * — one update, so the time and the balance belong to the same move.
   */
  private Document move(String shopId, String accountId, BigDecimal delta, boolean stamp) {
    Document set = new Document(FIELD, new Document("$add", Arrays.asList("$" + FIELD, new Decimal128(scale(delta)))));
    if (stamp) {
      Document afterLast =
          new Document("$add", Arrays.asList(new Document("$ifNull", Arrays.asList("$" + AT, new Date(0))), 1));
      set.append(AT, new Document("$max", Arrays.asList("$$NOW", afterLast)));
    }
    return accounts()
        .findOneAndUpdate(
            Filters.and(byId(shopId, accountId), Filters.exists(FIELD, true)),
            List.of(new Document("$set", set)),
            new FindOneAndUpdateOptions()
                .returnDocument(ReturnDocument.AFTER)
                .projection(Projections.include(FIELD, AT)));
  }

  private void startFromNewestRow(String shopId, String accountId) {
    List<LedgerEntry> newest =
        ledgerEntryRepository.findFirst1ByShopIdAndAccountIdOrderByPostedAtDesc(shopId, accountId);
    LedgerEntry row = newest.isEmpty() ? null : newest.get(0);
    BigDecimal start = row == null || row.getBalanceAfter() == null ? zero() : scale(row.getBalanceAfter());
    List<Bson> set = new java.util.ArrayList<>();
    set.add(Updates.set(FIELD, new Decimal128(start)));
    if (row != null && row.getPostedAt() != null) {
      set.add(Updates.set(AT, Date.from(row.getPostedAt())));
    }
    accounts()
        .updateOne(Filters.and(byId(shopId, accountId), Filters.exists(FIELD, false)), Updates.combine(set));
  }

  private MongoCollection<Document> accounts() {
    return mongoTemplate.getCollection(mongoTemplate.getCollectionName(Account.class));
  }

  private static Bson byId(String shopId, String accountId) {
    Object id = ObjectId.isValid(accountId) ? new ObjectId(accountId) : accountId;
    return Filters.and(Filters.eq("_id", id), Filters.eq("shopId", shopId));
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
