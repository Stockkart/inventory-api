package com.inventory.ocr.prompt;

import com.inventory.ocr.api.InvoicePricingLayout;
import java.util.EnumMap;
import java.util.Map;

/**
 * The vision-model prompt for each {@link InvoicePricingLayout}. Kept inside ocr so the layout
 * enum other modules see carries no prompt objects. Add a prompt here with every new layout.
 */
public final class InvoicePrompts {

  private static final Map<InvoicePricingLayout, InvoiceOcrPrompt> PROMPTS =
      new EnumMap<>(Map.of(
          InvoicePricingLayout.WHOLESALER, new WholesalerInvoiceOcrPrompt(),
          InvoicePricingLayout.RETAILER, new RetailerInvoiceOcrPrompt()));

  private InvoicePrompts() {}

  /** Prompt for {@code layout}; null means the default layout. */
  public static InvoiceOcrPrompt of(InvoicePricingLayout layout) {
    return PROMPTS.get(InvoicePricingLayout.orDefault(layout));
  }

  /** Prompt text for {@code layout}; null means the default layout. */
  public static String text(InvoicePricingLayout layout) {
    return of(layout).text();
  }
}
