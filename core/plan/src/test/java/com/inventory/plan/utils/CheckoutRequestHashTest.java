package com.inventory.plan.utils;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventory.plan.rest.dto.request.QuoteRequest;
import com.inventory.plan.rest.dto.request.QuoteRequest.AddOnLine;
import java.util.List;
import org.junit.jupiter.api.Test;

class CheckoutRequestHashTest {

  @Test
  void ignoresOrderAndCaseOfAddOnsAndVouchers() {
    QuoteRequest a = new QuoteRequest("GROWTH", 12,
        List.of(new AddOnLine("ocr_500", 2), new AddOnLine("SEAT", 1)), List.of("save10", "WELCOME"), true);
    QuoteRequest b = new QuoteRequest("GROWTH", 12,
        List.of(new AddOnLine("SEAT", 1), new AddOnLine("OCR_500", 2)), List.of("welcome", "SAVE10"), true);

    assertThat(CheckoutRequestHash.of("shop-1", "GROWTH", a)).isEqualTo(CheckoutRequestHash.of("shop-1", "GROWTH", b));
  }

  @Test
  void differsWhenAnythingThatChangesTheChargeDiffers() {
    QuoteRequest base = new QuoteRequest("GROWTH", 12, List.of(new AddOnLine("SEAT", 1)), null, false);
    String hash = CheckoutRequestHash.of("shop-1", "GROWTH", base);

    assertThat(CheckoutRequestHash.of("shop-2", "GROWTH", base)).isNotEqualTo(hash);
    assertThat(CheckoutRequestHash.of("shop-1", "PROFESSIONAL", base)).isNotEqualTo(hash);
    assertThat(CheckoutRequestHash.of("shop-1", "GROWTH",
        new QuoteRequest("GROWTH", 12, List.of(new AddOnLine("SEAT", 2)), null, false))).isNotEqualTo(hash);
    assertThat(CheckoutRequestHash.of("shop-1", "GROWTH",
        new QuoteRequest("GROWTH", 12, List.of(new AddOnLine("SEAT", 1)), null, true))).isNotEqualTo(hash);
  }
}
