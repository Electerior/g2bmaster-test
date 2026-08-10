package com.electerior.g2bmaster.common;

import java.util.List;

/**
 * 오류 응답 본문.
 *
 * <p>기존 계약은 {@code { "error": "..." }} 한 필드였고, 일부 경로만 {@code code} 나
 * {@code missing[]} 을 덧붙였다. {@code null} 필드는 직렬화에서 빠지므로
 * (application.yml 의 {@code default-property-inclusion: non_null}) 기본 모양은 그대로 유지된다.
 */
public record ErrorResponse(String error, String code, List<String> missing) {

	public static ErrorResponse of(String error) {
		return new ErrorResponse(error, null, null);
	}

	public static ErrorResponse of(String error, String code) {
		return new ErrorResponse(error, code, null);
	}
}
