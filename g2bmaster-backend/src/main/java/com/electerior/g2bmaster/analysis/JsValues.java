package com.electerior.g2bmaster.analysis;

import tools.jackson.databind.JsonNode;

/**
 * JsonNode 를 <b>자바스크립트 의미론</b>으로 읽는 헬퍼.
 *
 * <p>왜 필요한가: 분석 입력 해시는 Node 원본과 바이트 단위로 같아야 한다. 원본은
 * {@code input.title || ''}, {@code String(value ?? '')} 같은 JS 관용구로 값을 만드는데,
 * 그 관용구에는 자바에 대응이 없는 규칙이 들어 있다 — 숫자 {@code 0} 과 빈 문자열과
 * {@code false} 가 모두 거짓이고, {@code ??} 는 {@code null}/{@code undefined} 만 거른다.
 * 이 규칙을 호출부마다 손으로 흉내내면 반드시 어긋나므로 한 곳에 모아 둔다.
 *
 * @see AnalysisInputHasher
 */
final class JsValues {

	private JsValues() {
	}

	/** JS 진리값. {@code 0}·{@code ""}·{@code false}·{@code null}·부재는 거짓, 나머지는 참. */
	static boolean truthy(JsonNode node) {
		if (node == null || node.isMissingNode() || node.isNull()) {
			return false;
		}
		if (node.isBoolean()) {
			return node.booleanValue();
		}
		if (node.isNumber()) {
			double d = node.doubleValue();
			return d != 0.0 && !Double.isNaN(d);
		}
		if (node.isString()) {
			return !node.stringValue().isEmpty();
		}
		return true;   // 객체·배열은 비어 있어도 참이다.
	}

	/**
	 * JS {@code String(value)}.
	 *
	 * <p>{@code null}/부재를 {@code ""} 로 보는 것은 원본이 항상 {@code ?? ''} 를 붙여 쓰기
	 * 때문이다({@code String(null)} 자체는 {@code "null"} 이라 그대로 옮기면 틀린다).
	 */
	static String text(JsonNode node) {
		if (node == null || node.isMissingNode() || node.isNull()) {
			return "";
		}
		if (node.isString()) {
			return node.stringValue();
		}
		if (node.isNumber()) {
			return JsCanonicalJson.formatNumber(node.doubleValue());
		}
		if (node.isBoolean()) {
			return node.booleanValue() ? "true" : "false";
		}
		// 객체·배열이 여기 오는 것은 사실상 없다(자연키 후보는 전부 스칼라다).
		// JS 라면 "[object Object]" 가 되지만, 그 값이 해시에 들어가면 어차피 무의미하므로
		// 원본 문자열화 대신 JSON 표기를 쓴다. 실제 데이터에서 발생하면 로그가 아니라
		// 해시 불일치로 드러나므로 여기서 삼키지 않는 편이 낫다.
		return node.toString();
	}

	/** 원본 {@code firstText(...)} — 앞에서부터 훑어 공백을 걷어낸 첫 비어있지 않은 값. */
	static String firstText(JsonNode... nodes) {
		for (JsonNode node : nodes) {
			String value = text(node).trim();
			if (!value.isEmpty()) {
				return value;
			}
		}
		return "";
	}
}
