package com.electerior.g2bmaster.search;

import java.text.Collator;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/**
 * 검색 필터·정렬·랭킹 순수 헬퍼 — {@code lib/search.js} 의 이식.
 *
 * <p>서버 상태도 네트워크도 참조하지 않는다. 나라장터 응답은 스키마가 자주 바뀌고
 * 필드가 통째로 비기도 해서, 항목은 타입을 굳히지 않고 {@code Map} 으로 다룬다.
 */
public final class SearchQuery {

	private static final ZoneId KST = ZoneId.of("Asia/Seoul");
	private static final Collator KOREAN = Collator.getInstance(Locale.KOREAN);

	private SearchQuery() {
	}

	/** 콤마로 구분된 검색어 문자열을 목록으로 편다. */
	public static List<String> parseTerms(String raw) {
		if (raw == null || raw.isBlank()) {
			return List.of();
		}
		return Arrays.stream(raw.split(","))
				.map(String::trim)
				.filter(s -> !s.isEmpty())
				.toList();
	}

	/**
	 * AND / OR / NOT 조합 판정.
	 *
	 * <p>{@code orTerms} 가 비어 있으면 OR 조건 자체가 없는 것으로 본다 —
	 * "하나 이상 포함"을 빈 목록에 적용해 전부 탈락시키면 안 된다.
	 */
	public static boolean matchesQuery(String text, List<String> andTerms,
			List<String> orTerms, List<String> notTerms) {
		String haystack = text == null ? "" : text.toLowerCase(Locale.ROOT);

		for (String term : andTerms) {
			if (!haystack.contains(term.toLowerCase(Locale.ROOT))) {
				return false;
			}
		}
		if (!orTerms.isEmpty()
				&& orTerms.stream().noneMatch(t -> haystack.contains(t.toLowerCase(Locale.ROOT)))) {
			return false;
		}
		return notTerms.stream().noneMatch(t -> haystack.contains(t.toLowerCase(Locale.ROOT)));
	}

	/**
	 * 마감 여부. 나라장터 날짜는 자릿수가 8/10/12/14 로 제각각이다.
	 *
	 * <p>시각이 빠진 경우 {@code 23:59} 로 본다 — 날짜만 있는 마감일을 자정으로 해석하면
	 * 그날 하루가 통째로 마감된 것으로 잘못 표시된다.
	 */
	public static boolean isPastDeadline(Object value) {
		long millis = g2bDateValue(value, 23, 59);
		return millis != 0 && millis < System.currentTimeMillis();
	}

	/** 정렬용 시각 값. 파싱 실패는 예외가 아니라 0 — 값이 비어 있는 항목이 흔하다. */
	public static long g2bDateValue(Object value) {
		return g2bDateValue(value, 0, 0);
	}

	private static long g2bDateValue(Object value, int defaultHour, int defaultMinute) {
		if (value == null) {
			return 0;
		}
		String digits = String.valueOf(value).replaceAll("\\D", "");
		if (digits.length() < 8) {
			return 0;
		}
		try {
			int year = Integer.parseInt(digits.substring(0, 4));
			int month = Integer.parseInt(digits.substring(4, 6));
			int day = Integer.parseInt(digits.substring(6, 8));
			int hour = digits.length() >= 10 ? Integer.parseInt(digits.substring(8, 10)) : defaultHour;
			int minute = digits.length() >= 12 ? Integer.parseInt(digits.substring(10, 12)) : defaultMinute;
			int second = digits.length() >= 14 ? Integer.parseInt(digits.substring(12, 14)) : 0;

			ZonedDateTime at = LocalDateTime.of(year, month, day, hour, minute, second).atZone(KST);
			return at.toInstant().toEpochMilli();
		}
		catch (RuntimeException e) {
			return 0;   // 형식이 깨진 날짜는 "정렬 기준 없음"으로 처리한다.
		}
	}

	/**
	 * 정렬. 값의 성격을 보고 날짜 → 숫자 → 한국어 문자열 순으로 판정한다.
	 *
	 * <p>나라장터 응답은 금액도 {@code "1,234,000"} 같은 문자열로 오기 때문에
	 * 컬럼별 타입을 미리 알 수 없다. 원본과 같은 휴리스틱을 유지한다.
	 */
	public static List<Map<String, Object>> sortItems(List<Map<String, Object>> items,
			String sortKey, String sortDir) {
		if (sortKey == null || sortKey.isBlank()) {
			return items;
		}
		int mul = "desc".equalsIgnoreCase(sortDir) ? -1 : 1;

		List<Map<String, Object>> copy = new ArrayList<>(items);
		copy.sort(comparator(sortKey, mul));
		return copy;
	}

	private static Comparator<Map<String, Object>> comparator(String sortKey, int mul) {
		return (a, b) -> {
			Object va = a.get(sortKey);
			Object vb = b.get(sortKey);

			long da = g2bDateValue(va);
			long db = g2bDateValue(vb);
			if (da != 0 || db != 0) {
				return mul * Long.compare(da, db);
			}

			Double na = numericOrNull(va);
			Double nb = numericOrNull(vb);
			if (na != null && nb != null && (na != 0 || nb != 0)) {
				return mul * Double.compare(na, nb);
			}

			return mul * KOREAN.compare(asString(va), asString(vb));
		};
	}

	private static Double numericOrNull(Object value) {
		String cleaned = asString(value).replaceAll("[^0-9.-]", "");
		if (cleaned.isEmpty()) {
			return null;
		}
		try {
			return Double.valueOf(cleaned);
		}
		catch (NumberFormatException e) {
			return null;
		}
	}

	private static String asString(Object value) {
		return value == null ? "" : String.valueOf(value);
	}

	/**
	 * Okapi BM25 재정렬. IDF 가중치로 희소 검색어에 높은 점수를 준다.
	 *
	 * <p><b>단어 빈도를 토큰이 아니라 부분문자열로 센다</b> — 한국어 복합어("서버용메모리")
	 * 때문이다. 형태소 분석기 없이 토큰 일치만 보면 대부분 놓친다.
	 * 원본의 의도적 선택이므로 "고치지" 말 것.
	 */
	public static <T> List<T> bm25(List<String> queryTerms, List<T> docs,
			Function<T, String> textFn) {
		return bm25(queryTerms, docs, textFn, 1.5, 0.75);
	}

	public static <T> List<T> bm25(List<String> queryTerms, List<T> docs,
			Function<T, String> textFn, double k1, double b) {
		if (queryTerms.isEmpty() || docs.isEmpty()) {
			return docs;
		}
		List<String> terms = new ArrayList<>(new LinkedHashSet<>(
				queryTerms.stream()
						.map(t -> t.toLowerCase(Locale.ROOT).trim())
						.filter(t -> !t.isEmpty())
						.toList()));
		if (terms.isEmpty()) {
			return docs;
		}

		int n = docs.size();
		List<String> docTexts = docs.stream()
				.map(d -> {
					String text = textFn.apply(d);
					return text == null ? "" : text.toLowerCase(Locale.ROOT);
				})
				.toList();
		int[] docLengths = docTexts.stream()
				.mapToInt(t -> (int) Arrays.stream(t.split("\\s+")).filter(s -> !s.isEmpty()).count())
				.toArray();
		double avgDl = Arrays.stream(docLengths).sum() / (double) n;
		if (avgDl == 0) {
			avgDl = 1;
		}

		int[] df = new int[terms.size()];
		for (int ti = 0; ti < terms.size(); ti++) {
			String term = terms.get(ti);
			df[ti] = (int) docTexts.stream().filter(t -> t.contains(term)).count();
		}

		record Scored<T>(T doc, double score) {}
		List<Scored<T>> scored = new ArrayList<>(n);
		for (int i = 0; i < n; i++) {
			String text = docTexts.get(i);
			double dl = docLengths[i] == 0 ? 1 : docLengths[i];
			double score = 0;
			for (int ti = 0; ti < terms.size(); ti++) {
				String term = terms.get(ti);
				int f = countOccurrences(text, term);
				if (f == 0) {
					continue;
				}
				double idf = Math.log((n - df[ti] + 0.5) / (df[ti] + 0.5) + 1);
				score += idf * (f * (k1 + 1)) / (f + k1 * (1 - b + b * dl / avgDl));
			}
			scored.add(new Scored<>(docs.get(i), score));
		}

		scored.sort(Comparator.comparingDouble(Scored<T>::score).reversed());
		return scored.stream().map(Scored::doc).toList();
	}

	private static int countOccurrences(String text, String term) {
		int count = 0;
		int pos = 0;
		while ((pos = text.indexOf(term, pos)) != -1) {
			count++;
			pos += term.length();
		}
		return count;
	}
}
