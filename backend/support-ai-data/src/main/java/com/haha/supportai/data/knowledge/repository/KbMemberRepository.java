package com.haha.supportai.data.knowledge.repository;

import java.util.Optional;
import java.util.UUID;

import com.haha.supportai.data.knowledge.entity.po.KbMember;
import com.haha.supportai.data.knowledge.mapper.KbMemberMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class KbMemberRepository {

    private final KbMemberMapper kbMemberMapper;

    public KbMember insert(KbMember kbMember) {
        kbMemberMapper.insert(kbMember);
        return kbMember;
    }

    public Optional<KbMember> findByKbIdAndUserId(UUID kbId, UUID userId) {
        return kbMemberMapper.selectByKbIdAndUserId(kbId, userId);
    }
}
