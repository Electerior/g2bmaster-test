package com.electerior.g2bmaster.analysis;

import java.util.Locale;
import java.util.Set;
import tools.jackson.databind.JsonNode;

/**
 * 분석 대상의 자연키 — {@code lib/analysis-history.js} 의 {@code resolveAnalysisIdentity} 이식.
 *
 * <p>세 엔티티(발주계획·사전규격·입찰공고)가 서로 다른 자연키를 쓴다. 어느 것으로 볼지는
 * 요청이 {@code entityType} 을 명시했더라도 <b>그 타입의 값이 실제로 있을 때만</b> 따르고,
 * 없으면 값이 있는 것 중 하나를 순서대로 고른다. 원본의 동작이며 바꾸면 해시가 달라진다.
 *
 * <p>{@code ord}(차수)는 입찰공고에만 있다. 나머지는 항상 빈 문자열이어야 한다 —
 * {@code analysis_job} 의 유일 인덱스가 {@code entity_ord} 를 포함하므로, 여기서 null 을 흘리면
 * 같은 건이 두 행으로 갈라진다.
 *
 * @param type {@code procurement_request} / {@code pre_spec} / {@code bid_notice}
 * @param id   자연키 값
 * @param ord  차수 (입찰공고 전용, 그 외에는 {@code ""})
 */
public record AnalysisIdentity(String type, String id, String ord) {

	public static final String PROCUREMENT_REQUEST = "procurement_request";
	public static final String PRE_SPEC = "pre_spec";
	public static final String BID_NOTICE = "bid_notice";

	public static final Set<String> ENTITY_TYPES = Set.of(PROCUREMENT_REQUEST, PRE_SPEC, BID_NOTICE);

	public AnalysisIdentity {
		id = id == null ? "" : id;
		ord = ord == null ? "" : ord;
	}

	/** 원본 {@code normalizeEntityType} — 하이픈을 밑줄로 바꾸고 소문자화한 뒤 화이트리스트로 거른다. */
	public static String normalizeEntityType(String value) {
		if (value == null) {
			return "";
		}
		String normalized = value.trim().toLowerCase(Locale.ROOT).replace('-', '_');
		return ENTITY_TYPES.contains(normalized) ? normalized : "";
	}

	/**
	 * 요청 본문에서 자연키를 뽑는다. 어느 것도 찾지 못하면 {@code null} — 원본과 같이
	 * 호출부가 "분석 대상 아님"으로 처리해야 한다.
	 */
	public static AnalysisIdentity resolve(JsonNode input) {
		if (input == null) {
			return null;
		}
		JsonNode raw = input.path("rawFields");

		String pr = JsValues.firstText(
				input.path("prcrmntReqNo"), raw.path("prcrmntReqNo"), raw.path("prcrmnt_req_no"));
		String ps = JsValues.firstText(
				input.path("bfSpecRgstNo"), raw.path("bfSpecRgstNo"), raw.path("bf_spec_rgst_no"));
		String bn = JsValues.firstText(
				input.path("bidNtceNo"), raw.path("bidNtceNo"), raw.path("bid_ntce_no"));

		String requested = normalizeEntityType(JsValues.text(input.path("entityType")));
		String type = switch (requested) {
			case PROCUREMENT_REQUEST -> pr.isEmpty() ? "" : PROCUREMENT_REQUEST;
			case PRE_SPEC -> ps.isEmpty() ? "" : PRE_SPEC;
			case BID_NOTICE -> bn.isEmpty() ? "" : BID_NOTICE;
			default -> "";
		};
		if (type.isEmpty()) {
			// 명시 타입이 없거나 그 타입의 값이 비어 있으면 값이 있는 것을 순서대로 고른다.
			type = !pr.isEmpty() ? PROCUREMENT_REQUEST
					: !ps.isEmpty() ? PRE_SPEC
					: !bn.isEmpty() ? BID_NOTICE : "";
		}
		if (type.isEmpty()) {
			return null;
		}

		String id = switch (type) {
			case PROCUREMENT_REQUEST -> pr;
			case PRE_SPEC -> ps;
			default -> bn;
		};
		String ord = BID_NOTICE.equals(type)
				? JsValues.firstText(
						input.path("bidNtceSqNo"), input.path("bidNtceOrd"),
						raw.path("bidNtceSqNo"), raw.path("bidNtceOrd"), raw.path("bid_ntce_ord"))
				: "";
		return new AnalysisIdentity(type, id, ord);
	}
}
