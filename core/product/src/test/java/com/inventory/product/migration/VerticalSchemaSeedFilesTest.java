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

    assertEquals(
        List.of("name", "baseUnit", "sport", "sportsType", "brand", "model", "size"), keys);
  }

  @Test
  void sportsRegularModeStillShowsAllRegistrationFields() throws Exception {
    List<String> keys = fieldKeys("sports-v1.json", "inventory", SchemaDisplayMode.REGULAR);

    assertEquals(
        List.of(
            "name",
            "hsn",
            "baseUnit",
            "sport",
            "sportsType",
            "brand",
            "model",
            "size",
            "warrantyMonths"),
        keys);
  }

  @Test
  void sportsSizeAndSportsTypeAreOptionalExtensionFields() throws Exception {
    VerticalSchema schema = load("sports-v1.json");
    List<VerticalSchemaField> fields = schema.getEntities().get("inventory").getFields();

    VerticalSchemaField size = byKey(fields, "size");
    assertEquals("string", size.getType());
    assertEquals(Boolean.FALSE, size.getRequired());
    assertEquals("extension", size.getStorage());

    VerticalSchemaField sportsType = byKey(fields, "sportsType");
    assertEquals("enum", sportsType.getType());
    assertEquals(Boolean.FALSE, sportsType.getRequired());
    assertEquals("extension", sportsType.getStorage());
    assertTrue(sportsType.getValues().contains("other"));
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

  private static VerticalSchemaField byKey(List<VerticalSchemaField> fields, String key) {
    return fields.stream().filter(f -> key.equals(f.getKey())).findFirst().orElseThrow();
  }

  private VerticalSchema load(String seed) throws Exception {
    try (InputStream in = getClass().getResourceAsStream("/seeds/" + seed)) {
      return mapper.readValue(in, VerticalSchema.class);
    }
  }
}
