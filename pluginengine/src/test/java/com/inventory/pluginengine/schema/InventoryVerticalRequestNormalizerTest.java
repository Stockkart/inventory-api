package com.inventory.pluginengine.schema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

class InventoryVerticalRequestNormalizerTest {

  @Test
  void hoistsExtensionExpiryFromVerticalFieldsBag() {
    SampleCreateRequest request = new SampleCreateRequest();
    request.setExpiryDate(null);
    request.setBatchNo(null);
    request.setVerticalFields(
        Map.of("expiryDate", "2028-10-13T00:00:00Z", "batchNo", "30049089"));

    InventoryVerticalRequestNormalizer.normalizeCreate(request);

    assertEquals("30049089", request.getBatchNo());
    assertNotNull(request.getExpiryDate());
    assertEquals(Instant.parse("2028-10-13T00:00:00Z"), request.getExpiryDate());
  }

  @Test
  void doesNotOverrideTopLevelValues() {
    SampleCreateRequest request = new SampleCreateRequest();
    request.setBatchNo("TOP");
    request.setVerticalFields(Map.of("batchNo", "BAG"));

    InventoryVerticalRequestNormalizer.normalizeCreate(request);

    assertEquals("TOP", request.getBatchNo());
  }

  static class SampleCreateRequest {
    private String batchNo;
    private Instant expiryDate;
    private Map<String, Object> verticalFields;

    public String getBatchNo() {
      return batchNo;
    }

    public void setBatchNo(String batchNo) {
      this.batchNo = batchNo;
    }

    public Instant getExpiryDate() {
      return expiryDate;
    }

    public void setExpiryDate(Instant expiryDate) {
      this.expiryDate = expiryDate;
    }

    public Map<String, Object> getVerticalFields() {
      return verticalFields;
    }

    public void setVerticalFields(Map<String, Object> verticalFields) {
      this.verticalFields = verticalFields;
    }
  }
}
