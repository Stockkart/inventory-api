package com.inventory.product.service;

import com.inventory.product.domain.model.Purchase;
import com.inventory.product.domain.model.PurchaseItem;
import java.util.ArrayList;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.bson.Document;
import org.springframework.data.mapping.PersistentPropertyAccessor;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.mapping.MongoPersistentEntity;
import org.springframework.data.mongodb.core.mapping.MongoPersistentProperty;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Writes a {@link Purchase} the way {@code CartTotalsAdapter.storeTotals} does — named fields
 * through {@code updateFirst}, never a full-document replace.
 *
 * <p><b>Why.</b> {@code PurchaseRepository.save(purchase)} replaces the whole document with a
 * snapshot this request read some time ago, across several inventory and stock round-trips.
 * Anything another writer put on the bill in that window is deleted by it: a cafe tab's flush
 * appending a round and its {@code cafeFlushIds} entry, a {@code cafeKotCancels} push, an
 * {@code items.$.kotSentQuantity} decrement. For a flush that means lines whose tickets are
 * already in the kitchen vanishing from the bill — the food is cooked and nothing records it,
 * and with {@code cafeFlushIds} gone the tab that flushed is stranded too.
 *
 * <p><b>How.</b> The caller takes a {@link #snapshot(Purchase) pre-image} of the document before
 * it mutates anything — exactly the BSON {@code save} would have written at that moment — and
 * hands it back afterwards. The difference between that pre-image and the post-image is the set
 * of fields this request actually changed, and only those are written. A field this request did
 * not touch is not in the update at all, so a concurrent writer's value for it survives. With no
 * concurrent writer the resulting document is identical to what the replace produced: every
 * changed field is {@code $set} to the same value, and every field that became null is
 * {@code $unset}, which is what the replace did by omitting it.
 *
 * <p><b>Items.</b> {@code items} is never written as a whole array. Lines are paired between the
 * pre- and post-image by identity ({@code lineRef} when the line has one — a cafe bill can hold
 * two lines with the same {@code sellableRef} — otherwise the normalized sellable ref), and each
 * pairing yields only the sub-fields that differ, addressed as {@code items.$[f0].<field>} under
 * an array filter on that identity. So reducing a line's quantity writes that line's quantity and
 * nothing else: its {@code kotSentQuantity}, which belongs to the cancel path, is left where the
 * cancel path put it. Lines only in the post-image are {@code $push}ed; lines only in the
 * pre-image are {@code $pull}ed.
 *
 * <p>There is no {@code MongoTransactionManager} in this codebase, so an optimistic
 * {@code @Version} on {@link Purchase} would hand a conflict to every existing writer of the
 * document with no transaction to retry inside. A targeted write needs no retry: it cannot delete
 * what it does not name. It is still racy in the small — two requests changing the same line's
 * quantity leave whichever wrote last, as the replace did — but nothing is lost.
 *
 * <p>{@code $set} on {@code items.$[…]}, {@code $pull} on {@code items} and {@code $push} on
 * {@code items} conflict on the same path and cannot share one update, so a cart write is up to
 * three statements, and there is no {@code MongoTransactionManager} to wrap them in. They are
 * issued {@code $push}, then {@code $set}, then {@code $pull}, and that order is the whole of the
 * guarantee: a failure part way through leaves the bill <i>over-itemised</i> — a line on it that
 * the stored {@code grandTotal} does not yet include — never <i>over-totalled</i>. Settlement
 * reads the stored total, so an over-totalled bill charges the customer for an item that is not
 * on the invoice; an over-itemised one under-invoices goods that are visibly on it, which is
 * reconcilable from the document alone.
 */
@Component
@Slf4j
public class PurchaseTargetedWriter {

  private static final String ITEMS = "items";
  private static final String ID = "_id";
  private static final String CLASS = "_class";
  private static final String EXISTS = "$exists";
  private static final String CAFE_FLUSH_IDS = "cafeFlushIds";

  /**
   * Line identity fields, most specific first. {@code lineRef} names <i>this</i> line;
   * {@code sellableRef} only names what is being sold, and a cafe bill can hold two lines with the
   * same one.
   */
  private static final List<String> LINE_IDENTITY_FIELDS =
      List.of("lineRef", "sellableRef", "menuItemId", "inventoryId");

  private final MongoTemplate mongoTemplate;

  public PurchaseTargetedWriter(MongoTemplate mongoTemplate) {
    this.mongoTemplate = mongoTemplate;
  }

  /**
   * The document as {@code save} would write it right now: the pre-image a later
   * {@link #writeChangedFields} or {@link #writeChangedCart} diffs against.
   */
  public Document snapshot(Purchase purchase) {
    Document sink = new Document();
    if (purchase != null) {
      mongoTemplate.getConverter().write(purchase, sink);
    }
    return sink;
  }

  /**
   * Writes the scalar fields this request changed, and never {@code items}.
   *
   * <p>For the completion path, where {@code items} is not the subject of the change at all: the
   * status, the invoice number, the payment split. A round a tab flushed onto the bill while it
   * was being settled stays on the bill rather than being deleted by the settlement.
   *
   * <p>{@code items} is deliberately <b>not</b> writable through this method — {@link
   * #collectChanges} drops the key outright. A future "stamp the batch onto every line at
   * settlement" change must go through {@link #writeChangedCart}, or it will silently vanish here
   * rather than fail.
   *
   * @return the matched count — 0 means the document is no longer there.
   */
  public long writeChangedFields(String shopId, Purchase after, Document before) {
    Update update = new Update();
    boolean changed = collectScalarChanges(before, snapshot(after), after, update);
    if (!changed) {
      return 1L;
    }
    // Guarded on the cafeFlushIds this settlement read. The money fields are not in this update --
    // this request did not change them -- but a flush landing between the read and here recomputes
    // them, and the payment split, ledger, credit entries and printed response were all derived
    // from the smaller in-memory total. Rather than stamp COMPLETED over a bill whose stored total
    // no longer matches the money taken, the write matches nothing and the caller says so out
    // loud. Equality on the array as it was read: a flush always pushes its id, so it is the one
    // field that reliably marks "something landed under me". No other vertical ever has it, so
    // for grocery, medical and sports this is `cafeFlushIds: null` matching an absent field.
    return apply(shopId, after.getId(), update, new Document(CAFE_FLUSH_IDS, before.get(CAFE_FLUSH_IDS)));
  }

  /**
   * Writes everything a cart update changed: the scalars, and the lines one by one.
   *
   * @return {@code false} when the lines cannot be addressed individually — duplicate or missing
   *     line identity — in which case the caller must fall back to the full replace rather than
   *     write something it cannot aim. Callers should log that fall-back: it is the one path on
   *     which a concurrent append can still be lost.
   */
  public boolean writeChangedCart(String shopId, Purchase after, Document before) {
    Document afterDoc = snapshot(after);
    String billId = after.getId();
    Map<String, Document> beforeLines = byIdentity(before.getList(ITEMS, Document.class), billId, shopId);
    Map<String, Document> afterLines = byIdentity(afterDoc.getList(ITEMS, Document.class), billId, shopId);
    if (beforeLines == null || afterLines == null) {
      return false;
    }

    // Every statement is built before any of them is issued. A `return false` below means the
    // caller falls back to the full replace, and it must find the document exactly as it was --
    // a half-applied targeted write underneath a replace is worse than either alone.

    // Lines this request added, appended in their merged order. $push, not a replace of the
    // array, so a round another tab flushed in the meantime is still there.
    List<PurchaseItem> added = new ArrayList<>();
    for (String key : afterLines.keySet()) {
      if (beforeLines.containsKey(key)) {
        continue;
      }
      PurchaseItem line = lineOf(after, key);
      if (line == null) {
        return false;
      }
      added.add(line);
    }

    // The scalars, plus the sub-fields of lines that are on both images.
    Update fieldUpdate = new Update();
    collectScalarChanges(before, afterDoc, after, fieldUpdate);
    List<Criteria> arrayFilters = new ArrayList<>();
    for (Map.Entry<String, Document> entry : beforeLines.entrySet()) {
      Document afterLine = afterLines.get(entry.getKey());
      if (afterLine == null) {
        continue;
      }
      Document beforeLine = entry.getValue();
      String identifier = "f" + arrayFilters.size();
      Criteria filter = identityCriteria(beforeLine, identifier + ".");
      if (filter == null) {
        return false;
      }
      String placeholder = ITEMS + ".$[" + identifier + "].";
      PurchaseItem line = lineOf(after, entry.getKey());
      if (line == null) {
        return false;
      }
      PersistentPropertyAccessor<?> accessor = accessorFor(PurchaseItem.class, line);
      boolean lineChanged =
          collectChanges(beforeLine, afterLine, PurchaseItem.class, accessor, placeholder, fieldUpdate);
      if (lineChanged) {
        arrayFilters.add(filter);
      }
    }
    arrayFilters.forEach(fieldUpdate::filterArray);

    // Lines this request removed. Separate because $pull and $set on items.$[...] conflict on the
    // same path, and one statement per line because $pull matches an element by a condition on
    // the element, which is not a place an $or belongs.
    List<Document> removed = new ArrayList<>();
    for (Map.Entry<String, Document> entry : beforeLines.entrySet()) {
      if (afterLines.containsKey(entry.getKey())) {
        continue;
      }
      Document condition = identityCondition(entry.getValue());
      if (condition == null) {
        return false;
      }
      removed.add(condition);
    }

    // $push first, $set second, $pull last. These are three independent statements -- there is no
    // MongoTransactionManager here -- so the order decides what a failure between them leaves
    // behind. Totals last-but-one and lines first means a bill that stops half way is
    // over-itemised (a line present that the stored total does not include) rather than
    // over-totalled (a grandTotal larger than the sum of its lines). Settlement reads the stored
    // total, so the second state charges the customer for an item that is not on the invoice;
    // the first under-invoices goods that are, which is visible on the bill and reconcilable.
    // A pushed line cannot collide with an $[f] filter below it: a line that paired with a
    // stored one is not in `added` at all, and one that did not pair has a different identity.
    if (!added.isEmpty() && apply(shopId, billId, new Update().push(ITEMS).each(added.toArray())) == 0) {
      log.error("Cart {} in shop {} was gone when {} added line(s) were pushed", billId, shopId, added.size());
    }
    if (hasOperations(fieldUpdate)) {
      if (apply(shopId, billId, fieldUpdate) == 0) {
        log.error("Cart {} in shop {} was gone when its fields and totals were written", billId, shopId);
        return true;
      }
    }
    for (Document condition : removed) {
      if (apply(shopId, billId, new Update().pull(ITEMS, condition)) == 0) {
        log.error("Cart {} in shop {} was gone when line {} was pulled", billId, shopId, condition.toJson());
      }
    }
    return true;
  }

  // ----------------------------------------------------------------- the diff

  private boolean collectScalarChanges(
      Document before, Document after, Purchase entity, Update update) {
    return collectChanges(
        before, after, Purchase.class, accessorFor(Purchase.class, entity), "", update);
  }

  /**
   * {@code $set} for every key whose value changed, {@code $unset} for every key the post-image
   * dropped — which is exactly what the full replace did to a field that became null.
   *
   * <p>The value written is the mapped Java value, not the BSON of the post-image, so it goes
   * through the same conversion an ordinary save would use rather than a second one.
   */
  private boolean collectChanges(
      Document before,
      Document after,
      Class<?> type,
      PersistentPropertyAccessor<?> accessor,
      String prefix,
      Update update) {
    Set<String> keys = new LinkedHashSet<>();
    keys.addAll(before.keySet());
    keys.addAll(after.keySet());
    keys.remove(ID);
    keys.remove(CLASS);
    if (prefix.isEmpty()) {
      // items is written line by line, never as an array.
      keys.remove(ITEMS);
    }

    boolean changed = false;
    for (String key : keys) {
      if (Objects.equals(before.get(key), after.get(key))) {
        continue;
      }
      changed = true;
      if (!after.containsKey(key)) {
        update.unset(prefix + key);
        continue;
      }
      MongoPersistentProperty property = propertyFor(type, key);
      Object value =
          property != null && accessor != null ? accessor.getProperty(property) : after.get(key);
      update.set(prefix + key, value);
    }
    return changed;
  }

  // ------------------------------------------------------------- line identity

  /**
   * Lines by identity, or {@code null} when they cannot be told apart — an identity that is blank
   * or shared by two lines cannot address one of them, and guessing would write the wrong line.
   */
  private Map<String, Document> byIdentity(List<Document> lines, String billId, String shopId) {
    Map<String, Document> byKey = new LinkedHashMap<>();
    if (lines == null) {
      return byKey;
    }
    for (Document line : lines) {
      String key = identityKey(line);
      if (key == null) {
        log.warn(
            "Cart {} in shop {} holds a line with no usable identity: {}",
            billId,
            shopId,
            identityFieldsOf(line));
        return null;
      }
      if (byKey.put(key, line) != null) {
        // The colliding key, not just the fact of a collision: without it this line is
        // unactionable in production -- there is no way to tell which of the bill's lines to look
        // at, and the fallback that follows is the one path a concurrent append can still be
        // lost on.
        log.warn("Cart {} in shop {} has two lines sharing the identity {}", billId, shopId, key);
        return null;
      }
    }
    return byKey;
  }

  /** Just the identity fields of a line, for a log line that must not carry prices or names. */
  private static Document identityFieldsOf(Document line) {
    Document fields = new Document();
    for (String field : LINE_IDENTITY_FIELDS) {
      if (line.get(field) != null) {
        fields.append(field, line.get(field));
      }
    }
    return fields;
  }

  /**
   * The key a line is paired by across the two images. {@code lineRef} when it has one; otherwise
   * the sellable ref, normalized the way {@code PurchaseItemRefs} normalizes it, so a legacy line
   * that gains a {@code sellableRef} during the merge still pairs with its stored self.
   */
  private static String identityKey(Document line) {
    String lineRef = line.getString("lineRef");
    if (StringUtils.hasText(lineRef)) {
      return "line:" + lineRef;
    }
    String sellableRef = line.getString("sellableRef");
    if (StringUtils.hasText(sellableRef)) {
      return "ref:" + sellableRef;
    }
    String menuItemId = line.getString("menuItemId");
    if (StringUtils.hasText(menuItemId)) {
      return "ref:menu:" + menuItemId;
    }
    String inventoryId = line.getString("inventoryId");
    if (StringUtils.hasText(inventoryId)) {
      return "ref:inventory:" + inventoryId;
    }
    return null;
  }

  /**
   * The array filter that picks this stored line out of {@code items}, built from the same notion
   * of identity the pairing uses.
   */
  private static Criteria identityCriteria(Document storedLine, String prefix) {
    Document condition = identityCondition(storedLine);
    if (condition == null) {
      return null;
    }
    Criteria criteria = null;
    for (Map.Entry<String, Object> entry : condition.entrySet()) {
      String path = prefix + entry.getKey();
      Criteria clause = criteria == null ? Criteria.where(path) : criteria.and(path);
      criteria =
          entry.getValue() instanceof Document nested && nested.containsKey(EXISTS)
              ? clause.exists(nested.getBoolean(EXISTS))
              : clause.is(entry.getValue());
    }
    return criteria;
  }

  /**
   * The element condition that selects <i>the one line</i> {@link #identityKey} paired by -- the
   * condition a {@code $pull} matches it with, and the body of the array filter a
   * {@code items.$[f].field} write is aimed with.
   *
   * <p>It must agree with {@link #identityKey} exactly. It used not to: the key was
   * first-match-wins ({@code lineRef} if present, else the sellable ref) while the condition
   * conjoined whatever the stored line happened to carry. So a Sell-screen line with no
   * {@code lineRef} and a flushed line with one, both for the same {@code sellableRef}, paired as
   * two distinct keys -- no duplicate, no fallback -- and yet the first one's filter,
   * {@code {sellableRef: "menu:tea"}}, matched both. Re-quantifying the cashier's own tea
   * silently re-quantified the round already cooking in the kitchen, and the bill drifted from
   * both the ticket and its own {@code grandTotal}.
   *
   * <p>So: a line with a {@code lineRef} is addressed by that alone -- it names <i>this</i> line
   * and nothing else. A line without one is addressed by its refs <i>and</i>
   * {@code lineRef: {$exists: false}}, which is what makes it not the flushed line next to it.
   */
  private static Document identityCondition(Document storedLine) {
    Object lineRef = storedLine.get("lineRef");
    if (lineRef instanceof String text && StringUtils.hasText(text)) {
      return new Document("lineRef", text);
    }
    Document condition = new Document();
    for (String field : LINE_IDENTITY_FIELDS) {
      if ("lineRef".equals(field)) {
        continue;
      }
      Object value = storedLine.get(field);
      if (value instanceof String text && StringUtils.hasText(text)) {
        condition.append(field, text);
      }
    }
    if (condition.isEmpty()) {
      return null;
    }
    return condition.append("lineRef", new Document(EXISTS, false));
  }

  private PurchaseItem lineOf(Purchase purchase, String identityKey) {
    if (purchase.getItems() == null) {
      return null;
    }
    for (PurchaseItem item : purchase.getItems()) {
      Document sink = new Document();
      mongoTemplate.getConverter().write(item, sink);
      if (identityKey.equals(identityKey(sink))) {
        return item;
      }
    }
    return null;
  }

  // ------------------------------------------------------------------ plumbing

  private long apply(String shopId, String purchaseId, Update update) {
    return apply(shopId, purchaseId, update, null);
  }

  /**
   * @param guard extra equality clauses on values this request read, so the write matches nothing
   *     — rather than silently winning — if another writer changed them underneath it.
   */
  private long apply(String shopId, String purchaseId, Update update, Document guard) {
    // Every write scoped by shopId, as every read is.
    Criteria criteria = Criteria.where(ID).is(purchaseId).and("shopId").is(shopId);
    if (guard != null) {
      for (Map.Entry<String, Object> entry : guard.entrySet()) {
        criteria = criteria.and(entry.getKey()).is(entry.getValue());
      }
    }
    return mongoTemplate.updateFirst(Query.query(criteria), update, Purchase.class).getMatchedCount();
  }

  private static boolean hasOperations(Update update) {
    Document operations = update.getUpdateObject();
    for (Map.Entry<String, Object> entry : operations.entrySet()) {
      if (entry.getValue() instanceof Document body && !body.isEmpty()) {
        return true;
      }
    }
    return false;
  }

  private MongoPersistentEntity<?> entityFor(Class<?> type) {
    return mongoTemplate.getConverter().getMappingContext().getPersistentEntity(type);
  }

  private PersistentPropertyAccessor<?> accessorFor(Class<?> type, Object instance) {
    MongoPersistentEntity<?> entity = entityFor(type);
    return entity == null || instance == null ? null : entity.getPropertyAccessor(instance);
  }

  /** The mapped property behind a stored key, which is not always the property's own name. */
  private MongoPersistentProperty propertyFor(Class<?> type, String fieldName) {
    MongoPersistentEntity<?> entity = entityFor(type);
    if (entity == null) {
      return null;
    }
    for (MongoPersistentProperty property : entity) {
      if (fieldName.equals(property.getFieldName())) {
        return property;
      }
    }
    return null;
  }
}
