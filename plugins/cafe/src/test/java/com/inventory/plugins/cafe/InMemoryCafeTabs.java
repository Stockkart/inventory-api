package com.inventory.plugins.cafe;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.inventory.plugins.cafe.domain.CafeTab;
import com.inventory.plugins.cafe.domain.CafeTabRepository;
import com.inventory.plugins.cafe.domain.CafeTabStatus;
import com.mongodb.client.result.UpdateResult;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.bson.types.Decimal128;
import org.springframework.core.convert.converter.Converter;
import org.springframework.data.convert.ReadingConverter;
import org.springframework.data.convert.WritingConverter;
import org.springframework.data.mongodb.core.convert.MappingMongoConverter;
import org.springframework.data.mongodb.core.convert.MongoCustomConversions;
import org.springframework.data.mongodb.core.convert.NoOpDbRefResolver;
import org.springframework.data.mongodb.core.mapping.MongoMappingContext;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.UpdateDefinition;

/**
 * A {@code cafe_tabs} collection small enough to run in a unit test and faithful enough to tell a
 * full-document replace apart from a targeted write — the {@code InMemoryPurchases} of the cafe
 * module, and for the same reason: a {@code verify()} on a mocked template would prove a method
 * was called, not that the flush record another writer put on the tab is still there afterwards,
 * which is the entire question.
 *
 * <p>Each tab is held as the BSON {@link Document} the server would hold. {@code save} replaces it
 * wholesale, exactly as {@code MongoRepository.save} does; {@code updateFirst} applies
 * {@code $set}, {@code $unset}, {@code $push} and {@code $pull}, including
 * {@code lines.$[l].field} against an array filter.
 */
class InMemoryCafeTabs {

  /**
   * The converter, with the BigDecimal pair {@code app}'s {@code MongoConfig} registers — copied
   * because this module cannot depend on that one, and without them a frozen menu price
   * round-trips through a different BSON type here than in production.
   */
  private static final MappingMongoConverter CONVERTER = buildConverter();

  private static MappingMongoConverter buildConverter() {
    MongoCustomConversions conversions =
        new MongoCustomConversions(
            List.of(new BigDecimalToDecimal128(), new Decimal128ToBigDecimal()));
    MongoMappingContext context = new MongoMappingContext();
    context.setSimpleTypeHolder(conversions.getSimpleTypeHolder());
    context.afterPropertiesSet();
    MappingMongoConverter built = new MappingMongoConverter(NoOpDbRefResolver.INSTANCE, context);
    built.setCustomConversions(conversions);
    built.afterPropertiesSet();
    return built;
  }

  private final Map<String, Document> documents = new LinkedHashMap<>();
  private final MappingMongoConverter converter;
  private final CafeTabRepository repository;
  private final MongoTemplate mongoTemplate;

  /** Runs once, immediately before the next write lands: the concurrent claim's interleaving. */
  private Runnable betweenReadAndWrite;

  private boolean fullReplaceUsed;

  InMemoryCafeTabs() {
    converter = CONVERTER;

    repository = mock(CafeTabRepository.class);
    when(repository.findByIdAndShopIdAndUserId(anyString(), anyString(), anyString()))
        .thenAnswer(
            invocation -> {
              Document stored = documents.get(invocation.<String>getArgument(0));
              if (stored == null
                  || !invocation.getArgument(1).equals(stored.getString("shopId"))
                  || !invocation.getArgument(2).equals(stored.getString("userId"))) {
                return Optional.empty();
              }
              return Optional.of(read(stored));
            });
    when(repository.findByShopIdAndUserIdAndStatusOrderByCreatedAtDesc(
            anyString(), anyString(), any(CafeTabStatus.class)))
        .thenAnswer(
            invocation ->
                documents.values().stream()
                    .filter(stored -> invocation.getArgument(0).equals(stored.getString("shopId")))
                    .filter(stored -> invocation.getArgument(1).equals(stored.getString("userId")))
                    .filter(
                        stored ->
                            invocation
                                .<CafeTabStatus>getArgument(2)
                                .name()
                                .equals(stored.getString("status")))
                    .map(this::read)
                    .sorted(Comparator.comparing(CafeTab::getCreatedAt).reversed())
                    .toList());
    when(repository.countByShopIdAndUserIdAndStatus(anyString(), anyString(), any()))
        .thenAnswer(
            invocation ->
                (long)
                    repository
                        .findByShopIdAndUserIdAndStatusOrderByCreatedAtDesc(
                            invocation.getArgument(0),
                            invocation.getArgument(1),
                            invocation.getArgument(2))
                        .size());
    when(repository.save(any(CafeTab.class)))
        .thenAnswer(
            invocation -> {
              fireHook();
              CafeTab saved = invocation.getArgument(0);
              if (saved.getId() == null) {
                // An insert: the server mints the id, as it does for a newly opened tab.
                saved.setId("tab-" + (documents.size() + 1));
              } else {
                fullReplaceUsed = true;
              }
              documents.put(saved.getId(), write(saved));
              return saved;
            });

    mongoTemplate = mock(MongoTemplate.class);
    when(mongoTemplate.getConverter()).thenReturn(converter);
    when(mongoTemplate.updateFirst(
            any(Query.class), any(UpdateDefinition.class), eq(CafeTab.class), anyString()))
        .thenAnswer(
            invocation -> applyUpdate(invocation.getArgument(0), invocation.getArgument(1)));
  }

  // ------------------------------------------------------------------- handles

  CafeTabRepository repository() {
    return repository;
  }

  MongoTemplate mongoTemplate() {
    return mongoTemplate;
  }

  void interleave(Runnable concurrentWriter) {
    this.betweenReadAndWrite = concurrentWriter;
  }

  boolean fullReplaceUsed() {
    return fullReplaceUsed;
  }

  /** Puts a tab in as the server holds it. */
  CafeTab seed(CafeTab tab) {
    documents.put(tab.getId(), write(tab));
    return read(documents.get(tab.getId()));
  }

  /** What the server holds now, as BSON. */
  Document stored(String id) {
    return documents.get(id);
  }

  CafeTab read(String id) {
    Document stored = documents.get(id);
    return stored == null ? null : read(stored);
  }

  /** The concurrent writer, reaching the stored document directly as the claim does. */
  void mutateStored(String id, java.util.function.Consumer<Document> change) {
    change.accept(documents.get(id));
  }

  Document write(CafeTab tab) {
    Document sink = new Document();
    converter.write(tab, sink);
    return sink;
  }

  private CafeTab read(Document stored) {
    return converter.read(CafeTab.class, deepCopy(stored));
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
    if (target == null || !matchesQuery(target, criteria)) {
      return UpdateResult.acknowledged(0, 0L, null);
    }
    Document operations = update.getUpdateObject();

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

  /** Every clause but {@code _id}, as equality: a guard the store ignored would prove nothing. */
  private static boolean matchesQuery(Document target, Document criteria) {
    for (Map.Entry<String, Object> clause : criteria.entrySet()) {
      if ("_id".equals(clause.getKey())) {
        continue;
      }
      if (!Objects.equals(toMongo(clause.getValue()), target.get(clause.getKey()))) {
        return false;
      }
    }
    return true;
  }

  private static Document section(Document operations, String operator) {
    Object body = operations.get(operator);
    return body instanceof Document document ? document : new Document();
  }

  private void write(
      Document target, String path, Map<String, Document> filters, Object value, boolean unset) {
    int marker = path.indexOf(".$[");
    if (marker < 0) {
      if (unset) {
        target.remove(path);
      } else {
        target.put(path, toMongo(value));
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
        line.put(remainder, toMongo(value));
      }
    }
  }

  private static boolean matchesFilter(Document line, String identifier, Document filter) {
    if (filter == null) {
      return true;
    }
    for (Map.Entry<String, Object> clause : filter.entrySet()) {
      String field = clause.getKey().substring(identifier.length() + 1);
      if (!Objects.equals(line.get(field), toMongo(clause.getValue()))) {
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
    array.add(toMongo(value));
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
              .allMatch(clause -> Objects.equals(line.get(clause.getKey()), toMongo(clause.getValue())));
        });
  }

  /** What the real update mapper would put on the wire for a value handed to {@code Update}. */
  private static Object toMongo(Object value) {
    if (value instanceof Enum<?> enumValue) {
      return enumValue.name();
    }
    if (value instanceof Instant instant) {
      return Date.from(instant);
    }
    if (value instanceof Document || value == null) {
      return value;
    }
    if (value instanceof String || value instanceof Number || value instanceof Boolean) {
      return value;
    }
    if (value instanceof BigDecimal money) {
      return Decimal128.parse(money.toString());
    }
    Document sink = new Document();
    CONVERTER.write(value, sink);
    sink.remove("_class");
    return sink;
  }

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
