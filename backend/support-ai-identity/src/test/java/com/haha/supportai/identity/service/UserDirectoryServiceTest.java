package com.haha.supportai.identity.service;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.haha.supportai.data.identity.entity.po.AppUser;
import com.haha.supportai.data.identity.enums.UserRoleEnum;
import com.haha.supportai.data.identity.repository.AppUserRepository;
import com.haha.supportai.identity.entity.vo.UserSummaryResponse;
import com.haha.supportai.identity.security.AdministratorGuard;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

@ExtendWith(MockitoExtension.class)
class UserDirectoryServiceTest {

    @Mock
    AppUserRepository appUserRepository;

    @Mock
    AdministratorGuard administratorGuard;

    @InjectMocks
    UserDirectoryService userDirectoryService;

    @Test
    void listsAllUsersWithoutExposingPasswordHashes() {
        AppUser enabledUser = user("support-a", true);
        AppUser disabledUser = user("support-b", false);
        given(appUserRepository.listForAdministration()).willReturn(List.of(enabledUser, disabledUser));

        List<UserSummaryResponse> users = userDirectoryService.listForAdministration();

        assertThat(users).extracting(UserSummaryResponse::getUsername)
                .containsExactly("support-a", "support-b");
        assertThat(users).extracting(UserSummaryResponse::isEnabled).containsExactly(true, false);
        assertThat(UserSummaryResponse.class.getDeclaredFields())
                .extracting(field -> field.getName())
                .doesNotContain("passwordHash");
        then(administratorGuard).should().requireAdministrator();
    }

    @Test
    void onlyReturnsEnabledUserForMemberAuthorization() {
        AppUser disabledUser = user("disabled-user", false);
        given(appUserRepository.findById(disabledUser.getId())).willReturn(Optional.of(disabledUser));

        assertThat(userDirectoryService.findEnabledForAdministration(disabledUser.getId())).isEmpty();

        then(administratorGuard).should().requireAdministrator();
    }

    private AppUser user(String username, boolean enabled) {
        OffsetDateTime now = OffsetDateTime.parse("2026-09-21T09:00:00+08:00");
        return new AppUser(UUID.randomUUID(), username, "not-returned-password-hash", "测试用户",
                UserRoleEnum.USER, enabled, now, now);
    }
}
