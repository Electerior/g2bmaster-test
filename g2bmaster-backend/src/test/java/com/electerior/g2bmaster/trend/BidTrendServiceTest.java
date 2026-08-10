package com.electerior.g2bmaster.trend;

import static org.assertj.core.api.Assertions.assertThat;

import com.electerior.g2bmaster.trend.TrendProfiles.KeywordGroup;
import com.electerior.g2bmaster.trend.TrendProfiles.TrendProfile;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 트렌드 집계의 순수 부분.
 *
 * <p>원본({@code lib/bid-trends.js})은 협력자 7개를 받는 핸들러 팩토리라 {@code req}/{@code res}
 * 없이는 아무것도 부를 수 없었다. 서비스로 뒤집은 덕에 여기서 집계 규칙만 직접 검증한다 —
 * 이 뒤집기가 실제로 값을 하는지 보여 주는 테스트이기도 하다.
 */
class BidTrendServiceTest {

	private static Map<String, Object> item(Object... pairs) {
		Map<String, Object> map = new LinkedHashMap<>();
		for (int i = 0; i + 1 < pairs.length; i += 2) {
			map.put(String.valueOf(pairs[i]), pairs[i + 1]);
		}
		return map;
	}

	private static List<KeywordGroup> groups() {
		return TrendProfiles.of("product").keywordGroups();
	}

	// ── 프로파일 ────────────────────────────────────────────────────────────

	@Test
	void 프로파일은_셋이고_각각_12개_키워드_그룹을_갖는다() {
		assertThat(TrendProfiles.all()).containsOnlyKeys("product", "service", "construction");
		TrendProfiles.all().values().forEach(profile ->
				assertThat(profile.keywordGroups()).hasSize(12));
	}

	@Test
	void 프로파일마다_사업구분이_다르다() {
		assertThat(TrendProfiles.of("product").typeName()).isEqualTo("물품");
		assertThat(TrendProfiles.of("service").typeName()).isEqualTo("용역");
		assertThat(TrendProfiles.of("construction").typeName()).isEqualTo("공사");
		assertThat(TrendProfiles.of("unknown")).isNull();
	}

	// ── 그룹 확장 ───────────────────────────────────────────────────────────

	@Test
	void 그룹_라벨은_키워드_목록으로_펴진다() {
		// 라벨을 그대로 상류에 보내면 0건이 온다 — 'GPU/AI장비'가 공고명에 그대로 있을 리 없다.
		assertThat(BidTrendService.expandTerm("GPU/AI장비", groups()))
				.contains("gpu", "rtx", "nvidia");
	}

	@Test
	void 라벨이_아닌_검색어는_그대로_쓴다() {
		assertThat(BidTrendService.expandTerm("정수기", groups())).containsExactly("정수기");
		assertThat(BidTrendService.expandTerm("  ", groups())).isEmpty();
	}

	@Test
	void 확장_결과는_중복을_제거한다() {
		List<String> expanded = BidTrendService.expandTerms(
				List.of("GPU/AI장비", "gpu"), groups());
		assertThat(expanded).containsOnlyOnce("gpu");
	}

	@Test
	void AND_그룹은_키워드_하나만_걸려도_만족이다() {
		// 그렇지 않으면 그룹 검색이 '여섯 키워드를 모두 가진 공고'만 남긴다.
		boolean matched = BidTrendService.matchesTrendQuery(
				"AI 서버 구매", List.of("GPU/AI장비"), List.of(), List.of(), groups());
		assertThat(matched).isTrue();
	}

	@Test
	void NOT_도_그룹으로_확장된다() {
		boolean matched = BidTrendService.matchesTrendQuery(
				"프린터 토너 구매", List.of(), List.of(), List.of("프린터/복합기"), groups());
		assertThat(matched).isFalse();
	}

	@Test
	void OR_그룹이_하나도_안_걸리면_탈락한다() {
		assertThat(BidTrendService.matchesTrendQuery(
				"의자 구매", List.of(), List.of("PC/노트북"), List.of(), groups())).isFalse();
		assertThat(BidTrendService.matchesTrendQuery(
				"노트북 구매", List.of(), List.of("PC/노트북"), List.of(), groups())).isTrue();
	}

	// ── 금액 ────────────────────────────────────────────────────────────────

	@Test
	void 금액은_첫_번째로_값이_있는_필드에서_읽는다() {
		assertThat(BidTrendService.trendAmount(item("presmptPrce", "1,200,000"))).isEqualTo(1_200_000);
		assertThat(BidTrendService.trendAmount(item("presmptPrce", "", "asignBdgtAmt", "500000")))
				.isEqualTo(500_000);
		assertThat(BidTrendService.trendAmount(item())).isZero();
	}

	@Test
	void 추정가격이_0이면_예산액으로_슬쩍_대체하지_않는다() {
		// '0원으로 공고된 건'과 '추정가격 미공개'는 다른 사실이다.
		assertThat(BidTrendService.trendAmount(item("presmptPrce", "0", "asignBdgtAmt", "9999")))
				.isZero();
	}

	// ── 날짜 라벨 ───────────────────────────────────────────────────────────

	@Test
	void 날짜_라벨은_못_읽으면_미상이다() {
		assertThat(BidTrendService.dateLabel("20260722153000")).isEqualTo("2026-07-22");
		assertThat(BidTrendService.dateLabel("2026-07-22")).isEqualTo("2026-07-22");
		assertThat(BidTrendService.dateLabel("")).isEqualTo("미상");
		assertThat(BidTrendService.dateLabel(null)).isEqualTo("미상");
	}

	// ── 집계 ────────────────────────────────────────────────────────────────

	@Test
	void 같은_제목_기관의_공고는_하나로_접히고_건수가_남는다() {
		// 분할 발주가 그대로 세어지면 '이 기관이 갑자기 20건을 냈다'는 잘못된 신호가 된다.
		List<Map<String, Object>> notices = BidTrendService.uniqueTrendNotices(List.of(
				item("bidNtceNm", "노트북 구매", "ntceInsttNm", "○○청", "presmptPrce", "1000"),
				item("bidNtceNm", "노트북 구매", "ntceInsttNm", "○○청", "presmptPrce", "2000"),
				item("bidNtceNm", "모니터 구매", "ntceInsttNm", "○○청", "presmptPrce", "500")), 80);

		assertThat(notices).hasSize(2);
		Map<String, Object> merged = notices.get(0);
		assertThat(merged.get("bidNtceNm")).isEqualTo("노트북 구매");
		assertThat(merged.get("_sameTitleCount")).isEqualTo(2);
		assertThat(merged.get("_sameTitleAmount")).isEqualTo(3000L);
	}

	@Test
	void 키워드_집계는_한_공고가_여러_그룹에_잡히는_것을_허용한다() {
		List<Map<String, Object>> buckets = BidTrendService.groupedKeywordTrends(
				List.of(item("bidNtceNm", "AI 서버 구축", "presmptPrce", "1000")), groups());

		List<String> labels = buckets.stream().map(b -> String.valueOf(b.get("name"))).toList();
		assertThat(labels).contains("서버/스토리지", "GPU/AI장비");
	}

	@Test
	void 걸리지_않은_그룹은_집계에서_빠진다() {
		List<Map<String, Object>> buckets = BidTrendService.groupedKeywordTrends(
				List.of(item("bidNtceNm", "노트북 구매")), groups());

		assertThat(buckets).isNotEmpty();
		buckets.forEach(bucket -> assertThat(((Number) bucket.get("count")).longValue())
				.isGreaterThan(0));
	}

	@Test
	void 축약_항목은_화면이_쓰는_필드만_담는다() {
		Map<String, Object> compact = BidTrendService.compactTrendItem(item(
				"bidNtceNm", "노트북", "dminsttNm", "○○청", "presmptPrce", "1000",
				"bidNtceDtlCn", "아주 긴 본문"));

		assertThat(compact).containsOnlyKeys("bidNtceNo", "bidNtceNm", "ntceInsttNm", "presmptPrce",
				"bidNtceDt", "bidClseDt", "cntrctCnclsMthdNm", "_type",
				"_opportunityScore", "_opportunitySummary");
		assertThat(compact.get("ntceInsttNm")).isEqualTo("○○청");
	}

	@Test
	void 프로파일_키워드는_모두_소문자_비교에_견딘다() {
		TrendProfile service = TrendProfiles.of("service");
		boolean matched = BidTrendService.matchesTrendQuery(
				"ISP 수립 컨설팅", List.of("컨설팅"), List.of(), List.of(), service.keywordGroups());
		assertThat(matched).isTrue();
	}
}
