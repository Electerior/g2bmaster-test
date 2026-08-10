package com.electerior.g2bmaster.notice;

import com.electerior.g2bmaster.common.G2bDates;
import com.electerior.g2bmaster.common.G2bDates.DateWindow;
import com.electerior.g2bmaster.search.SearchQuery;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 검색 4탭이 공유하는 질의 파라미터 ({@code docs/api-contract.md} §1.3).
 *
 * <p>탭마다 손으로 파싱하던 것을 한 곳에 모은 이유는, 원본에서 <b>탭마다 기본값이 미묘하게
 * 달랐기</b> 때문이다(발주계획만 {@code perPage} 를 안 읽어 항상 20건). 그런 차이는 의도가
 * 아니라 복사 누락이었고, 한 벌로 묶어야 다시 생기지 않는다.
 *
 * <p>{@code @ModelAttribute} 로 바인딩된다. 값이 없으면 스프링이 {@code null} 을 넣으므로
 * 정규화는 생성자에서 한 번에 한다 — 접근자마다 null 체크를 흩어 두면 한 곳이 반드시 빠진다.
 */
public record SearchCriteria(
		String andTerms,
		String orTerms,
		String notTerms,
		Integer pageNo,
		String perPage,
		String fromDate,
		String toDate,
		String insttNm,
		String sortKey,
		String sortDir,
		String searchField,
		String bidType,
		String activeOnly) {

	/** 검색 화면 기본 조회 구간(일). 근거 탐색용 기본값 30일과 다르다. */
	public static final int DEFAULT_DAYS = 7;

	/** 한 페이지 최대 건수. 이 위로는 응답이 수십 MB가 되어 브라우저가 먼저 죽는다. */
	public static final int MAX_PER_PAGE = 500;

	/** {@code perPage=all} 이 뜻하는 값. 사실상 "전부"지만 무한대는 아니다. */
	public static final int ALL_PER_PAGE = 99999;

	private static final int DEFAULT_PER_PAGE = 20;

	public SearchCriteria {
		pageNo = pageNo == null ? 1 : pageNo;
		andTerms = blankToEmpty(andTerms);
		orTerms = blankToEmpty(orTerms);
		notTerms = blankToEmpty(notTerms);
		perPage = blankToEmpty(perPage);
		fromDate = blankToEmpty(fromDate);
		toDate = blankToEmpty(toDate);
		insttNm = blankToEmpty(insttNm);
		sortKey = blankToEmpty(sortKey);
		sortDir = blankToEmpty(sortDir);
		searchField = blankToEmpty(searchField);
		bidType = normalizeBidType(bidType);
		activeOnly = blankToEmpty(activeOnly);
	}

	/** 빈 조건(전체 조회). */
	public static SearchCriteria empty() {
		return new SearchCriteria(null, null, null, null, null, null, null, null, null, null, null, null, null);
	}

	// ── 검색어 ──────────────────────────────────────────────────────────────

	public List<String> and() {
		return SearchQuery.parseTerms(andTerms);
	}

	public List<String> or() {
		return SearchQuery.parseTerms(orTerms);
	}

	public List<String> not() {
		return SearchQuery.parseTerms(notTerms);
	}

	/** BM25 랭킹에 쓰는 질의어 = AND + OR. NOT 은 랭킹에 관여하지 않는다. */
	public List<String> queryTerms() {
		List<String> terms = new java.util.ArrayList<>(and());
		terms.addAll(or());
		return List.copyOf(terms);
	}

	/**
	 * 상류에 실제로 보낼 검색어.
	 *
	 * <p>AND 검색이면 <b>첫 항만</b> 보낸다. 나라장터는 공고명 부분일치 하나만 지원해서
	 * 두 번째 항을 같이 보내면 교집합이 아니라 빈 결과가 온다. 나머지 항은 받아온 뒤
	 * 로컬에서 건다.
	 */
	public List<String> upstreamTerms() {
		List<String> and = and();
		return and.isEmpty() ? or() : List.of(and.get(0));
	}

	public boolean hasTerms() {
		return !and().isEmpty() || !or().isEmpty();
	}

	// ── 페이징 ──────────────────────────────────────────────────────────────

	/** {@code 0} 은 "페이징 없이 전부"라는 뜻이다 — {@code PagedResponse.of} 가 처리한다. */
	public int page() {
		return pageNo;
	}

	/** {@code 'all'} → 99999, 그 외에는 1..500 로 클램프. 파싱 실패는 기본값 20. */
	public int perPageValue() {
		if ("all".equalsIgnoreCase(perPage)) {
			return ALL_PER_PAGE;
		}
		int parsed = DEFAULT_PER_PAGE;
		try {
			if (!perPage.isEmpty()) {
				parsed = Integer.parseInt(perPage.trim());
			}
		}
		catch (NumberFormatException ex) {
			parsed = DEFAULT_PER_PAGE;
		}
		if (parsed <= 0) {
			parsed = DEFAULT_PER_PAGE;
		}
		return Math.min(Math.max(parsed, 1), MAX_PER_PAGE);
	}

	// ── 모드 ────────────────────────────────────────────────────────────────

	/** 품목 모드 — 공고명이 아니라 분류·규격 필드만 본다. */
	public boolean itemSearch() {
		return "item".equals(searchField);
	}

	public boolean activeOnlyEnabled() {
		return "true".equalsIgnoreCase(activeOnly);
	}

	/** 정규화된 사업 구분. 알 수 없는 값은 빈 문자열(=필터 없음)이다. */
	public String type() {
		return bidType;
	}

	// ── 기간 ────────────────────────────────────────────────────────────────

	/**
	 * 조회 창. 미지정이면 최근 7일.
	 *
	 * <p>끝 시각을 23:59로 채우는 것은 {@link G2bDates#toG2bDt(String, boolean)} 이 한다 —
	 * 안 채우면 종료일 하루가 통째로 빠진다.
	 */
	public DateWindow dates() {
		DateWindow def = G2bDates.defaultDates(DEFAULT_DAYS);
		String from = G2bDates.toG2bDt(fromDate, false);
		String to = G2bDates.toG2bDt(toDate, true);
		return new DateWindow(from == null ? def.from() : from, to == null ? def.to() : to);
	}

	public List<DateWindow> dateWindows() {
		DateWindow window = dates();
		return G2bDates.splitG2bDateRange(window.from(), window.to());
	}

	// ── 캐시 키 ─────────────────────────────────────────────────────────────

	/**
	 * 캐시 키에 넣을 파라미터.
	 *
	 * <p><b>페이징·정렬은 넣지 않는다</b> — 캐시가 담는 것은 "필터링·스코어링까지 끝난 전체
	 * 목록"이고, 페이지 이동은 그 목록을 자르기만 하기 때문이다. 넣으면 페이지를 넘길 때마다
	 * 상류를 다시 친다.
	 */
	public Map<String, Object> cacheParams(String... extraPairs) {
		Map<String, Object> params = new LinkedHashMap<>();
		params.put("a", andTerms);
		params.put("o", orTerms);
		params.put("n", notTerms);
		params.put("fromDate", fromDate);
		params.put("toDate", toDate);
		params.put("insttNm", insttNm);
		params.put("searchField", searchField);
		params.put("bidType", bidType);
		for (int i = 0; i + 1 < extraPairs.length; i += 2) {
			params.put(extraPairs[i], extraPairs[i + 1]);
		}
		return params;
	}

	// ── 내부 ────────────────────────────────────────────────────────────────

	private static String blankToEmpty(String value) {
		return value == null ? "" : value.trim();
	}

	private static String normalizeBidType(String value) {
		String type = blankToEmpty(value);
		return G2bEndpoints.BID_TYPES.contains(type) ? type : "";
	}
}
