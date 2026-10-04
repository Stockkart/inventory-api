package com.inventory.product.service;

import com.inventory.common.constants.PurchaseTaxTreatment;
import com.inventory.product.rest.dto.request.CreateInventoryItemRequest;
import com.inventory.user.domain.model.Vendor;
import com.inventory.user.domain.repository.VendorRepository;
import java.math.BigDecimal;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Whether a supplier bill's line amounts already contain the tax.
 *
 * <p>In order: what the bill states; else what its lines show; else the vendor's usual
 * convention. The lines are read by cost against MRP. MRP always includes GST, so a cost equal to
 * the MRP has the tax inside it (the supplier billed at MRP), and a cost below the MRP does not
 * (the tax is added on top). Shared by stock-in and its preview so both read a bill the same way.
 */
@Component
@RequiredArgsConstructor
public class PurchaseTaxTreatmentResolver {

  /** Where the treatment applied to a bill came from. */
  public enum Source { STATED, LINES, VENDOR, NONE }

  /** The treatment applied, null read as exclusive, and where it came from. */
  public record Resolved(PurchaseTaxTreatment treatment, Source source) {}

  private final VendorRepository vendorRepository;

  public PurchaseTaxTreatment taxTreatmentFor(
      PurchaseTaxTreatment stated, String vendorId, List<CreateInventoryItemRequest> items) {
    return resolve(stated, vendorId, items).treatment();
  }

  public Resolved resolve(
      PurchaseTaxTreatment stated, String vendorId, List<CreateInventoryItemRequest> items) {
    if (stated != null) {
      return new Resolved(stated, Source.STATED);
    }
    PurchaseTaxTreatment fromLines = fromLines(items);
    if (fromLines != null) {
      return new Resolved(fromLines, Source.LINES);
    }
    if (StringUtils.hasText(vendorId)) {
      PurchaseTaxTreatment usual = vendorRepository.findById(vendorId.trim())
          .map(Vendor::getDefaultTaxTreatment)
          .orElse(null);
      if (usual != null) {
        return new Resolved(usual, Source.VENDOR);
      }
    }
    return new Resolved(null, Source.NONE);
  }

  /**
   * INCLUSIVE when every line that has both a cost and an MRP is costed at the MRP, EXCLUSIVE when
   * every such line is costed below it. Null when no line has both, or the lines disagree -- a bill
   * is one convention, so a mix means a line was keyed wrong and the lines cannot decide.
   */
  static PurchaseTaxTreatment fromLines(List<CreateInventoryItemRequest> items) {
    if (items == null) {
      return null;
    }
    int atMrp = 0;
    int belowMrp = 0;
    for (CreateInventoryItemRequest item : items) {
      BigDecimal cost = item.getCostPrice();
      BigDecimal mrp = item.getMaximumRetailPrice();
      if (cost == null || mrp == null || cost.signum() <= 0 || mrp.signum() <= 0) {
        continue;
      }
      int compared = cost.compareTo(mrp);
      if (compared == 0) {
        atMrp++;
      } else if (compared < 0) {
        belowMrp++;
      }
    }
    if (atMrp > 0 && belowMrp == 0) {
      return PurchaseTaxTreatment.INCLUSIVE;
    }
    if (belowMrp > 0 && atMrp == 0) {
      return PurchaseTaxTreatment.EXCLUSIVE;
    }
    return null;
  }
}
