package com.electerior.g2bmaster.integration.d2b;

/**
 * D2B 호출 실패.
 *
 * <p>{@code G2bException} 과 분리한다 — 전역 예외 처리기가 나라장터 실패를 502/503 으로
 * 바꾸는데, D2B 하나가 삐끗했다고 나라장터 결과까지 있는 검색을 5xx 로 떨어뜨리면 안 된다.
 * 이 예외는 검색 계층에서 {@code sourceErrors} 로 흡수되는 것이 정상 경로다.
 */
public class D2bException extends RuntimeException {

	public D2bException(String message) {
		super(message);
	}

	public D2bException(String message, Throwable cause) {
		super(message, cause);
	}
}
