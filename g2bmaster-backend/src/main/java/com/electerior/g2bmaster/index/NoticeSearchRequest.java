package com.electerior.g2bmaster.index;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

/**
 * {@code GET /api/search/notices} 질의 파라미터.
 *
 * <p>{@code @ModelAttribute} 로 바인딩된다. 값이 없으면 스프링이 {@code null} 을 넣으므로
 * 정규화는 접근자에서 한 번씩만 한다 — {@code SearchCriteria} 와 같은 규약이다.
 *
 * <p>검색어 입력이 둘인 이유: {@code q} 는 "검색창에 친 한 줄"이고
 * {@code andTerms}/{@code orTerms}/{@code notTerms} 는 화면의 3단 태그 입력이다. 둘 다 오면
 * 합쳐서 건다 — 어느 한쪽을 무시하면 사용자가 친 조건이 소리 없이 사라진다.
 */
public record NoticeSearchRequest(
		String q,
		String andTerms,
		String orTerms,
		String notTerms,
		String category,
		String state,
		String division,
		String region,
		String insttNm,
		String insttCd,
		String dmndInsttCd,
		String detailProductCode,
		String beforeSpecRgstNo,
		String officerName,
		String fromDate,
		String toDate,
		String closeFrom,
		String closeTo,
		String activeOnly,
		Long minAmount,
		Long maxAmount,
		String sort,
		String dir,
		Integer page,
		Integer perPage) {

	private static final int DEFAULT_PER_PAGE = 20;

	/** {@code q} + {@code andTerms} — 둘 다 '모두 포함' 조건이다. */
	public List<String> and() {
		List<String> terms = new java.util.ArrayList<>(split(q));
		terms.addAll(split(andTerms));
		return List.copyOf(terms);
	}

	public List<String> or() {
		return split(orTerms);
	}

	public List<String> not() {
		return split(notTerms);
	}

	public NoticeCategory categoryValue() {
		return NoticeCategory.of(category);
	}

	public NoticeState stateValue() {
		return NoticeState.of(state);
	}

	public BusinessDivision divisionValue() {
		return BusinessDivision.of(division);
	}

	public boolean activeOnlyEnabled() {
		return "true".equalsIgnoreCase(activeOnly);
	}

	public int pageValue() {
		return page == null || page < 1 ? 1 : page;
	}

	/** 1..{@link BidNoticeIndexRepository#MAX_LIMIT} 로 클램프. */
	public int perPageValue() {
		int size = perPage == null || perPage < 1 ? DEFAULT_PER_PAGE : perPage;
		return Math.min(size, BidNoticeIndexRepository.MAX_LIMIT);
	}

	public int offset() {
		return (pageValue() - 1) * perPageValue();
	}

	// ── 기간 ────────────────────────────────────────────────────────────────

	public LocalDateTime createdFrom() {
		return startOfDay(fromDate);
	}

	/** 종료일은 그 날 끝까지 포함한다 — 안 그러면 종료일 하루가 통째로 빠진다. */
	public LocalDateTime createdTo() {
		return endOfDay(toDate);
	}

	public LocalDateTime closeFromValue() {
		return startOfDay(closeFrom);
	}

	public LocalDateTime closeToValue() {
		return endOfDay(closeTo);
	}

	// ── 내부 ────────────────────────────────────────────────────────────────

	/** 공백·콤마로 자른다. 빈 낱말은 버린다. */
	private static List<String> split(String raw) {
		if (raw == null || raw.isBlank()) {
			return List.of();
		}
		return Arrays.stream(raw.split("[\\s,]+"))
				.map(String::trim)
				.filter(term -> !term.isEmpty())
				.toList();
	}

	private static LocalDateTime startOfDay(String date) {
		LocalDate parsed = parse(date);
		return parsed == null ? null : parsed.atStartOfDay();
	}

	private static LocalDateTime endOfDay(String date) {
		LocalDate parsed = parse(date);
		return parsed == null ? null : parsed.atTime(23, 59, 59);
	}

	/** 못 읽는 날짜는 조용히 무시한다(필터 없음) — 400 을 던지면 화면이 통째로 멈춘다. */
	private static LocalDate parse(String date) {
		if (date == null || date.isBlank()) {
			return null;
		}
		try {
			return LocalDate.parse(date.trim());
		}
		catch (java.time.format.DateTimeParseException ex) {
			return null;
		}
	}
}
