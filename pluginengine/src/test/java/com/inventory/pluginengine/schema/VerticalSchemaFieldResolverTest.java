package com.inventory.pluginengine.schema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class VerticalSchemaFieldResolverTest {

  @Test
  void readsSchemaFieldsFromRequestBeanWhenVerticalFieldsAbsent() {
    VerticalSchemaField companyName = new VerticalSchemaField();
    companyName.setKey("companyName");

    VerticalSchemaField batchNo = new VerticalSchemaField();
    batchNo.setKey("batchNo");

    SampleCreateRequest request = new SampleCreateRequest();
    request.setCompanyName("Acme Pharma");
    request.setBatchNo("B001");

    Map<String, Object> fields =
        VerticalSchemaFieldResolver.mergeVerticalFields(
            List.of(companyName, batchNo), request, null);

    assertEquals("Acme Pharma", fields.get("companyName"));
    assertEquals("B001", fields.get("batchNo"));
  }

  @Test
  void verticalFieldsOverrideRequestBeanValues() {
    VerticalSchemaField sport = new VerticalSchemaField();
    sport.setKey("sport");

    SampleCreateRequest request = new SampleCreateRequest();
    request.setSport("football");
    request.setVerticalFields(Map.of("sport", "cricket"));

    Map<String, Object> fields =
        VerticalSchemaFieldResolver.mergeVerticalFields(List.of(sport), request, null);

    assertEquals("cricket", fields.get("sport"));
  }

  @Test
  void verticalFieldsBagProvidesExtensionOnlyFields() {
    VerticalSchemaField brand = new VerticalSchemaField();
    brand.setKey("brand");

    SampleCreateRequest request = new SampleCreateRequest();
    request.setVerticalFields(Map.of("brand", "MRF", "model", "Grand"));

    Map<String, Object> fields =
        VerticalSchemaFieldResolver.mergeVerticalFields(List.of(brand), request, null);

    assertEquals("MRF", fields.get("brand"));
    assertEquals("Grand", fields.get("model"));
  }

  @Test
  void resolvesApiKeyWhenPropertyNameDiffersFromSchemaKey() {
    VerticalSchemaField legacy = new VerticalSchemaField();
    legacy.setKey("displayName");
    legacy.setApiKey("name");

    SampleCreateRequest request = new SampleCreateRequest();
    request.setName("Widget");

    Map<String, Object> fields =
        VerticalSchemaFieldResolver.mergeVerticalFields(List.of(legacy), request, null);

    assertEquals("Widget", fields.get("displayName"));
  }

  @Test
  void updateFallsBackToExistingInventory() {
    VerticalSchemaField batchNo = new VerticalSchemaField();
    batchNo.setKey("batchNo");

    SampleUpdateRequest request = new SampleUpdateRequest();
    SampleInventory existing = new SampleInventory();
    existing.setBatchNo("B-old");

    Map<String, Object> fields =
        VerticalSchemaFieldResolver.mergeVerticalFields(List.of(batchNo), request, existing);

    assertEquals("B-old", fields.get("batchNo"));
  }

  @Test
  void missingPropertyIsOmittedFromMap() {
    VerticalSchemaField storageTemp = new VerticalSchemaField();
    storageTemp.setKey("storageTemp");

    Map<String, Object> fields =
        VerticalSchemaFieldResolver.mergeVerticalFields(
            List.of(storageTemp), new SampleCreateRequest(), null);

    assertNull(fields.get("storageTemp"));
  }

  static class SampleCreateRequest {
    private String name;
    private String companyName;
    private String batchNo;
    private String sport;
    private Map<String, Object> verticalFields;

    public String getName() {
      return name;
    }

    public void setName(String name) {
      this.name = name;
    }

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

    public String getSport() {
      return sport;
    }

    public void setSport(String sport) {
      this.sport = sport;
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
