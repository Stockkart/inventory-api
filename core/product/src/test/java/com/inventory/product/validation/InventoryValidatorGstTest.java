package com.inventory.product.validation;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.inventory.common.exception.ValidationException;
import com.inventory.product.domain.model.enums.BillingMode;
import com.inventory.product.rest.dto.request.CreateInventoryRequest;
import com.inventory.product.rest.dto.request.UpdateInventoryRequest;
import com.inventory.product.service.PackagingUnitService;
import org.junit.jupiter.api.Test;

/** GST is the lot's own: required on a taxable lot, absent on a BASIC one, never blanked by an update. */
class InventoryValidatorGstTest {

  private final InventoryValidator validator = new InventoryValidator(mock(PackagingUnitService.class));

  private static CreateInventoryRequest create(BillingMode mode, String sgst, String cgst) {
    CreateInventoryRequest r = new CreateInventoryRequest();
    r.setName("Paracetamol 500mg");
    r.setCount(10);
    r.setBillingMode(mode);
    r.setSgst(sgst);
    r.setCgst(cgst);
    return r;
  }

  private static UpdateInventoryRequest update(BillingMode mode, String sgst, String cgst) {
    UpdateInventoryRequest r = new UpdateInventoryRequest();
    r.setBillingMode(mode);
    r.setSgst(sgst);
    r.setCgst(cgst);
    return r;
  }

  @Test
  void taxableLotWithoutGstIsRejected() {
    assertThatThrownBy(() -> validator.validateCreateRequest(create(BillingMode.REGULAR, null, null)))
        .isInstanceOf(ValidationException.class).hasMessage(InventoryValidator.GST_REQUIRED);
    assertThatThrownBy(() -> validator.validateCreateRequest(create(null, "2.5", " ")))
        .isInstanceOf(ValidationException.class).hasMessage(InventoryValidator.GST_REQUIRED);
  }

  @Test
  void taxableLotWithGstIncludingZeroIsAccepted() {
    assertThatCode(() -> validator.validateCreateRequest(create(BillingMode.REGULAR, "2.5", "2.5")))
        .doesNotThrowAnyException();
    assertThatCode(() -> validator.validateCreateRequest(create(null, "0", "0")))
        .doesNotThrowAnyException();
  }

  @Test
  void basicLotCarriesNoGst() {
    assertThatCode(() -> validator.validateCreateRequest(create(BillingMode.BASIC, null, null)))
        .doesNotThrowAnyException();
    assertThatThrownBy(() -> validator.validateCreateRequest(create(BillingMode.BASIC, "2.5", "2.5")))
        .isInstanceOf(ValidationException.class).hasMessageContaining("BASIC");
  }

  @Test
  void updateWithoutGstFieldsLeavesGstAlone() {
    assertThatCode(() -> validator.validateUpdateRequest(update(null, null, null))).doesNotThrowAnyException();
    assertThatCode(() -> validator.validateUpdateRequest(update(BillingMode.REGULAR, null, null)))
        .doesNotThrowAnyException();
    assertThatCode(() -> validator.validateUpdateRequest(update(null, "9", "9"))).doesNotThrowAnyException();
  }

  @Test
  void updateCannotBlankATaxableLotsGst() {
    assertThatThrownBy(() -> validator.validateUpdateRequest(update(null, "", "2.5")))
        .isInstanceOf(ValidationException.class).hasMessage(InventoryValidator.GST_REQUIRED);
    assertThatThrownBy(() -> validator.validateUpdateRequest(update(BillingMode.REGULAR, "2.5", " ")))
        .isInstanceOf(ValidationException.class).hasMessage(InventoryValidator.GST_REQUIRED);
  }

  @Test
  void updateToBasicMustNotSendGst() {
    assertThatThrownBy(() -> validator.validateUpdateRequest(update(BillingMode.BASIC, "2.5", "2.5")))
        .isInstanceOf(ValidationException.class).hasMessageContaining("BASIC");
  }
}
