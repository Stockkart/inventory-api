package com.inventory.product.service.vertical;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.inventory.pluginengine.schema.VerticalEntitySchema;
import com.inventory.pluginengine.schema.VerticalSchema;
import com.inventory.pluginengine.schema.VerticalSchemaStatus;
import com.inventory.product.domain.model.VerticalSchemaDocument;
import com.inventory.product.domain.repository.VerticalSchemaRepository;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SchemaLoaderTest {

  @Mock private VerticalSchemaRepository schemaRepository;

  private SchemaLoader schemaLoader;

  @BeforeEach
  void setUp() {
    schemaLoader = new SchemaLoader(schemaRepository);
  }

  @Test
  void loadUsesCacheAfterWarmWithoutFurtherDbReads() {
    VerticalSchema schema = medicalSchema();
    VerticalSchemaDocument doc = document("medical", "1.0.0", schema);

    when(schemaRepository.findByStatus(VerticalSchemaStatus.ACTIVE.name()))
        .thenReturn(List.of(doc));

    schemaLoader.warmCache();

    VerticalSchema first = schemaLoader.load("medical", "1.0.0");
    VerticalSchema second = schemaLoader.load("medical", "1.0.0");

    assertSame(first, second);
    verify(schemaRepository, times(0)).findByVerticalIdAndVersion("medical", "1.0.0");
  }

  @Test
  void loadResolvesActiveVersionFromMemoryAfterWarm() {
    VerticalSchema schema = medicalSchema();
    VerticalSchemaDocument doc = document("medical", "1.0.0", schema);

    when(schemaRepository.findByStatus(VerticalSchemaStatus.ACTIVE.name()))
        .thenReturn(List.of(doc));

    schemaLoader.warmCache();

    VerticalSchema loaded = schemaLoader.load("medical", null);

    assertEquals("medical", loaded.getVerticalId());
    verify(schemaRepository, times(0)).findByVerticalIdAndStatus("medical", VerticalSchemaStatus.ACTIVE.name());
  }

  @Test
  void evictCacheForcesReloadOnNextLoad() {
    VerticalSchema schema = medicalSchema();
    VerticalSchemaDocument doc = document("medical", "1.0.0", schema);

    when(schemaRepository.findByStatus(VerticalSchemaStatus.ACTIVE.name()))
        .thenReturn(List.of(doc));
    when(schemaRepository.findByVerticalIdAndVersion("medical", "1.0.0"))
        .thenReturn(Optional.of(doc));

    schemaLoader.warmCache();
    schemaLoader.evictCache();

    schemaLoader.load("medical", "1.0.0");

    verify(schemaRepository, times(1)).findByVerticalIdAndVersion("medical", "1.0.0");
  }

  private static VerticalSchema medicalSchema() {
    VerticalEntitySchema inventory = new VerticalEntitySchema();
    inventory.setFields(List.of());

    VerticalSchema schema = new VerticalSchema();
    schema.setVerticalId("medical");
    schema.setVersion("1.0.0");
    schema.setEntities(Map.of("inventory", inventory));
    return schema;
  }

  private static VerticalSchemaDocument document(
      String verticalId, String version, VerticalSchema schema) {
    VerticalSchemaDocument doc = new VerticalSchemaDocument();
    doc.setId(verticalId + "_" + version);
    doc.setVerticalId(verticalId);
    doc.setVersion(version);
    doc.setStatus(VerticalSchemaStatus.ACTIVE.name());
    doc.setSchema(schema);
    return doc;
  }
}
