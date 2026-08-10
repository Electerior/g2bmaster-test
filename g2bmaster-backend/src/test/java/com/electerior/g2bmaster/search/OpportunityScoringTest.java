package com.electerior.g2bmaster.search;

import static org.assertj.core.api.Assertions.assertThat;

import com.electerior.g2bmaster.search.OpportunityScoring.Opportunity;
import com.electerior.g2bmaster.search.OpportunityScoring.Stage;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code lib/scoring.js} 이식 검증.
 *
 * <p>가중치 자체가 실무 튜닝값이므로, 절대 점수보다 <b>판단이 뒤집히지 않는지</b>를 본다 —
 * 어떤 단계가 더 높은지, 규격잠금이 잡히는지, 마감 경과가 제외되는지.
 */
class OpportunityScoringTest {

	private static final DateTimeFormatter G2B = DateTimeFormatter.ofPattern("yyyyMMdd");

	private static String daysFromNow(int days) {
		return LocalDate.now().plusDays(days).format(G2B);
	}

	private static Map<String, Object> item(String... keyValues) {
		Map<String, Object> map = new LinkedHashMap<>();
		for (int i = 0; i < keyValues.length; i += 2) {
			map.put(keyValues[i], keyValues[i + 1]);
		}
		return map;
	}

	@Test
	@DisplayName("마감 경과 공고는 X 등급 0점으로 조기 반환된다")
	void expiredReturnsGradeX() {
		Opportunity result = OpportunityScoring.scoreOpportunity(
				item("bidNtceNm", "GPU 서버 구매", "bidClseDt", "19990101"), Stage.BID_ANNOUNCE);

		assertThat(result.score()).isZero();
		assertThat(result.grade()).isEqualTo("X");
		assertThat(result.action()).isEqualTo("마감제외");
		assertThat(result.summary()).isEqualTo("X 0점 · 마감제외");
	}

	@Test
	@DisplayName("같은 공고라도 사전규격 단계가 입찰공고 단계보다 높다 — 규격 개입 여지 때문")
	void preSpecOutranksBidAnnounce() {
		Map<String, Object> notice = item("bidNtceNm", "AI 서버 구매", "opninRgstClseDt", daysFromNow(10),
				"bidClseDt", daysFromNow(10));

		int preSpec = OpportunityScoring.scoreOpportunity(notice, Stage.PRE_SPEC).score();
		int bidAnnounce = OpportunityScoring.scoreOpportunity(notice, Stage.BID_ANNOUNCE).score();

		assertThat(preSpec).isGreaterThan(bidAnnounce);
	}

	@Test
	@DisplayName("입찰공고 렌탈은 일반구매보다 높다 — 일반구매는 -10")
	void rentalOutranksPlainPurchaseOnBidAnnounce() {
		int rental = OpportunityScoring.scoreOpportunity(
				item("bidNtceNm", "노트북 임대", "bidClseDt", daysFromNow(10)), Stage.BID_ANNOUNCE).score();
		int purchase = OpportunityScoring.scoreOpportunity(
				item("bidNtceNm", "노트북 구매", "bidClseDt", daysFromNow(10)), Stage.BID_ANNOUNCE).score();

		assertThat(rental).isGreaterThan(purchase);
	}

	@Test
	@DisplayName("Intel 고정 정황을 규격잠금으로 잡는다")
	void detectsIntelSpecLock() {
		var lock = OpportunityScoring.detectSpecLockRisks(
				item("bidNtceNm", "업무용 PC", "prdctDtlList", "Intel Core i7 프로세서 탑재"));

		assertThat(lock.warnings()).isNotEmpty();
		assertThat(lock.summary()).contains("Intel CPU 고정 의심");
	}

	@Test
	@DisplayName("특정 모델 지정 문구도 규격잠금이다")
	void detectsFixedModelClause() {
		var lock = OpportunityScoring.detectSpecLockRisks(
				item("bidNtceNm", "서버 구매", "ntceSpecCn", "상기 모델과 동등품 불가"));

		assertThat(lock.summary()).contains("특정 모델 제한 의심");
	}

	@Test
	@DisplayName("브랜드 언급이 없으면 오픈 스펙 가점을 준다")
	void openSpecGetsBonus() {
		Opportunity result = OpportunityScoring.scoreOpportunity(
				item("bidNtceNm", "서버 구매", "opninRgstClseDt", daysFromNow(10)), Stage.PRE_SPEC);

		assertThat(result.reasons()).anyMatch(r -> r.contains("오픈 스펙"));
	}

	@Test
	@DisplayName("담당자 전화와 이메일이 모두 있으면 가점")
	void contactabilityBonus() {
		int both = OpportunityScoring.scoreOpportunity(
				item("bidNtceNm", "서버", "ntceInsttOfclTelNo", "02-000-0000",
						"ntceInsttOfclEmailAdrs", "a@b.kr"), Stage.BID_PLAN).score();
		int neither = OpportunityScoring.scoreOpportunity(item("bidNtceNm", "서버"), Stage.BID_PLAN).score();

		assertThat(both).isEqualTo(neither + 5);
	}

	@Test
	@DisplayName("빈 문자열 연락처는 있는 것으로 치지 않는다")
	void blankContactIsNotPresent() {
		int blank = OpportunityScoring.scoreOpportunity(
				item("bidNtceNm", "서버", "ntceInsttOfclTelNo", "  "), Stage.BID_PLAN).score();
		int none = OpportunityScoring.scoreOpportunity(item("bidNtceNm", "서버"), Stage.BID_PLAN).score();

		assertThat(blank).isEqualTo(none);
	}

	@Test
	@DisplayName("사유는 최대 4개까지만 내보낸다")
	void reasonsAreCapped() {
		Opportunity result = OpportunityScoring.scoreOpportunity(
				item("bidNtceNm", "AI 서버 임대", "prdctDtlList", "AMD EPYC, Intel Xeon 비교",
						"opninRgstClseDt", daysFromNow(10),
						"ntceInsttOfclTelNo", "02-000-0000",
						"ntceInsttOfclEmailAdrs", "a@b.kr"),
				Stage.PRE_SPEC);

		assertThat(result.reasons()).hasSizeLessThanOrEqualTo(4);
	}

	@Test
	@DisplayName("applyOpportunity 는 이전 스코어 필드를 지우고 다시 계산한다")
	void applyOpportunityClearsPreviousScore() {
		Map<String, Object> stale = item("bidNtceNm", "서버 구매");
		stale.put("_opportunityScore", 999);
		stale.put("_opportunityGrade", "S");

		List<Map<String, Object>> scored = OpportunityScoring.applyOpportunity(List.of(stale), Stage.BID_PLAN);

		assertThat(scored.getFirst().get("_opportunityScore")).isNotEqualTo(999);
		assertThat(scored.getFirst()).containsKey("_opportunitySummary");
	}

	@Test
	@DisplayName("opportunityText 는 _ 접두 필드를 제외한다 — 자기 출력을 입력으로 먹지 않기 위해")
	void opportunityTextIgnoresUnderscoreFields() {
		String text = OpportunityScoring.opportunityText(
				item("bidNtceNm", "서버", "_opportunityReasons", "GPU 핵심 품목"));

		assertThat(text).contains("서버").doesNotContain("gpu");
	}

	@Test
	@DisplayName("removeExpired 는 사전규격·입찰공고 단계에서만 걸러낸다")
	void removeExpiredOnlyForDeadlineStages() {
		List<Map<String, Object>> items = List.of(
				item("bidNtceNm", "지난 공고", "bidClseDt", "19990101"),
				item("bidNtceNm", "살아있는 공고", "bidClseDt", daysFromNow(5)));

		assertThat(OpportunityScoring.removeExpired(items, Stage.BID_ANNOUNCE)).hasSize(1);
		assertThat(OpportunityScoring.removeExpired(items, Stage.BID_PLAN)).hasSize(2);
	}

	@Test
	@DisplayName("sortByOpportunity 는 점수 내림차순, 동점이면 날짜 내림차순")
	void sortsByScoreThenDate() {
		Map<String, Object> low = item("bidNtceDt", "20260801");
		low.put("_opportunityScore", 10);
		Map<String, Object> highOld = item("bidNtceDt", "20260801");
		highOld.put("_opportunityScore", 80);
		Map<String, Object> highNew = item("bidNtceDt", "20260803");
		highNew.put("_opportunityScore", 80);

		List<Map<String, Object>> sorted =
				OpportunityScoring.sortByOpportunity(List.of(low, highOld, highNew), "bidNtceDt");

		assertThat(sorted.get(0)).isEqualTo(highNew);
		assertThat(sorted.get(1)).isEqualTo(highOld);
		assertThat(sorted.get(2)).isEqualTo(low);
	}
}
