package com.inventory.plugins.search.support;

import com.inventory.pluginengine.InventorySearchContract;
import com.inventory.pluginengine.schema.VerticalSchemaField;
import com.inventory.pluginengine.schema.VerticalSearchSortField;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.AddFieldsOperation;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.AggregationOperation;
import org.springframework.data.mongodb.core.aggregation.AggregationResults;
import org.springframework.data.mongodb.core.aggregation.ConditionalOperators;
import org.springframework.data.mongodb.core.aggregation.LimitOperation;
import org.springframework.data.mongodb.core.aggregation.SkipOperation;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.util.StringUtils;
import org.bson.Document;

/** Schema-driven extension collection search (sort + cursor from vertical schema). */
public final class SchemaDrivenExtensionSearch {

  private SchemaDrivenExtensionSearch() {}

  public record SearchPage(List<String> inventoryIds, String nextCursor) {}

  public static SearchPage searchPage(
      MongoTemplate mongoTemplate,
      Class<?> documentClass,
      Criteria matchCriteria,
      SchemaSearchConfigResolver.ResolvedSearch resolved,
      String cursor,
      int skip,
      int limit) {
    if (InventorySearchContract.CURSOR_SKIP.equals(resolved.cursorMode())) {
      return searchWithSkip(mongoTemplate, documentClass, matchCriteria, resolved, cursor, skip, limit);
    }
    return searchWithCompoundKey(
        mongoTemplate, documentClass, matchCriteria, resolved, cursor, skip, limit);
  }

  private static SearchPage searchWithSkip(
      MongoTemplate mongoTemplate,
      Class<?> documentClass,
      Criteria matchCriteria,
      SchemaSearchConfigResolver.ResolvedSearch resolved,
      String cursor,
      int skip,
      int limit) {
    int effectiveLimit = effectiveLimit(limit);
    int effectiveSkip = Math.max(skip, 0);
    if (StringUtils.hasText(cursor) && cursor.startsWith("skip:")) {
      try {
        effectiveSkip = Integer.parseInt(cursor.substring("skip:".length()));
      } catch (NumberFormatException ignored) {
        // keep request skip
      }
    } else if (querySkipOnly(cursor)) {
      effectiveSkip = Math.max(skip, 0);
    }

    Query mongoQuery = new Query(matchCriteria);
    mongoQuery.with(buildSort(resolved, false));
    if (effectiveSkip > 0) {
      mongoQuery.skip(effectiveSkip);
    }
    mongoQuery.limit(effectiveLimit + 1);

    List<Document> docs =
        mongoTemplate.find(mongoQuery, Document.class, collectionName(mongoTemplate, documentClass));
    return pageFromDocuments(docs, effectiveLimit, resolved, null, "skip:" + (effectiveSkip + effectiveLimit));
  }

  private static SearchPage searchWithCompoundKey(
      MongoTemplate mongoTemplate,
      Class<?> documentClass,
      Criteria matchCriteria,
      SchemaSearchConfigResolver.ResolvedSearch resolved,
      String cursor,
      int skip,
      int limit) {
    int effectiveLimit = effectiveLimit(limit);
    int effectiveSkip = Math.max(skip, 0);

    List<AggregationOperation> ops = new ArrayList<>();
    ops.add(Aggregation.match(matchCriteria));

    if (StringUtils.hasText(cursor) && !cursor.startsWith("skip:")) {
      Criteria cursorCriteria = cursorAfter(resolved, cursor);
      if (!cursorCriteria.getCriteriaObject().isEmpty()) {
        ops.add(Aggregation.match(cursorCriteria));
      }
      effectiveSkip = 0;
    }

    for (VerticalSearchSortField spec : resolved.sortFields()) {
      if (usesNullsLastSentinel(spec, resolved.fieldTypesByKey())) {
        String mongoField = sortMongoField(spec);
        ops.add(
            AddFieldsOperation.builder()
                .addFieldWithValue(
                    mongoField,
                    ConditionalOperators.ifNull(spec.getField())
                        .then(CompoundKeySearchCursorCodec.NULL_DATE_SENTINEL))
                .build());
      }
    }

    ops.add(Aggregation.sort(buildSort(resolved, true)));
    if (effectiveSkip > 0) {
      ops.add(new SkipOperation(effectiveSkip));
    }
    ops.add(new LimitOperation(effectiveLimit + 1L));

    AggregationResults<Document> results =
        mongoTemplate.aggregate(
            Aggregation.newAggregation(ops),
            collectionName(mongoTemplate, documentClass),
            Document.class);

    List<Document> docs = results.getMappedResults();
    List<String> cursorTokens = lastRowCursorTokens(docs, effectiveLimit, resolved);
    String nextCursor =
        docs.size() > effectiveLimit && !cursorTokens.isEmpty()
            ? CompoundKeySearchCursorCodec.encode(cursorTokens)
            : null;
    return pageFromDocuments(docs, effectiveLimit, resolved, nextCursor, null);
  }

  private static SearchPage pageFromDocuments(
      List<Document> docs,
      int effectiveLimit,
      SchemaSearchConfigResolver.ResolvedSearch resolved,
      String compoundNextCursor,
      String skipNextCursor) {
    List<String> ids = new ArrayList<>();
    for (Document doc : docs) {
      if (ids.size() >= effectiveLimit) {
        break;
      }
      Object inventoryId = doc.get(InventorySearchContract.INVENTORY_ID_FIELD);
      if (inventoryId != null && StringUtils.hasText(String.valueOf(inventoryId))) {
        ids.add(String.valueOf(inventoryId));
      }
    }
    String nextCursor = compoundNextCursor;
    if (nextCursor == null && docs.size() > effectiveLimit && !ids.isEmpty()) {
      nextCursor = skipNextCursor;
    }
    return new SearchPage(ids, nextCursor);
  }

  private static List<String> lastRowCursorTokens(
      List<Document> docs,
      int effectiveLimit,
      SchemaSearchConfigResolver.ResolvedSearch resolved) {
    if (docs.size() <= effectiveLimit) {
      return List.of();
    }
    Document last = docs.get(effectiveLimit - 1);
    List<String> tokens = new ArrayList<>();
    for (VerticalSearchSortField spec : resolved.sortFields()) {
      String field = spec.getField();
      Object raw = last.get(field);
      String type = fieldType(field, resolved.fieldTypesByKey());
      tokens.add(CompoundKeySearchCursorCodec.tokenForValue(raw, type));
    }
    return tokens;
  }

  private static Criteria cursorAfter(
      SchemaSearchConfigResolver.ResolvedSearch resolved, String cursor) {
    List<String> tokens = CompoundKeySearchCursorCodec.decode(cursor);
    if (tokens.isEmpty() || tokens.size() != resolved.sortFields().size()) {
      return new Criteria();
    }
    return cursorAfterFrom(resolved.sortFields(), resolved.fieldTypesByKey(), tokens, 0);
  }

  private static Criteria cursorAfterFrom(
      List<VerticalSearchSortField> sortFields,
      Map<String, VerticalSchemaField> fieldTypes,
      List<String> tokens,
      int index) {
    VerticalSearchSortField spec = sortFields.get(index);
    String field = spec.getField();
    String token = tokens.get(index);
    String type = fieldType(field, fieldTypes);
    boolean nullsLast = usesNullsLastSentinel(spec, fieldTypes);

    if (index == sortFields.size() - 1) {
      return lastFieldAfter(field, type, token, nullsLast);
    }

    if (nullsLast
        && "date".equalsIgnoreCase(type)
        && CompoundKeySearchCursorCodec.NULL_TOKEN.equals(token)) {
      Criteria equal = equalsField(field, type, token, nullsLast);
      Criteria afterRest = cursorAfterFrom(sortFields, fieldTypes, tokens, index + 1);
      if (!afterRest.getCriteriaObject().isEmpty()) {
        return new Criteria().andOperator(equal, afterRest);
      }
      return equal;
    }

    List<Criteria> branches = new ArrayList<>();
    branches.add(greaterThan(field, type, token, nullsLast));

    Criteria equal = equalsField(field, type, token, nullsLast);
    Criteria afterRest = cursorAfterFrom(sortFields, fieldTypes, tokens, index + 1);
    if (!equal.getCriteriaObject().isEmpty() && !afterRest.getCriteriaObject().isEmpty()) {
      branches.add(new Criteria().andOperator(equal, afterRest));
    }

    if (nullsLast && "date".equalsIgnoreCase(type) && !CompoundKeySearchCursorCodec.NULL_TOKEN.equals(token)) {
      branches.add(
          new Criteria()
              .orOperator(
                  Criteria.where(field).exists(false), Criteria.where(field).is(null)));
    }

    return new Criteria().orOperator(branches.toArray(Criteria[]::new));
  }

  private static Criteria lastFieldAfter(
      String field, String type, String token, boolean nullsLast) {
    if (InventorySearchContract.INVENTORY_ID_FIELD.equals(field)
        || "string".equalsIgnoreCase(type)
        || "enum".equalsIgnoreCase(type)) {
      return Criteria.where(field).gt(token);
    }
    if ("date".equalsIgnoreCase(type) && nullsLast && CompoundKeySearchCursorCodec.NULL_TOKEN.equals(token)) {
      return new Criteria()
          .andOperator(
              new Criteria()
                  .orOperator(
                      Criteria.where(field).exists(false), Criteria.where(field).is(null)),
              Criteria.where(InventorySearchContract.INVENTORY_ID_FIELD).gt(token));
    }
    if ("date".equalsIgnoreCase(type)) {
      Instant expiry = Instant.ofEpochMilli(Long.parseLong(token));
      return Criteria.where(field).gt(expiry);
    }
    return Criteria.where(field).gt(token);
  }

  private static Criteria greaterThan(
      String field, String type, String token, boolean nullsLast) {
    if ("date".equalsIgnoreCase(type) && nullsLast && CompoundKeySearchCursorCodec.NULL_TOKEN.equals(token)) {
      return new Criteria().orOperator(Criteria.where(field).exists(false), Criteria.where(field).is(null));
    }
    if ("date".equalsIgnoreCase(type)) {
      return Criteria.where(field).gt(Instant.ofEpochMilli(Long.parseLong(token)));
    }
    return Criteria.where(field).gt(token);
  }

  private static Criteria equalsField(
      String field, String type, String token, boolean nullsLast) {
    if ("date".equalsIgnoreCase(type) && nullsLast && CompoundKeySearchCursorCodec.NULL_TOKEN.equals(token)) {
      return new Criteria()
          .orOperator(Criteria.where(field).exists(false), Criteria.where(field).is(null));
    }
    if ("date".equalsIgnoreCase(type)) {
      return Criteria.where(field).is(Instant.ofEpochMilli(Long.parseLong(token)));
    }
    return Criteria.where(field).is(token);
  }

  private static Sort buildSort(
      SchemaSearchConfigResolver.ResolvedSearch resolved, boolean useSentinelFields) {
    List<Sort.Order> orders = new ArrayList<>();
    for (VerticalSearchSortField spec : resolved.sortFields()) {
      Sort.Direction direction =
          "desc".equalsIgnoreCase(spec.getDirection()) ? Sort.Direction.DESC : Sort.Direction.ASC;
      String mongoField =
          useSentinelFields && usesNullsLastSentinel(spec, resolved.fieldTypesByKey())
              ? sortMongoField(spec)
              : spec.getField();
      orders.add(new Sort.Order(direction, mongoField));
    }
    return Sort.by(orders);
  }

  private static boolean usesNullsLastSentinel(
      VerticalSearchSortField spec, Map<String, VerticalSchemaField> fieldTypes) {
    if (!"last".equalsIgnoreCase(spec.getNulls())) {
      return false;
    }
    return "date".equalsIgnoreCase(fieldType(spec.getField(), fieldTypes));
  }

  private static String sortMongoField(VerticalSearchSortField spec) {
    return "__sort_" + spec.getField();
  }

  private static String fieldType(String field, Map<String, VerticalSchemaField> fieldTypes) {
    if (InventorySearchContract.INVENTORY_ID_FIELD.equals(field)) {
      return "string";
    }
    VerticalSchemaField schemaField = fieldTypes.get(field);
    return schemaField != null ? schemaField.getType() : "string";
  }

  private static int effectiveLimit(int limit) {
    return limit > 0 ? Math.min(limit, 200) : 50;
  }

  private static boolean querySkipOnly(String cursor) {
    return StringUtils.hasText(cursor);
  }

  private static String collectionName(MongoTemplate mongoTemplate, Class<?> documentClass) {
    return mongoTemplate.getCollectionName(documentClass);
  }
}
