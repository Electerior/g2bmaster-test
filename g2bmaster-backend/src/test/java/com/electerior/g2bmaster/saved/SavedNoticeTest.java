package com.electerior.g2bmaster.saved;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 저장 공고의 순수 로직 검증 — 검색 텍스트 조립, 금액 파싱, limit 클램프, 낱말 분해.
 *
 * <p>DB 를 태우지 않는다. 여기서 지키려는 것은 SQL 이 아니라 <b>원본과 같은 파생값</b>이다.
 */
class SavedNoticeTest {

	private static JsonNode json(String text) {
		return JsonMapper.builder().build().readTree(text);
	}

	@Test
	@DisplayName("search_text — 가격표 품목명이 반드시 들어간다 (이게 없으면 저장 검색이 무의미하다)")
	void searchTextIncludesPriceRowNames() {
		JsonNode priceRows = json("""
				[
				  { "name": "GPU", "resolvedName": "RTX 5090" },
				  { "name": "CPU" },
				  { "resolvedName": "Xeon 6530" },
				  { "qty": 2 }
				]
				""");
		String text = SavedSearchText.build("서버 구매", "전자통신연구원", "요약본", "메모", priceRows);

		assertThat(text).contains("서버 구매", "전자통신연구원", "요약본", "메모",
				"GPU", "RTX 5090", "CPU", "Xeon 6530");
		// 원본 구분자: 공백·개행·공백
		assertThat(text).startsWith("서버 구매 \n 전자통신연구원");
	}

	@Test
	@DisplayName("search_text — null/빈 값은 빠지고, 가격표가 아니면 무시한다")
	void searchTextSkipsEmpties() {
		assertThat(SavedSearchText.build(null, "", "요약", null, json("{}")))
				.isEqualTo("요약");
		assertThat(SavedSearchText.build(null, null, null, null, null)).isEmpty();
	}

	@Test
	@DisplayName("search_text — 20만자에서 자른다")
	void searchTextTruncates() {
		String huge = "가".repeat(300_000);
		assertThat(SavedSearchText.build(huge, null, null, null, null))
				.hasSize(SavedSearchText.MAX_LENGTH);
	}

	@Test
	@DisplayName("amount — 숫자 아닌 문자를 걷어내고, 0 은 null 로 본다")
	void parseAmount() {
		assertThat(SavedNoticeService.parseAmount(json("{\"a\":\"1,234,000원\"}").path("a"))).isEqualTo(1_234_000L);
		assertThat(SavedNoticeService.parseAmount(json("{\"a\":123456}").path("a"))).isEqualTo(123_456L);
		assertThat(SavedNoticeService.parseAmount(json("{\"a\":\"\"}").path("a"))).isNull();
		assertThat(SavedNoticeService.parseAmount(json("{\"a\":0}").path("a"))).isNull();
		assertThat(SavedNoticeService.parseAmount(json("{}").path("a"))).isNull();
		assertThat(SavedNoticeService.parseAmount(json("{\"a\":\"미정\"}").path("a"))).isNull();
	}

	@Test
	@DisplayName("real_estimate — 추정가 × 1.1 을 반올림한다")
	void realEstimate() {
		// Math.round 는 JS 와 같은 '0.5 는 위로'. 원본 계산식을 그대로 옮긴 값이다.
		assertThat(Math.round(1_000_000L * 1.1)).isEqualTo(1_100_000L);
		assertThat(Math.round(1_234_567L * 1.1)).isEqualTo(1_358_024L);
	}

	@Test
	@DisplayName("limit — 1..500, 기본 100")
	void clampLimit() {
		assertThat(SavedNoticeService.clampLimit(null)).isEqualTo(100);
		assertThat(SavedNoticeService.clampLimit(0)).isEqualTo(100);
		assertThat(SavedNoticeService.clampLimit(-5)).isEqualTo(100);
		assertThat(SavedNoticeService.clampLimit(1)).isEqualTo(1);
		assertThat(SavedNoticeService.clampLimit(250)).isEqualTo(250);
		assertThat(SavedNoticeService.clampLimit(9999)).isEqualTo(500);
	}

	@Test
	@DisplayName("q — 공백으로 나누고 최대 8낱말")
	void parseTerms() {
		assertThat(SavedNoticeRepository.parseTerms("서버  GPU\t메모리"))
				.containsExactly("서버", "GPU", "메모리");
		assertThat(SavedNoticeRepository.parseTerms(null)).isEmpty();
		assertThat(SavedNoticeRepository.parseTerms("   ")).isEmpty();
		assertThat(SavedNoticeRepository.parseTerms("a b c d e f g h i j"))
				.hasSize(SavedNoticeRepository.MAX_TERMS)
				.containsExactly("a", "b", "c", "d", "e", "f", "g", "h");
	}
}
