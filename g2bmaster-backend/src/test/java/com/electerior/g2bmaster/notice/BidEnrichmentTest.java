package com.electerior.g2bmaster.notice;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 공고 제한조건 요약 — "이 공고에 우리가 참여할 수 있는가"를 가르는 규칙.
 *
 * <p>{@code lib/bid-enrichment.js} 에는 자체 검증이 없었지만, 여기 규칙이 조용히 무너지면
 * 참여 불가 공고가 레이더 상단에 올라오는 형태로만 드러난다(오래 걸린다). 그래서
 * "플래그가 비어 있어도 텍스트로 잡는다"는 이 파일의 핵심 판단을 못 박아 둔다.
 */
class BidEnrichmentTest {

	private static Map<String, Object> item(Object... pairs) {
		Map<String, Object> map = new LinkedHashMap<>();
		for (int i = 0; i + 1 < pairs.length; i += 2) {
			map.put(String.valueOf(pairs[i]), pairs[i + 1]);
		}
		return map;
	}

	@Test
	void 기본_출처와_상태를_채운다() {
		Map<String, Object> enriched = BidEnrichment.enrichBidNotice(item("bidNtceNm", "노트북 구매"));

		assertThat(enriched.get("_source")).isEqualTo("g2b");
		assertThat(enriched.get("_sourceLabel")).isEqualTo("나라장터");
		assertThat(enriched.get("_noticeStatus")).isEqualTo("공고");
		assertThat(enriched.get("_isCancelled")).isEqualTo(false);
		assertThat(enriched).containsKey("_requirementSummary");
	}

	@Test
	void 이미_붙은_출처는_덮어쓰지_않는다() {
		Map<String, Object> enriched = BidEnrichment.enrichBidNotice(
				item("_source", "d2b", "bidNtceNm", "장비 구매"));

		assertThat(enriched.get("_sourceLabel")).isEqualTo("국방전자조달");
	}

	@Test
	void 계약방식은_하이픈_뒤_부연을_잘라_요약한다() {
		Map<String, Object> enriched = BidEnrichment.enrichBidNotice(
				item("cntrctCnclsMthdNm", "일반경쟁 - 총액", "sucsfbidMthdNm", "적격심사"));

		assertThat(enriched.get("_contractSummary")).isEqualTo("일반경쟁 / 적격심사");
	}

	@Test
	void 지역제한_플래그가_켜지면_요약에_지역이_들어간다() {
		Map<String, Object> enriched = BidEnrichment.enrichBidNotice(item(
				"bidNtceNm", "노트북 구매",
				"cmmnSpldmdCorpRgnLmtYn", "Y",
				"rgnLmtBidLocplcJdgmBssNm", "서울특별시"));

		assertThat(String.valueOf(enriched.get("_requirementSummary"))).contains("지역: 서울특별시");
	}

	@Test
	void 플래그가_비어_있어도_본문_텍스트로_업종제한을_잡는다() {
		// 실측에서 indstrytyLmtYn 이 비어 있는데 실제로는 업종제한이 걸린 공고가 흔하다.
		Map<String, Object> enriched = BidEnrichment.enrichBidNotice(item(
				"bidNtceNm", "구내통신 공사",
				"ntceInsttNm", "○○청",
				"bidNtceDtlCn", "정보통신공사업 등록업체에 한함"));

		String summary = String.valueOf(enriched.get("_requirementSummary"));
		assertThat(summary).contains("업종");
		assertThat(summary).contains("정보통신");
	}

	@Test
	void 직접생산_중소기업_조건을_요약한다() {
		Map<String, Object> enriched = BidEnrichment.enrichBidNotice(item(
				"bidNtceNm", "책상 구매",
				"bidNtceDtlCn", "중소기업자간 경쟁제품, 직접생산확인증명서 제출 필요"));

		String summary = String.valueOf(enriched.get("_requirementSummary"));
		assertThat(summary).contains("중소/직접생산");
	}

	@Test
	void 물품목록_문자열에서_품명과_코드를_뽑는다() {
		Map<String, Object> enriched = BidEnrichment.enrichBidNotice(item(
				"prdctClsfcLmtYn", "Y",
				"purchsObjPrdctList", "[1^43211503^노트북컴퓨터][2^43211508^태블릿]"));

		assertThat(String.valueOf(enriched.get("_requirementSummary")))
				.contains("노트북컴퓨터(43211503)");
	}

	@Test
	void 검색_haystack_은_고른_필드만_담는다() {
		// 전체 필드를 넣으면 URL·전화번호까지 걸려 '1234' 같은 검색어가 아무 공고나 잡는다.
		Map<String, Object> source = item(
				"bidNtceNm", "노트북 구매",
				"ntceInsttNm", "○○청",
				"ntceInsttOfclTelNo", "02-1234-5678",
				"bidNtceDtlUrl", "https://g2b.go.kr/1234");

		String haystack = BidEnrichment.bidSearchHaystack(source);
		assertThat(haystack).contains("노트북 구매", "○○청");
		assertThat(haystack).doesNotContain("1234-5678", "g2b.go.kr");
	}

	@Test
	void 품목_haystack_은_D2B_공고에서만_공고명을_포함한다() {
		Map<String, Object> g2b = item("_source", "g2b", "bidNtceNm", "노트북", "prdctClsfcNoNm", "컴퓨터");
		Map<String, Object> d2b = item("_source", "d2b", "bidNtceNm", "노트북");

		assertThat(BidEnrichment.bidItemHaystack(g2b)).isEqualTo("컴퓨터");
		assertThat(BidEnrichment.bidItemHaystack(d2b)).isEqualTo("노트북");
	}

	@Test
	void 목록_요약은_상한을_넘으면_외_N건으로_접는다() {
		String compact = BidEnrichment.compactList(
				java.util.List.of("가", "나", "다", "라", "마"), 3);
		assertThat(compact).isEqualTo("가 / 나 / 다 외 2건");
	}
}
