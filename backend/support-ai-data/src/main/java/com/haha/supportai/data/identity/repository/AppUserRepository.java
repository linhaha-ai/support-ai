package com.haha.supportai.data.identity.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.haha.supportai.data.identity.entity.po.AppUser;
import com.haha.supportai.data.identity.mapper.AppUserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class AppUserRepository {

    private final AppUserMapper appUserMapper;

    public Optional<AppUser> findByUsername(String username) {
        return Optional.ofNullable(appUserMapper.selectOne(Wrappers.<AppUser>lambdaQuery()
                .eq(AppUser::getUsername, username)));
    }

    public Optional<AppUser> findById(UUID id) {
        return Optional.ofNullable(appUserMapper.selectById(id));
    }

    public List<AppUser> listForAdministration() {
        return appUserMapper.selectList(Wrappers.<AppUser>lambdaQuery()
                .orderByAsc(AppUser::getUsername)
                .orderByAsc(AppUser::getId));
    }

    public boolean isEnabled(UUID id) {
        return appUserMapper.selectCount(Wrappers.<AppUser>lambdaQuery()
                .eq(AppUser::getId, id)
                .eq(AppUser::isEnabled, true)) > 0;
    }
}
