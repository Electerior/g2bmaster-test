package com.electerior.g2bmaster.integration.g2b;

/**
 * 서비스키 문제(미등록·오타·URL 인코딩 이중 적용·승인 대기).
 *
 * <p>재시도해도 절대 안 풀린다. {@link G2bApiClient} 의 재시도 대상에서 명시적으로 빠져 있다 —
 * 잘못된 키로 3번 더 두드리면 쿼터만 태운다.
 */
public class G2bAuthException extends G2bException {

	public G2bAuthException(String message, String resultCode, Integer httpStatus, String responseBody,
			Throwable cause) {
		super(message, resultCode, httpStatus, responseBody, cause);
	}

	public G2bAuthException(String message) {
		super(message, null, null, null, null);
	}
}
