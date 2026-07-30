package com.inventory.product.migration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.inventory.pluginengine.schema.VerticalSchema;
import com.inventory.pluginengine.schema.VerticalSchemaStatus;
import com.inventory.product.domain.model.VerticalSchemaDocument;
import com.inventory.product.domain.repository.VerticalSchemaRepository;
import com.inventory.product.service.vertical.SchemaLoader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers the reconcile path.
 *
 * <p>The seeder used to skip any existing vertical + version row. Because seed files are edited in
 * place without a version bump, a field added to a seed never reached an environment whose row
 * already existed — which is how {@code itemType} shipped to fresh databases but not to long-lived
 * ones.
 */
class VerticalSchemaSeederTest {

  private VerticalSchemaRepository schemaRepository;
  private SchemaLoader schemaLoader;
  private VerticalSchemaSeeder seeder;
  private final ObjectMapper objectMapper = new ObjectMapper();

  private static final String FOUR_FIELDS =
      "{\"verticalId\":\"medical\",\"version\":\"1.0.0\",\"entities\":{\"inventory\":{\"fields\":["
          + "{\"key\":\"name\"},{\"key\":\"batchNo\"},{\"key\":\"expiryDate\"},{\"key\":\"companyName\"}]}}}";

  private static final String SIX_FIELDS =
      "{\"verticalId\":\"medical\",\"version\":\"1.0.0\",\"entities\":{\"inventory\":{\"fields\":["
          + "{\"key\":\"name\"},{\"key\":\"batchNo\"},{\"key\":\"expiryDate\"},{\"key\":\"companyName\"},"
          + "{\"key\":\"itemType\"},{\"key\":\"itemTypeDegree\"}]}}}";

  @BeforeEach
  void setUp() {
    schemaRepository = mock(VerticalSchemaRepository.class);
    schemaLoader = mock(SchemaLoader.class);
    seeder = new VerticalSchemaSeeder();
    ReflectionTestUtils.setField(seeder, "schemaRepository", schemaRepository);
    ReflectionTestUtils.setField(seeder, "schemaLoader", schemaLoader);
    ReflectionTestUtils.setField(seeder, "objectMapper", objectMapper);
  }

  private Resource seedFile(String json) {
    return new ByteArrayResource(json.getBytes()) {
      @Override
      public String getFilename() {
        return "medical-v1.json";
      }
    };
  }

  private VerticalSchemaDocument storedWith(String json) throws Exception {
    VerticalSchemaDocument doc = new VerticalSchemaDocument();
    doc.setId("medical_1.0.0");
    doc.setVerticalId("medical");
    doc.setVersion("1.0.0");
    doc.setStatus(VerticalSchemaStatus.ACTIVE.name());
    doc.setSchema(objectMapper.readValue(json, VerticalSchema.class));
    doc.setPublishedAt(Instant.parse("2025-01-01T00:00:00Z"));
    doc.setCreatedBy("seed:medical-v1.json");
    return doc;
  }

  private boolean seed(Resource resource) {
    return (boolean) ReflectionTestUtils.invokeMethod(seeder, "seedResource", resource);
  }

  @Test
  void insertsWhenNoRowExists() {
    when(schemaRepository.findByVerticalIdAndVersion(anyString(), anyString()))
        .thenReturn(Optional.empty());

    assertTrue(seed(seedFile(SIX_FIELDS)));
    verify(schemaRepository).save(any(VerticalSchemaDocument.class));
  }

  @Test
  void reconcilesAStoredRowThatDriftedFromTheSeedFile() throws Exception {
    VerticalSchemaDocument stored = storedWith(FOUR_FIELDS);
    when(schemaRepository.findByVerticalIdAndVersion("medical", "1.0.0"))
        .thenReturn(Optional.of(stored));

    // Same version, more fields — the exact itemType situation.
    assertTrue(seed(seedFile(SIX_FIELDS)));

    verify(schemaRepository).save(stored);
    assertEquals(
        6, stored.getSchema().getEntities().get("inventory").getFields().size());
  }

  @Test
  void leavesAMatchingRowAloneSoBootDoesNotRewriteIt() throws Exception {
    when(schemaRepository.findByVerticalIdAndVersion("medical", "1.0.0"))
        .thenReturn(Optional.of(storedWith(SIX_FIELDS)));

    assertFalse(seed(seedFile(SIX_FIELDS)));
    verify(schemaRepository, never()).save(any(VerticalSchemaDocument.class));
  }

  @Test
  void reconcileKeepsStatusPublishedAtAndCreatedBy() throws Exception {
    VerticalSchemaDocument stored = storedWith(FOUR_FIELDS);
    stored.setStatus("RETIRED");
    when(schemaRepository.findByVerticalIdAndVersion("medical", "1.0.0"))
        .thenReturn(Optional.of(stored));

    seed(seedFile(SIX_FIELDS));

    // A deliberately retired vertical must not be silently reactivated by a redeploy.
    assertEquals("RETIRED", stored.getStatus());
    assertEquals(Instant.parse("2025-01-01T00:00:00Z"), stored.getPublishedAt());
    assertEquals("seed:medical-v1.json", stored.getCreatedBy());
  }

  @Test
  void skipsSeedMissingVerticalIdOrVersion() {
    assertFalse(seed(seedFile("{\"entities\":{}}")));
    verify(schemaRepository, never()).save(any(VerticalSchemaDocument.class));
  }
}
