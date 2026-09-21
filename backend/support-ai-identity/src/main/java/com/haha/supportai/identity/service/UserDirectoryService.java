package com.haha.supportai.identity.service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.haha.supportai.data.identity.entity.po.AppUser;
import com.haha.supportai.data.identity.repository.AppUserRepository;
import com.haha.supportai.identity.entity.vo.UserSummaryResponse;
import com.haha.supportai.identity.security.AdministratorGuard;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class UserDirectoryService {

    private final AppUserRepository appUserRepository;
    private final AdministratorGuard administratorGuard;

    public List<UserSummaryResponse> listForAdministration() {
        administratorGuard.requireAdministrator();
        return appUserRepository.listForAdministration().stream()
                .map(UserSummaryResponse::from)
                .toList();
    }

    public Optional<UserSummaryResponse> findEnabledForAdministration(UUID userId) {
        administratorGuard.requireAdministrator();
        return appUserRepository.findById(userId)
                .filter(AppUser::isEnabled)
                .map(UserSummaryResponse::from);
    }
}
