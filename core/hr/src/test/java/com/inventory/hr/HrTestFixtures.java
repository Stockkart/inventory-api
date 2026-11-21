package com.inventory.hr;

import com.inventory.hr.domain.model.Employee;
import com.inventory.hr.domain.model.enums.EmployeeStatus;
import com.inventory.hr.domain.model.enums.HrPaymentMethod;
import com.inventory.hr.domain.model.enums.HrPermission;
import com.inventory.hr.domain.model.enums.PayType;
import com.inventory.user.rest.dto.response.ShopAccessResponse;
import com.inventory.user.service.RbacService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Shared HR test data (T7). */
public final class HrTestFixtures {

  public static final String SHOP = "shop-1";
  public static final String USER = "user-1";

  private HrTestFixtures() {}

  /** Access as RbacService would resolve it; {@code hrModule} null = key absent. */
  public static ShopAccessResponse access(boolean owner, String role, Boolean hrModule) {
    Map<String, Boolean> modules = new HashMap<>();
    if (hrModule != null) {
      modules.put(RbacService.MODULE_HR, hrModule);
    }
    return ShopAccessResponse.builder().owner(owner).role(role).modules(modules).build();
  }

  public static Set<HrPermission> all() {
    return EnumSet.allOf(HrPermission.class);
  }

  public static Set<HrPermission> without(HrPermission... removed) {
    EnumSet<HrPermission> set = EnumSet.allOf(HrPermission.class);
    for (HrPermission p : removed) {
      set.remove(p);
    }
    return set;
  }

  /** An employee with every pay and payment field filled. */
  public static Employee employee(String id) {
    Employee e = new Employee();
    e.setId(id);
    e.setShopId(SHOP);
    e.setCode("EMP-001");
    e.setName("Ravi Kumar");
    e.setPhone("9876543210");
    e.setEmail("ravi@example.com");
    e.setDesignation("Helper");
    e.setJoinDate("2024-04-01");
    e.setStatus(EmployeeStatus.ACTIVE);
    List<Employee.SalaryRevision> revisions = new ArrayList<>();
    revisions.add(new Employee.SalaryRevision("2024-04", PayType.MONTHLY,
        new BigDecimal("14500.00"), Instant.EPOCH, USER));
    e.setSalaryRevisions(revisions);
    e.setPaymentMethod(HrPaymentMethod.BANK);
    e.setBankAccountNumber("123456789012");
    e.setIfsc("HDFC0001234");
    e.setUpiId("ravi.kumar@okaxis");
    e.setRevision(0L);
    return e;
  }
}
