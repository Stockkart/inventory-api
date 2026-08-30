package com.inventory.documentservice.service.preview;

import com.inventory.documentservice.domain.PrinterType;
import com.inventory.documentservice.rest.dto.GenerateInvoiceRequest;
import com.inventory.documentservice.service.InvoiceTextRenderer;
import java.util.Locale;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Previews a dot-matrix bill as the very characters the printer will receive.
 *
 * <p>Rendering dot matrix from its own Thymeleaf template meant the preview and the paper were two
 * implementations of one layout, free to disagree - and they did, on whether the shop block
 * appears at all. Both now come from {@link InvoiceTextRenderer}.
 */
@Component
@Order(0)
public class DotMatrixInvoicePreviewRenderer implements InvoicePreviewRenderer {

  /** The preview page is sized for eighty columns; a wider bill is set smaller to fit it. */
  private static final int PREVIEW_BASE_COLUMNS = 80;

  private static final double PREVIEW_BASE_FONT_PX = 13.0;

  private final InvoiceTextRenderer invoiceTextRenderer;

  public DotMatrixInvoicePreviewRenderer(InvoiceTextRenderer invoiceTextRenderer) {
    this.invoiceTextRenderer = invoiceTextRenderer;
  }

  @Override
  public boolean supports(PrinterType printerType) {
    return printerType == PrinterType.DOT_MATRIX;
  }

  @Override
  public String render(GenerateInvoiceRequest request) {
    return previewHtml(invoiceTextRenderer.render(request));
  }

  /**
   * Wraps rendered invoice text in the smallest page that shows it at a fixed pitch.
   *
   * <p>The type is scaled to the widest line in the document rather than fixed. The tax invoice
   * prints condensed well past eighty columns, and at the eighty-column size everything past
   * column eighty fell outside the preview pane and was simply not shown - the totals, the right
   * of the item grid and half the signature line all vanished, which read as the renderer having
   * dropped them.
   */
  private static String previewHtml(String text) {
    int widest = text.lines().mapToInt(line -> measured(line).length()).max().orElse(0);
    double fontPx =
        PREVIEW_BASE_FONT_PX * PREVIEW_BASE_COLUMNS / Math.max(PREVIEW_BASE_COLUMNS, widest);
    return "<!DOCTYPE html><html><head><meta charset=\"UTF-8\"/>"
        + "<style>body{margin:0;padding:12px;background:#fff;}"
        + "pre{font-family:'Courier New',Courier,monospace;font-size:"
        + String.format(Locale.ROOT, "%.2f", fontPx) + "px;line-height:1.25;"
        + "white-space:pre;margin:0;color:#000;}"
        + ".sm{font-size:7px;}</style></head><body><pre>"
        + showEmphasis(escapeHtml(text))
        + "</pre></body></html>";
  }

  /** The line as the printer sees it: emphasis codes occupy no columns. */
  private static String measured(String line) {
    return line
        .replace(InvoiceTextRenderer.BOLD_ON, "")
        .replace(InvoiceTextRenderer.BOLD_OFF, "")
        .replace(InvoiceTextRenderer.CONDENSED_ON, "")
        .replace(InvoiceTextRenderer.CONDENSED_OFF, "");
  }

  private static String escapeHtml(String raw) {
    return raw.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
  }

  /** The printer's emphasis codes have no width; the preview shows them as the bold they are. */
  private static String showEmphasis(String escaped) {
    return escaped
        .replace(InvoiceTextRenderer.BOLD_ON, "<b>")
        .replace(InvoiceTextRenderer.BOLD_OFF, "</b>")
        .replace(InvoiceTextRenderer.CONDENSED_ON, "<span class=\"sm\">")
        .replace(InvoiceTextRenderer.CONDENSED_OFF, "</span>");
  }
}
