package com.inventory.product.labels;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

import com.inventory.pluginengine.schema.VerticalEntitySchema;
import com.inventory.pluginengine.schema.VerticalSchema;
import com.inventory.pluginengine.schema.VerticalSchemaField;
import com.inventory.pricing.domain.repository.PricingRepository;
import com.inventory.product.domain.model.Shop;
import com.inventory.product.domain.model.enums.ShopType;
import com.inventory.product.domain.repository.ShopRepository;
import com.inventory.product.rest.dto.response.FieldCatalogResponse;
import com.inventory.product.rest.dto.response.PrintableFieldDto;
import com.inventory.product.service.vertical.SchemaLoader;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Golden regression for the barcode-label field catalog (configurable-product-card Req 1.6).
 *
 * <p>The card feature widened the shared Field_Catalog with card-only fields. The sticker endpoint
 * must not notice: the {@code fields} list it returns — keys, labels and order — is pinned here to
 * the exact output captured before that change, for a retailer without a vertical and a wholesaler
 * on a medical-like schema with two named rates.
 */
@ExtendWith(MockitoExtension.class)
class LabelFieldCatalogGoldenTest {

  private static final String SHOP_ID = "golden-shop";

  @Mock private ShopRepository shopRepository;
  @Mock private PricingRepository pricingRepository;
  @Mock private SchemaLoader schemaLoader;

  private LabelFieldCatalogService service;

  @BeforeEach
  void setUp() {
    service =
        new LabelFieldCatalogService(
            shopRepository, pricingRepository, schemaLoader, new VerticalValueTypeMapper());
  }

  @Test
  void retailerWithoutVerticalMatchesPreCardCatalog() {
    when(pricingRepository.findDistinctRateNamesByShopId(SHOP_ID)).thenReturn(List.of());

    FieldCatalogResponse response = FieldCatalogResponse.from(service.catalog(shop(ShopType.RETAILER, null)));

    assertEquals(
        List.of(
            "productName:Product name",
            "companyName:Company",
            "barcodeText:Barcode",
            "hsn:HSN",
            "baseUnit:Unit",
            "packSize:Pack size",
            "description:Description",
            "mrp:MRP",
            "sellingPrice:Selling price",
            "ptr:PTR",
            "costPrice:Cost price",
            "saleScheme:Scheme",
            "gstRate:GST %",
            "batchNo:Batch no.",
            "expiryDate:Expiry",
            "receivedDate:Received on",
            "shopName:Shop name",
            "shopTagline:Tagline",
            "shopPhone:Phone",
            "shopEmail:Email",
            "shopAddress:Address",
            "shopGstin:GSTIN",
            "shopFssai:FSSAI",
            "shopDlNo:D.L. No."),
        keyLabels(response));
  }

  @Test
  void wholesalerWithRatesAndVerticalMatchesPreCardCatalog() {
    when(pricingRepository.findDistinctRateNamesByShopId(SHOP_ID))
        .thenReturn(List.of("Retail", "Wholesale"));
    when(schemaLoader.load("medical", "1.0.0"))
        .thenReturn(
            schema(
                Map.of(
                    LabelFieldCatalogService.INVENTORY_ENTITY,
                    entity(
                        field("batchNo", "batchNo", "Batch", "string"), // dedup'd against lot
                        field("expiryDate", "expiryDate", "Expiry", "date"), // dedup'd against lot
                        field("schedule", null, "Drug schedule", "enum"),
                        field("storageTemp", "storageTemperature", "Storage °C", "number")))));

    FieldCatalogResponse response =
        FieldCatalogResponse.from(service.catalog(shop(ShopType.WHOLESALER, "medical")));

    assertEquals(
        List.of(
            "productName:Product name",
            "companyName:Company",
            "barcodeText:Barcode",
            "hsn:HSN",
            "baseUnit:Unit",
            "packSize:Pack size",
            "description:Description",
            "mrp:MRP",
            "sellingPrice:Selling price",
            "ptr:PTR",
            "costPrice:Cost price",
            "saleScheme:Scheme",
            "gstRate:GST %",
            "pricing.rate.Retail:Rate: Retail",
            "pricing.rate.Wholesale:Rate: Wholesale",
            "batchNo:Batch no.",
            "expiryDate:Expiry",
            "receivedDate:Received on",
            "vertical.schedule:Drug schedule",
            "vertical.storageTemp:Storage °C",
            "shopName:Shop name",
            "shopTagline:Tagline",
            "shopPhone:Phone",
            "shopEmail:Email",
            "shopAddress:Address",
            "shopGstin:GSTIN",
            "shopFssai:FSSAI",
            "shopDlNo:D.L. No."),
        keyLabels(response));
  }

  private static List<String> keyLabels(FieldCatalogResponse response) {
    return response.fields().stream().map(f -> f.fieldKey() + ":" + f.label()).toList();
  }

  private static Shop shop(ShopType type, String verticalId) {
    Shop shop = new Shop();
    shop.setShopId(SHOP_ID);
    shop.setShopType(type);
    shop.setVerticalId(verticalId);
    shop.setPluginVersion(verticalId == null ? null : "1.0.0");
    return shop;
  }

  private static VerticalSchemaField field(String key, String apiKey, String label, String type) {
    VerticalSchemaField f = new VerticalSchemaField();
    f.setKey(key);
    f.setApiKey(apiKey);
    f.setLabel(label);
    f.setType(type);
    return f;
  }

  private static VerticalEntitySchema entity(VerticalSchemaField... fields) {
    VerticalEntitySchema e = new VerticalEntitySchema();
    e.setFields(List.of(fields));
    return e;
  }

  private static VerticalSchema schema(Map<String, VerticalEntitySchema> entities) {
    VerticalSchema s = new VerticalSchema();
    s.setEntities(entities);
    return s;
  }

  @SuppressWarnings("unused")
  private static String describe(PrintableFieldDto dto) {
    return dto.fieldKey() + ":" + dto.label();
  }
}
