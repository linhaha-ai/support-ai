package com.haha.supportai.data.knowledge.repository;

import java.util.List;
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

    public List<KbMember> listByKbId(UUID kbId) {
        return kbMemberMapper.selectByKbId(kbId);
    }

    public KbMember upsert(KbMember kbMember) {
        kbMemberMapper.upsert(kbMember);
        return kbMember;
    }

    public boolean deleteByKbIdAndUserId(UUID kbId, UUID userId) {
        return kbMemberMapper.deleteByKbIdAndUserId(kbId, userId) > 0;
    }
}
