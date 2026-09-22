package com.inventory.product.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.inventory.product.domain.model.Purchase;
import com.inventory.product.domain.repository.PurchaseRepository;
import com.mongodb.client.result.UpdateResult;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.bson.Document;
import org.bson.types.Decimal128;
import org.springframework.core.convert.converter.Converter;
import org.springframework.data.convert.ReadingConverter;
import org.springframework.data.convert.WritingConverter;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.convert.MappingMongoConverter;
import org.springframework.data.mongodb.core.convert.MongoCustomConversions;
import org.springframework.data.mongodb.core.convert.NoOpDbRefResolver;
import org.springframework.data.mongodb.core.mapping.MongoMappingContext;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.UpdateDefinition;

/**
 * A {@code purchases} collection small enough to run in a unit test and faithful enough to tell a
 * full-document replace apart from a targeted write.
 *
 * <p>There is no embedded Mongo in this build, and a {@code verify()} on a mocked template would
 * prove only that a method was called — not that a line another writer appended is still on the
 * bill afterwards, which is the entire question. So the store holds each bill as the BSON
 * {@link Document} the server would hold, reads are mapped out of it and writes are applied to it:
 * {@code save} replaces the document wholesale, exactly as {@code MongoRepository.save} does, and
 * {@code updateFirst} applies {@code $set}, {@code $unset}, {@code $push} and {@code $pull},
 * including {@code items.$[f0].field} against array filters.
 *
 * <p>The BigDecimal converters are the ones {@code app}'s {@code MongoConfig} registers, copied
 * because this module cannot depend on that one. Without them a money field round-trips through a
 * different BSON type here than in production and the byte-identity assertions would be testing
 * the wrong document.
 */
class InMemoryPurchases {

  private final Map<String, Document> documents = new LinkedHashMap<>();
  private final MappingMongoConverter converter;
  private final PurchaseRepository repository;
  private final MongoTemplate mongoTemplate;

  /** Runs once, immediately before the next write lands: the concurrent writer's interleaving. */
  private Runnable betweenReadAndWrite;

  /** Every update statement the code under test issued, in order. */
  private final List<Document> statements = new ArrayList<>();

  private boolean fullReplaceUsed;

  InMemoryPurchases() {
    MongoCustomConversions conversions =
        new MongoCustomConversions(
            List.of(new BigDecimalToDecimal128(), new Decimal128ToBigDecimal()));
    MongoMappingContext context = new MongoMappingContext();
    context.setSimpleTypeHolder(conversions.getSimpleTypeHolder());
    context.afterPropertiesSet();
    converter = new MappingMongoConverter(NoOpDbRefResolver.INSTANCE, context);
    converter.setCustomConversions(conversions);
    converter.afterPropertiesSet();

    repository = mock(PurchaseRepository.class);
    when(repository.findById(anyString()))
        .thenAnswer(
            invocation -> {
              Document stored = documents.get(invocation.<String>getArgument(0));
              return Optional.ofNullable(stored).map(this::readDocument);
            });
    when(repository.save(any(Purchase.class)))
        .thenAnswer(
            invocation -> {
              fireHook();
              Purchase saved = invocation.getArgument(0);
              fullReplaceUsed = true;
              documents.put(saved.getId(), write(saved));
              return saved;
            });

    mongoTemplate = mock(MongoTemplate.class);
    when(mongoTemplate.getConverter()).thenReturn(converter);
    when(mongoTemplate.updateFirst(any(Query.class), any(UpdateDefinition.class), eq(Purchase.class)))
        .thenAnswer(
            invocation -> applyUpdate(invocation.getArgument(0), invocation.getArgument(1)));
  }

  // ------------------------------------------------------------------- handles

  PurchaseRepository repository() {
    return repository;
  }

  MongoTemplate mongoTemplate() {
    return mongoTemplate;
  }

  MappingMongoConverter converter() {
    return converter;
  }

  void interleave(Runnable concurrentWriter) {
    this.betweenReadAndWrite = concurrentWriter;
  }

  boolean fullReplaceUsed() {
    return fullReplaceUsed;
  }

  List<Document> statements() {
    return statements;
  }

  /** Puts a bill in as the server holds it, and returns the snapshot a request would read. */
  Purchase seed(Purchase bill) {
    documents.put(bill.getId(), write(bill));
    return readDocument(documents.get(bill.getId()));
  }

  /** What the server holds now. */
  Document stored(String id) {
    return documents.get(id);
  }

  Purchase read(String id) {
    Document stored = documents.get(id);
    return stored == null ? null : readDocument(stored);
  }

  /** What a full-document replace of this entity would have stored. */
  Document write(Purchase purchase) {
    Document sink = new Document();
    converter.write(purchase, sink);
    return sink;
  }

  /** The concurrent writer, reaching the stored document directly as another request would. */
  void mutateStored(String id, java.util.function.Consumer<Document> change) {
    change.accept(documents.get(id));
  }

  private Purchase readDocument(Document stored) {
    // A copy, as a read from the server is: mutating it cannot reach the store.
    return converter.read(Purchase.class, deepCopy(stored));
  }

  @SuppressWarnings("unchecked")
  private static Document deepCopy(Document source) {
    Document copy = new Document();
    source.forEach(
        (key, value) -> {
          if (value instanceof Document nested) {
            copy.put(key, deepCopy(nested));
          } else if (value instanceof List<?> list) {
            List<Object> copied = new ArrayList<>();
            for (Object element : list) {
              copied.add(element instanceof Document nested ? deepCopy(nested) : element);
            }
            copy.put(key, copied);
          } else {
            copy.put(key, value);
          }
        });
    return copy;
  }

  private void fireHook() {
    if (betweenReadAndWrite == null) {
      return;
    }
    Runnable hook = betweenReadAndWrite;
    betweenReadAndWrite = null;
    hook.run();
  }

  // -------------------------------------------------------------- the "server"

  private UpdateResult applyUpdate(Query query, UpdateDefinition update) {
    fireHook();
    Document criteria = query.getQueryObject();
    Document target = documents.get(criteria.getString("_id"));
    if (target == null || !Objects.equals(criteria.get("shopId"), target.get("shopId"))) {
      return UpdateResult.acknowledged(0, 0L, null);
    }
    Document operations = update.getUpdateObject();
    statements.add(operations);

    Map<String, Document> filters = new LinkedHashMap<>();
    update
        .getArrayFilters()
        .forEach(
            filter -> {
              Document asDocument = filter.asDocument();
              String identifier = asDocument.keySet().iterator().next().split("\\.")[0];
              filters.put(identifier, asDocument);
            });

    section(operations, "$set").forEach((path, value) -> write(target, path, filters, value, false));
    section(operations, "$unset").forEach((path, value) -> write(target, path, filters, null, true));
    section(operations, "$push").forEach((path, value) -> push(target, path, value));
    section(operations, "$pull").forEach((path, value) -> pull(target, path, value));
    return UpdateResult.acknowledged(1, 1L, null);
  }

  private static Document section(Document operations, String operator) {
    Object body = operations.get(operator);
    return body instanceof Document document ? document : new Document();
  }

  /** {@code $set}/{@code $unset} of a path, resolving any {@code $[identifier]} against filters. */
  private void write(
      Document target, String path, Map<String, Document> filters, Object value, boolean unset) {
    int marker = path.indexOf(".$[");
    if (marker < 0) {
      if (unset) {
        target.remove(path);
      } else {
        target.put(path, converter.convertToMongoType(value));
      }
      return;
    }
    String arrayField = path.substring(0, marker);
    int close = path.indexOf(']', marker);
    String identifier = path.substring(marker + 3, close);
    String remainder = path.substring(close + 2);
    List<?> elements = target.getList(arrayField, Document.class);
    if (elements == null) {
      return;
    }
    Document filter = filters.get(identifier);
    for (Object element : elements) {
      Document line = (Document) element;
      if (!matchesFilter(line, identifier, filter)) {
        continue;
      }
      if (unset) {
        line.remove(remainder);
      } else {
        line.put(remainder, converter.convertToMongoType(value));
      }
    }
  }

  private static boolean matchesFilter(Document line, String identifier, Document filter) {
    if (filter == null) {
      return true;
    }
    for (Map.Entry<String, Object> clause : filter.entrySet()) {
      String field = clause.getKey().substring(identifier.length() + 1);
      if (!Objects.equals(line.get(field), clause.getValue())) {
        return false;
      }
    }
    return true;
  }

  @SuppressWarnings("unchecked")
  private void push(Document target, String field, Object value) {
    List<Object> array = (List<Object>) target.get(field);
    if (array == null) {
      array = new ArrayList<>();
      target.put(field, array);
    }
    Object each = eachOf(value);
    if (each != null) {
      for (Object element : each instanceof Object[] elements ? List.of(elements) : (List<?>) each) {
        array.add(converter.convertToMongoType(element));
      }
      return;
    }
    array.add(converter.convertToMongoType(value));
  }

  /**
   * {@code Update.push(field).each(...)} leaves a {@code Modifiers} holder in the update object
   * that the real {@code UpdateMapper} unwraps into {@code {$each: [...]}}; unwrap it the same way.
   */
  private static Object eachOf(Object value) {
    if (value instanceof Document document && document.containsKey("$each")) {
      return document.get("$each");
    }
    if (value instanceof org.springframework.data.mongodb.core.query.Update.Modifiers modifiers) {
      for (org.springframework.data.mongodb.core.query.Update.Modifier modifier :
          modifiers.getModifiers()) {
        if ("$each".equals(modifier.getKey())) {
          return modifier.getValue();
        }
      }
    }
    return null;
  }

  private static void pull(Document target, String field, Object condition) {
    List<?> array = target.getList(field, Document.class);
    if (array == null || !(condition instanceof Document clauses)) {
      return;
    }
    array.removeIf(
        element -> {
          Document line = (Document) element;
          return clauses.entrySet().stream()
              .allMatch(clause -> Objects.equals(line.get(clause.getKey()), clause.getValue()));
        });
  }

  // ------------------------------------------------- app's MongoConfig, copied

  @WritingConverter
  static class BigDecimalToDecimal128 implements Converter<BigDecimal, Decimal128> {
    @Override
    public Decimal128 convert(BigDecimal source) {
      return source == null ? null : Decimal128.parse(source.toString());
    }
  }

  @ReadingConverter
  static class Decimal128ToBigDecimal implements Converter<Decimal128, BigDecimal> {
    @Override
    public BigDecimal convert(Decimal128 source) {
      return source == null ? null : source.bigDecimalValue();
    }
  }
}
