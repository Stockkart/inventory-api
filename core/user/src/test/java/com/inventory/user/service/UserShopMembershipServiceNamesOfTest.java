package com.inventory.user.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyIterable;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.inventory.user.domain.model.UserAccount;
import com.inventory.user.domain.repository.UserAccountRepository;
import com.inventory.user.rest.dto.response.LoginContactDto;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Batched login names for HR history and audit rows. */
@ExtendWith(MockitoExtension.class)
class UserShopMembershipServiceNamesOfTest {

  @Mock private UserAccountRepository userAccountRepository;
  @InjectMocks private UserShopMembershipService service;

  @Test
  void looksAllNamesUpInOneRead() {
    UserAccount a = new UserAccount();
    a.setUserId("u1");
    a.setName("Asha");
    UserAccount nameless = new UserAccount();
    nameless.setUserId("u2");
    when(userAccountRepository.findAllById(anyIterable())).thenReturn(List.of(a, nameless));

    Map<String, String> names = service.namesOf(List.of("u1", "u2", "u1", "gone"));

    assertEquals(Map.of("u1", "Asha"), names);
    verify(userAccountRepository, times(1)).findAllById(anyIterable());
  }

  @Test
  void noIdsReadsNothing() {
    assertTrue(service.namesOf(List.of()).isEmpty());
    assertTrue(service.namesOf(null).isEmpty());
    verifyNoInteractions(userAccountRepository);
  }

  @Test
  void looksAllContactsUpInOneRead() {
    UserAccount a = new UserAccount();
    a.setUserId("u1");
    a.setName("Asha");
    a.setEmail("asha@example.com");
    a.setPhone("9000000001");
    UserAccount bare = new UserAccount();
    bare.setUserId("u2");
    when(userAccountRepository.findAllById(anyIterable())).thenReturn(List.of(a, bare));

    Map<String, LoginContactDto> contacts =
        service.contactsOf(List.of("u1", "u2", "u1", "gone"));

    assertEquals(Map.of(
        "u1", new LoginContactDto("u1", "Asha", "asha@example.com", "9000000001"),
        "u2", new LoginContactDto("u2", null, null, null)), contacts);
    verify(userAccountRepository, times(1)).findAllById(anyIterable());
  }

  @Test
  void noIdsReadsNoContacts() {
    assertTrue(service.contactsOf(List.of()).isEmpty());
    assertTrue(service.contactsOf(null).isEmpty());
    verifyNoInteractions(userAccountRepository);
  }
}
