package com.haha.supportai.conversation.service.context;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.haha.supportai.conversation.config.RagProperties;
import com.haha.supportai.conversation.entity.dto.PreparedChatRunResponse;
import com.haha.supportai.data.conversation.entity.po.CompletedChatTurn;
import com.haha.supportai.data.conversation.entity.po.HistoricalMessageSource;
import com.haha.supportai.data.conversation.enums.ChatRunStatusEnum;
import com.haha.supportai.data.conversation.enums.MessageSourceTypeEnum;
import com.haha.supportai.data.conversation.repository.ChatMessageRepository;
import com.haha.supportai.data.conversation.repository.MessageSourceRepository;
import com.haha.supportai.knowledge.entity.vo.DocumentSourceResponse;
import com.haha.supportai.knowledge.service.source.DocumentSourceQueryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
class ConversationContextServiceTest {

    private static final UUID SESSION_ID = UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final UUID USER_ID = UUID.fromString("10000000-0000-4000-8000-000000000002");
    private static final UUID CURRENT_USER_MESSAGE_ID = UUID.fromString("10000000-0000-4000-8000-000000000003");
    private static final UUID KB_ID = UUID.fromString("20000000-0000-4000-8000-000000000001");

    @Mock
    ChatMessageRepository chatMessageRepository;

    @Mock
    MessageSourceRepository messageSourceRepository;

    @Mock
    DocumentSourceQueryService documentSourceQueryService;

    private RagProperties properties;
    private ConversationContextService contextService;

    @BeforeEach
    void setUp() {
        properties = new RagProperties();
        properties.setHistoryMaxRounds(3);
        properties.setHistoryMaxCharacters(6000);
        contextService = new ConversationContextService(chatMessageRepository, messageSourceRepository,
                documentSourceQueryService, properties);
    }

    @Test
    void restoresChronologicalTurnsAndPreservesDistinctRootsForTheSameChunk() {
        CompletedChatTurn older = turn(1, "第一问", "第一答。[1]");
        CompletedChatTurn newer = turn(2, "第二问", "沿用旧资料。[2]，并参考新增资料。[1]");
        HistoricalMessageSource olderSource = historySource(1, 1, 1, true);
        HistoricalMessageSource repeatedSource = historySource(2, 2, 1, true);
        repeatedSource.setRootSourceId(uuid(42, 2));
        HistoricalMessageSource newerSource = historySource(3, 1, 2, true);
        given(chatMessageRepository.listCompletedTurnsBefore(SESSION_ID, USER_ID, CURRENT_USER_MESSAGE_ID, null, 20))
                .willReturn(List.of(newer, older));
        given(messageSourceRepository.listHistoricalSources(newer.getAssistantMessageId(), SESSION_ID, USER_ID))
                .willReturn(List.of(newerSource, repeatedSource));
        given(messageSourceRepository.listHistoricalSources(older.getAssistantMessageId(), SESSION_ID, USER_ID))
                .willReturn(List.of(olderSource));
        allowCurrentSource(olderSource);
        allowCurrentSource(newerSource);

        var context = contextService.load(preparedRun());

        assertThat(context.getTurns()).hasSize(2);
        assertThat(context.getTurns().get(0).getQuestion()).isEqualTo("第一问");
        assertThat(context.getTurns().get(0).getAnswer()).isEqualTo("第一答。[1]");
        assertThat(context.getTurns().get(1).getQuestion()).isEqualTo("第二问");
        assertThat(context.getTurns().get(1).getAnswer()).isEqualTo("沿用旧资料。[3]，并参考新增资料。[2]");
        assertThat(context.getSources()).hasSize(3);
        assertThat(context.getSources().get(0).getRootSourceId()).isEqualTo(olderSource.getRootSourceId());
        assertThat(context.getSources().get(1).getRootSourceId()).isEqualTo(newerSource.getRootSourceId());
        assertThat(context.getSources().get(2).getRootSourceId()).isEqualTo(repeatedSource.getRootSourceId());
    }

    @Test
    void excludesWholeTurnWhenAnySourceIsNotCurrent() {
        CompletedChatTurn turn = turn(1, "问题", "回答。[1][2]");
        HistoricalMessageSource valid = historySource(1, 1, 1, true);
        HistoricalMessageSource obsolete = historySource(2, 2, 2, true);
        given(chatMessageRepository.listCompletedTurnsBefore(SESSION_ID, USER_ID, CURRENT_USER_MESSAGE_ID, null, 20))
                .willReturn(List.of(turn));
        given(messageSourceRepository.listHistoricalSources(turn.getAssistantMessageId(), SESSION_ID, USER_ID))
                .willReturn(List.of(valid, obsolete));
        allowCurrentSource(valid);
        given(documentSourceQueryService.findReadableSource(USER_ID, obsolete.getChunkId()))
                .willReturn(Optional.of(documentSource(obsolete, false)));

        var context = contextService.load(preparedRun());

        assertThat(context.getTurns()).isEmpty();
        assertThat(context.getSources()).isEmpty();
    }

    @Test
    void excludesOversizedTurnWithoutTruncatingAndCanUseAnOlderCompleteTurn() {
        properties.setHistoryMaxCharacters(256);
        CompletedChatTurn oversized = turn(2, "新问题", "x".repeat(300));
        CompletedChatTurn fitting = turn(1, "旧问题", "完整旧回答。[1]");
        HistoricalMessageSource oversizedSource = historySource(2, 1, 2, true);
        HistoricalMessageSource fittingSource = historySource(1, 1, 1, true);
        given(chatMessageRepository.listCompletedTurnsBefore(SESSION_ID, USER_ID, CURRENT_USER_MESSAGE_ID, null, 20))
                .willReturn(List.of(oversized, fitting));
        given(messageSourceRepository.listHistoricalSources(oversized.getAssistantMessageId(), SESSION_ID, USER_ID))
                .willReturn(List.of(oversizedSource));
        given(messageSourceRepository.listHistoricalSources(fitting.getAssistantMessageId(), SESSION_ID, USER_ID))
                .willReturn(List.of(fittingSource));
        allowCurrentSource(oversizedSource);
        allowCurrentSource(fittingSource);

        var context = contextService.load(preparedRun());

        assertThat(context.getTurns()).singleElement().satisfies(turn -> {
            assertThat(turn.getQuestion()).isEqualTo("旧问题");
            assertThat(turn.getAnswer()).isEqualTo("完整旧回答。[1]");
        });
    }

    @Test
    void rejectsInvalidLineageBeforeReadingDocumentContent() {
        CompletedChatTurn turn = turn(1, "问题", "回答。[1]");
        HistoricalMessageSource invalid = historySource(1, 1, 1, false);
        given(chatMessageRepository.listCompletedTurnsBefore(SESSION_ID, USER_ID, CURRENT_USER_MESSAGE_ID, null, 20))
                .willReturn(List.of(turn));
        given(messageSourceRepository.listHistoricalSources(turn.getAssistantMessageId(), SESSION_ID, USER_ID))
                .willReturn(List.of(invalid));

        var context = contextService.load(preparedRun());

        assertThat(context.getTurns()).isEmpty();
        then(documentSourceQueryService).should(never()).findReadableSource(USER_ID, invalid.getChunkId());
    }

    @Test
    void continuesPagingWhenTheNewestCandidatePageContainsNoValidTurn() {
        List<CompletedChatTurn> invalidPage = new ArrayList<>();
        for (int number = 40; number >= 21; number--) {
            CompletedChatTurn invalidTurn = turn(number, "无效问题" + number, "无效回答。[1]");
            invalidPage.add(invalidTurn);
            given(messageSourceRepository.listHistoricalSources(invalidTurn.getAssistantMessageId(), SESSION_ID,
                    USER_ID)).willReturn(List.of(historySource(number, 1, number, false)));
        }
        CompletedChatTurn validOlderTurn = turn(1, "更早的有效问题", "更早的有效回答。[1]");
        HistoricalMessageSource validOlderSource = historySource(1, 1, 1, true);
        given(chatMessageRepository.listCompletedTurnsBefore(SESSION_ID, USER_ID, CURRENT_USER_MESSAGE_ID,
                null, 20)).willReturn(invalidPage);
        given(chatMessageRepository.listCompletedTurnsBefore(SESSION_ID, USER_ID, CURRENT_USER_MESSAGE_ID,
                invalidPage.getLast().getUserSequence(), 20)).willReturn(List.of(validOlderTurn));
        given(messageSourceRepository.listHistoricalSources(validOlderTurn.getAssistantMessageId(), SESSION_ID,
                USER_ID)).willReturn(List.of(validOlderSource));
        allowCurrentSource(validOlderSource);

        var context = contextService.load(preparedRun());

        assertThat(context.getTurns()).singleElement()
                .satisfies(turn -> assertThat(turn.getQuestion()).isEqualTo("更早的有效问题"));
    }

    private void allowCurrentSource(HistoricalMessageSource source) {
        given(documentSourceQueryService.findReadableSource(USER_ID, source.getChunkId()))
                .willReturn(Optional.of(documentSource(source, true)));
    }

    private DocumentSourceResponse documentSource(HistoricalMessageSource source, boolean current) {
        return new DocumentSourceResponse(source.getKnowledgeBaseId(), uuid(60, source.getCitationNo()),
                "排障手册", uuid(70, source.getCitationNo()), 1, source.getChunkId(), 0, "排障", 1, 0, 10,
                current, "资料正文");
    }

    private CompletedChatTurn turn(int number, String question, String answer) {
        return new CompletedChatTurn(uuid(30, number), uuid(31, number), uuid(32, number), number * 2L - 1,
                question, answer);
    }

    private HistoricalMessageSource historySource(int sourceNumber, int citationNumber, int chunkNumber,
            boolean lineageValid) {
        return new HistoricalMessageSource(uuid(40, sourceNumber), uuid(41, chunkNumber), citationNumber,
                MessageSourceTypeEnum.DOCUMENT, KB_ID, uuid(50, chunkNumber), "{}", "{}", lineageValid);
    }

    private PreparedChatRunResponse preparedRun() {
        return new PreparedChatRunResponse(uuid(10, 1), SESSION_ID, USER_ID, CURRENT_USER_MESSAGE_ID,
                uuid(11, 1), "当前问题", ChatRunStatusEnum.QUEUED, false);
    }

    private UUID uuid(int prefix, int number) {
        return UUID.fromString("%08d-0000-4000-8000-%012d".formatted(prefix, number));
    }
}
