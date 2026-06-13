package com.inventory.pluginengine.schema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class VerticalSchemaStorageTest {

  @Test
  void extractExtensionFields_filtersByStorage() {
    List<VerticalSchemaField> fields =
        List.of(
            field("name", "core"),
            field("batchNo", "extension"),
            field("expiryDate", "extension"));

    Map<String, Object> merged = new LinkedHashMap<>();
    merged.put("name", "Paracetamol");
    merged.put("batchNo", "B-1");
    merged.put("expiryDate", Instant.parse("2026-12-31T00:00:00Z"));

    Map<String, Object> extension =
        VerticalSchemaStorage.extractExtensionFields(fields, merged);

    assertEquals(2, extension.size());
    assertEquals("B-1", extension.get("batchNo"));
    assertTrue(extension.containsKey("expiryDate"));
    assertTrue(!extension.containsKey("name"));
  }

  @Test
  void mergeExtensionReadFields_prefersStoredExtensionThenLegacyCore() {
    List<VerticalSchemaField> extensionFields = List.of(field("batchNo", "extension"));
    LegacyInventory legacy = new LegacyInventory();
    legacy.setBatchNo("LEGACY-BATCH");

    Map<String, Object> fromExtension = Map.of("batchNo", "EXT-BATCH");
    Map<String, Object> mergedExtension =
        VerticalSchemaStorage.mergeExtensionReadFields(
            extensionFields, fromExtension, legacy);
    assertEquals("EXT-BATCH", mergedExtension.get("batchNo"));

    Map<String, Object> mergedLegacy =
        VerticalSchemaStorage.mergeExtensionReadFields(
            extensionFields, Map.of(), legacy);
    assertEquals("LEGACY-BATCH", mergedLegacy.get("batchNo"));
  }

  private static VerticalSchemaField field(String key, String storage) {
    VerticalSchemaField field = new VerticalSchemaField();
    field.setKey(key);
    field.setStorage(storage);
    return field;
  }

  @SuppressWarnings("unused")
  private static class LegacyInventory {
    private String batchNo;

    public String getBatchNo() {
      return batchNo;
    }

    public void setBatchNo(String batchNo) {
      this.batchNo = batchNo;
    }
  }
}
