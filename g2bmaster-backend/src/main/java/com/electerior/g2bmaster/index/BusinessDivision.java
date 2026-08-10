package com.electerior.g2bmaster.index;

/**
 * 업종코드 — ERD {@code business_division} 의 값 집합 {@code {물품, 용역, 공사, 외자}}.
 *
 * <p>나라장터 API 의 근본 제약이 여기에 있다: 넷이 <b>각각 다른 오퍼레이션</b>이라 한 번의
 * 호출로 전부 훑을 수 없다. 적재기가 매 주기 네 번(+지역 1번) 도는 이유고, 검색이 로컬
 * 색인을 봐야 하는 이유이기도 하다 — 사용자 요청마다 이 팬아웃을 재현할 수는 없다.
 *
 * <p>출처마다 이름이 미묘하게 다른 것이 함정이다. 발주계획·사전규격은 용역을
 * <b>'일반용역'</b> 으로 부르고, 오퍼레이션 이름은 영문 약칭({@code Thng/Servc/Cnstwk/Frgcpt})
 * 이다. {@link #of(String)} 가 그 셋을 한 값으로 모은다.
 */
public enum BusinessDivision {

	물품,
	용역,
	공사,
	외자;

	/**
	 * 어떤 표기로 와도 하나로 모은다.
	 *
	 * <p>부분 일치를 쓰는 것은 '일반용역'·'기술용역'처럼 수식어가 붙은 표기 때문이다.
	 * 완전 일치로 두면 발주계획의 용역이 통째로 분류 실패한다.
	 *
	 * @return 알 수 없으면 {@code null}
	 */
	public static BusinessDivision of(String value) {
		if (value == null || value.isBlank()) {
			return null;
		}
		String v = value.trim();
		if (v.contains("물품") || v.contains("Thng")) {
			return 물품;
		}
		// 외자를 용역보다 먼저 본다 — '외자' 표기에 '용역'이 섞여 오는 건은 없지만,
		// 영문 약칭 Frgcpt 를 놓치면 외자 공고가 통째로 물품으로 떨어진다.
		if (v.contains("외자") || v.contains("Frgcpt")) {
			return 외자;
		}
		if (v.contains("용역") || v.contains("Servc")) {
			return 용역;
		}
		if (v.contains("공사") || v.contains("Cnstwk")) {
			return 공사;
		}
		return null;
	}
}
