package com.electerior.g2bmaster.pricing;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 규격서가 요구한 조건과 후보 제품의 스펙을 맞춰 본다.
 *
 * <p><b>왜 백엔드인가.</b> "이 제품이 규격서와 맞는가"를 문자열로 대조하려던 것이
 * 지금까지의 오류 원인이었다 — 제품명 토큰이 절반만 겹쳐도 통과하는 바람에
 * {@code "Alphacool ES GPU 워터 블록 Nvidia H200 141GB"} 가 GPU 로 값매김됐다.
 * 그건 대조가 아니라 추측이다.
 *
 * <p>여기서 하는 일은 다르다. AI 가 규격서를 {@code {attr, op, value}} 로 뽑고
 * 제품 사전이 같은 어휘로 값을 내면, 남는 것은 <b>숫자 비교</b>뿐이다. 추론이 없으므로
 * 문서가 제각각이어도 성립한다.
 *
 * <p><b>판정하지 않는 것.</b> {@code approx}("동급"·"내외")는 폭이 정해지지 않은 요구다.
 * 여기에 임의의 허용 오차를 넣는 순간 백엔드가 다시 적합성을 추론하게 되므로,
 * {@link Outcome#UNJUDGED} 로 남기고 사람에게 넘긴다. 후보에 그 속성이 아예 없을 때도
 * 마찬가지다 — <b>모르는 것과 틀린 것은 다르다.</b>
 *
 * @see com.electerior.g2bmaster.attachment.SpecDocumentValidator 문서가 규격서인지의 판정
 */
public final class SpecConstraintMatcher {

	private SpecConstraintMatcher() {
	}

	/** 조건 하나의 판정. */
	public enum Outcome {
		/** 조건을 만족한다. */
		SATISFIED,
		/** 조건을 어긴다. */
		VIOLATED,
		/** 판정하지 않는다 — 후보에 값이 없거나, 폭이 정해지지 않은 요구다. */
		UNJUDGED
	}

	/**
	 * 규격서가 요구한 조건 하나.
	 *
	 * @param attr  공용 어휘의 속성 이름 (AI 저장소 {@code hardware_schema.ATTR_UNITS})
	 * @param op    {@code gte} · {@code lte} · {@code eq} · {@code approx}
	 * @param value 정규화된 값. 숫자 속성이면 숫자, 아니면 문자열
	 * @param raw   이 조건이 나온 규격서 원문 조각
	 */
	public record Constraint(String attr, String op, Object value, String raw) {
	}

	/**
	 * 조건 하나에 대한 결과.
	 *
	 * @param constraint 판정 대상
	 * @param outcome    판정
	 * @param actual     후보가 가진 값. 없으면 null
	 * @param reason     사람이 읽을 사유
	 */
	public record Result(Constraint constraint, Outcome outcome, Object actual, String reason) {
	}

	/**
	 * 후보 전체에 대한 판정.
	 *
	 * @param results   조건별 결과
	 * @param satisfied 만족한 조건 수
	 * @param violated  어긴 조건 수
	 * @param unjudged  판정하지 않은 조건 수
	 */
	public record Verdict(List<Result> results, int satisfied, int violated, int unjudged) {

		/** 어긴 조건이 하나도 없는가. 판정하지 못한 것은 어긴 것으로 세지 않는다. */
		public boolean acceptable() {
			return violated == 0;
		}

		/** 실제로 맞춰 본 조건이 하나라도 있는가. 전부 UNJUDGED 면 "맞다"고 말할 근거가 없다. */
		public boolean grounded() {
			return satisfied > 0;
		}
	}

	/**
	 * 조건 목록을 후보 스펙에 맞춰 본다.
	 *
	 * @param constraints 규격서가 요구한 것
	 * @param actual      후보 제품의 속성 (공용 어휘)
	 */
	public static Verdict match(List<Constraint> constraints, Map<String, Object> actual) {
		List<Result> results = new ArrayList<>();
		int satisfied = 0;
		int violated = 0;
		int unjudged = 0;

		for (Constraint constraint : constraints == null ? List.<Constraint>of() : constraints) {
			Result result = matchOne(constraint, actual == null ? Map.of() : actual);
			results.add(result);
			switch (result.outcome()) {
				case SATISFIED -> satisfied++;
				case VIOLATED -> violated++;
				case UNJUDGED -> unjudged++;
			}
		}
		return new Verdict(List.copyOf(results), satisfied, violated, unjudged);
	}

	private static Result matchOne(Constraint constraint, Map<String, Object> actual) {
		if (constraint == null || constraint.attr() == null || constraint.attr().isBlank()) {
			return new Result(constraint, Outcome.UNJUDGED, null, "속성 이름이 없다");
		}
		Object have = actual.get(constraint.attr());
		if (have == null) {
			// 후보 사전에 그 값이 없다. "요구를 어겼다"고 말할 근거가 없다.
			return new Result(constraint, Outcome.UNJUDGED, null, "후보에 " + constraint.attr() + " 값이 없다");
		}

		String op = constraint.op() == null ? "" : constraint.op().toLowerCase(Locale.ROOT);
		if ("approx".equals(op)) {
			// 폭이 정해지지 않은 요구다. 허용 오차를 지어내면 그때부터 추론이다.
			return new Result(constraint, Outcome.UNJUDGED, have, "\"동급·내외\" 는 기계가 판정하지 않는다");
		}

		Double want = toNumber(constraint.value());
		Double got = toNumber(have);
		if (want != null && got != null) {
			return numeric(constraint, op, want, got);
		}
		return textual(constraint, op, have);
	}

	private static Result numeric(Constraint constraint, String op, double want, double got) {
		boolean ok = switch (op) {
			case "gte" -> got >= want;
			case "lte" -> got <= want;
			// 부동소수 오차로 24000.0 != 24000.0000001 이 되는 것을 막는다. 값의 크기에
			// 비례한 허용치라 GFLOPS 처럼 큰 수에서도 같은 뜻이 된다.
			case "eq" -> Math.abs(got - want) <= Math.max(1e-9, Math.abs(want) * 1e-9);
			default -> false;
		};
		if (!"gte".equals(op) && !"lte".equals(op) && !"eq".equals(op)) {
			return new Result(constraint, Outcome.UNJUDGED, got, "모르는 연산자: " + op);
		}
		return new Result(constraint, ok ? Outcome.SATISFIED : Outcome.VIOLATED, got,
				"요구 " + op + " " + want + " · 후보 " + got);
	}

	private static Result textual(Constraint constraint, String op, Object have) {
		String want = String.valueOf(constraint.value()).trim();
		String got = String.valueOf(have).trim();
		if (want.isEmpty() || got.isEmpty()) {
			return new Result(constraint, Outcome.UNJUDGED, have, "값이 비어 있다");
		}
		if (!"eq".equals(op)) {
			// 문자열에 크기 비교를 걸 수는 없다. DDR5 가 DDR4 보다 "크다"는 건 우리 지식이지
			// 문서에 적힌 사실이 아니다.
			return new Result(constraint, Outcome.UNJUDGED, have, "문자열에는 " + op + " 를 적용하지 않는다");
		}
		boolean ok = normalize(got).contains(normalize(want)) || normalize(want).contains(normalize(got));
		return new Result(constraint, ok ? Outcome.SATISFIED : Outcome.VIOLATED, have,
				"요구 " + want + " · 후보 " + got);
	}

	/** 대소문자·공백·하이픈만 걷어낸다. {@code "DDR5 RDIMM"} 과 {@code "ddr5-rdimm"} 은 같은 말이다. */
	private static String normalize(String value) {
		return value.toLowerCase(Locale.ROOT).replaceAll("[\\s\\-_]+", "");
	}

	private static Double toNumber(Object value) {
		if (value instanceof Number number) {
			return number.doubleValue();
		}
		if (value instanceof Boolean flag) {
			return flag ? 1.0 : 0.0;
		}
		if (value instanceof String text) {
			String trimmed = text.trim();
			if (trimmed.isEmpty()) {
				return null;
			}
			try {
				return Double.valueOf(trimmed);
			}
			catch (NumberFormatException e) {
				return null;
			}
		}
		return null;
	}
}
