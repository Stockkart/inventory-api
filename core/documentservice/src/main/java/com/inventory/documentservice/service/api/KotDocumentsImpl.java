package com.inventory.documentservice.service.api;

import com.inventory.documentservice.api.KotDocument;
import com.inventory.documentservice.api.KotDocuments;
import com.inventory.documentservice.rest.dto.GenerateKotRequest;
import com.inventory.documentservice.rest.dto.KotItem;
import com.inventory.documentservice.service.KotPdfService;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * {@link KotDocuments} for other modules: copies the api record into the renderer's own request
 * class, field for field, and hands it to the existing KOT renderer unchanged.
 */
@Service
@RequiredArgsConstructor
public class KotDocumentsImpl implements KotDocuments {

  private final KotPdfService kotPdfService;

  @Override
  public byte[] pdf(KotDocument document) {
    return kotPdfService.generateKotPdf(toRequest(document));
  }

  /** Every field is set, nulls included, so the renderer sees exactly what the caller gave. */
  static GenerateKotRequest toRequest(KotDocument d) {
    if (d == null) {
      return null;
    }
    GenerateKotRequest r = new GenerateKotRequest();
    r.setKotNo(d.kotNo());
    r.setDepartment(d.department());
    r.setRoundNo(d.roundNo());
    r.setTableLabel(d.tableLabel());
    r.setTokenNo(d.tokenNo());
    r.setPrintedAt(d.printedAt());
    r.setStamp(d.stamp());
    r.setItems(items(d.items()));
    return r;
  }

  private static List<KotItem> items(List<KotDocument.Item> in) {
    if (in == null) {
      return null;
    }
    List<KotItem> out = new ArrayList<>(in.size());
    for (KotDocument.Item item : in) {
      if (item == null) {
        out.add(null);
        continue;
      }
      KotItem k = new KotItem();
      k.setName(item.name());
      k.setQuantity(item.quantity());
      k.setNote(item.note());
      out.add(k);
    }
    return out;
  }
}
