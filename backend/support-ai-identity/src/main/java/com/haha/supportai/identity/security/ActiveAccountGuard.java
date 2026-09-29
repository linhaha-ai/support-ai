package com.haha.supportai.identity.security;

import java.util.UUID;

import cn.dev33.satoken.stp.StpUtil;
import com.haha.supportai.data.identity.entity.po.AppUser;
import com.haha.supportai.data.identity.repository.AppUserRepository;
import com.haha.supportai.identity.entity.dto.AuthenticatedUser;
import com.haha.supportai.identity.exception.AccountUnavailableException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ActiveAccountGuard {

    private final AppUserRepository appUserRepository;

    public AuthenticatedUser currentUser() {
        return currentUser(false);
    }

    public AuthenticatedUser currentUserForUpdate() {
        return currentUser(true);
    }

    private AuthenticatedUser currentUser(boolean lockForUpdate) {
        try {
            UUID userId = UUID.fromString(StpUtil.getLoginIdAsString());
            AppUser user = (lockForUpdate
                    ? appUserRepository.findEnabledByIdForUpdate(userId)
                    : appUserRepository.findById(userId).filter(AppUser::isEnabled))
                    .orElseThrow(AccountUnavailableException::new);
            return AuthenticatedUser.from(user);
        } catch (IllegalArgumentException exception) {
            StpUtil.logout();
            throw new AccountUnavailableException();
        } catch (AccountUnavailableException exception) {
            StpUtil.logout();
            throw exception;
        }
    }
}
