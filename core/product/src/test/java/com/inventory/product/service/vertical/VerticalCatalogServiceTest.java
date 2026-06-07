package com.inventory.product.service.vertical;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import com.inventory.common.exception.ValidationException;
import com.inventory.pluginengine.schema.VerticalSchemaStatus;
import com.inventory.product.domain.model.VerticalSchemaDocument;
import com.inventory.product.domain.repository.VerticalSchemaRepository;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class VerticalCatalogServiceTest {

  private static final String ACTIVE = VerticalSchemaStatus.ACTIVE.name();

  @Mock private VerticalSchemaRepository schemaRepository;

  private VerticalCatalogService catalogService;

  @BeforeEach
  void setUp() {
    catalogService = new VerticalCatalogService(schemaRepository);
  }

  @Test
  void isActiveVerticalChecksDb() {
    when(schemaRepository.existsByVerticalIdAndStatus("sports", ACTIVE)).thenReturn(true);

    assertTrue(catalogService.isActiveVertical("sports"));
    assertFalse(catalogService.isActiveVertical("apparel"));
  }

  @Test
  void resolveForNewShopUsesActiveSchemaFromDb() {
    VerticalSchemaDocument doc = activeDoc("sports", "1.0.0");
    when(schemaRepository.findByVerticalIdAndStatus("sports", ACTIVE)).thenReturn(List.of(doc));

    VerticalCatalogService.VerticalPin pin = catalogService.resolveForNewShop("sports");

    assertEquals("sports", pin.verticalId());
    assertEquals("1.0.0", pin.pluginVersion());
  }

  @Test
  void blankVerticalIdThrows() {
    assertThrows(ValidationException.class, () -> catalogService.resolveForNewShop(null));
    assertThrows(ValidationException.class, () -> catalogService.resolveForNewShop("  "));
  }

  @Test
  void unknownVerticalThrows() {
    when(schemaRepository.findByVerticalIdAndStatus("apparel", ACTIVE)).thenReturn(List.of());

    assertThrows(ValidationException.class, () -> catalogService.resolveForNewShop("apparel"));
  }

  private static VerticalSchemaDocument activeDoc(String verticalId, String version) {
    VerticalSchemaDocument doc = new VerticalSchemaDocument();
    doc.setVerticalId(verticalId);
    doc.setVersion(version);
    doc.setStatus(ACTIVE);
    return doc;
  }
}
