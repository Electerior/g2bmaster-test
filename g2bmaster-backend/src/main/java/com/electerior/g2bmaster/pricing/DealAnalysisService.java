package com.electerior.g2bmaster.pricing;

import com.electerior.g2bmaster.attachment.ParsedDocument;
import com.electerior.g2bmaster.common.Numbers;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * {@code POST /api/deal-analysis} 의 조립부 — 시가({@link MarketPriceService})와
 * 딜 계산({@link DealCalculator})과 규격서({@link ParsedDocument})를 하나의 응답으로 엮는다.
 *
 * <p><b>순수 조립이다 — I/O 를 하지 않는다.</b> 낙찰 표본 조회(나라장터)·첨부 다운로드·
 * 부품 단가 추정은 컨트롤러가 하고, 결과만 이리로 넘긴다. 그래야 이 서비스는 나라장터 키
 * 없이도 단위 테스트로 검증되고, 값이 틀리면 조회가 아니라 계산에서 틀린 것이 드러난다.
 *
 * <p>응답 필드명은 프론트 계약({@code api/analysis.ts DealAnalysisResponse})이므로 그대로 둔다.
 * 아직 이식하지 않은 갈래({@code estimatedUnitCost}·{@code opening}·{@code d2bGw})는 <b>빈
 * 값이 아니라 명시적 {@code null} + {@code note}</b> 로 낸다 — 0 을 채우면 "단가 0원"이라는
 * 거짓말이 되고, 그 거짓말이 딜 계산으로 흘러든다.
 */
@Service
public class DealAnalysisService {

	/**
	 * 딜 분석 옵션. 컨트롤러가 요청 파라미터에서 채운다.
	 *
	 * @param deep           규격서 첨부까지 열어 부품·단가를 뽑는다(느림). 기본 켜짐
	 * @param includeMarket  시가(유사 낙찰) 갈래를 포함할지
	 * @param includeOpening 개찰결과(참여업체) 갈래를 포함할지
	 *
	 * <p>규격서(spec)·부품단가(estimatedUnitCost) 토글은 컨트롤러가 그 인자를 넘기느냐로
	 * 가른다 — 끄면 아예 조회하지 않으므로 여기까지 오지 않는다.
	 */
	public record Options(Object bidPrice, Object unitCost, Object quantity,
			boolean deep, boolean includeMarket, boolean includeOpening) {}

	/**
	 * @param item     공고 객체(나라장터 필드). facts 추출원
	 * @param awards   유사 낙찰 표본. 나라장터 키가 없으면 빈 목록 — 그래도 구조는 나온다
	 * @param spec     규격서 파싱 결과. {@code deep} 가 아니거나 첨부가 없으면 {@code null}
	 * @param specFiles 파싱한 첨부의 {name,url} 목록(화면 표시용)
	 */
	public Map<String, Object> analyze(Map<String, Object> item, Options options,
			List<MarketPriceService.Award> awards, ParsedDocument spec,
			List<Map<String, String>> specFiles, Map<String, Object> estimatedUnitCost) {

		Map<String, Object> it = item == null ? Map.of() : item;
		Options opt = options == null ? new Options(null, null, null, true, true, true) : options;

		// ── facts — "무엇을, 얼마에, 몇 개" ──────────────────────────────────
		String productName = firstString(it, "bidNtceNm", "prdctClsfcNoNm", "dtilPrdctClsfcNoNm", "bizNm");
		String productCode = firstString(it, "dtilPrdctClsfcNo", "prdctClsfcNo");
		BigDecimal budget = firstNumber(it, "presmptPrce", "asignBdgtAmt", "budget");
		// 수량·단가는 공고 API 에 잘 없다 — 요청 파라미터를 우선으로, 그다음 공고 필드.
		BigDecimal quantity = coalesce(Numbers.toNumber(opt.quantity()), firstNumber(it, "prdctQty", "quantity"));
		BigDecimal unitCost = Numbers.toNumber(opt.unitCost());
		BigDecimal unitPrice = firstNumber(it, "prdctUprc", "unitPrice");

		Map<String, Object> facts = new LinkedHashMap<>();
		facts.put("productName", productName == null ? "" : productName);
		facts.put("productCode", productCode == null ? "" : productCode);
		facts.put("quantity", quantity);
		facts.put("unitPrice", unitPrice);
		facts.put("budget", budget);

		// ── market — 유사 낙찰 표본의 시가 신호 (토글) ──────────────────────
		MarketPriceService.MarketSummary market = null;
		if (opt.includeMarket()) {
			List<MarketPriceService.Award> matched = MarketPriceService.matchAwards(
					productName, awards == null ? List.of() : awards);
			market = MarketPriceService.summarizeMarket(matched, budget);
		}

		// ── deal — 이 시세에서 이 원가로 남는가 ─────────────────────────────
		// 부품 추정으로 단가가 나왔으면 그것을 우선(source=estimated), 사용자 입력이면 user.
		// 단, 추정 단가는 규격서 원문 대조를 통과해야 쓴다 — 근거 없는 부품으로 선 단가는
		// 화면에서 정상적인 숫자로 보이고 그대로 손익·마진이 된다.
		UnitCostValidator.Verdict costVerdict =
				UnitCostValidator.validate(estimatedUnitCost, spec == null ? null : spec.text());
		BigDecimal estimatedUnit = costVerdict.confirmedMid();
		BigDecimal effectiveUnit = coalesce(unitCost, estimatedUnit);
		String unitCostSource = unitCost != null ? "user" : (estimatedUnit != null ? "estimated" : null);
		Object expectedAward = market != null ? market.expectedAward() : null;
		Object medianRate = market != null ? market.medianRate() : null;
		DealCalculator.Deal deal = DealCalculator.computeDeal(new DealCalculator.DealInput(
				budget, expectedAward, medianRate, effectiveUnit, quantity, opt.bidPrice()));

		// ── 조립 ────────────────────────────────────────────────────────────
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("bidNtceNo", firstString(it, "bidNtceNo"));
		out.put("score", null);   // 수주기회 점수는 OpportunityScoring 소관 — 이 표면 밖
		out.put("facts", facts);
		out.put("market", market != null ? marketMap(market) : null);
		out.put("opening", null);   // 개찰결과(참여업체)는 별도 조회 — 컨트롤러가 채운다
		out.put("deal", dealMap(deal, unitCostSource));
		out.put("simBidUsed", null);
		out.put("spec", specMap(spec, specFiles));
		out.put("estimatedUnitCost", annotate(estimatedUnitCost, costVerdict));
		out.put("d2bGw", null);

		// 단가원이 하나도 없으면(사용자 입력도, 부품 추정도) 그 사실을 알린다 — 조용한 null 은
		// "분석 실패"로 오해된다.
		if (effectiveUnit == null) {
			boolean rejected = costVerdict.indicativeMid() != null;
			out.put("note", !opt.deep()
					? "빠른 분석(deep=off)입니다 — 시가·예산 기반 딜 계산만 제공합니다. "
							+ "규격서 부품 단가는 deep 를 켜거나 단가를 직접 입력하세요."
					: rejected
							// 추정은 나왔지만 규격서와 대조해 채택하지 않았다. "못 찾았다"와 다른 사실이므로
							// 다르게 알린다 — 같은 문구를 쓰면 왜 숫자가 없는지 알 수 없다.
							? "추정된 부품이 규격서 내용과 일치하지 않아 단가를 확정하지 않았습니다. "
									+ "아래 부품 목록은 참고용이며, 단가를 직접 입력하면 원가·손익이 계산됩니다."
							: "규격서에서 부품 단가를 추정하지 못했습니다. 단가를 직접 입력하면 원가·손익이 계산됩니다.");
		}
		return out;
	}

	/**
	 * AI 의 추정 봉투에 <b>백엔드 검증 결과를 덧붙여</b> 돌려준다. 원본 필드는 지우지 않는다 —
	 * 화면이 "AI 는 이렇게 봤고 우리는 이만큼만 인정했다"를 함께 보여줄 수 있어야 한다.
	 *
	 * <p>덧붙는 것: {@code costConfidence}(confirmed|partial|untrusted) · {@code confirmedMid} ·
	 * {@code evidenceRatio} · {@code costWarnings[]}, 그리고 {@code breakdown} 각 행의
	 * {@code evidenceInSpec}·{@code acceptedForCost}·{@code rejectReason}.
	 */
	private static Map<String, Object> annotate(Map<String, Object> estimated,
			UnitCostValidator.Verdict verdict) {
		if (estimated == null) {
			return null;
		}
		Map<String, Object> out = new LinkedHashMap<>(estimated);
		out.put("costConfidence", verdict.confidence().name().toLowerCase(java.util.Locale.ROOT));
		out.put("confirmedMid", verdict.confirmedMid());
		out.put("evidenceRatio", Math.round(verdict.evidenceRatio() * 100) / 100.0);
		out.put("costWarnings", verdict.warnings());
		if (!verdict.rows().isEmpty()) {
			out.put("breakdown", verdict.rows());
		}
		return out;
	}

	// ── 응답 매핑 (필드명은 프론트 계약) ─────────────────────────────────────
	private static Map<String, Object> marketMap(MarketPriceService.MarketSummary m) {
		Map<String, Object> map = new LinkedHashMap<>();
		map.put("sampleCount", m.sampleCount());
		map.put("rateSampleCount", m.rateSampleCount());
		map.put("medianRate", m.medianRate());
		map.put("minRate", m.minRate());
		map.put("maxRate", m.maxRate());
		map.put("medianAmountRaw", m.medianAmountRaw());
		map.put("latestDate", m.latestDate());
		map.put("budget", m.budget());
		map.put("expectedAward", m.expectedAward());
		map.put("expectedSaving", m.expectedSaving());
		map.put("expectedSavingPct", m.expectedSavingPct());
		map.put("matchCount", m.sampleCount());
		map.put("usedBaseline", false);
		return map;
	}

	private static Map<String, Object> dealMap(DealCalculator.Deal d, String unitCostSource) {
		Map<String, Object> map = new LinkedHashMap<>();
		map.put("budget", d.budget());
		map.put("expectedAward", d.expectedAward());
		map.put("expectedRate", d.expectedRate());
		map.put("unitCost", d.unitCost());
		map.put("quantity", d.quantity());
		map.put("cost", d.cost());
		map.put("hasCost", d.hasCost());
		map.put("profitAtBudget", d.profitAtBudget());
		map.put("profitAtExpected", d.profitAtExpected());
		map.put("profitAtBid", d.profitAtBid());
		map.put("marginPctAtExpected", d.marginPctAtExpected());
		map.put("breakevenBid", d.breakevenBid());
		map.put("bidRate", d.bidRate());
		map.put("unitCostSource", unitCostSource);
		return map;
	}

	private static Map<String, Object> specMap(ParsedDocument spec, List<Map<String, String>> files) {
		if (spec == null) {
			return null;
		}
		Map<String, Object> map = new LinkedHashMap<>();
		map.put("text", spec.text());
		map.put("products", List.of());   // 부품 목록은 extract/specs 이식 후
		map.put("parsedFiles", List.of(Map.of("name", spec.filename(), "textLength", spec.length())));
		map.put("files", files == null ? List.of() : files);
		map.put("fileEntryCount", files == null ? 0 : files.size());
		map.put("quantityFound", null);
		map.put("deliveryFound", null);
		map.put("truncated", spec.truncated());
		return map;
	}

	// ── 필드 추출 ────────────────────────────────────────────────────────────
	private static String firstString(Map<String, Object> item, String... keys) {
		for (String key : keys) {
			Object value = item.get(key);
			if (value != null && !String.valueOf(value).isBlank()) {
				return String.valueOf(value).trim();
			}
		}
		return null;
	}

	private static BigDecimal firstNumber(Map<String, Object> item, String... keys) {
		for (String key : keys) {
			BigDecimal number = Numbers.toNumber(item.get(key));
			if (number != null) {
				return number;
			}
		}
		return null;
	}

	private static BigDecimal coalesce(BigDecimal a, BigDecimal b) {
		return a != null ? a : b;
	}

	/**
	 * 낙찰결과를 훑을 <b>검색어 한 낱말</b>을 고른다.
	 *
	 * <p>공고명 전체를 검색어로 쓰면 거의 언제나 0건이다. 실측:
	 * {@code "2026년 충남 청년 Job Planning Day 운영 용역"} → 0건,
	 * {@code "서버"} → 2건. 공고명은 사업명이지 품목명이 아니다.
	 *
	 * <p>품목분류명이 있으면 그것이 곧 품목명이므로 그대로 쓴다. 없으면 공고명에서 캐낸다 —
	 * 국내 공고명은 <b>{@code <연도><기관·사업명> … <품목> <구매/구입>}</b> 꼴이라 품목이
	 * 조달 관용어 바로 앞에 온다. 그래서 잡음을 걷어낸 뒤 <b>마지막</b> 남은 낱말을 고른다.
	 */
	public static String awardKeyword(String productClassName, String noticeName) {
		String direct = productClassName == null ? "" : productClassName.trim();
		if (!direct.isBlank()) {
			// 품목분류명은 이미 품목명이다("컴퓨터서버"). 첫 낱말만 떼어 넓게 훑는다.
			String[] parts = direct.split("[\\s,/·]+");
			for (String p : parts) {
				String token = clean(p);
				if (!token.isBlank() && !NOISE.contains(token)) {
					return token;
				}
			}
		}
		String title = noticeName == null ? "" : noticeName;
		// 괄호는 내용을 살리고 기호만 지운다 — "(컴퓨터서버 2점, 공과대학)" 안에 품목이 있다.
		String flattened = title.replaceAll("[\\[\\]()<>{}“”\"'·,/]", " ");
		String picked = "";
		for (String raw : flattened.split("\\s+")) {
			String token = clean(raw);
			if (token.length() < 2 || NOISE.contains(token)
					|| NUMERIC.matcher(token).matches() || isInstitution(token)) {
				continue;
			}
			picked = token;   // 마지막까지 덮어써 "구매" 직전 낱말이 남는다
		}
		return picked;
	}

	/** 품목이 아닌 낱말 — 조달 관용어·계약방식·수량 단위·접속사. 검색어가 되면 표본이 오염된다. */
	private static final java.util.Set<String> NOISE = java.util.Set.of(
			"구매", "구입", "납품", "설치", "임차", "임대", "제작", "교체", "신규", "증설",
			"유지보수", "유지관리", "용역", "공사", "사업", "사업자", "선정", "계약", "공고",
			"재공고", "입찰", "견적", "운영", "운용", "지원", "관리", "구축", "도입", "외",
			"종", "건", "식", "대", "점", "세트", "개", "및", "등", "위한", "관련", "일괄",
			"단가", "수의", "긴급", "년도", "학년도",
			// 계약 방식·범위 수식어. 품목처럼 보이지만 무엇을 사는지 말하지 않는다.
			"학교주관", "공동", "통합", "일반", "제한", "지명", "협상", "총액", "분할",
			"신입생", "재학생", "기존", "추가", "일부", "전체");

	/**
	 * 기관명 접미. 이것으로 끝나는 낱말은 품목이 아니라 발주처다 —
	 * {@code "…(컴퓨터서버 2점, 공과대학) 교체 구매"} 에서 {@code 공과대학} 이 잡히던 것을 막는다.
	 */
	private static final String[] INSTITUTION_SUFFIX = {
			"대학교", "대학", "학교", "중학교", "고등학교", "초등학교", "연구원", "연구소",
			"공단", "시청", "군청", "도청", "병원", "진흥원", "센터", "본부", "사업단",
			"재단", "공제회", "청", "군", "시", "도"};

	/**
	 * 숫자로 시작하고 짧은 꼬리만 붙은 낱말 — {@code 2026년}·{@code 50대}·{@code 2점}·
	 * {@code 2027학년도}. 꼬리를 3자로 제한해 {@code 3D프린터} 같은 실제 품목은 살린다.
	 */
	private static final java.util.regex.Pattern NUMERIC =
			java.util.regex.Pattern.compile("\\d+[가-힣A-Za-z]{0,3}");

	private static boolean isInstitution(String token) {
		for (String suffix : INSTITUTION_SUFFIX) {
			if (token.length() > suffix.length() && token.endsWith(suffix)) {
				return true;
			}
		}
		return false;
	}

	/** 낱말 끝의 조사·꼬리를 떼어 검색어를 넓힌다("서버용" → "서버"). */
	private static String clean(String raw) {
		String token = raw == null ? "" : raw.trim();
		for (String tail : new String[] {"용의", "용", "등의", "의", "를", "을", "은", "는", "이", "가"}) {
			if (token.length() > tail.length() + 1 && token.endsWith(tail)) {
				return token.substring(0, token.length() - tail.length());
			}
		}
		return token;
	}

	/** 나라장터 낙찰결과 항목(Map)을 {@link MarketPriceService.Award} 로 옮긴다. */
	public static List<MarketPriceService.Award> awardsFromResults(List<Map<String, Object>> results) {
		List<MarketPriceService.Award> awards = new ArrayList<>();
		for (Map<String, Object> row : results == null ? List.<Map<String, Object>>of() : results) {
			awards.add(new MarketPriceService.Award(
					firstOf(row, "sucsfbidAmt", "bidwinnrAmt", "amount"),
					firstOf(row, "sucsfbidRate", "bidwinnrRate", "rate"),
					asDate(firstOf(row, "rlOpengDt", "opengDt", "date")),
					String.valueOf(firstOf(row, "bidNtceNm", "prdctClsfcNoNm", "name", ""))));
		}
		return awards;
	}

	private static Object firstOf(Map<String, Object> row, Object... keys) {
		for (Object key : keys) {
			if (key instanceof String k) {
				Object value = row.get(k);
				if (value != null) {
					return value;
				}
			}
			else {
				return key;   // 기본값(마지막 인자)
			}
		}
		return null;
	}

	/** {@code YYYYMMDDHHmm} → {@code YYYY-MM-DD}. 이미 하이픈이면 앞 10자만. */
	private static String asDate(Object raw) {
		if (raw == null) {
			return null;
		}
		String s = String.valueOf(raw).trim();
		if (s.length() >= 10 && s.charAt(4) == '-') {
			return s.substring(0, 10);
		}
		if (s.length() >= 8 && s.chars().limit(8).allMatch(Character::isDigit)) {
			return s.substring(0, 4) + "-" + s.substring(4, 6) + "-" + s.substring(6, 8);
		}
		return s.isEmpty() ? null : s;
	}
}
