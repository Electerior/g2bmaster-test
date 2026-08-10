package com.electerior.g2bmaster.search;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code lib/search.js} 이식 검증.
 *
 * <p>원본 모듈에는 파일 하단에 self-check 블록이 있었다. 그 단언들을 JUnit 으로 옮긴다.
 */
class SearchQueryTest {

	@Test
	@DisplayName("parseTerms — 콤마 구분, 공백 제거, 빈 항목 탈락")
	void parseTerms() {
		assertThat(SearchQuery.parseTerms("서버, GPU ,,메모리"))
				.containsExactly("서버", "GPU", "메모리");
		assertThat(SearchQuery.parseTerms(null)).isEmpty();
		assertThat(SearchQuery.parseTerms("  ")).isEmpty();
	}

	@Test
	@DisplayName("matchesQuery — AND 는 전부, OR 는 하나 이상, NOT 은 하나도")
	void matchesQuery() {
		String text = "GPU 서버 구매 공고";

		assertThat(SearchQuery.matchesQuery(text, List.of("GPU", "서버"), List.of(), List.of())).isTrue();
		assertThat(SearchQuery.matchesQuery(text, List.of("GPU", "노트북"), List.of(), List.of())).isFalse();
		assertThat(SearchQuery.matchesQuery(text, List.of(), List.of("노트북", "서버"), List.of())).isTrue();
		assertThat(SearchQuery.matchesQuery(text, List.of(), List.of("노트북", "태블릿"), List.of())).isFalse();
		assertThat(SearchQuery.matchesQuery(text, List.of(), List.of(), List.of("구매"))).isFalse();
	}

	@Test
	@DisplayName("matchesQuery — orTerms 가 비면 OR 조건이 없는 것으로 본다")
	void emptyOrTermsDoNotExcludeEverything() {
		assertThat(SearchQuery.matchesQuery("아무 텍스트", List.of(), List.of(), List.of())).isTrue();
	}

	@Test
	@DisplayName("matchesQuery — 대소문자 무시")
	void caseInsensitive() {
		assertThat(SearchQuery.matchesQuery("GPU Server", List.of("gpu"), List.of(), List.of())).isTrue();
	}

	@Test
	@DisplayName("g2bDateValue — 8/12/14 자리를 모두 받고, 깨진 값은 0")
	void g2bDateValue() {
		assertThat(SearchQuery.g2bDateValue("20260801")).isGreaterThan(0);
		assertThat(SearchQuery.g2bDateValue("202608011530")).isGreaterThan(SearchQuery.g2bDateValue("20260801"));
		assertThat(SearchQuery.g2bDateValue("2026-08-01 15:30")).isGreaterThan(0);
		assertThat(SearchQuery.g2bDateValue("")).isZero();
		assertThat(SearchQuery.g2bDateValue(null)).isZero();
		assertThat(SearchQuery.g2bDateValue("2026")).isZero();
	}

	@Test
	@DisplayName("isPastDeadline — 날짜만 있으면 그날 23:59 로 본다")
	void isPastDeadlineTreatsDateOnlyAsEndOfDay() {
		assertThat(SearchQuery.isPastDeadline("19990101")).isTrue();
		assertThat(SearchQuery.isPastDeadline("99991231")).isFalse();
		assertThat(SearchQuery.isPastDeadline(null)).isFalse();
		assertThat(SearchQuery.isPastDeadline("")).isFalse();
	}

	@Test
	@DisplayName("sortItems — 날짜 컬럼은 시각 순")
	void sortByDate() {
		List<Map<String, Object>> items = List.of(
				Map.of("bidNtceDt", "20260803", "bidNtceNm", "나"),
				Map.of("bidNtceDt", "20260801", "bidNtceNm", "가"),
				Map.of("bidNtceDt", "20260802", "bidNtceNm", "다"));

		assertThat(SearchQuery.sortItems(items, "bidNtceDt", "asc"))
				.extracting(m -> m.get("bidNtceNm"))
				.containsExactly("가", "다", "나");
		assertThat(SearchQuery.sortItems(items, "bidNtceDt", "desc"))
				.extracting(m -> m.get("bidNtceNm"))
				.containsExactly("나", "다", "가");
	}

	@Test
	@DisplayName("sortItems — 금액은 콤마가 섞여 있어도 숫자로 비교한다")
	void sortByMoneyString() {
		List<Map<String, Object>> items = List.of(
				Map.of("presmptPrce", "1,000,000"),
				Map.of("presmptPrce", "90,000"),
				Map.of("presmptPrce", "500,000"));

		assertThat(SearchQuery.sortItems(items, "presmptPrce", "asc"))
				.extracting(m -> m.get("presmptPrce"))
				.containsExactly("90,000", "500,000", "1,000,000");
	}

	@Test
	@DisplayName("sortItems — 정렬 키가 없으면 원본 순서를 그대로 돌려준다")
	void noSortKeyKeepsOrder() {
		List<Map<String, Object>> items = List.of(Map.of("a", "2"), Map.of("a", "1"));
		assertThat(SearchQuery.sortItems(items, null, "asc")).isSameAs(items);
	}

	@Test
	@DisplayName("bm25 — 희소 검색어를 포함한 문서가 앞으로 온다")
	void bm25RanksRareTermsHigher() {
		List<String> docs = List.of(
				"서버 구매 공고",
				"서버 구매 공고",
				"서버 구매 공고 GPU 가속기 도입",
				"서버 구매 공고");

		List<String> ranked = SearchQuery.bm25(List.of("GPU"), docs, d -> d);
		assertThat(ranked.getFirst()).contains("GPU");
	}

	@Test
	@DisplayName("bm25 — 한국어 복합어를 부분문자열로 센다")
	void bm25MatchesInsideCompoundWords() {
		List<String> docs = List.of("사무용품 구매", "서버용메모리 128GB 구매");

		List<String> ranked = SearchQuery.bm25(List.of("메모리"), docs, d -> d);
		assertThat(ranked.getFirst()).isEqualTo("서버용메모리 128GB 구매");
	}

	@Test
	@DisplayName("bm25 — 입력이 비면 원본을 그대로 돌려준다")
	void bm25NoOpOnEmptyInput() {
		List<String> docs = List.of("가", "나");
		assertThat(SearchQuery.bm25(List.of(), docs, d -> d)).isSameAs(docs);
		assertThat(SearchQuery.bm25(List.of("가"), List.<String>of(), d -> d)).isEmpty();
	}
}
