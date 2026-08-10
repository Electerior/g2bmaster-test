package com.electerior.g2bmaster.integration.g2b;

/**
 * 429 중에서도 <em>일일 트래픽(토큰) 한도 소진</em>.
 *
 * <p>{@link G2bRateLimitException} 을 상속하는 것이 의도다. 원본에서도 쿼터 판정은 429 분기
 * 안에서만 이뤄지고, 클라이언트에 나가는 HTTP 상태는 동일하게 429여야 한다.
 *
 * <p>다만 성격이 정반대다. 이건 자정 초기화 전까지 절대 안 풀리므로 "1~2분 뒤 재시도" 안내가
 * 거짓말이 되고, 재시도는 순수한 낭비다. data.go.kr 은 순간 동시요청엔 관대하고(실측 15동시 OK),
 * 429는 사실상 일일 쿼터 소진을 뜻한다.
 */
public class G2bQuotaExceededException extends G2bRateLimitException {

	public G2bQuotaExceededException(String message, String resultCode, Integer httpStatus, String responseBody,
			Throwable cause) {
		super(message, resultCode, httpStatus, responseBody, cause);
	}

	public G2bQuotaExceededException(String message) {
		super(message);
	}
}
