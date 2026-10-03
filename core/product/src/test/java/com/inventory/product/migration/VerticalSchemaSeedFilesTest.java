package com.inventory.product.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.inventory.pluginengine.schema.SchemaDisplayMode;
import com.inventory.pluginengine.schema.SchemaFieldFilter;
import com.inventory.pluginengine.schema.VerticalSchema;
import com.inventory.pluginengine.schema.VerticalSchemaField;
import java.io.InputStream;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Reads the classpath seeds that {@link VerticalSchemaSeeder} loads and checks each mode's view. */
class VerticalSchemaSeedFilesTest {

  private ObjectMapper mapper;

  @BeforeEach
  void setUp() {
    mapper = new ObjectMapper().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
  }

  @Test
  void sportsBasicModeKeepsRequiredRegistrationFields() throws Exception {
    List<String> keys = fieldKeys("sports-v1.json", "inventory", SchemaDisplayMode.BASIC);

    assertEquals(List.of("name", "baseUnit", "sport", "brand", "model"), keys);
  }

  @Test
  void sportsRegularModeStillShowsAllRegistrationFields() throws Exception {
    List<String> keys = fieldKeys("sports-v1.json", "inventory", SchemaDisplayMode.REGULAR);

    assertEquals(
        List.of("name", "hsn", "baseUnit", "sport", "brand", "model", "warrantyMonths"), keys);
  }

  @Test
  void cafeOnboardingAsksForFssai() throws Exception {
    VerticalSchema schema = load("cafe-v1.json");
    List<VerticalSchemaField> fields =
        SchemaFieldFilter.filterForMode(
            schema.getEntities().get("shop").getFields(), SchemaDisplayMode.ONBOARDING);

    assertEquals(1, fields.size());
    assertEquals("fssai", fields.get(0).getKey());
    assertTrue(fields.get(0).getRequired());
  }

  private List<String> fieldKeys(String seed, String entity, SchemaDisplayMode mode)
      throws Exception {
    VerticalSchema schema = load(seed);
    return SchemaFieldFilter.filterForMode(schema.getEntities().get(entity).getFields(), mode)
        .stream()
        .map(VerticalSchemaField::getKey)
        .toList();
  }

  private VerticalSchema load(String seed) throws Exception {
    try (InputStream in = getClass().getResourceAsStream("/seeds/" + seed)) {
      return mapper.readValue(in, VerticalSchema.class);
    }
  }
}
