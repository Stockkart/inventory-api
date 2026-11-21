package com.inventory.product.rest.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.inventory.product.domain.model.enums.ShopType;
import com.inventory.product.labels.BlankValueBehavior;
import com.inventory.product.labels.EffectiveLayout;
import com.inventory.product.labels.EnabledFieldDto;
import com.inventory.product.labels.PrintableField;
import com.inventory.product.labels.SourceGroup;
import com.inventory.product.labels.StickerSizeSpec;
import com.inventory.product.labels.ValueType;
import com.inventory.product.rest.dto.request.SaveLabelLayoutRequest;
import com.inventory.product.rest.dto.response.LabelLayoutResponse;
import com.inventory.product.rest.dto.response.PrintableFieldDto;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** JSON binding of the barcode label layout DTOs (lenient booleans, {@code isDefault} naming). */
class LabelLayoutDtoJsonTest {

  private final ObjectMapper mapper =
      new ObjectMapper()
          .registerModule(new JavaTimeModule())
          .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

  @Test
  void acceptsJsonBooleansAndBooleanStrings() throws Exception {
    SaveLabelLayoutRequest req =
        mapper.readValue(
            "{\"enabledFieldKeys\":[\"productName\"],\"stickerSize\":\"50x25\","
                + "\"showBarcodeText\":true,\"showFieldLabels\":\" False \","
                + "\"blankValueBehavior\":\"HIDE_LINE\",\"shopId\":\"ignored\"}",
            SaveLabelLayoutRequest.class);

    assertEquals(List.of("productName"), req.enabledFieldKeys());
    assertEquals(Boolean.TRUE, req.showBarcodeTextValue());
    assertEquals(Boolean.FALSE, req.showFieldLabelsValue());
    assertFalse(req.isShowBarcodeTextInvalid());
    assertFalse(req.isShowFieldLabelsInvalid());
  }

  @Test
  void missingOrNullBooleansAreNull() throws Exception {
    SaveLabelLayoutRequest req =
        mapper.readValue("{\"showFieldLabels\":null}", SaveLabelLayoutRequest.class);

    assertNull(req.showBarcodeText());
    assertNull(req.showFieldLabels());
    assertNull(req.showBarcodeTextValue());
    assertFalse(req.isShowBarcodeTextInvalid());
    assertFalse(req.isShowFieldLabelsInvalid());
  }

  @Test
  void nonBooleanInputIsKeptAsInvalidMarker() throws Exception {
    SaveLabelLayoutRequest req =
        mapper.readValue(
            "{\"showBarcodeText\":\"yes\",\"showFieldLabels\":1,\"stickerSize\":\"50x25\"}",
            SaveLabelLayoutRequest.class);

    assertTrue(req.isShowBarcodeTextInvalid());
    assertEquals("yes", req.showBarcodeText().invalidRaw());
    assertNull(req.showBarcodeTextValue());
    assertTrue(req.isShowFieldLabelsInvalid());
    assertEquals("1", req.showFieldLabels().invalidRaw());
    // Parser stayed in sync: the following property was still bound.
    assertEquals("50x25", req.stickerSize());
  }

  @Test
  void responseSerialisesIsDefaultAndLowercaseEnums() throws Exception {
    EffectiveLayout layout =
        new EffectiveLayout(
            List.of(new EnabledFieldDto("productName", "Product name", "text")),
            "50x25",
            new StickerSizeSpec("50x25", 50, 25, 3),
            true,
            false,
            BlankValueBehavior.HIDE_LINE);
    LabelLayoutResponse response =
        LabelLayoutResponse.from(layout, true, null, null, ShopType.RETAILER);

    JsonNode json = mapper.valueToTree(response);
    assertTrue(json.get("isDefault").asBoolean());
    assertFalse(json.has("default"));
    assertEquals("HIDE_LINE", json.get("blankValueBehavior").asText());
    assertEquals("RETAILER", json.get("shopType").asText());
    assertEquals(3, json.get("stickerSizeSpec").get("maxLines").asInt());

    LabelLayoutResponse withMeta =
        LabelLayoutResponse.from(
            layout, false, Instant.parse("2026-03-05T10:00:00Z"), "u1", ShopType.DISTRIBUTOR);
    assertEquals("u1", mapper.valueToTree(withMeta).get("updatedByUserId").asText());

    PrintableFieldDto field =
        PrintableFieldDto.from(
            new PrintableField(
                "ptr",
                "PTR",
                SourceGroup.PRICING,
                ValueType.CURRENCY,
                Set.of(ShopType.WHOLESALER, ShopType.DISTRIBUTOR)));
    JsonNode fieldJson = mapper.valueToTree(field);
    assertEquals("pricing", fieldJson.get("sourceGroup").asText());
    assertEquals("currency", fieldJson.get("valueType").asText());
    assertEquals("DISTRIBUTOR", fieldJson.get("availableForShopTypes").get(0).asText());
    assertEquals("WHOLESALER", fieldJson.get("availableForShopTypes").get(1).asText());
  }
}
