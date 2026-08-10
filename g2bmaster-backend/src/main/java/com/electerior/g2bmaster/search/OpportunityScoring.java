package com.electerior.g2bmaster.search;

import com.electerior.g2bmaster.common.G2bDates;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 수주기회 스코어링 — {@code lib/scoring.js} 의 이식.
 *
 * <p>제품 적합도(35) + AMD·전략 연계(20) + 공고 단계(25) + 마감 잔여일(10) +
 * 담당자 접촉 가능성(5) 을 더해 0~100 으로 자른다.
 *
 * <p><b>가중치와 한국어 키워드 목록은 실무에서 튜닝된 값이다. 정리하거나 "개선"하지 말 것.</b>
 * 예컨대 입찰공고 단계의 일반구매에 -10 을 주는 것은 버그가 아니라
 * "규격이 이미 확정돼 영업 여지가 없다"는 판단을 점수에 반영한 것이다.
 *
 * <p>LLM 은 전혀 관여하지 않는다 — 순수 규칙이므로 AI 없이도 동작한다.
 */
public final class OpportunityScoring {

	// ── 규격잠금(특정 벤더 고정) 탐지 ──────────────────────────────────────
	private static final Pattern INTEL_CPU = Pattern.compile(
			"\\bintel\\b|인텔|코어\\s*ultra|core\\s*ultra|\\bcore\\s*i[3579]\\b|코어\\s*i[3579]|\\bxeon\\b|\\bceleron\\b|\\bpentium\\b");
	private static final Pattern INTEL_GPU = Pattern.compile(
			"intel\\s*arc|인텔\\s*arc|\\barc\\s*graphics\\b|iris\\s*xe");
	private static final Pattern INTEL_WIFI = Pattern.compile(
			"ax211|ax201|intel\\s*wi-?fi|인텔\\s*wi-?fi|wi-?fi\\s*6e?\\s*ax");
	private static final Pattern THUNDERBOLT = Pattern.compile("thunderbolt|썬더볼트");
	private static final Pattern FIXED_MODEL = Pattern.compile(
			"특정\\s*모델|모델\\s*지정|동등품\\s*불가|동등\\s*이상\\s*불가|제조사\\s*지정|브랜드\\s*지정|상기\\s*모델");

	// ── 제품 적합도 ────────────────────────────────────────────────────────
	private static final Pattern SERVER = Pattern.compile("서버|워크스테이션|hpc|슈퍼컴|고성능컴퓨팅|ai\\s*인프라|ai\\s*서버");
	private static final Pattern GPU = Pattern.compile("gpu|그래픽카드|그래픽\\s*카드|연산장치|슈퍼컴퓨터|가속기");
	private static final Pattern PC_WIDE = Pattern.compile(
			"노트북|데스크탑|데스크\\s*탑|컴퓨터|pc\\b|퍼스널\\s*컴퓨터|태블릿|정보화\\s*기기");
	private static final Pattern PC = Pattern.compile("노트북|데스크탑|데스크\\s*탑|컴퓨터|pc\\b|퍼스널\\s*컴퓨터");
	private static final Pattern RENTAL_NARROW = Pattern.compile("임대|렌탈|대여|리스");
	private static final Pattern RENTAL_WIDE = Pattern.compile("임대|렌탈|대여|리스|임차");
	private static final Pattern WATER = Pattern.compile("정수기|비데|냉온수기|얼음정수기|얼음\\s*정수기|냉수기");
	private static final Pattern OFFICE = Pattern.compile("사무기기|프린터|복합기|모니터");
	private static final Pattern NETWORK = Pattern.compile("네트워크|스위치|라우터|스토리지|nas\\b|ups\\b|랙|rack");
	private static final Pattern SOFTWARE = Pattern.compile("소프트웨어|sw\\b|라이선스|솔루션");

	private static final Pattern AMD = Pattern.compile("\\bamd\\b|라이젠|ryzen|epyc|threadripper|radeon");
	private static final Pattern INTEL_ANY = Pattern.compile(
			"\\bintel\\b|인텔|코어\\s*i[3579]|\\bxeon\\b|\\bceleron\\b|\\bcore\\s*ultra");
	private static final Pattern MAINTENANCE = Pattern.compile("유지보수|유지\\s*보수|운영|서비스");

	/** 공고 단계. 점수 배분이 단계마다 완전히 다르다. */
	public enum Stage {
		PRE_SPEC("pre-spec"),
		BID_PLAN("bid-plan"),
		BID_ANNOUNCE("bid-announce"),
		OTHER("");

		private final String key;

		Stage(String key) {
			this.key = key;
		}

		public String key() {
			return key;
		}

		public static Stage of(String key) {
			for (Stage stage : values()) {
				if (stage.key.equals(key)) {
					return stage;
				}
			}
			return OTHER;
		}
	}

	/** 규격잠금 경고 한 건. */
	public record SpecLockWarning(String label, String detail) {}

	/** 규격잠금 판정 결과. */
	public record SpecLock(List<SpecLockWarning> warnings, String summary) {}

	/** 스코어링 결과. 필드명은 프론트 계약이므로 그대로 유지한다. */
	public record Opportunity(
			int score,
			String grade,
			String action,
			List<String> reasons,
			String summary,
			List<SpecLockWarning> specLockWarnings,
			String specLockSummary) {

		/** 검색 응답 항목에 얹을 때 쓰는 {@code _} 접두 필드들. */
		public Map<String, Object> asFields() {
			Map<String, Object> fields = new LinkedHashMap<>();
			fields.put("_opportunityScore", score);
			fields.put("_opportunityGrade", grade);
			fields.put("_opportunityAction", action);
			fields.put("_opportunityReasons", reasons);
			fields.put("_opportunitySummary", summary);
			fields.put("_specLockWarnings", specLockWarnings);
			fields.put("_specLockSummary", specLockSummary);
			return fields;
		}
	}

	private OpportunityScoring() {
	}

	/**
	 * 판정 대상 텍스트 — {@code _} 로 시작하지 않는 모든 값을 이어붙인 것.
	 *
	 * <p>{@code _} 접두 필드를 빼는 이유는 그것들이 <b>이전 스코어링이 남긴 결과</b>이기
	 * 때문이다. 포함시키면 자기 출력을 입력으로 먹는다.
	 */
	public static String opportunityText(Map<String, Object> item) {
		if (item == null) {
			return "";
		}
		StringBuilder sb = new StringBuilder();
		for (Map.Entry<String, Object> entry : item.entrySet()) {
			if (entry.getKey().startsWith("_") || entry.getValue() == null) {
				continue;
			}
			if (!sb.isEmpty()) {
				sb.append(' ');
			}
			sb.append(entry.getValue());
		}
		return sb.toString().toLowerCase(Locale.ROOT);
	}

	/** 단계별 마감 기준일까지 남은 일수. 해당 없으면 null. */
	public static Integer opportunityDeadline(Map<String, Object> item, Stage stage) {
		return switch (stage) {
			case PRE_SPEC -> G2bDates.daysUntil(item.get("opninRgstClseDt"));
			case BID_ANNOUNCE -> G2bDates.daysUntil(item.get("bidClseDt"));
			default -> null;
		};
	}

	public static boolean isExpired(Map<String, Object> item, Stage stage) {
		Integer deadline = opportunityDeadline(item, stage);
		return deadline != null && deadline < 0;
	}

	/** 특정 벤더로 규격이 고정된 정황을 찾는다. 사전규격 단계에서 개입 근거가 된다. */
	public static SpecLock detectSpecLockRisks(Map<String, Object> item) {
		String text = opportunityText(item);
		List<SpecLockWarning> warnings = new ArrayList<>();

		if (INTEL_CPU.matcher(text).find()) {
			warnings.add(new SpecLockWarning("Intel CPU 고정 의심",
					"AMD Ryzen/Ryzen PRO/EPYC 동등 이상 대체 질의 필요"));
		}
		if (INTEL_GPU.matcher(text).find()) {
			warnings.add(new SpecLockWarning("Intel 그래픽 고정 의심",
					"내장/외장 그래픽 동등 이상 허용 여부 확인"));
		}
		if (INTEL_WIFI.matcher(text).find()) {
			warnings.add(new SpecLockWarning("Intel 무선칩셋 고정 의심",
					"Wi-Fi 6/6E 동등 규격 허용 문구 요청"));
		}
		if (THUNDERBOLT.matcher(text).find()) {
			warnings.add(new SpecLockWarning("Thunderbolt 조건 확인", "USB4/동등 포트 허용 여부 확인"));
		}
		if (FIXED_MODEL.matcher(text).find()) {
			warnings.add(new SpecLockWarning("특정 모델 제한 의심",
					"특정 모델 필수인지 동등 이상 제안 가능 여부 질의"));
		}

		String summary = String.join(", ", warnings.stream().map(SpecLockWarning::label).toList());
		return new SpecLock(List.copyOf(warnings), summary);
	}

	public static Opportunity scoreOpportunity(Map<String, Object> item, Stage stage) {
		String text = opportunityText(item);
		SpecLock specLock = detectSpecLockRisks(item);
		List<String> reasons = new ArrayList<>();
		int score = 0;

		Integer deadline = opportunityDeadline(item, stage);

		// 마감 경과는 조기 반환 — 점수를 매길 이유가 없다.
		if (deadline != null && deadline < 0) {
			return new Opportunity(0, "X", "마감제외",
					List.of("마감 경과: 수주기회 레이더 제외"), "X 0점 · 마감제외",
					specLock.warnings(), specLock.summary());
		}

		// 카테고리 1: 제품 적합도 [최대 35]
		if (SERVER.matcher(text).find()) {
			score += 35;
			reasons.add("서버/워크스테이션/HPC — 핵심 납품 품목");
		}
		else if (GPU.matcher(text).find()) {
			score += 32;
			reasons.add("GPU/그래픽카드/연산장치 — 핵심 납품 품목");
		}
		else if (PC_WIDE.matcher(text).find() && RENTAL_NARROW.matcher(text).find()) {
			score += 30;
			reasons.add("PC/노트북 렌탈 — 전략 집중 품목");
		}
		else if (PC.matcher(text).find()) {
			score += 20;
			reasons.add("노트북/데스크탑/PC — 납품 가능 품목");
		}
		else if (WATER.matcher(text).find()) {
			score += 15;
			reasons.add("정수기/비데/냉온수기 — 부수 품목");
		}
		else if (OFFICE.matcher(text).find()) {
			score += 12;
			reasons.add("사무기기/프린터/복합기 — 납품 가능 품목");
		}
		else if (NETWORK.matcher(text).find()) {
			score += 12;
			reasons.add("네트워크/스토리지 — 납품 가능 품목");
		}
		else if (SOFTWARE.matcher(text).find()) {
			score += 5;
			reasons.add("소프트웨어 단독");
		}

		// 카테고리 2: AMD·전략 연계 [최대 20]
		boolean hasAmd = AMD.matcher(text).find();
		boolean hasIntel = INTEL_ANY.matcher(text).find();

		if (hasAmd) {
			score += 15;
			reasons.add("AMD 브랜드 직접 언급 — 최적합");
		}
		if (!specLock.warnings().isEmpty() && stage != Stage.BID_ANNOUNCE) {
			score += 12;
			reasons.add("규격잠금 의심 → 사전규격 개입 가능: " + specLock.summary());
		}
		else if (hasIntel && stage != Stage.BID_ANNOUNCE) {
			score += 10;
			reasons.add("Intel 고정 의심 → AMD 대체 제안 가능");
		}
		else if (hasIntel) {
			score -= 10;
			reasons.add("Intel 완전 고정 → 입찰공고 단계 (개입 불가)");
		}
		if (!hasAmd && !hasIntel && specLock.warnings().isEmpty()) {
			score += 8;
			reasons.add("오픈 스펙 — 브랜드 무관 제안 가능");
		}
		if (MAINTENANCE.matcher(text).find() && !RENTAL_NARROW.matcher(text).find()) {
			score += 5;
			reasons.add("유지보수/운영 서비스 조건 포함");
		}

		// 카테고리 3: 공고 단계
		// 사전규격·발주계획이 압도적으로 중요하다 — 규격 개입과 선제 영업이 핵심이기 때문.
		// 입찰공고는 렌탈·임차만 의미가 있고, 일반 구매는 이미 규격이 확정돼 여지가 없다.
		boolean isRental = RENTAL_WIDE.matcher(text).find();
		switch (stage) {
			case PRE_SPEC -> {
				score += 25;
				reasons.add("사전규격: 규격 직접 개입 가능 — 최우선");
			}
			case BID_PLAN -> {
				score += 20;
				reasons.add("발주계획: 공고 전 선제 영업 가능");
			}
			case BID_ANNOUNCE -> {
				if (isRental) {
					score += 10;
					reasons.add("입찰공고 렌탈·임차 — 실질적 참여 기회");
				}
				else {
					score -= 10;
					reasons.add("입찰공고 일반구매 — 규격 확정, 개입 여지 없음");
				}
			}
			default -> { }
		}

		// 카테고리 4: 마감 잔여일 [최대 10]
		if (deadline != null) {
			if (stage == Stage.BID_ANNOUNCE) {
				// 입찰공고는 마감이 임박할수록 긴박한 행동이 필요하다.
				if (deadline <= 3) {
					score += 10;
					reasons.add("마감 D-3 이내 — 즉시 행동");
				}
				else if (deadline <= 7) {
					score += 8;
					reasons.add("마감 D-7 이내 — 빠른 검토 필요");
				}
				else if (deadline <= 14) {
					score += 6;
					reasons.add("마감 D-14 이내");
				}
				else {
					score += 4;
					reasons.add("마감 여유 있음");
				}
			}
			else {
				// 사전규격·발주계획은 반대다 — 여유가 있어야 준비할 수 있다.
				if (deadline >= 7) {
					score += 10;
					reasons.add("잔여 7일 이상 — 충분한 준비 가능");
				}
				else if (deadline >= 3) {
					score += 7;
					reasons.add("잔여 3~7일");
				}
				else {
					score += 3;
					reasons.add("마감 임박 — 즉시 대응 필요");
				}
			}
		}

		// 카테고리 5: 담당자 접촉 가능성 [최대 5]
		boolean hasTel = present(item, "ntceInsttOfclTelNo", "chargerTelNo", "telNo", "ofclTelNo");
		boolean hasEmail = present(item, "ntceInsttOfclEmailAdrs", "chargerEmail", "ofclEmailAdrs");
		if (hasTel && hasEmail) {
			score += 5;
			reasons.add("전화 + 이메일 모두 확인 가능");
		}
		else if (hasTel) {
			score += 3;
			reasons.add("담당자 전화 있음");
		}
		else if (hasEmail) {
			score += 2;
			reasons.add("담당자 이메일 있음");
		}

		// 이론상 최대 85점(35+20+25+10+5)이지만 상한은 100 으로 둔다.
		score = Math.max(0, Math.min(100, score));

		String grade = score >= 75 ? "S" : score >= 60 ? "A" : score >= 45 ? "B" : score >= 30 ? "C" : "D";
		String action = switch (stage) {
			case PRE_SPEC -> score >= 60 ? "규격개입" : "의견검토";
			case BID_PLAN -> score >= 60 ? "선제영업" : "추적";
			default -> score >= 60 ? "즉시질의" : "참여검토";
		};

		List<String> topReasons = reasons.size() > 4 ? List.copyOf(reasons.subList(0, 4)) : List.copyOf(reasons);
		return new Opportunity(score, grade, action, topReasons,
				grade + " " + score + "점 · " + action,
				specLock.warnings(), specLock.summary());
	}

	/**
	 * 항목 목록에 스코어를 얹는다.
	 *
	 * <p>기존 {@code _opportunity*} 필드를 먼저 지우는 것이 중요하다 — 남겨 두면
	 * {@link #opportunityText} 가 걸러 주더라도 재계산 결과와 뒤섞인 상태가 응답에 나간다.
	 */
	public static List<Map<String, Object>> applyOpportunity(List<Map<String, Object>> items, Stage stage) {
		if (items == null) {
			return List.of();
		}
		List<Map<String, Object>> out = new ArrayList<>(items.size());
		for (Map<String, Object> item : items) {
			Map<String, Object> copy = new LinkedHashMap<>(item == null ? Map.of() : item);
			copy.keySet().removeIf(key -> key.startsWith("_opportunity"));
			copy.putAll(scoreOpportunity(copy, stage).asFields());
			out.add(copy);
		}
		return out;
	}

	/** 마감 경과 항목 제거. 사전규격·입찰공고 단계에서만 의미가 있다. */
	public static List<Map<String, Object>> removeExpired(List<Map<String, Object>> items, Stage stage) {
		if (items == null) {
			return List.of();
		}
		if (stage != Stage.PRE_SPEC && stage != Stage.BID_ANNOUNCE) {
			return items;
		}
		return items.stream().filter(item -> !isExpired(item, stage)).toList();
	}

	/** 점수 내림차순, 동점이면 날짜 내림차순. */
	public static List<Map<String, Object>> sortByOpportunity(List<Map<String, Object>> items, String dateKey) {
		if (items == null) {
			return List.of();
		}
		List<Map<String, Object>> copy = new ArrayList<>(items);
		copy.sort((a, b) -> {
			int byScore = Integer.compare(intOf(b.get("_opportunityScore")), intOf(a.get("_opportunityScore")));
			if (byScore != 0) {
				return byScore;
			}
			return String.valueOf(b.getOrDefault(dateKey, ""))
					.compareTo(String.valueOf(a.getOrDefault(dateKey, "")));
		});
		return copy;
	}

	private static boolean present(Map<String, Object> item, String... keys) {
		for (String key : keys) {
			Object value = item.get(key);
			if (value != null && !String.valueOf(value).isBlank()) {
				return true;
			}
		}
		return false;
	}

	private static int intOf(Object value) {
		return value instanceof Number n ? n.intValue() : 0;
	}
}
