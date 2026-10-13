package com.inventory.product.labels;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.inventory.product.domain.model.LabelLayoutDocument;
import com.inventory.product.domain.repository.LabelLayoutRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.bson.Document;
import org.springframework.data.mongodb.core.convert.MappingMongoConverter;
import org.springframework.data.mongodb.core.convert.MongoCustomConversions;
import org.springframework.data.mongodb.core.convert.NoOpDbRefResolver;
import org.springframework.data.mongodb.core.mapping.MongoMappingContext;

/**
 * In-memory stand-in for {@link LabelLayoutRepository} used by the {@code LabelLayoutService}
 * property tests (tasks 3.8–3.11).
 *
 * <p>Backed by a map keyed by {@code shopId}, which also models the unique index on that field.
 * Only the methods the service touches are stubbed: {@code findByShopId}, {@code save}, {@code
 * findAll} and {@code count}. {@code save} assigns an id when the document has none and stores a
 * copy made through the Mongo converter, so later mutation of the caller's instance cannot leak
 * into "the database" and anything Mongo would refuse to store fails the test.
 */
final class InMemoryLabelLayoutRepository {

  private static final MappingMongoConverter CONVERTER = converter();

  private final Map<String, LabelLayoutDocument> byShopId = new LinkedHashMap<>();
  private final AtomicInteger saveCalls = new AtomicInteger();
  private final LabelLayoutRepository mock;

  InMemoryLabelLayoutRepository() {
    mock = mock(LabelLayoutRepository.class);
    when(mock.findByShopId(anyString()))
        .thenAnswer(inv -> Optional.ofNullable(byShopId.get(inv.getArgument(0))).map(this::copy));
    when(mock.save(any(LabelLayoutDocument.class)))
        .thenAnswer(
            inv -> {
              LabelLayoutDocument doc = inv.getArgument(0);
              saveCalls.incrementAndGet();
              if (doc.getId() == null) {
                doc.setId(UUID.randomUUID().toString());
              }
              byShopId.put(doc.getShopId(), copy(doc));
              return copy(doc);
            });
    when(mock.findAll()).thenAnswer(inv -> new ArrayList<>(byShopId.values()));
    when(mock.count()).thenAnswer(inv -> (long) byShopId.size());
  }

  /** The Mockito-backed repository to hand to the service under test. */
  LabelLayoutRepository repository() {
    return mock;
  }

  /** Snapshot of every stored document (one per shop). */
  List<LabelLayoutDocument> documents() {
    return byShopId.values().stream().map(this::copy).toList();
  }

  /** The stored document for a shop, if any. */
  Optional<LabelLayoutDocument> stored(String shopId) {
    return Optional.ofNullable(byShopId.get(shopId)).map(this::copy);
  }

  /** Number of documents currently stored. */
  int size() {
    return byShopId.size();
  }

  /** Number of {@code save} invocations seen so far. */
  int saveCalls() {
    return saveCalls.get();
  }

  /**
   * A copy made by writing the document to BSON and reading it back with Spring Data's converter,
   * as a real save would: a value Mongo cannot store (such as a map key with a dot) fails here.
   */
  private LabelLayoutDocument copy(LabelLayoutDocument d) {
    Document bson = new Document();
    CONVERTER.write(d, bson);
    return CONVERTER.read(LabelLayoutDocument.class, bson);
  }

  private static MappingMongoConverter converter() {
    MongoCustomConversions conversions = new MongoCustomConversions(List.of());
    MongoMappingContext context = new MongoMappingContext();
    context.setSimpleTypeHolder(conversions.getSimpleTypeHolder());
    context.afterPropertiesSet();
    MappingMongoConverter converter =
        new MappingMongoConverter(NoOpDbRefResolver.INSTANCE, context);
    converter.setCustomConversions(conversions);
    converter.afterPropertiesSet();
    return converter;
  }
}
