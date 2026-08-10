package com.electerior.g2bmaster.pricing;

import com.electerior.g2bmaster.common.Numbers;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 시가(낙찰가) 프록시 — 유사 품목 실낙찰가로 시장 신호·차익을 계산하는 순수 로직
 * ({@code lib/marketprice.js} 이식).
 *
 * <p>네트워크(낙찰정보 조회)는 호출부가 담당하고, 여기서는 모은 낙찰 표본을 요약만 한다.
 *
 * <p><b>핵심 판단</b>: 낙찰 <em>총액</em>은 수량·규모가 달라 카테고리 간 비교가 안 된다.
 * 반면 <em>낙찰률</em>(낙찰가/예산)은 규모에 무관해 안정적이다. 그래서 시가 신호는
 * "이 공고 예산 × 카테고리 중앙 낙찰률 = 예상 낙찰가"로 산출하고, 절대액 중앙값은
 * 참고용으로만 노출한다.
 */
public final class MarketPriceService {

	/**
	 * 매칭에서 제외할 일반 단어 — 품목 식별력이 없다.
	 *
	 * <p>실측에서 오탐을 유발한 단어들이 그대로 들어 있다("2분기", "정기청구" 등).
	 * 이 목록을 줄이면 무관한 공고끼리 매칭돼 중앙 낙찰률이 오염된다.
	 */
	private static final Set<String> STOP = Set.of(
			"구매", "입찰", "공고", "사업", "용역", "납품", "계약", "물품", "수의", "견적", "제안",
			"설치", "임대", "렌탈", "대여", "리스", "외", "및", "등", "재", "재공고", "긴급", "일반",
			"연간", "단가", "통합", "조달", "구입", "교체", "보수", "유지", "관리", "운영",
			"분기", "1분기", "2분기", "3분기", "4분기", "정기", "청구", "정기청구", "포함", "이상",
			"제출", "학년도", "학기", "재료비", "대행", "계약대행", "육성", "제작", "시제품", "구입건",
			"선정", "위탁", "지원", "개선", "확대", "구축", "연구", "관련", "기타", "내역", "건",
			"set", "kit", "tube", "cap", "rack");

	private static final Pattern BRACKETS = Pattern.compile("\\([^)]*\\)|\\[[^\\]]*\\]");
	private static final Pattern YEAR_ORDINAL = Pattern.compile("\\d{4}\\s*년도?|제?\\s*\\d+\\s*호|\\d+\\s*차");
	private static final Pattern NON_WORD = Pattern.compile("[^가-힣A-Za-z0-9 ]");
	private static final Pattern DIGITS_ONLY = Pattern.compile("^\\d+$");
	private static final Pattern SPACES = Pattern.compile("\\s+");

	/** 낙찰 표본 한 건. {@code date} 는 {@code YYYY-MM-DD}. */
	public record Award(Object amount, Object rate, String date, String name) {}

	/** 표본 요약 = 시가 신호 + 예상 절감액. */
	public record MarketSummary(
			int sampleCount,
			int rateSampleCount,
			BigDecimal medianRate,
			BigDecimal minRate,
			BigDecimal maxRate,
			BigDecimal medianAmountRaw,
			String latestDate,
			BigDecimal budget,
			BigDecimal expectedAward,
			BigDecimal expectedSaving,
			BigDecimal expectedSavingPct) {}

	private MarketPriceService() {
	}

	/** 중앙값. 표본이 없으면 {@code null}(0 아님 — 0은 "무료 낙찰"이라는 거짓말이 된다). */
	public static BigDecimal median(List<BigDecimal> values) {
		List<BigDecimal> sorted = new ArrayList<>();
		for (BigDecimal value : values == null ? List.<BigDecimal>of() : values) {
			if (value != null) {
				sorted.add(value);
			}
		}
		if (sorted.isEmpty()) {
			return null;
		}
		sorted.sort(BigDecimal::compareTo);
		int mid = sorted.size() / 2;
		if (sorted.size() % 2 == 1) {
			return sorted.get(mid);
		}
		return plain(sorted.get(mid - 1).add(sorted.get(mid))
				.divide(BigDecimal.valueOf(2), 10, RoundingMode.HALF_UP));
	}

	/**
	 * 공고명/품목명에서 식별력 있는 검색어 추출.
	 *
	 * <p>연도·차수·괄호 내용·지역·일반어를 걷어낸다. 남는 것이 "무엇을 사는가"다.
	 */
	public static List<String> priceTerms(String name) {
		String cleaned = NON_WORD.matcher(
						YEAR_ORDINAL.matcher(
								BRACKETS.matcher(name == null ? "" : name).replaceAll(" "))
								.replaceAll(" "))
				.replaceAll(" ");
		List<String> terms = new ArrayList<>();
		for (String token : SPACES.split(cleaned)) {
			String term = token.trim();
			if (term.length() >= 2 && !STOP.contains(term) && !DIGITS_ONLY.matcher(term).matches()) {
				terms.add(term);
			}
		}
		return terms;
	}

	/** 두 이름이 식별 검색어를 하나라도 공유하는가 — 느슨한 판정(참고용). */
	public static boolean shareTerm(String a, String b) {
		Set<String> ta = new LinkedHashSet<>(priceTerms(a));
		return priceTerms(b).stream().anyMatch(ta::contains);
	}

	/**
	 * 강한 유사 판정: 공유어 2개 이상, 또는 3글자 이상 식별어 1개.
	 *
	 * <p>단일 2글자 일반어의 우연 일치로 인한 오탐을 막는다 — 그 오탐 하나가 중앙 낙찰률을
	 * 통째로 옮겨 놓는다.
	 */
	public static boolean isStrongMatch(String a, String b) {
		Set<String> ta = new LinkedHashSet<>(priceTerms(a));
		List<String> shared = priceTerms(b).stream().filter(ta::contains).toList();
		return shared.size() >= 2 || shared.stream().anyMatch(t -> t.length() >= 3);
	}

	/** 대상 공고명과 강하게 매칭되는 낙찰만 추린다. */
	public static List<Award> matchAwards(String name, List<Award> awards) {
		if (awards == null) {
			return List.of();
		}
		return awards.stream().filter(a -> isStrongMatch(name, a.name())).toList();
	}

	/**
	 * 낙찰 표본 요약.
	 *
	 * <p>낙찰률 이상치(0 이하 · 130% 초과)는 미기재/오류로 보고 통계에서 뺀다. 남겨 두면
	 * 값 하나가 중앙값을 끌고 다닌다.
	 */
	public static MarketSummary summarizeMarket(List<Award> awards, Object budget) {
		List<Award> list = awards == null ? List.of() : awards;

		List<BigDecimal> amounts = new ArrayList<>();
		List<BigDecimal> rates = new ArrayList<>();
		List<String> dates = new ArrayList<>();
		for (Award award : list) {
			BigDecimal amount = Numbers.toNumber(award.amount());
			if (amount != null && amount.signum() > 0) {
				amounts.add(amount);
			}
			BigDecimal rate = Numbers.toNumber(award.rate());
			if (rate != null && rate.signum() > 0 && rate.compareTo(BigDecimal.valueOf(130)) <= 0) {
				rates.add(rate);
			}
			if (award.date() != null && !award.date().isEmpty()) {
				dates.add(award.date());
			}
		}
		dates.sort(String::compareTo);

		BigDecimal medianRate = median(rates);
		BigDecimal bud = Numbers.toNumber(budget);
		BigDecimal expectedAward = (bud != null && medianRate != null)
				? bud.multiply(medianRate).divide(BigDecimal.valueOf(100), 0, RoundingMode.HALF_UP)
				: null;

		return new MarketSummary(
				list.size(),
				rates.size(),
				round2(medianRate),
				rates.isEmpty() ? null : round2(rates.stream().min(BigDecimal::compareTo).orElseThrow()),
				rates.isEmpty() ? null : round2(rates.stream().max(BigDecimal::compareTo).orElseThrow()),
				median(amounts),
				dates.isEmpty() ? null : dates.get(dates.size() - 1),
				bud,
				expectedAward,
				(bud != null && expectedAward != null) ? bud.subtract(expectedAward) : null,
				medianRate == null ? null : round2(BigDecimal.valueOf(100).subtract(medianRate)));
	}

	private static BigDecimal round2(BigDecimal value) {
		return value == null ? null : plain(value.setScale(2, RoundingMode.HALF_UP));
	}

	/**
	 * 불필요한 0을 떼되 <b>지수 표기로 넘어가지 않게</b> 한다.
	 *
	 * <p>{@code new BigDecimal("100.00").stripTrailingZeros()} 는 {@code 1E+2} 가 되고,
	 * 그대로 직렬화하면 응답에 {@code 1E+2} 가 실린다. JSON 숫자로는 유효하지만 로그·테스트·
	 * 스프레드시트 내보내기에서 사람 눈을 정확히 한 번 속인다.
	 */
	private static BigDecimal plain(BigDecimal value) {
		BigDecimal stripped = value.stripTrailingZeros();
		return stripped.scale() < 0 ? stripped.setScale(0, RoundingMode.UNNECESSARY) : stripped;
	}
}
