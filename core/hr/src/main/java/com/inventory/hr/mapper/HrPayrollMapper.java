package com.inventory.hr.mapper;

import com.inventory.hr.domain.model.Advance;
import com.inventory.hr.domain.model.PayrollLine;
import com.inventory.hr.domain.model.PayrollRun;
import com.inventory.hr.domain.model.enums.SalaryPaymentStatus;
import com.inventory.hr.rest.dto.response.AdvanceResponse;
import com.inventory.hr.rest.dto.response.PayrollLineResponse;
import com.inventory.hr.rest.dto.response.PayrollRunResponse;
import com.inventory.hr.utils.PayCycle;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

/**
 * Advances, payroll runs and lines to responses. Every caller of these already holds
 * VIEW_SALARIES (MANAGE_ADVANCES, RUN_PAYROLL and RECORD_PAYMENTS require it), so nothing is
 * masked here; bank and UPI details are not on these documents.
 */
@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE,
    imports = {SalaryPaymentStatus.class, PayCycle.class})
public interface HrPayrollMapper {

  /** Employee name/code, recovered and outstanding are filled in by the service. */
  @Mapping(target = "employeeCode", ignore = true)
  @Mapping(target = "employeeName", ignore = true)
  @Mapping(target = "recovered", ignore = true)
  @Mapping(target = "outstanding", ignore = true)
  @Mapping(target = "journalPosted", ignore = true)
  AdvanceResponse toResponse(Advance advance);

  /** fromDate/toDate: as stored, else the calendar month (runs from before pay cycles). */
  @Mapping(target = "lines", ignore = true)
  @Mapping(target = "revision",
      expression = "java(run.getRevision() != null ? run.getRevision() : 0)")
  @Mapping(target = "fromDate", expression = "java(PayCycle.stored(run.getPeriod(),"
      + " run.getFromDate(), run.getToDate()).fromText())")
  @Mapping(target = "toDate", expression = "java(PayCycle.stored(run.getPeriod(),"
      + " run.getFromDate(), run.getToDate()).toText())")
  PayrollRunResponse toResponse(PayrollRun run);

  @Mapping(target = "frozen", expression = "java(line.getFrozenAt() != null)")
  @Mapping(target = "paymentStatus", expression = "java(line.getPaymentStatus() != null"
      + " ? line.getPaymentStatus() : SalaryPaymentStatus.PENDING)")
  @Mapping(target = "fromDate", expression = "java(PayCycle.stored(line.getPeriod(),"
      + " line.getFromDate(), line.getToDate()).fromText())")
  @Mapping(target = "toDate", expression = "java(PayCycle.stored(line.getPeriod(),"
      + " line.getFromDate(), line.getToDate()).toText())")
  PayrollLineResponse toResponse(PayrollLine line);
}
