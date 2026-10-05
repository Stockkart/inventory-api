package com.inventory.product.search;

import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoCollection;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

/**
 * Finishes a search whose sort key lives on another collection (see {@link SearchPlan.DeferredSort}).
 *
 * <p>The pipeline hands back every candidate lot id (unsorted) together with the key that links it to
 * the other collection. This class reads the sort value for those keys, orders the candidates (nulls
 * last, {@code _id} as the tiebreak so pages never overlap) and returns the ids of the requested
 * page in order.
 *
 * <p>Two ways to read the values, picked by how many distinct keys there are:
 *
 * <ul>
 *   <li><b>Few keys</b> (a filtered search): one indexed {@code $in} query for exactly those keys.
 *   <li><b>Many keys</b> (browsing most of the shop): one scan of the shop's rows ordered by the
 *       {@code (shopId, value)} index. Measured on 45,000 lots this is about half the cost of a
 *       45,000-key {@code $in}, and the rows arrive already sorted so the page is found by walking
 *       them until it is full.
 * </ul>
 */
@Component
public class DeferredSortRunner {

  /** Above this many distinct keys the shop scan beats the {@code $in}. */
  static final int SHOP_SCAN_THRESHOLD = 10_000;

  private final MongoTemplate mongoTemplate;

  public DeferredSortRunner(MongoTemplate mongoTemplate) {
    this.mongoTemplate = mongoTemplate;
  }

  /**
   * @param candidates rows with {@code _id} and {@code deferred.localKey()}
   * @param maxTimeMs time limit for the key read
   * @return ids of the page {@code [skip, skip + size)} in sorted order
   */
  public List<String> sortAndPage(List<Document> candidates, SearchPlan.DeferredSort deferred, int skip, int size, long maxTimeMs) {
    if (candidates.isEmpty()) {
      return List.of();
    }
    // local key (as string) → candidate ids carrying it (several lots share one product)
    Map<String, List<Document>> byKey = new HashMap<>();
    List<Document> keyless = new ArrayList<>();
    for (Document c : candidates) {
      Object k = c.get(deferred.localKey());
      if (k == null) {
        keyless.add(c);
      } else {
        byKey.computeIfAbsent(k.toString(), x -> new ArrayList<>(1)).add(c);
      }
    }
    // both readers remove every key they could order from byKey; what is left has no value
    List<Document> ordered =
        byKey.size() > SHOP_SCAN_THRESHOLD
            ? orderByShopScan(byKey, deferred, skip + size, maxTimeMs)
            : orderByInQuery(byKey, deferred, maxTimeMs);

    int needed = skip + size;
    if (ordered.size() >= needed) {
      return idsOf(ordered.subList(skip, needed));
    }
    // nulls last, in a stable id order
    List<Document> withoutValue = new ArrayList<>(keyless);
    byKey.values().forEach(withoutValue::addAll);
    withoutValue.sort(BY_ID);

    List<Document> all = new ArrayList<>(ordered.size() + withoutValue.size());
    all.addAll(ordered);
    all.addAll(withoutValue);
    int from = Math.min(skip, all.size());
    int to = Math.min(needed, all.size());
    return idsOf(all.subList(from, to));
  }

  private static List<String> idsOf(List<Document> page) {
    List<String> ids = new ArrayList<>(page.size());
    for (Document c : page) {
      ids.add(String.valueOf(c.get("_id")));
    }
    return ids;
  }

  private static final Comparator<Document> BY_ID = Comparator.comparing(c -> String.valueOf(c.get("_id")));

  /**
   * Rows of the whole shop, already ordered by the index; keep the ones that are candidates and stop
   * as soon as the page is full. The first pages of a browse therefore read a few dozen rows, not the
   * whole shop; only a page past every valued row walks the full scan (and then the nulls follow).
   */
  private List<Document> orderByShopScan(Map<String, List<Document>> byKey, SearchPlan.DeferredSort d, int needed, long maxTimeMs) {
    List<Document> ordered = new ArrayList<>(Math.min(needed, byKey.size()));
    FindIterable<Document> rows =
        collection(d)
            .find(new Document("shopId", d.shopId()).append(d.valueField(), new Document("$ne", null)))
            .projection(new Document("_id", 0).append(d.foreignKey(), 1).append(d.valueField(), 1))
            // no tiebreak here: it would force a blocking sort of the whole shop instead of walking the
            // (shopId, value) index; equal values come back in index order, which is stable for paging
            .sort(new Document(d.valueField(), d.ascending() ? 1 : -1))
            .maxTime(maxTimeMs, TimeUnit.MILLISECONDS);
    try (var cursor = rows.iterator()) {
      while (ordered.size() < needed && cursor.hasNext()) {
        Document row = cursor.next();
        Object fk = row.get(d.foreignKey());
        if (fk == null) continue;
        List<Document> cs = byKey.remove(fk.toString());
        if (cs == null) continue;
        if (cs.size() > 1) cs.sort(BY_ID);
        ordered.addAll(cs);
      }
    }
    return ordered;
  }

  /** One {@code $in} for exactly the candidate keys, then an in-memory sort by the value read. */
  private List<Document> orderByInQuery(Map<String, List<Document>> byKey, SearchPlan.DeferredSort d, long maxTimeMs) {
    List<Object> foreignKeys = new ArrayList<>(byKey.size());
    for (String k : byKey.keySet()) {
      // product._id is an ObjectId while inventory.productId is its string; ext.inventoryId is a string
      foreignKeys.add("_id".equals(d.foreignKey()) && ObjectId.isValid(k) ? new ObjectId(k) : k);
    }
    Map<String, Object> keyToValue = new HashMap<>(byKey.size());
    for (Document row :
        collection(d)
            .find(new Document(d.foreignKey(), new Document("$in", foreignKeys)))
            .projection(new Document(d.foreignKey(), 1).append(d.valueField(), 1))
            .maxTime(maxTimeMs, TimeUnit.MILLISECONDS)) {
      Object fk = row.get(d.foreignKey());
      Object value = row.get(d.valueField());
      if (fk != null && value != null) {
        keyToValue.put(fk.toString(), value);
      }
    }
    final int dir = d.ascending() ? 1 : -1;
    Comparator<Document> byValue =
        (a, b) -> dir * compareValues(keyToValue.get(String.valueOf(a.get(d.localKey()))), keyToValue.get(String.valueOf(b.get(d.localKey()))));
    List<Document> ordered = new ArrayList<>();
    for (String key : keyToValue.keySet()) {
      List<Document> cs = byKey.remove(key);
      if (cs != null) {
        ordered.addAll(cs);
      }
    }
    ordered.sort(byValue.thenComparing(BY_ID));
    return ordered;
  }

  private MongoCollection<Document> collection(SearchPlan.DeferredSort d) {
    return mongoTemplate.getCollection(d.collection());
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  static int compareValues(Object a, Object b) {
    if (a instanceof String sa && b instanceof String sb) {
      int ci = sa.compareToIgnoreCase(sb);
      return ci != 0 ? ci : sa.compareTo(sb);
    }
    if (a instanceof Number na && b instanceof Number nb) {
      return Double.compare(na.doubleValue(), nb.doubleValue());
    }
    if (a instanceof Comparable ca && a.getClass().isInstance(b)) {
      return ca.compareTo(b);
    }
    return a.toString().compareTo(b.toString());
  }
}
