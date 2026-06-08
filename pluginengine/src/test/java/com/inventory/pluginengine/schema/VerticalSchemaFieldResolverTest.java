package com.inventory.pluginengine.schema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class VerticalSchemaFieldResolverTest {

  @Test
  void resolvesApiKeyFromRequestBean() {
    VerticalSchemaField manufacturer = new VerticalSchemaField();
    manufacturer.setKey("manufacturer");
    manufacturer.setApiKey("companyName");

    VerticalSchemaField batchNo = new VerticalSchemaField();
    batchNo.setKey("batchNo");

    SampleCreateRequest request = new SampleCreateRequest();
    request.setCompanyName("Acme Pharma");
    request.setBatchNo("B001");

    Map<String, Object> fields =
        VerticalSchemaFieldResolver.resolve(
            List.of(manufacturer, batchNo), request, null);

    assertEquals("Acme Pharma", fields.get("manufacturer"));
    assertEquals("B001", fields.get("batchNo"));
  }

  @Test
  void updateFallsBackToExistingInventory() {
    VerticalSchemaField batchNo = new VerticalSchemaField();
    batchNo.setKey("batchNo");

    SampleUpdateRequest request = new SampleUpdateRequest();
    SampleInventory existing = new SampleInventory();
    existing.setBatchNo("B-old");

    Map<String, Object> fields =
        VerticalSchemaFieldResolver.resolve(List.of(batchNo), request, existing);

    assertEquals("B-old", fields.get("batchNo"));
  }

  @Test
  void mergeVerticalFieldsOverridesSchemaExtractedValues() {
    Map<String, Object> fields = new java.util.LinkedHashMap<>();
    fields.put("sport", "football");

    SampleCreateRequest request = new SampleCreateRequest();
    request.setVerticalFields(Map.of("sport", "cricket"));

    VerticalSchemaFieldResolver.mergeVerticalFields(fields, request);

    assertEquals("cricket", fields.get("sport"));
  }

  @Test
  void missingPropertyIsOmittedFromMap() {
    VerticalSchemaField storageTemp = new VerticalSchemaField();
    storageTemp.setKey("storageTemp");

    Map<String, Object> fields =
        VerticalSchemaFieldResolver.resolve(List.of(storageTemp), new SampleCreateRequest(), null);

    assertNull(fields.get("storageTemp"));
  }

  static class SampleCreateRequest {
    private String companyName;
    private String batchNo;
    private Map<String, Object> verticalFields;

    public String getCompanyName() {
      return companyName;
    }

    public void setCompanyName(String companyName) {
      this.companyName = companyName;
    }

    public String getBatchNo() {
      return batchNo;
    }

    public void setBatchNo(String batchNo) {
      this.batchNo = batchNo;
    }

    public Map<String, Object> getVerticalFields() {
      return verticalFields;
    }

    public void setVerticalFields(Map<String, Object> verticalFields) {
      this.verticalFields = verticalFields;
    }
  }

  static class SampleUpdateRequest {
    private String batchNo;

    public String getBatchNo() {
      return batchNo;
    }
  }

  static class SampleInventory {
    private String batchNo;
    private Instant expiryDate;

    public String getBatchNo() {
      return batchNo;
    }

    public void setBatchNo(String batchNo) {
      this.batchNo = batchNo;
    }

    public Instant getExpiryDate() {
      return expiryDate;
    }
  }
}
