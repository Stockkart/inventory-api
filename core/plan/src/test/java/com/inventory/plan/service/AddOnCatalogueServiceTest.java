package com.inventory.plan.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.inventory.plan.domain.model.AddOn;
import com.inventory.plan.domain.repository.AddOnRepository;
import com.inventory.plan.mapper.AddOnMapper;
import com.inventory.plan.rest.dto.response.AddOnResponse;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AddOnCatalogueServiceTest {

  @Mock private AddOnRepository addOnRepository;
  @Mock private AddOnMapper addOnMapper;

  @InjectMocks private AddOnCatalogueService service;

  @Test
  void keepsTheActiveListBetweenCallsUntilEvicted() {
    AddOn addOn = AddOn.builder().code("EXTRA_USER").active(true).build();
    when(addOnRepository.findAll()).thenReturn(List.of(addOn));
    when(addOnMapper.toResponse(any(AddOn.class))).thenAnswer(inv -> new AddOnResponse());

    assertThat(service.listActive()).hasSize(1);
    service.listActive();
    verify(addOnRepository, times(1)).findAll();

    service.evictActive();
    service.listActive();
    verify(addOnRepository, times(2)).findAll();
  }
}
