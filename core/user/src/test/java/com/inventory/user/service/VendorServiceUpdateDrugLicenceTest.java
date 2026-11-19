package com.inventory.user.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.inventory.common.exception.ValidationException;
import com.inventory.common.gst.PostalAddress;
import com.inventory.metrics.MetricsWrapper;
import com.inventory.user.domain.model.Vendor;
import com.inventory.user.domain.repository.ShopVendorRepository;
import com.inventory.user.domain.repository.VendorRepository;
import com.inventory.user.mapper.VendorMapper;
import com.inventory.user.rest.dto.request.UpdateVendorRequest;
import com.inventory.user.utils.DrugLicence;
import com.inventory.user.validation.VendorValidator;
import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class VendorServiceUpdateDrugLicenceTest {

  private static final LocalDate EXPIRY = LocalDate.of(2028, 3, 31);

  @Mock private VendorRepository vendorRepository;
  @Mock private ShopVendorRepository shopVendorRepository;
  @Mock private VendorMapper vendorMapper;
  @Spy private VendorValidator vendorValidator = new VendorValidator();
  @Mock private MetricsWrapper metrics;
  @InjectMocks private VendorService vendorService;

  private final Vendor vendor = new Vendor();

  @BeforeEach
  void setUp() {
    vendor.setId("v1");
    vendor.setPostalAddress(PostalAddress.builder().stateCode("10").build());
    when(shopVendorRepository.existsByShopIdAndVendorId("s1", "v1")).thenReturn(true);
    when(vendorRepository.findById("v1")).thenReturn(Optional.of(vendor));
    when(vendorRepository.save(any(Vendor.class))).thenAnswer(i -> i.getArgument(0));
  }

  @Test
  void aNumberWithItsExpiryIsSaved() {
    UpdateVendorRequest request = new UpdateVendorRequest();
    request.setDlNo("20B/123");
    request.setDlExpiry(EXPIRY);

    vendorService.updateVendor("v1", "s1", request);

    assertEquals("20B/123", vendor.getDlNo());
    assertEquals(EXPIRY, vendor.getDlExpiry());
    verify(vendorRepository).save(vendor);
  }

  @Test
  void aNumberWithoutAnExpiryIsRefused() {
    UpdateVendorRequest request = new UpdateVendorRequest();
    request.setDlNo("20B/123");

    ValidationException e =
        assertThrows(ValidationException.class, () -> vendorService.updateVendor("v1", "s1", request));

    assertEquals(DrugLicence.EXPIRY_REQUIRED, e.getMessage());
    verify(vendorRepository, never()).save(any(Vendor.class));
  }

  @Test
  void clearingTheNumberClearsTheExpiryAndNeedsNone() {
    vendor.setDlNo("20B/123");
    vendor.setDlExpiry(EXPIRY);
    UpdateVendorRequest request = new UpdateVendorRequest();
    request.setDlNo("");

    vendorService.updateVendor("v1", "s1", request);

    assertNull(vendor.getDlNo());
    assertNull(vendor.getDlExpiry());
  }

  @Test
  void anOlderVendorWithoutAnExpiryCanStillBeEditedElsewhere() {
    vendor.setDlNo("20B/123");
    UpdateVendorRequest request = new UpdateVendorRequest();
    request.setName("Cipla Ltd");

    vendorService.updateVendor("v1", "s1", request);

    assertEquals("Cipla Ltd", vendor.getName());
    assertNull(vendor.getDlExpiry());
  }
}
