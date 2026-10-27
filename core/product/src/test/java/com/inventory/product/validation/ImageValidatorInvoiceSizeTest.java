package com.inventory.product.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.inventory.common.exception.ValidationException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

/** OCR invoice images are capped at 2 MB each, checked before the bytes are read. */
class ImageValidatorInvoiceSizeTest {

  private final ImageValidator validator = new ImageValidator();

  private static MockMultipartFile jpeg(int bytes) {
    return new MockMultipartFile("images", "page.jpg", "image/jpeg", new byte[bytes]);
  }

  @Test
  void exactlyTwoMegabytesIsAllowed() {
    validator.validateInvoiceImageBatch(List.of(jpeg(300_000), jpeg(2 * 1024 * 1024)));
  }

  @Test
  void overTwoMegabytesNamesThePageAndTheLimit() {
    assertThatThrownBy(() -> validator.validateInvoiceImageBatch(List.of(jpeg(500_000), jpeg(2 * 1024 * 1024 + 1))))
        .isInstanceOf(ValidationException.class)
        .hasMessage("Image 2 is 2.0 MB; the limit is 2 MB per image. Please retake or crop the photo.");
    assertThatThrownBy(() -> validator.validateInvoiceImageSize(0, 6L * 1024 * 1024 + 300_000))
        .hasMessageStartingWith("Image 1 is 6.3 MB;");
  }

  @Test
  void theSizeIsCheckedWithoutReadingTheBytes() throws Exception {
    MultipartFile big = mock(MultipartFile.class);
    when(big.isEmpty()).thenReturn(false);
    when(big.getContentType()).thenReturn("image/jpeg");
    when(big.getSize()).thenReturn(9L * 1024 * 1024);

    assertThatThrownBy(() -> validator.validateInvoiceImageBatch(List.of(big)))
        .isInstanceOf(ValidationException.class);
    verify(big, never()).getBytes();
  }

  @Test
  void theCapIsTwoMegabytes() {
    assertThat(ImageValidator.MAX_INVOICE_IMAGE_BYTES).isEqualTo(2L * 1024 * 1024);
  }
}
