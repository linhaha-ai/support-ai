package com.haha.supportai.data.assistant.repository;

import java.util.Optional;

import com.haha.supportai.data.assistant.entity.po.Ticket;
import com.haha.supportai.data.assistant.mapper.TicketMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class TicketRepository {

    private final TicketMapper ticketMapper;

    public Optional<Ticket> findByTicketNo(String ticketNo) {
        return ticketMapper.selectByTicketNo(ticketNo);
    }
}
