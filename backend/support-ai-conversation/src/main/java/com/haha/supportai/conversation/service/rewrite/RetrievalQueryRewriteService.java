package com.haha.supportai.conversation.service.rewrite;

import java.util.List;
import java.util.regex.Pattern;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.haha.supportai.conversation.config.RagProperties;
import com.haha.supportai.conversation.entity.dto.RagHistoryTurn;
import com.haha.supportai.conversation.entity.dto.RetrievalQueryDiagnostics;
import com.haha.supportai.conversation.enums.RetrievalQueryRewriteStatusEnum;
import com.haha.supportai.conversation.service.rag.ChatModelGateway;
import com.haha.supportai.conversation.support.RetrievalQueryPromptFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class RetrievalQueryRewriteService {

    private static final Pattern CONTEXT_REFERENCE = Pattern.compile(
            "(这个|那个|上述|上面|前面|之前|刚才|它|这些|那些|"
                    + "该(?:错误|接口|问题|配置|服务|请求)|此(?:错误|接口|问题|配置|服务|请求))");
    private static final Pattern SHORT_FOLLOW_UP = Pattern.compile(
            "^\\s*(?:怎么解决|如何处理|怎么办|为什么呢|然后呢|接下来呢|"
                    + "还有哪些(?:参数|步骤|配置|原因)?|继续(?:说明|分析)?|详细一点)[？?。！!\\s]*$");
    private static final Pattern SELF_CONTAINED = Pattern.compile(
            "(?i)(HTTP\\s*[1-5]\\d{2}|(?:错误码|异常码|状态码)\\s*[:：]?\\s*[\\w-]+|"
                    + "\\b[A-Z][A-Z0-9]{0,9}[-_]?[0-9]{2,}\\b|"
                    + "(?:GET|POST|PUT|PATCH|DELETE)\\s+/[\\w/{}?=&.-]+|"
                    + "/[a-z0-9][\\w.-]*/[\\w/{}?=&.-]+|"
                    + "[\\w.]+(?:Exception|Error)\\b)");

    private final ChatModelGateway chatModelGateway;
    private final RetrievalQueryPromptFactory promptFactory;
    private final ObjectMapper objectMapper;
    private final RagProperties ragProperties;

    public RetrievalQueryDiagnostics rewrite(String originalQuestion, List<RagHistoryTurn> historyTurns) {
        String question = originalQuestion == null ? "" : originalQuestion.trim();
        List<RagHistoryTurn> safeHistory = historyTurns == null ? List.of() : historyTurns;
        if (!ragProperties.isQueryRewriteEnabled()) {
            return result(question, RetrievalQueryRewriteStatusEnum.NOT_ATTEMPTED);
        }
        if (safeHistory.isEmpty()) {
            return result(question, RetrievalQueryRewriteStatusEnum.NO_HISTORY);
        }
        if (!needsRewrite(question)) {
            return result(question, RetrievalQueryRewriteStatusEnum.SELF_CONTAINED);
        }
        try {
            String modelResult = chatModelGateway.generate(promptFactory.systemPrompt(),
                    promptFactory.userPrompt(question, safeHistory));
            if (modelResult != null
                    && modelResult.length() > ragProperties.getQueryRewriteMaxCharacters() + 64) {
                return result(question, RetrievalQueryRewriteStatusEnum.FALLBACK);
            }
            String rewritten = parseQuery(modelResult);
            if (!isValidQuery(rewritten)) {
                return result(question, RetrievalQueryRewriteStatusEnum.FALLBACK);
            }
            String normalized = rewritten.trim();
            if (normalized.equals(question)) {
                return result(question, RetrievalQueryRewriteStatusEnum.UNCHANGED);
            }
            return new RetrievalQueryDiagnostics(question, normalized, RetrievalQueryRewriteStatusEnum.REWRITTEN);
        } catch (RuntimeException exception) {
            return result(question, RetrievalQueryRewriteStatusEnum.FALLBACK);
        }
    }

    private boolean needsRewrite(String question) {
        boolean contextual = CONTEXT_REFERENCE.matcher(question).find()
                || SHORT_FOLLOW_UP.matcher(question).matches();
        return contextual && !SELF_CONTAINED.matcher(question).find();
    }

    private String parseQuery(String modelResult) {
        if (!StringUtils.hasText(modelResult)) {
            return null;
        }
        JsonNode root;
        try {
            root = objectMapper.reader()
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .readTree(modelResult);
        } catch (JsonProcessingException exception) {
            return null;
        }
        if (root == null || !root.isObject() || root.size() != 1 || !root.has("query")
                || !root.get("query").isTextual()) {
            return null;
        }
        return root.get("query").asText();
    }

    private boolean isValidQuery(String query) {
        if (!StringUtils.hasText(query) || query.length() > ragProperties.getQueryRewriteMaxCharacters()) {
            return false;
        }
        return query.chars().noneMatch(Character::isISOControl);
    }

    private RetrievalQueryDiagnostics result(String question, RetrievalQueryRewriteStatusEnum status) {
        return new RetrievalQueryDiagnostics(question, question, status);
    }
}
