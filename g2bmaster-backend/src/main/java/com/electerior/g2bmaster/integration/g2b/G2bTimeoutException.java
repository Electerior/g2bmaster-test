package com.electerior.g2bmaster.integration.g2b;

/**
 * 상류 응답 지연.
 *
 * <p>2026-07-20 실측: G2B 응답이 평균 6~9초, 최대 12.8초까지 걸린다. 건수와 무관한 서버측
 * 변동이라 타임아웃을 너무 짧게 잡으면 정상 응답이 타임아웃으로 처리되고, 타임아웃은 범위
 * 이분 분할을 유발해 오히려 호출량을 늘린다(그래서 기본 20초).
 */
public class G2bTimeoutException extends G2bException {

	public G2bTimeoutException(String message, Throwable cause) {
		super(message, cause);
	}
}
