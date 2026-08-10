package com.electerior.g2bmaster.pricing;

import static org.assertj.core.api.Assertions.assertThat;

import com.electerior.g2bmaster.attachment.ParsedDocument;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 딜 분석 조립 — 시가(MarketPriceService)와 딜 계산(DealCalculator)이 하나의 응답으로
 * 엮이는지, 그리고 <b>모르는 값을 지어내지 않는지</b>를 본다.
 *
 * <p>나라장터 없이 표본을 손으로 주입해 검증한다 — 값이 틀리면 조회가 아니라 계산에서
 * 틀린 것이 드러난다.
 */
class DealAnalysisServiceTest {

	private final DealAnalysisService service = new DealAnalysisService();

	private static Map<String, Object> item() {
		return Map.of(
				"bidNtceNo", "20260101-00",
				"bidNtceNm", "GPU 서버 구매",
				"presmptPrce", "100000000",          // 예산 1억
				"dtilPrdctClsfcNo", "43201809");
	}

	@Test
	@SuppressWarnings("unchecked")
	void 시가와_딜을_엮어_낸다() {
		// 유사 낙찰 3건: 낙찰률 88·90·92 → 중앙 90%
		List<MarketPriceService.Award> awards = List.of(
				new MarketPriceService.Award("88000000", "88", "2026-07-01", "GPU 서버 구매"),
				new MarketPriceService.Award("90000000", "90", "2026-07-10", "GPU 서버 도입"),
				new MarketPriceService.Award("92000000", "92", "2026-07-20", "GPU 서버 구매 건"));

		// 단가 30만, 수량 200 → 원가 6천만. 예산 1억, 시세 90% → 예상낙찰 9천만.
		DealAnalysisService.Options opt = new DealAnalysisService.Options(null, "300000", "200", false, true, true);

		Map<String, Object> out = service.analyze(item(), opt, awards, null, null, null);

		assertThat(out.get("bidNtceNo")).isEqualTo("20260101-00");

		Map<String, Object> facts = (Map<String, Object>) out.get("facts");
		assertThat(facts.get("productName")).isEqualTo("GPU 서버 구매");
		assertThat(facts.get("budget")).isEqualTo(new BigDecimal("100000000"));

		Map<String, Object> market = (Map<String, Object>) out.get("market");
		assertThat(market.get("medianRate")).isEqualTo(new BigDecimal("90"));
		assertThat(market.get("expectedAward")).isEqualTo(new BigDecimal("90000000"));   // 1억 × 90%

		Map<String, Object> deal = (Map<String, Object>) out.get("deal");
		assertThat(deal.get("cost")).isEqualTo(new BigDecimal("60000000"));              // 30만 × 200
		assertThat(deal.get("hasCost")).isEqualTo(true);
		assertThat(deal.get("profitAtExpected")).isEqualTo(new BigDecimal("30000000"));  // 9천만 − 6천만
		assertThat(deal.get("unitCostSource")).isEqualTo("user");
		// 부품 추정은 아직 없다 — 있는 척하지 않는다.
		assertThat(out.get("estimatedUnitCost")).isNull();
		assertThat(out.get("note")).isNull();   // 단가를 사용자가 줬으므로 안내가 필요 없다
	}

	@Test
	@SuppressWarnings("unchecked")
	void 단가가_없으면_원가를_지어내지_않고_안내를_붙인다() {
		Map<String, Object> out = service.analyze(item(),
				new DealAnalysisService.Options(null, null, null, false, true, true), List.of(), null, null, null);

		Map<String, Object> deal = (Map<String, Object>) out.get("deal");
		assertThat(deal.get("cost")).isNull();
		assertThat(deal.get("hasCost")).isEqualTo(false);
		assertThat(deal.get("unitCostSource")).isNull();
		assertThat(out.get("estimatedUnitCost")).isNull();
		// 단가를 못 구했다는 사실을 화면에 알린다 — 조용한 null 은 "분석 실패"로 오해된다.
		assertThat((String) out.get("note")).contains("단가");   // 단가원이 없다는 안내
	}

	@Test
	@SuppressWarnings("unchecked")
	void 표본이_없으면_시가는_0이_아니라_모름이다() {
		Map<String, Object> out = service.analyze(item(),
				new DealAnalysisService.Options(null, null, null, false, true, true), List.of(), null, null, null);

		Map<String, Object> market = (Map<String, Object>) out.get("market");
		assertThat(market.get("sampleCount")).isEqualTo(0);
		assertThat(market.get("medianRate")).isNull();       // 0% 가 아니라 null
		assertThat(market.get("expectedAward")).isNull();
	}

	@Test
	@SuppressWarnings("unchecked")
	void 규격서가_있으면_spec_블록을_붙인다() {
		ParsedDocument spec = new ParsedDocument(
				"규격서.hwpx", ParsedDocument.DocumentFormat.HWPX, "NVIDIA H200 141GB\n메모리 512GB", 3, false);
		List<Map<String, String>> files = List.of(Map.of("name", "규격서.hwpx", "url", "https://g2b.go.kr/x"));

		Map<String, Object> out = service.analyze(item(),
				new DealAnalysisService.Options(null, null, null, true, true, true), List.of(), spec, files, null);

		Map<String, Object> specBlock = (Map<String, Object>) out.get("spec");
		assertThat(specBlock).isNotNull();
		assertThat((String) specBlock.get("text")).contains("NVIDIA H200");
		assertThat(specBlock.get("fileEntryCount")).isEqualTo(1);
	}

	@Test
	void 나라장터_낙찰결과_Map을_Award로_옮긴다() {
		List<Map<String, Object>> results = List.of(
				Map.of("sucsfbidAmt", "88000000", "sucsfbidRate", "88", "rlOpengDt", "202607011400",
						"bidNtceNm", "GPU 서버"));
		List<MarketPriceService.Award> awards = DealAnalysisService.awardsFromResults(results);

		assertThat(awards).hasSize(1);
		assertThat(awards.get(0).rate()).isEqualTo("88");
		assertThat(awards.get(0).date()).isEqualTo("2026-07-01");   // YYYYMMDDHHmm → YYYY-MM-DD
	}
}
