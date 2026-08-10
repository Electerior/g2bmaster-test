package com.electerior.g2bmaster.integration.g2b;

/**
 * 나라장터 OpenAPI 호출이 실패했을 때 던지는 기본 예외.
 *
 * <p>JS 원본은 {@code Error} 에 {@code g2bCode}/{@code response.status} 를 붙여 다녔고,
 * 분류는 정규식으로 사후에 했다. 자바에서는 던지는 쪽이 이미 아는 정보(결과코드·HTTP 상태·
 * 응답 본문)를 필드로 실어 보낸다 — 그래야 {@link G2bErrorTranslator} 가 메시지 문자열을
 * 뒤지지 않고도 확실히 분류할 수 있다. 정규식 분류는 이 정보가 없는 하위 예외(소켓/파싱 등)를
 * 위한 보조 수단으로만 남는다.
 */
public class G2bException extends RuntimeException {

	/** G2B 응답 헤더의 {@code resultCode}(예: 범위 초과 {@code "07"}). 없으면 null. */
	private final String resultCode;

	/** 상류 HTTP 상태. 없으면 null. */
	private final Integer httpStatus;

	/** 응답 본문 일부 — 일일 쿼터 소진 판정({@code LIMITED_NUMBER} 등)에 필요하다. */
	private final String responseBody;

	public G2bException(String message) {
		this(message, null, null, null, null);
	}

	public G2bException(String message, Throwable cause) {
		this(message, null, null, null, cause);
	}

	public G2bException(String message, String resultCode, Integer httpStatus, String responseBody) {
		this(message, resultCode, httpStatus, responseBody, null);
	}

	public G2bException(String message, String resultCode, Integer httpStatus, String responseBody,
			Throwable cause) {
		super(message, cause);
		this.resultCode = resultCode;
		this.httpStatus = httpStatus;
		this.responseBody = responseBody;
	}

	public String getResultCode() {
		return resultCode;
	}

	public Integer getHttpStatus() {
		return httpStatus;
	}

	public String getResponseBody() {
		return responseBody;
	}
}
