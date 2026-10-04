package com.inventory.user.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.inventory.common.constants.PurchaseTaxTreatment;
import com.inventory.metrics.MetricsWrapper;
import com.inventory.user.domain.model.Vendor;
import com.inventory.user.domain.repository.ShopVendorRepository;
import com.inventory.user.domain.repository.VendorRepository;
import com.inventory.user.mapper.VendorMapper;
import com.inventory.user.rest.dto.request.UpdateVendorRequest;
import com.inventory.user.validation.VendorValidator;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class VendorServiceUpdateTaxTreatmentTest {

  @Mock private VendorRepository vendorRepository;
  @Mock private ShopVendorRepository shopVendorRepository;
  @Mock private VendorMapper vendorMapper;
  @Mock private VendorValidator vendorValidator;
  @Mock private MetricsWrapper metrics;
  @InjectMocks private VendorService vendorService;

  private final Vendor vendor = new Vendor();

  @BeforeEach
  void setUp() {
    vendor.setId("v1");
    when(shopVendorRepository.existsByShopIdAndVendorId("s1", "v1")).thenReturn(true);
    when(vendorRepository.findById("v1")).thenReturn(Optional.of(vendor));
    when(vendorRepository.save(any(Vendor.class))).thenAnswer(i -> i.getArgument(0));
  }

  @Test
  void anExplicitChoiceReplacesTheDefault() {
    vendor.setDefaultTaxTreatment(PurchaseTaxTreatment.EXCLUSIVE);
    UpdateVendorRequest request = new UpdateVendorRequest();
    request.setDefaultTaxTreatment(PurchaseTaxTreatment.INCLUSIVE);

    vendorService.updateVendor("v1", "s1", request);

    assertEquals(PurchaseTaxTreatment.INCLUSIVE, vendor.getDefaultTaxTreatment());
    verify(vendorRepository).save(vendor);
  }

  @Test
  void leavingItOutKeepsTheDefault() {
    vendor.setDefaultTaxTreatment(PurchaseTaxTreatment.INCLUSIVE);

    vendorService.updateVendor("v1", "s1", new UpdateVendorRequest());

    assertEquals(PurchaseTaxTreatment.INCLUSIVE, vendor.getDefaultTaxTreatment());
    verify(vendorRepository, never()).save(any(Vendor.class));
  }
}
