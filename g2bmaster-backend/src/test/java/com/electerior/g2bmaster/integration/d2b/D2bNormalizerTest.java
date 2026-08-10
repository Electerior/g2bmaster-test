package com.electerior.g2bmaster.integration.d2b;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * D2B 정규화 — 국방 공고가 검색 결과에서 사라지지 않게 하는 규칙들.
 *
 * <p>{@code lib/d2b-normalizer.js} 에는 자체 검증 블록이 없었다. 그런데 이 파일의 규칙은
 * "사라지면 조용히 사라지는" 종류(빈 날짜 항목 제외, 공고번호 충돌)라 회귀를 아무도 못 본다.
 * 그래서 원본 동작을 명시적으로 못 박아 둔다.
 */
class D2bNormalizerTest {

	private static Map<String, Object> item(Object... pairs) {
		Map<String, Object> map = new LinkedHashMap<>();
		for (int i = 0; i + 1 < pairs.length; i += 2) {
			map.put(String.valueOf(pairs[i]), pairs[i + 1]);
		}
		return map;
	}

	@Test
	void 날짜_포맷은_자릿수에_따라_분까지_또는_날짜까지다() {
		assertThat(D2bNormalizer.d2bFmtDate("202607221530")).isEqualTo("2026-07-22 15:30");
		assertThat(D2bNormalizer.d2bFmtDate("20260722")).isEqualTo("2026-07-22");
		assertThat(D2bNormalizer.d2bFmtDate("2026")).isEmpty();
		assertThat(D2bNormalizer.d2bFmtDate(null)).isEmpty();
	}

	@Test
	void 날짜를_못_읽으면_범위_안으로_본다() {
		// D2B는 공고일이 빈 항목이 실제로 있다. 범위 밖으로 보고 버리면 국방 공고가 통째로 사라진다.
		assertThat(D2bNormalizer.isDateInRange("", "20260701", "20260731")).isTrue();
		assertThat(D2bNormalizer.isDateInRange("20260715", "20260701", "20260731")).isTrue();
		assertThat(D2bNormalizer.isDateInRange("20260630", "20260701", "20260731")).isFalse();
		assertThat(D2bNormalizer.isDateInRange("20260801", "20260701", "20260731")).isFalse();
	}

	@Test
	void 시작일만_주어져도_종료일_판정은_건너뛴다() {
		assertThat(D2bNormalizer.isDateInRange("20261231", "20260701", "")).isTrue();
		assertThat(D2bNormalizer.isDateInRange("20260101", "", "20260731")).isTrue();
		assertThat(D2bNormalizer.isDateInRange("20260801", "", "20260731")).isFalse();
	}

	@Test
	void 공고번호는_기관_공고_차수를_합쳐_D2B_접두사를_붙인다() {
		// 접두사가 없으면 나라장터 번호와 우연히 겹쳐 중복 제거 단계에서 서로를 지운다.
		Map<String, Object> normalized = D2bNormalizer.normalizeD2bItem(
				item("orntCode", "1234", "pblancNo", "A0001", "pblancOdr", "02",
						"bidNm", "노트북 구매", "ornt", "제32사단", "busiDivs", "물품"),
				"getDmstcCmpetBidPblancList");

		assertThat(normalized.get("bidNtceNo")).isEqualTo("D2B-1234-A0001-02");
		assertThat(normalized.get("bidNtceOrd")).isEqualTo("02");
		assertThat(normalized.get("bidNtceNm")).isEqualTo("노트북 구매");
		assertThat(normalized.get("ntceInsttNm")).isEqualTo("제32사단");
		assertThat(normalized.get("_source")).isEqualTo("d2b");
		assertThat(normalized.get("_sourceLabel")).isEqualTo("국방전자조달");
		assertThat(normalized.get("_type")).isEqualTo("물품");
	}

	@Test
	void 오타_필드_pblanc0dr_도_차수로_읽는다() {
		// D2B 응답에 실제로 섞여 오는 오타(숫자 0). 원본이 둘 다 보므로 유지한다.
		Map<String, Object> normalized = D2bNormalizer.normalizeD2bItem(
				item("orntCode", "1", "dcsNo", "D9", "pblanc0dr", "03"), "getFcltyCmpetBidPblancList");

		assertThat(normalized.get("bidNtceNo")).isEqualTo("D2B-1-D9-03");
	}

	@Test
	void 공개수의는_상태가_공개수의로_떨어진다() {
		Map<String, Object> negotiation = D2bNormalizer.normalizeD2bItem(
				item("othbcNtatNm", "장비 임차"), "getDmstcOthbcVltrnNtatPlanList");
		Map<String, Object> competition = D2bNormalizer.normalizeD2bItem(
				item("bidNm", "장비 구매"), "getDmstcCmpetBidPblancList");

		assertThat(negotiation.get("_noticeStatus")).isEqualTo("공개수의");
		assertThat(competition.get("_noticeStatus")).isEqualTo("경쟁입찰");
	}

	@Test
	void 취소_공고를_상태_텍스트로_잡는다() {
		assertThat(D2bNormalizer.isCancelledNotice(item("progrsSttus", "입찰취소"))).isTrue();
		assertThat(D2bNormalizer.isCancelledNotice(item("bidNm", "노트북 구매(재공고)"))).isFalse();
		assertThat(D2bNormalizer.isCancelledNotice(item("pblancSe", "무효"))).isTrue();
	}

	@Test
	void 공개수의_오퍼레이션에는_날짜_파라미터를_붙이지_않는다() {
		// 붙이면 빈 응답이 온다. 그래서 날짜 필터는 조회 후에 다시 건다.
		Map<String, String> negotiation = D2bNormalizer.d2bParamsForOperation(
				"getDmstcOthbcVltrnNtatPlanList", "노트북", "20260701", "20260731");
		assertThat(negotiation).containsOnlyKeys("othbcNtatNm");

		Map<String, String> competition = D2bNormalizer.d2bParamsForOperation(
				"getDmstcCmpetBidPblancList", "노트북", "20260701", "20260731");
		assertThat(competition)
				.containsEntry("bidNm", "노트북")
				.containsEntry("anmtDateBegin", "20260701")
				.containsEntry("anmtDateEnd", "20260731");
	}
}
