package com.electerior.g2bmaster.index;

/**
 * 공고 분류 — ERD {@code category} 의 값 집합 {@code {계획, 사전규격, 입찰, 마감}}.
 *
 * <p>넷은 서로 다른 종류가 아니라 <b>같은 조달 건의 생애주기 단계</b>다:
 * <pre>
 *   계획(발주계획) → 사전규격 → 입찰(공고 게시) → 마감(입찰 마감)
 * </pre>
 * 앞의 셋은 출처 오퍼레이션이 정하지만, {@link #입찰} 과 {@link #마감} 은 같은 오퍼레이션에서
 * 오고 <b>마감일시가 지났는지</b>로만 갈린다. 그래서 마감은 적재 시점의 판정만으로는 유지되지
 * 않는다 — 시간이 흐르면 저절로 틀려진다. 스위퍼({@code BidNoticeSyncScheduler#sweepClosed})가
 * 주기적으로 입찰 → 마감 전이를 밀어 준다.
 *
 * <p>DB 는 ENUM 이고 값이 한글 그대로다. 코드 상수와 DB 값을 일치시켜 두면 적재기·질의·화면이
 * 같은 문자열을 쓰므로 매핑 표가 필요 없다.
 */
public enum NoticeCategory {

	계획,
	사전규격,
	입찰,
	마감;

	/** 알 수 없는 값은 {@code null}. 질의 파라미터 정규화에 쓴다(모르는 값 = 필터 없음). */
	public static NoticeCategory of(String value) {
		if (value == null || value.isBlank()) {
			return null;
		}
		for (NoticeCategory category : values()) {
			if (category.name().equals(value.trim())) {
				return category;
			}
		}
		return null;
	}
}
