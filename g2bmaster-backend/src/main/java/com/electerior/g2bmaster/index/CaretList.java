package com.electerior.g2bmaster.index;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 나라장터의 캐럿 구분 목록 문자열 파서.
 *
 * <p>{@code purchsObjPrdctList}(구매대상물품목록)와 {@code prdctDtlList}(사전규격 품목)는
 * JSON 이 아니라 이런 문자열로 온다:
 * <pre>
 *   [1^4110412701^실험실용공급기기]
 *   [1^2010170702^파쇄기설비],[1^2010179901^선회해머분쇄기],[1^2410172201^체인컨베이어]
 *   [1^^(긴급) 밸브,앵글형 1종]                     ← 품명번호가 비고, 품명에 콤마가 들어 있다
 * </pre>
 *
 * <p><b>콤마로 자르면 안 된다.</b> 세 번째 예처럼 품명 자체에 콤마가 들어간 건이 실제로 있고
 * (해군 '밸브,앵글형'), 그렇게 자르면 품목 하나가 둘로 쪼개져 없는 품명이 만들어진다.
 * 구분자는 콤마가 아니라 <b>{@code "],["}</b> 다 — 실측 300건에서 {@code "]["}(콤마 없는 인접)는
 * 한 건도 없었고 {@code "],["} 만 나온다.
 */
public final class CaretList {

	/**
	 * 한 공고에서 받아들일 품목 수 상한.
	 *
	 * <p>단가계약 공고는 같은 품명을 수백 줄로 반복한다(실측: 혈액대용제 10회 반복).
	 * 전부 담아도 검색에 보태는 것이 없고 JSON 만 부푼다.
	 */
	private static final int MAX_ENTRIES = 100;

	/** 품목 한 줄. {@code code} 는 세부품명번호이며 사전규격에서는 비어 있을 수 있다. */
	public record Entry(String seq, String code, String name) {}

	private CaretList() {}

	/**
	 * 캐럿 목록을 항목들로 편다. 파싱할 수 없으면 빈 리스트(예외를 던지지 않는다 —
	 * 품목 하나 때문에 공고 전체가 색인에서 빠지는 것이 훨씬 나쁘다).
	 */
	public static List<Entry> parse(String raw) {
		if (raw == null || raw.isBlank()) {
			return List.of();
		}
		String body = raw.trim();
		// 바깥 대괄호를 벗긴다. 한쪽만 있는 깨진 값도 있어 각각 확인한다.
		if (body.startsWith("[")) {
			body = body.substring(1);
		}
		if (body.endsWith("]")) {
			body = body.substring(0, body.length() - 1);
		}
		if (body.isBlank()) {
			return List.of();
		}

		List<Entry> entries = new ArrayList<>();
		// 구분자 앞뒤의 공백을 허용한다(일부 응답에 '], [' 가 섞인다).
		for (String segment : body.split("]\\s*,\\s*\\[", -1)) {
			if (segment.isBlank()) {
				continue;
			}
			// limit 3 — 품명에 '^' 가 들어가도 뒤쪽을 통째로 품명으로 남긴다.
			String[] parts = segment.split("\\^", 3);
			String seq = parts.length > 0 ? parts[0].trim() : "";
			String code = parts.length > 1 ? parts[1].trim() : "";
			String name = parts.length > 2 ? parts[2].trim() : "";
			// 셋 다 비면 담을 것이 없다. 품명만 있는 건(사전규격)은 유효하다.
			if (code.isEmpty() && name.isEmpty()) {
				continue;
			}
			entries.add(new Entry(seq, code, name));
			if (entries.size() >= MAX_ENTRIES) {
				break;
			}
		}
		return entries;
	}

	/**
	 * 검색 본문에 넣을 품명들 — <b>중복을 없앤다</b>.
	 *
	 * <p>같은 품명이 10번 반복되면 FULLTEXT 의 단어 빈도가 그만큼 부풀어, 단가계약 공고가
	 * 관련도 순 상위를 독차지한다. 표시용 {@code product_list} 는 원본 그대로 두되
	 * (품목 수가 곧 계약 규모의 단서다) 검색 텍스트에서는 한 번씩만 센다.
	 */
	public static List<String> distinctNames(List<Entry> entries) {
		Set<String> names = new LinkedHashSet<>();
		for (Entry entry : entries) {
			if (!entry.name().isEmpty()) {
				names.add(entry.name());
			}
		}
		return List.copyOf(names);
	}
}
