package com.electerior.g2bmaster.search;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 검색 캐시 키 · 누리장터 정규화 · 최신 차수 선택 — {@code lib/notice-search.js} 의 이식.
 */
public final class NoticeSearchSupport {

	private NoticeSearchSupport() {
	}

	/**
	 * 검색 캐시 키.
	 *
	 * <p>파라미터 맵의 <b>키 순서에 흔들리지 않아야</b> 한다 — 같은 조건인데 순서만 다른
	 * 요청이 캐시를 비껴가면 상류 호출이 그만큼 늘고, 나라장터는 호출량을 제한한다.
	 * 그래서 직렬화 전에 키를 정렬한다.
	 */
	public static String buildSearchCacheKey(String tab, Map<String, ?> params) {
		String canonical = "{\"tab\":\"" + (tab == null ? "" : tab) + "\",\"params\":"
				+ stableJson(params) + "}";
		return sha1Hex(canonical);
	}

	private static String stableJson(Object value) {
		if (value == null) {
			return "null";
		}
		if (value instanceof Map<?, ?> map) {
			Map<String, Object> sorted = new TreeMap<>();
			map.forEach((k, v) -> {
				if (v != null) {
					sorted.put(String.valueOf(k), v);
				}
			});
			StringBuilder sb = new StringBuilder("{");
			boolean first = true;
			for (Map.Entry<String, Object> entry : sorted.entrySet()) {
				if (!first) {
					sb.append(',');
				}
				first = false;
				sb.append(quote(entry.getKey())).append(':').append(stableJson(entry.getValue()));
			}
			return sb.append('}').toString();
		}
		if (value instanceof Iterable<?> iterable) {
			StringBuilder sb = new StringBuilder("[");
			boolean first = true;
			for (Object element : iterable) {
				if (!first) {
					sb.append(',');
				}
				first = false;
				sb.append(stableJson(element));
			}
			return sb.append(']').toString();
		}
		if (value instanceof Number || value instanceof Boolean) {
			return String.valueOf(value);
		}
		return quote(String.valueOf(value));
	}

	private static String quote(String raw) {
		StringBuilder sb = new StringBuilder("\"");
		for (char c : raw.toCharArray()) {
			switch (c) {
				case '"' -> sb.append("\\\"");
				case '\\' -> sb.append("\\\\");
				case '\n' -> sb.append("\\n");
				case '\r' -> sb.append("\\r");
				case '\t' -> sb.append("\\t");
				default -> sb.append(c);
			}
		}
		return sb.append('"').toString();
	}

	private static String sha1Hex(String input) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-1").digest(input.getBytes(StandardCharsets.UTF_8));
			StringBuilder sb = new StringBuilder(digest.length * 2);
			for (byte b : digest) {
				sb.append(Character.forDigit((b >> 4) & 0xF, 16));
				sb.append(Character.forDigit(b & 0xF, 16));
			}
			return sb.toString();
		}
		catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-1 을 쓸 수 없습니다.", e);
		}
	}

	/**
	 * 누리장터(민간 공고) 항목을 나라장터 항목과 같은 모양으로 맞춘다.
	 *
	 * <p>필드명이 미묘하게 다르다({@code ntceNm} vs {@code bidNtceNm},
	 * {@code nticeDt} vs {@code bidNtceDt}). 화면과 점수 계산이 한 벌의 필드만 알면 되도록
	 * 여기서 흡수한다.
	 */
	public static Map<String, Object> normalizePrivateNotice(Map<String, Object> item, String type) {
		Map<String, Object> out = new LinkedHashMap<>(item);

		String inferredType = (type == null || type.isBlank())
				? blankToDefault(String.valueOf(item.getOrDefault("bidNtceClsfc", ""))
						.replaceFirst("^민간", ""), "기타")
				: type;

		out.put("_source", "private-g2b");
		out.put("_sourceLabel", "누리장터");
		out.put("_type", inferredType);
		out.put("__sourceStage", "bid-announce");
		out.put("bidNtceNm", firstNonBlank(item, "bidNtceNm", "ntceNm"));
		out.put("bidNtceDt", firstNonBlank(item, "bidNtceDt", "nticeDt"));
		out.put("ntceInsttNm", firstNonBlank(item, "ntceInsttNm", "dminsttNm"));
		out.put("dminsttNm", firstNonBlank(item, "dminsttNm", "ntceInsttNm"));
		out.put("bsnsDivNm", blankToDefault(firstNonBlank(item, "bsnsDivNm"), inferredType));
		out.put("_noticeStatus", blankToDefault(firstNonBlank(item, "bidNtceClsfc", "ntceKindNm"), "민간공고"));
		return out;
	}

	/**
	 * 같은 공고번호의 여러 차수 중 최신 것만 남긴다.
	 *
	 * <p>정정공고가 나면 같은 {@code bidNtceNo} 로 차수만 다른 항목이 여러 건 온다.
	 * 출처({@code _source})까지 키에 넣는 이유는 나라장터·D2B·누리장터가 공고번호 체계를
	 * 공유하지 않아서, 우연히 번호가 겹치면 서로를 지워 버리기 때문이다.
	 */
	public static List<Map<String, Object>> selectLatestNoticeRevisions(List<Map<String, Object>> items) {
		if (items == null) {
			return List.of();
		}
		Map<String, Map<String, Object>> latest = new LinkedHashMap<>();
		for (Map<String, Object> item : items) {
			String source = String.valueOf(item.getOrDefault("_source", "g2b"));
			String key = source + "|" + item.getOrDefault("bidNtceNo", "");
			Map<String, Object> previous = latest.get(key);
			if (previous == null || isNewerRevision(item.get("bidNtceOrd"), previous.get("bidNtceOrd"))) {
				latest.put(key, item);
			}
		}
		return new ArrayList<>(new LinkedHashSet<>(latest.values()));
	}

	/**
	 * 차수 비교. 숫자 문자열이면 숫자로, 아니면 문자열로 본다.
	 *
	 * <p>한쪽만 숫자인 경우는 <b>비교 불가로 보고 기존 항목을 유지한다</b> —
	 * 원본 JS 가 {@code NaN} 비교로 false 를 내던 동작과 같다.
	 */
	private static boolean isNewerRevision(Object candidate, Object current) {
		Long a = asLongOrNull(candidate);
		Long b = asLongOrNull(current);
		if (a != null && b != null) {
			return a > b;
		}
		if (a == null && b == null) {
			return String.valueOf(candidate == null ? "" : candidate)
					.compareTo(String.valueOf(current == null ? "" : current)) > 0;
		}
		return false;
	}

	private static Long asLongOrNull(Object value) {
		String text = value == null ? "" : String.valueOf(value);
		if (text.isEmpty() || !text.chars().allMatch(Character::isDigit)) {
			return null;
		}
		try {
			return Long.valueOf(text);
		}
		catch (NumberFormatException e) {
			return null;
		}
	}

	private static String firstNonBlank(Map<String, Object> item, String... keys) {
		for (String key : keys) {
			Object value = item.get(key);
			if (value != null && !String.valueOf(value).isBlank()) {
				return String.valueOf(value);
			}
		}
		return "";
	}

	private static String blankToDefault(String value, String fallback) {
		return value == null || value.isBlank() ? fallback : value;
	}
}
