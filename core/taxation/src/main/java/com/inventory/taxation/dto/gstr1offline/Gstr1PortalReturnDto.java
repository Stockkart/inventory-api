package com.inventory.taxation.dto.gstr1offline;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * Offline GSTR-1 JSON shaped like the GST utility / portal return file
 * ({@code gstin}, {@code fp}, {@code b2b}, {@code b2cs}, {@code hsn}, {@code doc_issue}).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonPropertyOrder({
    "gstin", "fp", "version", "hash", "b2b", "b2cs", "hsn", "doc_issue"
})
public class Gstr1PortalReturnDto {

  private String gstin;
  /** Financial period MMyyyy */
  private String fp;
  private String version;
  private String hash;

  @Builder.Default
  private List<B2bByCtinDto> b2b = new ArrayList<>();

  @Builder.Default
  private List<B2csDto> b2cs = new ArrayList<>();

  private HsnAggregateDto hsn;

  @JsonProperty("doc_issue")
  private DocIssueOuterDto docIssue;

  @Data
  @Builder
  @NoArgsConstructor
  @AllArgsConstructor
  @JsonPropertyOrder({"ctin", "inv"})
  public static class B2bByCtinDto {
    private String ctin;
    @Builder.Default
    private List<B2bInvoiceDto> inv = new ArrayList<>();
  }

  @Data
  @Builder
  @NoArgsConstructor
  @AllArgsConstructor
  @JsonPropertyOrder({"inum", "idt", "val", "pos", "rchrg", "inv_typ", "itms"})
  public static class B2bInvoiceDto {
    private String inum;
    private String idt;
    private Double val;
    private String pos;
    private String rchrg;

    @JsonProperty("inv_typ")
    private String invTyp;

    @Builder.Default
    private List<B2bLineItemDto> itms = new ArrayList<>();
  }

  @Data
  @Builder
  @NoArgsConstructor
  @AllArgsConstructor
  @JsonPropertyOrder({"num", "itm_det"})
  public static class B2bLineItemDto {
    private int num;

    @JsonProperty("itm_det")
    private ItemDetDto itmDet;
  }

  /** Per GST schema: interstate uses iamt; intra uses camt/samt; cess always csamt when present */
  @Data
  @Builder
  @NoArgsConstructor
  @AllArgsConstructor
  @JsonInclude(JsonInclude.Include.NON_NULL)
  @JsonPropertyOrder({"txval", "rt", "iamt", "camt", "samt", "csamt"})
  public static class ItemDetDto {
    private Double txval;
    private Double rt;
    private Double iamt;
    private Double camt;
    private Double samt;

    @JsonProperty("csamt")
    private Double csamt;
  }

  @Data
  @Builder
  @NoArgsConstructor
  @AllArgsConstructor
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public static class B2csDto {
    @JsonProperty("sply_ty")
    private String splyTy;

    private Double rt;

    /** e.g. OE */
    private String typ;
    private String pos;
    private Double txval;
    /** Inter-state supplies */
    private Double iamt;

    private Double samt;

    private Double camt;

    @JsonProperty("csamt")
    private Double csamt;
  }

  @Data
  @Builder
  @NoArgsConstructor
  @AllArgsConstructor
  @JsonPropertyOrder({"hsn_b2b", "hsn_b2c"})
  public static class HsnAggregateDto {
    @JsonProperty("hsn_b2b")
    @Builder.Default
    private List<HsnSummaryRowDto> hsnB2b = new ArrayList<>();

    @JsonProperty("hsn_b2c")
    @Builder.Default
    private List<HsnSummaryRowDto> hsnB2c = new ArrayList<>();
  }

  @Data
  @Builder
  @NoArgsConstructor
  @AllArgsConstructor
  @JsonPropertyOrder({
      "num", "hsn_sc", "desc", "uqc", "qty", "rt", "txval",
      "iamt", "samt", "camt", "csamt"
  })
  public static class HsnSummaryRowDto {
    private int num;

    @JsonProperty("hsn_sc")
    private String hsnSc;

    private String desc;
    private String uqc;
    private Double qty;
    private Double rt;
    private Double txval;

    private Double iamt;
    private Double samt;
    private Double camt;

    @JsonProperty("csamt")
    private Double csamt;
  }

  @Data
  @Builder
  @NoArgsConstructor
  @AllArgsConstructor
  public static class DocIssueOuterDto {
    @JsonProperty("doc_det")
    @Builder.Default
    private List<DocDetBlockDto> docDet = new ArrayList<>();
  }

  @Data
  @Builder
  @NoArgsConstructor
  @AllArgsConstructor
  @JsonPropertyOrder({"doc_num", "doc_typ", "docs"})
  public static class DocDetBlockDto {
    @JsonProperty("doc_num")
    private int docNum;

    @JsonProperty("doc_typ")
    private String docTyp;

    @Builder.Default
    private List<DocIssueLineDto> docs = new ArrayList<>();
  }

  @Data
  @Builder
  @NoArgsConstructor
  @AllArgsConstructor
  @JsonPropertyOrder({"num", "from", "to", "totnum", "cancel", "net_issue"})
  public static class DocIssueLineDto {
    private int num;
    private String from;
    private String to;

    @JsonProperty("totnum")
    private Integer totnum;

    private Integer cancel;

    @JsonProperty("net_issue")
    private Integer netIssue;
  }
}
