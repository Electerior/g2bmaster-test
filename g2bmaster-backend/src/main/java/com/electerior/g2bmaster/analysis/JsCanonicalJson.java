package com.electerior.g2bmaster.analysis;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import tools.jackson.databind.JsonNode;

/**
 * Node 의 {@code JSON.stringify} 와 <b>바이트 단위로 같은</b> 문자열을 만든다.
 *
 * <p>왜 Jackson 직렬화를 그냥 쓰지 않는가: 분석 입력 해시는 이 문자열의 SHA-256 이고,
 * 이관 시점에 Node 가 남긴 {@code analysis_history.input_hash} 와 맞아야 한다. 한 바이트만
 * 달라도 캐시가 통째로 고아가 되어 전부 다시 추론한다(첫 분석에 1~4분 걸린다).
 * Jackson 과 {@code JSON.stringify} 는 최소 세 곳에서 다르다:
 *
 * <ol>
 *   <li><b>숫자 표기</b> — JS 의 모든 숫자는 double 이고 {@code 1.0} 은 {@code 1} 로,
 *       {@code 1e21} 은 {@code 1e+21} 로 찍힌다. Java 의 {@code Double.toString} 은
 *       {@code "1.0"}, {@code "1.0E21"} 이다. ECMA-262 Number::toString 을 그대로 구현했다.</li>
 *   <li><b>이스케이프</b> — {@code JSON.stringify} 는 비ASCII 를 escape 하지 않고 그대로 내보내며,
 *       제어문자와 짝 없는 서로게이트만 소문자 {@code \\uXXXX} 로 바꾼다.</li>
 *   <li><b>키 정렬</b> — 원본이 {@code Object.keys(v).sort()} 로 UTF-16 코드유닛 순으로 정렬한다.
 *       Java {@code String.compareTo} 가 같은 순서이므로 그것을 쓴다(콜레이터가 아니다).</li>
 * </ol>
 *
 * <p>정규화 규칙 자체도 원본({@code canonicalize})을 그대로 옮겼다. 특히 <b>배열은 키 문맥을
 * 초기화한다</b> — {@code rawFields} 아래 배열 원소 안에서는 휘발성 필드가 제거되지 <em>않는다</em>.
 * 이상해 보이지만 원본의 실제 동작이고(고정값 테스트로 확인했다), "고치면" 해시가 달라진다.
 */
final class JsCanonicalJson {

	/**
	 * {@code rawFields} 문맥에서 제거하는 휘발성 필드.
	 *
	 * <p>검색 응답이 화면용으로 붙이는 값들이라 같은 공고라도 요청마다 달라진다.
	 * 해시에 들어가면 재사용이 사실상 불가능해진다.
	 */
	private static final Set<String> TRANSIENT_RAW_FIELDS =
			Set.of("__rowId", "_cached", "_opportunityScore", "_searchScore", "_analysis");

	private static final String RAW_FIELDS = "rawFields";

	private JsCanonicalJson() {
	}

	/** 정규화 + 직렬화. */
	static String stringify(JsonNode node) {
		StringBuilder out = new StringBuilder(512);
		write(node, "", out);
		return out.toString();
	}

	// ── 직렬화 ──────────────────────────────────────────────────────────────

	/**
	 * @param key 원본 {@code canonicalize(value, key)} 의 문맥 키. 최상위에서 {@code "rawFields"}
	 *            를 만나면 그 아래로 계속 전파되어 휘발성 필드 제거를 발동시킨다.
	 *            <b>배열을 지나면 초기화된다</b>(원본 {@code value.map(item => canonicalize(item))}).
	 */
	private static void write(JsonNode node, String key, StringBuilder out) {
		if (node == null || node.isMissingNode() || node.isNull()) {
			out.append("null");
			return;
		}
		if (node.isObject()) {
			writeObject(node, key, out);
			return;
		}
		if (node.isArray()) {
			writeArray(node, out);
			return;
		}
		if (node.isString()) {
			writeString(node.stringValue(), out);
			return;
		}
		if (node.isBoolean()) {
			out.append(node.booleanValue() ? "true" : "false");
			return;
		}
		if (node.isNumber()) {
			out.append(formatNumber(node.doubleValue()));
			return;
		}
		// 바이너리 등 JSON 에 없는 노드. 도달할 일이 없지만 조용히 틀린 해시를 내는 것보다 낫다.
		throw new IllegalArgumentException("정규화할 수 없는 JSON 노드입니다: " + node.getNodeType());
	}

	private static void writeObject(JsonNode node, String key, StringBuilder out) {
		List<String> names = new ArrayList<>();
		node.propertyNames().forEach(names::add);
		// UTF-16 코드유닛 순 — JS Array.prototype.sort 의 기본 비교와 같다.
		Collections.sort(names);

		out.append('{');
		boolean first = true;
		for (String name : names) {
			if (isDropped(name, key)) {
				continue;
			}
			if (!first) {
				out.append(',');
			}
			first = false;
			writeString(name, out);
			out.append(':');
			// 원본: canonicalize(value[name], key || name) — 문맥 키는 한 번 정해지면 유지된다.
			write(node.get(name), key.isEmpty() ? name : key, out);
		}
		out.append('}');
	}

	private static void writeArray(JsonNode node, StringBuilder out) {
		out.append('[');
		for (int i = 0; i < node.size(); i++) {
			if (i > 0) {
				out.append(',');
			}
			write(node.get(i), "", out);   // 배열은 문맥 키를 초기화한다 (원본 동작)
		}
		out.append(']');
	}

	private static boolean isDropped(String name, String key) {
		return (RAW_FIELDS.equals(key) && TRANSIENT_RAW_FIELDS.contains(name)) || name.startsWith("__");
	}

	// ── 문자열 이스케이프 (ECMA-262 QuoteJSONString) ─────────────────────────

	private static final char[] HEX = "0123456789abcdef".toCharArray();

	private static void writeString(String value, StringBuilder out) {
		out.append('"');
		for (int i = 0; i < value.length(); i++) {
			char c = value.charAt(i);
			switch (c) {
				case '"' -> out.append("\\\"");
				case '\\' -> out.append("\\\\");
				case '\b' -> out.append("\\b");
				case '\f' -> out.append("\\f");
				case '\n' -> out.append("\\n");
				case '\r' -> out.append("\\r");
				case '\t' -> out.append("\\t");
				default -> {
					if (c < 0x20) {
						appendUnicodeEscape(c, out);
					}
					else if (Character.isHighSurrogate(c)) {
						// 정상 쌍이면 그대로, 짝이 없으면 escape (ES2019 well-formed JSON.stringify)
						if (i + 1 < value.length() && Character.isLowSurrogate(value.charAt(i + 1))) {
							out.append(c).append(value.charAt(++i));
						}
						else {
							appendUnicodeEscape(c, out);
						}
					}
					else if (Character.isLowSurrogate(c)) {
						appendUnicodeEscape(c, out);   // 앞선 상위 서로게이트 없이 나온 하위 서로게이트
					}
					else {
						out.append(c);   // 비ASCII 는 escape 하지 않는다
					}
				}
			}
		}
		out.append('"');
	}

	private static void appendUnicodeEscape(char c, StringBuilder out) {
		out.append("\\u")
				.append(HEX[(c >> 12) & 0xF]).append(HEX[(c >> 8) & 0xF])
				.append(HEX[(c >> 4) & 0xF]).append(HEX[c & 0xF]);
	}

	// ── 숫자 표기 (ECMA-262 Number::toString) ────────────────────────────────

	/**
	 * JS 의 숫자 문자열화. JS 숫자는 전부 double 이므로 double 하나만 받는다.
	 *
	 * <p>{@code Double.toString} 은 JDK 19+ 부터 <b>왕복 가능한 최단 십진 표현</b>을 주며
	 * 이는 V8 이 쓰는 성질과 같다. 자릿수는 거기서 얻고, 자리 배치(지수 표기 전환점 등)만
	 * ECMA 규칙으로 다시 조립한다.
	 */
	static String formatNumber(double value) {
		if (Double.isNaN(value) || Double.isInfinite(value)) {
			return "null";   // JSON.stringify(NaN) === "null"
		}
		if (value == 0.0) {
			return "0";      // -0 도 "0"
		}
		StringBuilder out = new StringBuilder();
		if (value < 0) {
			out.append('-');
			value = -value;
		}

		String repr = Double.toString(value);
		int e = repr.indexOf('E');
		int exponent = e < 0 ? 0 : Integer.parseInt(repr.substring(e + 1));
		String mantissa = e < 0 ? repr : repr.substring(0, e);
		int dot = mantissa.indexOf('.');
		String intPart = dot < 0 ? mantissa : mantissa.substring(0, dot);
		String fracPart = dot < 0 ? "" : mantissa.substring(dot + 1);

		String digits = intPart + fracPart;
		// n: value = 0.digits × 10^n  (ECMA 의 n)
		int n = intPart.length() + exponent;

		int lead = 0;
		while (lead < digits.length() - 1 && digits.charAt(lead) == '0') {
			lead++;
			n--;
		}
		digits = digits.substring(lead);
		int end = digits.length();
		while (end > 1 && digits.charAt(end - 1) == '0') {
			end--;
		}
		digits = digits.substring(0, end);
		int k = digits.length();

		if (k <= n && n <= 21) {
			out.append(digits).append("0".repeat(n - k));
		}
		else if (0 < n && n <= 21) {
			out.append(digits, 0, n).append('.').append(digits, n, k);
		}
		else if (-6 < n && n <= 0) {
			out.append("0.").append("0".repeat(-n)).append(digits);
		}
		else {
			int exp = n - 1;
			if (k == 1) {
				out.append(digits);
			}
			else {
				out.append(digits.charAt(0)).append('.').append(digits, 1, k);
			}
			out.append('e').append(exp >= 0 ? '+' : '-').append(Math.abs(exp));
		}
		return out.toString();
	}
}
