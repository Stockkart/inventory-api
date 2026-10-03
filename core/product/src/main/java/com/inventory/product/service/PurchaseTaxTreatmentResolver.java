package com.inventory.product.service;

import com.inventory.common.constants.PurchaseTaxTreatment;
import com.inventory.user.domain.model.Vendor;
import com.inventory.user.domain.repository.VendorRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Whether a supplier bill's line amounts already contain the tax.
 *
 * <p>The bill decides; the vendor answers when the bill did not. A supplier's billing convention is
 * a property of their software rather than of any one invoice, so asking on every bill from the
 * same vendor would be asking a question already answered. Shared by stock-in and its preview so
 * both read a bill the same way.
 */
@Component
@RequiredArgsConstructor
public class PurchaseTaxTreatmentResolver {

  private final VendorRepository vendorRepository;

  /** The stated treatment, else the vendor's default, else null (read as exclusive). */
  public PurchaseTaxTreatment taxTreatmentFor(PurchaseTaxTreatment stated, String vendorId) {
    if (stated != null) {
      return stated;
    }
    if (!StringUtils.hasText(vendorId)) {
      return null;
    }
    return vendorRepository.findById(vendorId.trim())
        .map(Vendor::getDefaultTaxTreatment)
        .orElse(null);
  }
}
