package com.electerior.g2bmaster.common;

import org.springframework.http.HttpStatus;

/**
 * 클라이언트에게 그대로 보여줄 오류.
 *
 * <p>기존 서버는 어디서든 {@code res.status(n).json({ error: '한국어 메시지' })} 로 끝냈고,
 * 그 한국어 문구가 화면에 그대로 렌더링된다. 문구는 사용자 대상 계약이므로 이식 과정에서
 * 임의로 바꾸지 않는다.
 */
public class ApiException extends RuntimeException {

	private final HttpStatus status;
	private final String code;

	public ApiException(HttpStatus status, String message) {
		this(status, message, null);
	}

	public ApiException(HttpStatus status, String message, String code) {
		super(message);
		this.status = status;
		this.code = code;
	}

	public HttpStatus getStatus() {
		return status;
	}

	public String getCode() {
		return code;
	}

	public static ApiException badRequest(String message) {
		return new ApiException(HttpStatus.BAD_REQUEST, message);
	}

	public static ApiException notFound(String message) {
		return new ApiException(HttpStatus.NOT_FOUND, message);
	}

	public static ApiException conflict(String message, String code) {
		return new ApiException(HttpStatus.CONFLICT, message, code);
	}

	public static ApiException unauthorized(String message) {
		return new ApiException(HttpStatus.UNAUTHORIZED, message);
	}

	/** 상류(나라장터·D2B 등)가 실패한 경우. */
	public static ApiException upstream(String message) {
		return new ApiException(HttpStatus.BAD_GATEWAY, message);
	}

	/** DB·LLM 등 의존 컴포넌트가 일시적으로 못 쓰는 경우. */
	public static ApiException unavailable(String message) {
		return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, message);
	}
}
