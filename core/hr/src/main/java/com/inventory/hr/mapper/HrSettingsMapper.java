package com.inventory.hr.mapper;

import com.inventory.hr.domain.model.Holiday;
import com.inventory.hr.domain.model.HrSettings;
import com.inventory.hr.domain.model.LeaveType;
import com.inventory.hr.rest.dto.response.HolidayResponse;
import com.inventory.hr.rest.dto.response.HrSettingsResponse;
import com.inventory.hr.rest.dto.response.LeaveTypeResponse;
import java.util.List;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

/** Settings, holidays and leave types to their responses. Defaults are filled in by the service. */
@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface HrSettingsMapper {

  @Mapping(target = "saved", ignore = true)
  @Mapping(target = "currentWeeklyOffDays", ignore = true)
  @Mapping(target = "attendanceCapture", ignore = true)
  HrSettingsResponse toResponse(HrSettings settings);

  HrSettingsResponse.WeeklyOffEntryDto toDto(HrSettings.WeeklyOffEntry entry);

  HolidayResponse toResponse(Holiday holiday);

  List<HolidayResponse> toHolidayResponses(List<Holiday> holidays);

  LeaveTypeResponse toResponse(LeaveType leaveType);

  List<LeaveTypeResponse> toLeaveTypeResponses(List<LeaveType> leaveTypes);
}
