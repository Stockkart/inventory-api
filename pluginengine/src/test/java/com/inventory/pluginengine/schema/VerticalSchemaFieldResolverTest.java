package com.inventory.pluginengine.schema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class VerticalSchemaFieldResolverTest {

  @Test
  void readsCoreFieldsFromRequestBean() {
    VerticalSchemaField companyName = field("companyName", "core");

    SampleCreateRequest request = new SampleCreateRequest();
    request.setCompanyName("Acme Pharma");

    Map<String, Object> fields =
        VerticalSchemaFieldResolver.mergeVerticalFields(List.of(companyName), request, null);

    assertEquals("Acme Pharma", fields.get("companyName"));
  }

  @Test
  void extensionFieldsComeOnlyFromVerticalFieldsBag() {
    VerticalSchemaField batchNo = field("batchNo", "extension");

    SampleCreateRequest request = new SampleCreateRequest();
    request.setBatchNo("IGNORED");
    request.setVerticalFields(Map.of("batchNo", "B001"));

    Map<String, Object> fields =
        VerticalSchemaFieldResolver.mergeVerticalFields(List.of(batchNo), request, null);

    assertEquals("B001", fields.get("batchNo"));
  }

  @Test
  void verticalFieldsOverrideRequestBeanValues() {
    VerticalSchemaField sport = field("sport", "extension");

    SampleCreateRequest request = new SampleCreateRequest();
    request.setSport("football");
    request.setVerticalFields(Map.of("sport", "cricket"));

    Map<String, Object> fields =
        VerticalSchemaFieldResolver.mergeVerticalFields(List.of(sport), request, null);

    assertEquals("cricket", fields.get("sport"));
  }

  @Test
  void verticalFieldsBagProvidesExtensionOnlyFields() {
    VerticalSchemaField brand = field("brand", "extension");

    SampleCreateRequest request = new SampleCreateRequest();
    request.setVerticalFields(Map.of("brand", "MRF", "model", "Grand"));

    Map<String, Object> fields =
        VerticalSchemaFieldResolver.mergeVerticalFields(List.of(brand), request, null);

    assertEquals("MRF", fields.get("brand"));
    assertEquals("Grand", fields.get("model"));
  }

  @Test
  void resolvesApiKeyWhenPropertyNameDiffersFromSchemaKey() {
    VerticalSchemaField legacy = field("displayName", "core");
    legacy.setApiKey("name");

    SampleCreateRequest request = new SampleCreateRequest();
    request.setName("Widget");

    Map<String, Object> fields =
        VerticalSchemaFieldResolver.mergeVerticalFields(List.of(legacy), request, null);

    assertEquals("Widget", fields.get("displayName"));
  }

  @Test
  void updateFallsBackToExtensionDocument() {
    VerticalSchemaField batchNo = field("batchNo", "extension");

    SampleUpdateRequest request = new SampleUpdateRequest();

    Map<String, Object> fields =
        VerticalSchemaFieldResolver.mergeVerticalFields(
            List.of(batchNo), request, null, Map.of("batchNo", "B-old"));

    assertEquals("B-old", fields.get("batchNo"));
  }

  @Test
  void missingPropertyIsOmittedFromMap() {
    VerticalSchemaField storageTemp = field("storageTemp", "extension");

    Map<String, Object> fields =
        VerticalSchemaFieldResolver.mergeVerticalFields(
            List.of(storageTemp), new SampleCreateRequest(), null);

    assertNull(fields.get("storageTemp"));
  }

  private static VerticalSchemaField field(String key, String storage) {
    VerticalSchemaField field = new VerticalSchemaField();
    field.setKey(key);
    field.setStorage(storage);
    return field;
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

  @SuppressWarnings("unused")
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
