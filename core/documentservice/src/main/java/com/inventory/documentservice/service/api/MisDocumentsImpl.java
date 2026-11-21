package com.inventory.documentservice.service.api;

import com.inventory.documentservice.api.MisDocuments;
import com.inventory.documentservice.api.MisKpi;
import com.inventory.documentservice.api.MisSheet;
import com.inventory.documentservice.api.MisTabularDocument;
import com.inventory.documentservice.rest.dto.mis.MisDocumentKpi;
import com.inventory.documentservice.rest.dto.mis.MisDocumentSheet;
import com.inventory.documentservice.rest.dto.mis.MisTabularDocumentRequest;
import com.inventory.documentservice.service.mis.MisExcelDocumentService;
import com.inventory.documentservice.service.mis.MisPdfDocumentService;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * {@link MisDocuments} for other modules: copies the api record into the renderers' own request
 * class, field for field, and hands it to the existing Excel or PDF renderer unchanged.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class MisDocumentsImpl implements MisDocuments {

  private final MisExcelDocumentService misExcelDocumentService;
  private final MisPdfDocumentService misPdfDocumentService;

  @Override
  public byte[] excel(MisTabularDocument document) {
    // same line DocumentService#generateMisExcel wrote for analytics' exports
    log.info("Generating MIS Excel: {}", document != null ? document.title() : null);
    return misExcelDocumentService.generateExcel(toRequest(document));
  }

  @Override
  public byte[] pdf(MisTabularDocument document) {
    log.info("Generating MIS PDF: {}", document != null ? document.title() : null);
    return misPdfDocumentService.generatePdf(toRequest(document));
  }

  /**
   * Every field is set, nulls included, so the renderer sees exactly what the caller gave. The
   * renderers treat a null list and an empty list the same.
   */
  static MisTabularDocumentRequest toRequest(MisTabularDocument d) {
    if (d == null) {
      return null;
    }
    MisTabularDocumentRequest r = new MisTabularDocumentRequest();
    r.setTitle(d.title());
    r.setShopName(d.shopName());
    r.setPeriodLabel(d.periodLabel());
    r.setGeneratedAtLabel(d.generatedAtLabel());
    r.setKpis(kpis(d.kpis()));
    r.setColumns(d.columns());
    r.setRows(d.rows());
    r.setDetailSheetTitle(d.detailSheetTitle());
    r.setSecondarySheetTitle(d.secondarySheetTitle());
    r.setSecondaryColumns(d.secondaryColumns());
    r.setSecondaryRows(d.secondaryRows());
    r.setExtraSheets(sheets(d.extraSheets()));
    return r;
  }

  private static List<MisDocumentKpi> kpis(List<MisKpi> in) {
    if (in == null) {
      return null;
    }
    List<MisDocumentKpi> out = new ArrayList<>(in.size());
    for (MisKpi k : in) {
      out.add(k == null ? null : new MisDocumentKpi(k.label(), k.value()));
    }
    return out;
  }

  private static List<MisDocumentSheet> sheets(List<MisSheet> in) {
    if (in == null) {
      return null;
    }
    List<MisDocumentSheet> out = new ArrayList<>(in.size());
    for (MisSheet s : in) {
      out.add(s == null ? null : new MisDocumentSheet(s.title(), s.columns(), s.rows()));
    }
    return out;
  }
}
