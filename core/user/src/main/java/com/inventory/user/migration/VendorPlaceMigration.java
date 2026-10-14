package com.inventory.user.migration;

import com.inventory.common.gst.Gstin;
import com.inventory.common.gst.PostalAddress;
import com.inventory.common.util.GstStateCode;
import com.inventory.user.domain.model.Vendor;
import com.inventory.user.domain.repository.VendorRepository;
import java.time.Instant;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Gives every existing vendor the state the tax code now reads ({@code postalAddress.stateCode}).
 *
 * <p>Vendors already carrying a state are left alone. For the rest the state comes from a valid
 * GSTIN, else from a state name found in the free-text address — the same two readings the old
 * interstate code made at every purchase, now written down once. Vendors with neither are logged
 * so the shop can fill them in; new bills from them stay local until then, as before.
 *
 * <p>Idempotent; disable with {@code stockkart.vendors.place-migration.enabled=false}.
 */
@Slf4j
@Component
public class VendorPlaceMigration {

  @Autowired private VendorRepository vendors;

  @Value("${stockkart.vendors.place-migration.enabled:true}")
  private boolean enabled;

  @EventListener(ApplicationReadyEvent.class)
  @Order(12)
  public void run() {
    if (!enabled) {
      return;
    }
    int placed = 0;
    int unplaceable = 0;
    try {
      List<Vendor> all = vendors.findAll();
      for (Vendor v : all) {
        if (v.getPostalAddress() != null && v.getPostalAddress().hasState()) {
          continue;
        }
        String code = Gstin.parse(v.getGstinUin()).map(Gstin::stateCode).orElse("");
        if (!StringUtils.hasText(code)) {
          code = GstStateCode.codeFromAddress(v.getAddress());
        }
        if (!StringUtils.hasText(code)) {
          unplaceable++;
          continue;
        }
        PostalAddress address = v.getPostalAddress() == null ? new PostalAddress() : v.getPostalAddress();
        address.setStateCode(code);
        if (!StringUtils.hasText(address.getLine1()) && StringUtils.hasText(v.getAddress())) {
          address.setLine1(v.getAddress().trim());
        }
        v.setPostalAddress(address);
        v.setUpdatedAt(Instant.now());
        vendors.save(v);
        placed++;
      }
    } catch (RuntimeException e) {
      log.error("[vendor-place] migration failed: {}", e.getMessage(), e);
      return;
    }
    if (placed > 0 || unplaceable > 0) {
      log.info("[vendor-place] state filled on {} vendors; {} have neither a GSTIN nor a state and need attention", placed, unplaceable);
    }
  }
}
