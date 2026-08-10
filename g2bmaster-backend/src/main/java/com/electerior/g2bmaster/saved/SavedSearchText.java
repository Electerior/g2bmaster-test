package com.electerior.g2bmaster.saved;

import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.JsonNode;

/**
 * 저장 공고 검색용 한 칸을 만든다 — 원본 {@code server.js} 의 {@code savedSearchText}.
 *
 * <p><b>가격표의 품목명이 여기 들어가는 것이 핵심이다.</b> 제목·기관·요약·메모만 넣으면
 * "RTX 5090 넣었던 건 어디였지"로 찾을 수 없고, 그러면 저장 기능 자체가 쓸모없어진다.
 * 부품명은 공고 제목 어디에도 나오지 않는다 — 사람이 견적을 짜면서 채워 넣은 값이다.
 *
 * <p>인덱스가 없는 {@code MEDIUMTEXT} 를 통째로 훑는 구조라 상한이 필요하다. 20만자는 원본 값이다.
 * (느려지면 {@code FULLTEXT ... WITH PARSER ngram} 이 올바른 MySQL 대체재이며, 그때도 이 칸을 쓴다.)
 */
final class SavedSearchText {

	/** 원본 {@code .slice(0, 200000)}. */
	static final int MAX_LENGTH = 200_000;

	/** 원본 {@code .join(' \n ')} — 공백·개행·공백. 낱말이 서로 붙지 않게만 하면 된다. */
	private static final String SEPARATOR = " \n ";

	private SavedSearchText() {
	}

	static String build(String title, String insttNm, String summary, String note, JsonNode priceRows) {
		List<String> parts = new ArrayList<>();
		add(parts, title);
		add(parts, insttNm);
		add(parts, summary);
		add(parts, note);
		if (priceRows != null && priceRows.isArray()) {
			for (JsonNode row : priceRows) {
				// 원본은 name 과 resolvedName 을 모두 넣는다. 규격 문구로 검색하는 사람과
				// 실제 모델명으로 검색하는 사람이 둘 다 있기 때문이다.
				add(parts, textOf(row.path("name")));
				add(parts, textOf(row.path("resolvedName")));
			}
		}
		String joined = String.join(SEPARATOR, parts);
		return joined.length() > MAX_LENGTH ? joined.substring(0, MAX_LENGTH) : joined;
	}

	/** 원본 {@code .filter(Boolean)} — 빈 문자열만 걸러낸다(공백만 있는 값은 원본도 남긴다). */
	private static void add(List<String> parts, String value) {
		if (value != null && !value.isEmpty()) {
			parts.add(value);
		}
	}

	private static String textOf(JsonNode node) {
		if (node == null || node.isMissingNode() || node.isNull()) {
			return null;
		}
		return node.isString() ? node.stringValue() : node.toString();
	}
}
