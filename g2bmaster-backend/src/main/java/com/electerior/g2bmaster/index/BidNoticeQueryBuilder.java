package com.electerior.g2bmaster.index;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 검색 조건 → SQL {@code WHERE} 절.
 *
 * <p>이 클래스에는 DB 접근이 없다(순수 함수). 조건 조합이 열 가지가 넘어 조합 폭발을 눈으로
 * 확인해야 하는데, 저장소 안에 섞여 있으면 테스트하려고 DB 를 띄워야 하기 때문이다.
 *
 * <h2>한국어 전문검색의 두 가지 함정</h2>
 * <ol>
 *   <li><b>ngram 파서 없이는 부분일치가 안 된다.</b> MySQL 기본 파서는 공백으로 끊어서
 *       '서버'로 '노트북서버구매'를 못 찾는다. 그래서 V7 이 {@code WITH PARSER ngram} 을 걸었다.</li>
 *   <li><b>ngram 은 토큰 크기(기본 2) 미만의 낱말을 통째로 버린다.</b> 한 글자 검색어는
 *       MATCH 로는 <em>절대</em> 걸리지 않는다 — 0건이 아니라 '조용히 무시'라 더 나쁘다.
 *       그래서 한 글자 낱말만 따로 모아 {@code LIKE} 로 떨어뜨린다({@link #SHORT_TERM_LENGTH}).</li>
 * </ol>
 */
public final class BidNoticeQueryBuilder {

	/** ngram 토큰 크기(MySQL {@code ngram_token_size} 기본값). 이 미만은 MATCH 가 버린다. */
	private static final int SHORT_TERM_LENGTH = 2;

	/** 낱말 수 상한. 늘어날수록 MATCH 식이 길어지고 LIKE 폴백이 급격히 비싸진다. */
	private static final int MAX_TERMS = 8;

	/**
	 * 조립된 조건.
	 *
	 * @param sql             {@code " WHERE …"} (조건이 없으면 빈 문자열)
	 * @param params          바인딩 파라미터
	 * @param relevanceSelect SELECT 목록에 덧붙일 관련도 식. 전문검색이 없으면 {@code , 0 AS relevance}
	 * @param fullText        MATCH 를 실제로 쓰는가 — 정렬 기본값을 관련도로 할지 판단한다
	 */
	public record Where(String sql, Map<String, Object> params, String relevanceSelect, boolean fullText) {}

	private final List<String> conditions = new ArrayList<>();
	private final Map<String, Object> params = new LinkedHashMap<>();
	private String relevanceSelect = ", 0 AS relevance";
	private boolean fullText;
	private int paramSeq;

	/**
	 * 자유 검색어.
	 *
	 * @param andTerms 모두 포함해야 하는 낱말
	 * @param orTerms  하나라도 포함하면 되는 낱말
	 * @param notTerms 포함되면 제외할 낱말
	 */
	public BidNoticeQueryBuilder keywords(List<String> andTerms, List<String> orTerms, List<String> notTerms) {
		List<String> and = capped(andTerms);
		List<String> or = capped(orTerms);
		List<String> not = capped(notTerms);

		StringBuilder booleanQuery = new StringBuilder();
		// 양(陽) 낱말 수를 세어 둔다 — MATCH 를 쓸 수 있는지가 여기에 달렸다(아래 참고).
		// 조립된 문자열을 정규식으로 되읽어 판정하면 따옴표 안의 문자에 걸려 틀린다.
		int positives = 0;

		// MATCH 로 감당되는 낱말과, 너무 짧아 LIKE 로 내려야 하는 낱말을 가른다.
		for (String term : and) {
			if (isShort(term)) {
				addLike(term, true);
			}
			else {
				booleanQuery.append('+').append(quote(term)).append(' ');
				positives++;
			}
		}
		for (String term : or) {
			if (isShort(term)) {
				// OR 낱말이 한 글자면 LIKE 로 강제 조건을 걸 수 없다(하나만 만족해도 되므로).
				// 아래에서 OR 묶음으로 따로 처리한다.
				continue;
			}
			booleanQuery.append(quote(term)).append(' ');
			positives++;
		}
		for (String term : not) {
			if (isShort(term)) {
				addLike(term, false);
			}
			else {
				booleanQuery.append('-').append(quote(term)).append(' ');
			}
		}

		// 한 글자 OR 낱말들은 "이 중 하나라도" 라는 하나의 괄호 조건이 된다.
		List<String> shortOr = or.stream().filter(BidNoticeQueryBuilder::isShort).toList();
		if (!shortOr.isEmpty()) {
			List<String> parts = new ArrayList<>();
			for (String term : shortOr) {
				String key = nextKey("kw");
				params.put(key, "%" + escapeLike(term) + "%");
				parts.add("(n.notice_name LIKE :" + key + " ESCAPE '!' "
						+ "OR n.notice_body LIKE :" + key + " ESCAPE '!')");
			}
			conditions.add("(" + String.join(" OR ", parts) + ")");
		}

		/*
		 * 양(+/무기호) 낱말이 하나도 없으면 MATCH 를 쓰지 않는다.
		 * MySQL 불리언 모드에서 '-foo' 만 있는 식은 아무 행도 돌려주지 않는다 —
		 * "제외만 지정한 검색"이 0건이 되어 버리는데, 사용자가 기대하는 것은
		 * "그것만 뺀 전체"다. 제외 낱말은 이미 LIKE 로도 걸리므로 MATCH 를 빼면 된다.
		 */
		String query = booleanQuery.toString().trim();
		if (positives > 0) {
			params.put("ftQuery", query);
			conditions.add("MATCH(n.notice_name, n.notice_body) AGAINST (:ftQuery IN BOOLEAN MODE)");
			relevanceSelect = ", MATCH(n.notice_name, n.notice_body) "
					+ "AGAINST (:ftQuery IN BOOLEAN MODE) AS relevance";
			fullText = true;
		}
		else if (!query.isEmpty()) {
			// 제외 낱말만 남은 경우 — MATCH 없이 LIKE 로만 뺀다.
			for (String term : not) {
				if (!isShort(term)) {
					addLike(term, false);
				}
			}
		}
		return this;
	}

	// ── 단순 필터 ───────────────────────────────────────────────────────────

	public BidNoticeQueryBuilder category(NoticeCategory category) {
		return equalsIfPresent("n.category", category == null ? null : category.name(), "category");
	}

	public BidNoticeQueryBuilder state(NoticeState state) {
		return equalsIfPresent("n.state", state == null ? null : state.name(), "state");
	}

	public BidNoticeQueryBuilder businessDivision(BusinessDivision division) {
		return equalsIfPresent("n.business_division", division == null ? null : division.name(), "division");
	}

	public BidNoticeQueryBuilder noticeInstitutionCode(String code) {
		return equalsIfPresent("n.notice_institution_code", trimToNull(code), "ntceInsttCd");
	}

	public BidNoticeQueryBuilder demandInstitutionCode(String code) {
		return equalsIfPresent("n.demand_institution_code", trimToNull(code), "dmndInsttCd");
	}

	public BidNoticeQueryBuilder beforeSpecRgstNo(String no) {
		return equalsIfPresent("n.before_spec_rgst_no", trimToNull(no), "beforeSpec");
	}

	/**
	 * 지역. {@code region} 은 콤마로 여러 지역을 담으므로 정확일치가 아니라 포함으로 건다.
	 *
	 * <p>지역 제한이 없는 공고(빈 문자열 = 전국)는 <b>어떤 지역으로 좁혀도 함께 나온다</b>.
	 * 서울 업체가 "서울"로 검색했을 때 참가할 수 있는 전국 공고가 빠지면 그게 더 큰 손해다.
	 */
	public BidNoticeQueryBuilder region(String region) {
		String value = trimToNull(region);
		if (value == null) {
			return this;
		}
		String key = nextKey("region");
		params.put(key, "%" + escapeLike(value) + "%");
		conditions.add("(n.region LIKE :" + key + " ESCAPE '!' OR n.region = '')");
		return this;
	}

	/** 세부품명번호. 접두 일치라 상위 분류(예: {@code 4110}) 로도 훑을 수 있다. */
	public BidNoticeQueryBuilder detailProductCode(String code) {
		String value = trimToNull(code);
		if (value == null) {
			return this;
		}
		String key = nextKey("prdct");
		params.put(key, escapeLike(value) + "%");
		conditions.add("n.detail_product_code LIKE :" + key + " ESCAPE '!'");
		return this;
	}

	/**
	 * 발주기관명 — 공고기관·수요기관 둘 다에서 찾는다.
	 *
	 * <p>둘을 OR 로 묶는 이유: 사용자는 "이 기관이 내는 공고"를 찾는 것이지 그 기관이
	 * 공고기관인지 수요기관인지를 구분해서 묻지 않는다. 조달청이 대행 공고하는 건은
	 * 공고기관이 조달청이고 수요기관이 실제 발주처라, 한쪽만 보면 그 건이 통째로 빠진다.
	 */
	public BidNoticeQueryBuilder institutionName(String name) {
		String value = trimToNull(name);
		if (value == null) {
			return this;
		}
		String key = nextKey("instt");
		params.put(key, "%" + escapeLike(value) + "%");
		conditions.add("(n.notice_institution_name LIKE :" + key + " ESCAPE '!'"
				+ " OR n.demand_institution_name LIKE :" + key + " ESCAPE '!')");
		return this;
	}

	public BidNoticeQueryBuilder officerName(String name) {
		String value = trimToNull(name);
		if (value == null) {
			return this;
		}
		String key = nextKey("ofcl");
		params.put(key, "%" + escapeLike(value) + "%");
		conditions.add("n.officer_name LIKE :" + key + " ESCAPE '!'");
		return this;
	}

	// ── 기간 ────────────────────────────────────────────────────────────────

	/** 공고 생성일 구간. {@code to} 는 그 날 23:59:59 까지 포함한다(호출부가 넘겨준 값 그대로 쓴다). */
	public BidNoticeQueryBuilder createdBetween(java.time.LocalDateTime from, java.time.LocalDateTime to) {
		if (from != null) {
			params.put("createdFrom", from);
			conditions.add("n.created_date >= :createdFrom");
		}
		if (to != null) {
			params.put("createdTo", to);
			conditions.add("n.created_date <= :createdTo");
		}
		return this;
	}

	public BidNoticeQueryBuilder closeBetween(java.time.LocalDateTime from, java.time.LocalDateTime to) {
		if (from != null) {
			params.put("closeFrom", from);
			conditions.add("n.close_date >= :closeFrom");
		}
		if (to != null) {
			params.put("closeTo", to);
			conditions.add("n.close_date <= :closeTo");
		}
		return this;
	}

	/**
	 * 아직 참여할 수 있는 공고만.
	 *
	 * <p>{@code category <> '마감'} 이 아니라 마감일시를 직접 본다. 스위퍼는 주기적으로 도는
	 * 것이라 방금 마감된 건이 아직 '입찰'로 남아 있을 수 있는데, 이 필터까지 그 지연을
	 * 물려받으면 사용자가 이미 닫힌 공고에 시간을 쓴다.
	 */
	public BidNoticeQueryBuilder activeOnly(boolean active) {
		if (active) {
			conditions.add("(n.close_date IS NULL OR n.close_date >= NOW(6))");
		}
		return this;
	}

	// ── 금액 ────────────────────────────────────────────────────────────────

	/**
	 * 추정가격 구간.
	 *
	 * <p>금액은 {@code price_detail} JSON 안에 있어 인덱스를 못 탄다. 다른 필터가 먼저 좁힌
	 * 뒤에 걸리므로 실사용에서는 문제가 없지만, <b>금액만</b> 지정한 검색은 전체 훑기가 된다.
	 * 그 조합이 흔해지면 생성 컬럼 + 인덱스로 승격할 자리다.
	 */
	public BidNoticeQueryBuilder estimatedPriceBetween(Long min, Long max) {
		if (min == null && max == null) {
			return this;
		}
		String expr = "CAST(JSON_UNQUOTE(JSON_EXTRACT(n.price_detail, '$.estimatedPrice')) AS DECIMAL(20,4))";
		if (min != null) {
			params.put("minAmount", min);
			conditions.add(expr + " >= :minAmount");
		}
		if (max != null) {
			params.put("maxAmount", max);
			conditions.add(expr + " <= :maxAmount");
		}
		return this;
	}

	// ── 조립 ────────────────────────────────────────────────────────────────

	public Where build() {
		String sql = conditions.isEmpty() ? "" : "\n WHERE " + String.join("\n   AND ", conditions);
		return new Where(sql, Map.copyOf(params), relevanceSelect, fullText);
	}

	// ── 내부 ────────────────────────────────────────────────────────────────

	private BidNoticeQueryBuilder equalsIfPresent(String column, String value, String name) {
		if (value == null) {
			return this;
		}
		String key = nextKey(name);
		params.put(key, value);
		conditions.add(column + " = :" + key);
		return this;
	}

	/** 제목·본문 어느 쪽이든 포함(또는 미포함)해야 한다. */
	private void addLike(String term, boolean include) {
		String key = nextKey("kw");
		params.put(key, "%" + escapeLike(term) + "%");
		String match = "(n.notice_name LIKE :" + key + " ESCAPE '!' "
				+ "OR n.notice_body LIKE :" + key + " ESCAPE '!')";
		conditions.add(include ? match : "NOT " + match);
	}

	private static boolean isShort(String term) {
		return term.length() < SHORT_TERM_LENGTH;
	}

	private String nextKey(String prefix) {
		return prefix + (paramSeq++);
	}

	private static List<String> capped(List<String> terms) {
		if (terms == null || terms.isEmpty()) {
			return List.of();
		}
		return terms.stream()
				.filter(t -> t != null && !t.isBlank())
				.map(String::trim)
				.distinct()
				.limit(MAX_TERMS)
				.toList();
	}

	/**
	 * 불리언 모드 낱말을 큰따옴표로 감싼다.
	 *
	 * <p>감싸는 이유는 사용자가 친 {@code +}·{@code -}·{@code *}·{@code (} 가 연산자로 해석돼
	 * 문법 오류(→ 500)를 내는 것을 막기 위해서다. 따옴표 안에서는 전부 literal 이다.
	 * 낱말 자체에 든 따옴표만 지운다 — 이스케이프 문법이 없어 지우는 것이 유일한 방법이다.
	 */
	private static String quote(String term) {
		return '"' + term.replace("\"", " ").trim() + '"';
	}

	/**
	 * {@code LIKE} 와일드카드 무력화.
	 *
	 * <p>{@code %}·{@code _} 를 그대로 두면 사용자가 친 {@code _} 가 '아무 글자 하나'가 되어
	 * 결과가 소리 없이 넓어진다. SQL 표준 {@code ESCAPE} 절과 짝을 이룬다.
	 */
	private static String escapeLike(String value) {
		return value.replace("!", "!!").replace("%", "!%").replace("_", "!_");
	}

	private static String trimToNull(String value) {
		if (value == null) {
			return null;
		}
		String trimmed = value.trim();
		return trimmed.isEmpty() ? null : trimmed;
	}
}
