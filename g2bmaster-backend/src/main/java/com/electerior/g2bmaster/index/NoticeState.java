package com.electerior.g2bmaster.index;

/**
 * 공고 상태 — ERD {@code state} 의 값 집합 {@code {취소, 재, 다시, 정정}}.
 *
 * <p><b>네 값 모두 예외 상태다.</b> 정상적으로 처음 올라온 공고(원본 {@code ntceKindNm='등록공고'})는
 * 어디에도 해당하지 않으므로 컬럼이 {@code NULL} 이다. 실측(2026-07-01 물품 300건) 분포는
 * 등록공고 224 · 재공고 48 · 변경공고 14 · 취소공고 14 로, 압도적 다수가 NULL 이다.
 * '정상'을 뜻하는 다섯 번째 값을 만들지 않은 것은 ERD 의 선택이고, NULL 이 그 자리를 대신한다.
 *
 * <p>구분이 실무에서 갖는 뜻:
 * <ul>
 *   <li>{@link #취소} — 발주가 접혔다. 검색 결과에 남기되 눈에 띄게 표시해야 한다.</li>
 *   <li>{@link #재} — 유찰 뒤 같은 건을 다시 올린 것(재공고). 경쟁이 약했다는 신호다.</li>
 *   <li>{@link #다시} — 재공고마저 유찰돼 조건을 바꿔 다시 올린 것.</li>
 *   <li>{@link #정정} — 내용이 바뀌었다(변경공고). 차수가 올라간다.</li>
 * </ul>
 */
public enum NoticeState {

	취소,
	재,
	다시,
	정정;

	/**
	 * 나라장터 {@code ntceKindNm}(공고구분명) → 상태.
	 *
	 * <p>원본 값은 '…공고' 로 끝나는 명사다(등록공고·재공고·변경공고·취소공고·다시공고).
	 * 접두어로 맞추는 이유는 '긴급취소공고' 처럼 수식어가 붙어 오는 변형이 있어서다 —
	 * 완전 일치로 잡으면 그런 건이 조용히 '정상'으로 분류된다.
	 *
	 * @return 해당 없음(=등록공고 등 정상)이면 {@code null}
	 */
	public static NoticeState fromNoticeKind(String noticeKindName) {
		if (noticeKindName == null || noticeKindName.isBlank()) {
			return null;
		}
		String kind = noticeKindName.trim();
		if (kind.contains("취소")) {
			return 취소;
		}
		// '다시공고' 를 '재' 보다 먼저 본다. 뒤로 미루면 순서에 따라 다시공고가 재로 접힌다.
		if (kind.contains("다시")) {
			return 다시;
		}
		if (kind.contains("재공고")) {
			return 재;
		}
		if (kind.contains("변경") || kind.contains("정정")) {
			return 정정;
		}
		return null;
	}

	/** 알 수 없는 값은 {@code null}(= 필터 없음). */
	public static NoticeState of(String value) {
		if (value == null || value.isBlank()) {
			return null;
		}
		for (NoticeState state : values()) {
			if (state.name().equals(value.trim())) {
				return state;
			}
		}
		return null;
	}
}
