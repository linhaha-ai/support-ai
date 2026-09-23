package com.haha.supportai.knowledge.support;

import java.util.List;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RetrievalKeywordExtractorTest {

    private final RetrievalKeywordExtractor extractor = new RetrievalKeywordExtractor();

    @Test
    void extractsExactErrorCodeApiPathAndBusinessIdentifier() {
        List<String> patterns = extractor.extractPatterns(
                "PAY-CB-1001 和 /api/v1/orders/{merchantOrderNo} 中 merchantRefundNo 分别是什么？", 8);

        assertThat(matches(patterns, "错误码 PAY-CB-1001 表示签名失败")).isTrue();
        assertThat(matches(patterns, "错误码 PAY-CB-10010 表示其他失败")).isFalse();
        assertThat(matches(patterns, "调用 /api/v1/orders/{merchantOrderNo} 查询订单")).isTrue();
        assertThat(matches(patterns, "调用 /api/v1/orders/{merchantOrderNo}/close 关闭订单")).isTrue();
        assertThat(matches(patterns, "字段 merchantRefundNo 必填")).isTrue();
    }

    @Test
    void pathPatternDoesNotTreatLongerApiAsExactPath() {
        String pathPattern = extractor.extractPatterns("/api/v1/orders/{merchantOrderNo}", 8).get(0);

        assertThat(Pattern.compile(pathPattern).matcher("/api/v1/orders/{merchantOrderNo}".toLowerCase()).find()).isTrue();
        assertThat(Pattern.compile(pathPattern).matcher(
                "/api/v1/orders/{merchantOrderNo}/close".toLowerCase()).find()).isFalse();
    }

    @Test
    void methodAndPathPatternDistinguishesTheSamePathWithAnotherMethod() {
        String methodPathPattern = extractor.extractPatterns("POST /api/v1/orders", 8).get(0);

        assertThat(Pattern.compile(methodPathPattern).matcher("POST /api/v1/orders".toLowerCase()).find()).isTrue();
        assertThat(Pattern.compile(methodPathPattern).matcher("GET /api/v1/orders".toLowerCase()).find()).isFalse();
    }

    @Test
    void keepsNaturalLanguageOnSemanticRouteUnlessTermIsExplicitlyQuoted() {
        assertThat(extractor.extractPatterns("退款状态为什么没有更新", 8)).isEmpty();
        assertThat(extractor.extractPatterns("术语“退款处理中”是什么意思", 8))
                .singleElement()
                .satisfies(pattern -> assertThat(Pattern.compile(pattern).matcher("退款处理中需要等待回调").find()).isTrue());
    }

    @Test
    void limitsKeywordCountAndEscapesRegexCharacters() {
        List<String> patterns = extractor.extractPatterns("`A+B` E1001 E1002 E1003", 2);

        assertThat(patterns).hasSize(2);
        assertThat(matches(patterns, "A+B")).isTrue();
    }

    private boolean matches(List<String> patterns, String content) {
        String normalized = content.toLowerCase();
        return patterns.stream().anyMatch(pattern -> Pattern.compile(pattern).matcher(normalized).find());
    }
}
