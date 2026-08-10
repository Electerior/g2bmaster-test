package com.electerior.g2bmaster.integration.g2b;

/**
 * 결과코드 {@code 07} — 입력범위값 초과. 조회 기간(또는 페이지 수)이 오퍼레이션 상한을 넘었다.
 *
 * <p>{@link G2bFetchService} 가 이 예외를 잡아 날짜 창을 반으로 갈라 재귀 호출한다.
 * 즉 여기서는 "실패"가 아니라 "더 쪼개라"는 신호에 가깝다.
 */
public class G2bRangeException extends G2bException {

	public G2bRangeException(String message, String resultCode, Integer httpStatus, String responseBody,
			Throwable cause) {
		super(message, resultCode, httpStatus, responseBody, cause);
	}

	public G2bRangeException(String message) {
		super(message, "07", null, null, null);
	}
}
