package com.inventory.taxation.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.inventory.taxation.domain.gstr1.Gstr1ReportContext;
import com.inventory.taxation.domain.model.GstDocumentSummaryLine;
import com.inventory.taxation.domain.model.GstHsnLine;
import com.inventory.taxation.domain.model.GstInvoiceLine;
import com.inventory.taxation.dto.gstr1offline.Gstr1PortalReturnDto;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.MessageDigest;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Builds the offline GSTR-1 JSON matching the GST Return schema style used by the desktop utility /
 * GST portal uploads (same top-level layout as gstin/fp/version/hash/b2b/b2cs/hsn/doc_issue).
 */
@Service
public class Gstr1OfflinePortalJsonService {

  /** Offline tool compatibility version mirror (GST utility format string). */
  public static final String DEFAULT_OFFLINE_TOOL_VERSION = "GST3.2.3";

  private static final DateTimeFormatter INV_DATE = DateTimeFormatter.ofPattern("dd-MM-yyyy");

  private final ObjectMapper objectMapper;

  public Gstr1OfflinePortalJsonService(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper.copy();
    this.objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
  }

  public byte[] toJsonUtf8(Gstr1ReportContext ctx) {
    try {
      Gstr1PortalReturnDto dto = buildDto(ctx);
      dto.setHash(computePayloadHash(dto));
      return objectMapper.writeValueAsBytes(dto);
    } catch (Exception e) {
      throw new IllegalStateException("Failed to serialize GSTR-1 offline JSON", e);
    }
  }

  /** Build DTO (for testing or layered APIs). */
  public Gstr1PortalReturnDto buildDto(Gstr1ReportContext ctx) {
    String shopGst = ctx.getShopGstin() != null ? ctx.getShopGstin().trim().toUpperCase() : "";
    String sellerState = gstinPrefix(shopGst);

    Gstr1PortalReturnDto dto = new Gstr1PortalReturnDto();
    dto.setGstin(shopGst);
    dto.setFp(formatFp(ctx.getMonth(), ctx.getYear()));
    dto.setVersion(DEFAULT_OFFLINE_TOOL_VERSION);
    dto.setHash("");

    List<GstInvoiceLine> b2bSorted = ctx.getB2bLines() == null ? List.of() : ctx.getB2bLines().stream()
        .sorted(Comparator
            .comparing((GstInvoiceLine l) -> safe(l.getRecipientGstin()))
            .thenComparing(l -> safe(l.getInvoiceNo()))
            .thenComparing(l -> l.getInvoiceDate() != null ? l.getInvoiceDate() : java.time.LocalDate.MIN))
        .collect(Collectors.toList());

    dto.setB2b(buildB2b(b2bSorted, sellerState));

    dto.setB2cs(buildB2cs(ctx.getB2csLines(), shopGst));

    dto.setHsn(buildHsn(ctx));

    dto.setDocIssue(buildDocIssue(ctx.getDocLines()));
    return dto;
  }

  private List<Gstr1PortalReturnDto.B2bByCtinDto> buildB2b(List<GstInvoiceLine> sorted, String sellerState) {
    Map<String, List<GstInvoiceLine>> grouped = sorted.stream()
        .filter(l -> StringUtils.hasText(l.getRecipientGstin()))
        .collect(Collectors.groupingBy(
            l -> l.getRecipientGstin().trim().toUpperCase(),
            LinkedHashMap::new,
            Collectors.toList()));

    List<Gstr1PortalReturnDto.B2bByCtinDto> out = new ArrayList<>();
    for (Map.Entry<String, List<GstInvoiceLine>> e : grouped.entrySet()) {
      Gstr1PortalReturnDto.B2bByCtinDto ctinBlock = new Gstr1PortalReturnDto.B2bByCtinDto();
      ctinBlock.setCtin(e.getKey());
      List<Gstr1PortalReturnDto.B2bInvoiceDto> invoices = new ArrayList<>();
      for (GstInvoiceLine line : e.getValue()) {
        invoices.add(toB2bInvoice(line, sellerState));
      }
      ctinBlock.setInv(invoices);
      out.add(ctinBlock);
    }
    return out;
  }

  private Gstr1PortalReturnDto.B2bInvoiceDto toB2bInvoice(GstInvoiceLine line, String sellerState) {
    String pos = resolvePos(line.getRecipientGstin(), line.getPlaceOfSupply(), sellerState);
    boolean interstate = interstate(pos, sellerState);

    Gstr1PortalReturnDto.ItemDetDto det = buildItemDet(
        interstate,
        line.getTaxableValue(),
        nz(line.getRate()),
        nz(line.getIntegratedTaxAmount()),
        nz(line.getCentralTaxAmount()),
        nz(line.getStateTaxAmount()),
        nz(line.getCessAmount()));

    List<Gstr1PortalReturnDto.B2bLineItemDto> items = List.of(Gstr1PortalReturnDto.B2bLineItemDto.builder()
        .num(slabLineNum(line.getRate()))
        .itmDet(det)
        .build());

    return Gstr1PortalReturnDto.B2bInvoiceDto.builder()
        .inum(trimOrEmpty(line.getInvoiceNo()))
        .idt(line.getInvoiceDate() != null ? line.getInvoiceDate().format(INV_DATE) : "")
        .val(d(line.getInvoiceValue()))
        .pos(pos)
        .rchrg(trimOrBlank(line.getReverseCharge(), "N"))
        .invTyp(mapInvTypAbbrev(line.getInvoiceType()))
        .itms(new ArrayList<>(items))
        .build();
  }

  private Gstr1PortalReturnDto.ItemDetDto buildItemDet(boolean interstate,
      BigDecimal taxable,
      BigDecimal rate,
      BigDecimal igstStored,
      BigDecimal cgst,
      BigDecimal sgst,
      BigDecimal cess) {
    Double txval = d(taxable);
    Double rt = d(rate);

    Double cs = d(cess);
    if (interstate) {
      BigDecimal iamtBd = nz(igstStored).add(nz(cgst)).add(nz(sgst));
      return Gstr1PortalReturnDto.ItemDetDto.builder().txval(txval).rt(rt).iamt(d(iamtBd)).csamt(cs).build();
    }
    return Gstr1PortalReturnDto.ItemDetDto.builder()
        .txval(txval)
        .rt(rt)
        .camt(d(cgst))
        .samt(d(sgst))
        .csamt(cs)
        .build();
  }

  private List<Gstr1PortalReturnDto.B2csDto> buildB2cs(List<GstInvoiceLine> lines, String shopGstin) {
    if (lines == null || lines.isEmpty()) return new ArrayList<>();
    String seller = gstinPrefix(shopGstin);
    List<GstInvoiceLine> sorted = lines.stream().sorted(Comparator
        .comparing((GstInvoiceLine l) -> nySply(l, seller))
        .thenComparing(l -> safe(resolvePos(null, l.getPlaceOfSupply(), seller)))
        .thenComparing(l -> nz(l.getRate())))
        .collect(Collectors.toList());

    List<Gstr1PortalReturnDto.B2csDto> out = new ArrayList<>();
    for (GstInvoiceLine l : sorted) {
      String pos = resolvePos(null, l.getPlaceOfSupply(), seller);
      boolean inter = interstate(pos, seller);
      Gstr1PortalReturnDto.B2csDto.B2csDtoBuilder b = Gstr1PortalReturnDto.B2csDto.builder()
          .splyTy(inter ? "INTER" : "INTRA")
          .rt(d(nz(l.getRate())))
          .typ(StringUtils.hasText(l.getB2csType()) ? l.getB2csType().trim().toUpperCase() : "OE")
          .pos(pos)
          .txval(d(l.getTaxableValue()))
          .csamt(d(nz(l.getCessAmount())));
      if (inter) {
        BigDecimal iCombined = nz(l.getIntegratedTaxAmount()).add(nz(l.getCentralTaxAmount())).add(nz(l.getStateTaxAmount()));
        out.add(b.iamt(d(iCombined)).build());
      } else {
        out.add(b.camt(d(l.getCentralTaxAmount())).samt(d(l.getStateTaxAmount())).build());
      }
    }
    return out;
  }

  private Gstr1PortalReturnDto.HsnAggregateDto buildHsn(Gstr1ReportContext ctx) {
    Gstr1PortalReturnDto.HsnAggregateDto h = new Gstr1PortalReturnDto.HsnAggregateDto();
    h.setHsnB2b(mapHsnLines(ctx.getHsnB2bLines()));
    h.setHsnB2c(mapHsnLines(ctx.getHsnB2cLines()));
    return h;
  }

  private List<Gstr1PortalReturnDto.HsnSummaryRowDto> mapHsnLines(List<GstHsnLine> lines) {
    if (lines == null) return new ArrayList<>();
    ArrayList<GstHsnLine> sorted = new ArrayList<>(lines);
    sorted.sort(Comparator.comparing(GstHsnLine::getHsn, Comparator.nullsLast(String::compareToIgnoreCase)));

    int i = 1;
    List<Gstr1PortalReturnDto.HsnSummaryRowDto> rows = new ArrayList<>();
    for (GstHsnLine line : sorted) {
      Double qtyDouble = qtyAsDouble(line.getTotalQuantity());
      rows.add(Gstr1PortalReturnDto.HsnSummaryRowDto.builder()
          .num(i++)
          .hsnSc(normalizeHsnCode(line.getHsn()))
          .desc(line.getDescription() != null ? line.getDescription() : "")
          .uqc(normalizeUqc(line.getUqc()))
          .qty(qtyDouble)
          .rt(d(line.getRate()))
          .txval(d(line.getTaxableValue()))
          .iamt(d(line.getIntegratedTaxAmount()))
          .samt(d(line.getStateUtTaxAmount()))
          .camt(d(line.getCentralTaxAmount()))
          .csamt(d(line.getCessAmount()))
          .build());
    }
    return rows;
  }

  private Gstr1PortalReturnDto.DocIssueOuterDto buildDocIssue(List<GstDocumentSummaryLine> docLines) {
    Gstr1PortalReturnDto.DocIssueOuterDto outer = new Gstr1PortalReturnDto.DocIssueOuterDto();
    if (docLines == null || docLines.isEmpty()) {
      outer.setDocDet(new ArrayList<>());
      return outer;
    }
    List<Gstr1PortalReturnDto.DocDetBlockDto> blocks = new ArrayList<>();
    int docNum = 1;
    for (GstDocumentSummaryLine dl : docLines) {
      int cancel = dl.getCancelled() != null ? dl.getCancelled() : 0;
      int tot = dl.getTotalNumber() != null ? dl.getTotalNumber() : 0;
      Gstr1PortalReturnDto.DocIssueLineDto row = Gstr1PortalReturnDto.DocIssueLineDto.builder()
          .num(1)
          .from(dl.getSrNoFrom() != null ? dl.getSrNoFrom() : "")
          .to(dl.getSrNoTo() != null ? dl.getSrNoTo() : "")
          .totnum(tot)
          .cancel(cancel)
          .netIssue(tot - cancel)
          .build();
      blocks.add(Gstr1PortalReturnDto.DocDetBlockDto.builder()
          .docNum(docNum++)
          .docTyp(dl.getNatureOfDocument() != null ? dl.getNatureOfDocument() : "")
          .docs(List.of(row))
          .build());
    }
    outer.setDocDet(blocks);
    return outer;
  }

  private static String nySply(GstInvoiceLine l, String seller) {
    String pos = resolvePos(null, l.getPlaceOfSupply(), seller);
    return interstate(pos, seller) ? "1" : "0";
  }

  /** GST portal line numbering for rate slabs (501 for 5%, 1801 for 18%). */
  private static int slabLineNum(BigDecimal rate) {
    int r100 = nz(rate).multiply(BigDecimal.valueOf(100)).setScale(0, RoundingMode.HALF_UP).intValue();
    return Math.max(r100 + 1, 1);
  }

  /** MMyyyy filing period code. */
  private static String formatFp(int month, int year) {
    return String.format("%02d%d", month, year);
  }

  private static String gstinPrefix(String gstin) {
    if (!StringUtils.hasText(gstin) || gstin.length() < 2) return "";
    CharSequence s = gstin.substring(0, 2);
    if (Character.isDigit(gstin.charAt(0)) && Character.isDigit(gstin.charAt(1))) {
      return gstin.substring(0, 2).toUpperCase();
    }
    return "";
  }

  private static String resolvePos(String recipientGstin, String placeFallback, String sellerStateFallback) {
    if (StringUtils.hasText(recipientGstin) && recipientGstin.length() >= 2) {
      String prefix = gstinPrefix(recipientGstin.trim());
      if (prefix.length() == 2) return prefix;
    }
    if (StringUtils.hasText(placeFallback)) {
      String t = placeFallback.trim();
      if (t.length() >= 2 && Character.isDigit(t.charAt(0)) && Character.isDigit(t.charAt(1))) {
        return t.substring(0, 2).toUpperCase();
      }
    }
    return sellerStateFallback.length() == 2 ? sellerStateFallback : "";
  }

  private static boolean interstate(String posTwoDigit, String sellerStateTwoDigit) {
    if (!StringUtils.hasText(posTwoDigit) || posTwoDigit.length() != 2) return false;
    if (!StringUtils.hasText(sellerStateTwoDigit) || sellerStateTwoDigit.length() != 2) return false;
    return !posTwoDigit.equalsIgnoreCase(sellerStateTwoDigit);
  }

  private static String mapInvTypAbbrev(String invoiceType) {
    if (!StringUtils.hasText(invoiceType)) return "R";
    String t = invoiceType.toLowerCase();
    if (t.contains("sez") || t.contains("deemed")) return "R";
    return "R";
  }

  private static String normalizeHsnCode(String raw) {
    if (!StringUtils.hasText(raw) || raw.equalsIgnoreCase("0")) return "";
    String s = raw.trim();
    if (s.length() > 8) return s.substring(0, 8);
    return s;
  }

  private static String normalizeUqc(String uqc) {
    if (!StringUtils.hasText(uqc)) return "OTH";
    String u = uqc.trim().toUpperCase();
    int dash = u.indexOf('-');
    if (dash > 0) {
      String left = u.substring(0, dash).trim();
      return left.isBlank() ? "OTH" : left;
    }
    return u;
  }

  private static Double qtyAsDouble(BigDecimal q) {
    if (q == null) return 0d;
    return q.setScale(6, RoundingMode.HALF_UP).stripTrailingZeros().doubleValue();
  }

  private static Double d(BigDecimal bd) {
    if (bd == null) return 0d;
    return bd.setScale(2, RoundingMode.HALF_UP).doubleValue();
  }

  private static BigDecimal nz(BigDecimal b) {
    return b != null ? b : BigDecimal.ZERO;
  }

  private static String safe(String s) {
    return s != null ? s : "";
  }

  private static String trimOrEmpty(String s) {
    return s != null ? s.trim() : "";
  }

  private static String trimOrBlank(String v, String dflt) {
    if (!StringUtils.hasText(v)) return dflt;
    return v.trim();
  }

  /**
   * Computes a stable SHA-256 + Base64 hash of the JSON payload with hash blank.
   * This avoids self-referential hashing while still giving a deterministic integrity value.
   */
  private String computePayloadHash(Gstr1PortalReturnDto dto) {
    try {
      dto.setHash("");
      byte[] canonicalBytes = objectMapper.writeValueAsBytes(dto);
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] sha = digest.digest(canonicalBytes);
      return Base64.getEncoder().encodeToString(sha);
    } catch (Exception e) {
      throw new IllegalStateException("Failed to compute GSTR-1 JSON hash", e);
    }
  }
}
