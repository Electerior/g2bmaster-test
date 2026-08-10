package com.electerior.g2bmaster.integration.g2b;

/**
 * 순간 과다요청으로 data.go.kr 이 429를 던진 경우. 잠깐 물러섰다 재시도하면 풀린다.
 */
public class G2bRateLimitException extends G2bException {

	public G2bRateLimitException(String message, String resultCode, Integer httpStatus, String responseBody,
			Throwable cause) {
		super(message, resultCode, httpStatus, responseBody, cause);
	}

	public G2bRateLimitException(String message) {
		super(message, null, null, null, null);
	}
}
