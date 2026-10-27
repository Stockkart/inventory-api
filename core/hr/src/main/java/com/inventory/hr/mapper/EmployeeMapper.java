package com.inventory.hr.mapper;

import static com.inventory.hr.utils.EmployeeMaskingUtils.maskAccount;
import static com.inventory.hr.utils.EmployeeMaskingUtils.maskUpi;

import com.inventory.hr.domain.model.Employee;
import com.inventory.hr.rest.dto.response.EmployeeResponse;
import com.inventory.hr.utils.HrDateUtils;
import com.inventory.hr.utils.SalaryRevisionUtils;
import org.mapstruct.AfterMapping;
import org.mapstruct.Context;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;
import org.mapstruct.ReportingPolicy;

/**
 * Employee to response with server-side masking (R4.5, R4.13). Mapping copies everything; the
 * {@link AfterMapping} step then withholds what the caller may not see.
 */
@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
public abstract class EmployeeMapper {

  /**
   * How to mask one response.
   *
   * @param canViewSalaries the caller holds VIEW_SALARIES
   * @param listRow a row of the employee list (bank and UPI are always masked there)
   */
  public record MaskContext(boolean canViewSalaries, boolean listRow) {}

  /** Detail view: full values with VIEW_SALARIES, masked without. */
  public EmployeeResponse toDetail(Employee employee, boolean canViewSalaries) {
    return toResponse(employee, new MaskContext(canViewSalaries, false));
  }

  /** List row: bank account and UPI always masked; pay only with VIEW_SALARIES. */
  public EmployeeResponse toListRow(Employee employee, boolean canViewSalaries) {
    return toResponse(employee, new MaskContext(canViewSalaries, true));
  }

  @Mapping(target = "payType", ignore = true)
  @Mapping(target = "salaryAmount", ignore = true)
  @Mapping(target = "payHidden", ignore = true)
  public abstract EmployeeResponse toResponse(Employee employee, @Context MaskContext context);

  public abstract EmployeeResponse.SalaryRevisionResponse toRevisionResponse(
      Employee.SalaryRevision revision);

  @AfterMapping
  protected void applyMasking(Employee source, @MappingTarget EmployeeResponse target,
      @Context MaskContext context) {
    if (!context.canViewSalaries()) {
      // no VIEW_SALARIES: no amount, pay type, revision history, method or IFSC at all; account
      // and UPI keep just enough to recognise them (last 4 digits, first 2 letters + domain)
      target.setSalaryRevisions(null);
      target.setPayType(null);
      target.setSalaryAmount(null);
      target.setPaymentMethod(null);
      target.setIfsc(null);
      target.setBankAccountNumber(maskAccount(source.getBankAccountNumber()));
      target.setUpiId(maskUpi(source.getUpiId()));
      target.setPayHidden(true);
      return;
    }
    Employee.SalaryRevision current =
        SalaryRevisionUtils.revisionFor(source.getSalaryRevisions(), HrDateUtils.currentPeriod());
    if (current != null) {
      target.setPayType(current.getPayType());
      target.setSalaryAmount(current.getAmount());
    }
    target.setPayHidden(false);
    if (context.listRow()) {
      // lists are glanced at over a shoulder; full details only on the detail page
      target.setBankAccountNumber(maskAccount(source.getBankAccountNumber()));
      target.setUpiId(maskUpi(source.getUpiId()));
    }
  }
}
