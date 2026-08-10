package com.electerior.g2bmaster.integration.g2b;

import java.util.List;
import java.util.Map;

/**
 * 나라장터 응답 1페이지를 정규화한 결과.
 *
 * <p>{@code items} 는 항상 리스트다. 원본 응답은 항목이 1건일 때 배열이 아니라 객체 하나로
 * 접혀 오는데({@code items.item}), 그 차이를 여기서 흡수한다 — 호출부가 매번 "배열인가?"를
 * 확인하게 두면 언젠가 한 곳이 빠지고, 하필 그게 1건짜리 공고라 조용히 누락된다.
 *
 * @param items      정규화된 항목들 (항상 non-null, 빈 리스트 가능)
 * @param totalCount 조건에 맞는 전체 건수 (페이징 종료 판정에 쓴다)
 * @param pageNo     응답이 알려준 페이지 번호
 * @param numOfRows  응답이 알려준 페이지 크기
 * @param resultCode 정상은 {@code "00"}
 * @param resultMsg  결과 메시지
 */
public record G2bResponse(
		List<Map<String, Object>> items,
		int totalCount,
		int pageNo,
		int numOfRows,
		String resultCode,
		String resultMsg) {

	public static G2bResponse empty() {
		return new G2bResponse(List.of(), 0, 1, 0, "00", null);
	}

	public boolean isEmpty() {
		return items.isEmpty();
	}
}
