package com.electerior.g2bmaster.integration.g2b;

import com.electerior.g2bmaster.common.ApiException;
import java.net.SocketTimeoutException;
import java.nio.channels.InterruptedByTimeoutException;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * G2B API 에러 분류·응답 헬퍼 ({@code lib/g2b-errors.js} 이식).
 *
 * <p><strong>여기 한국어 문구는 사용자 대상 계약이다.</strong> 그대로 화면에 렌더링되고,
 * 각 문구는 "이 사람이 지금 뭘 해야 하는가"를 다르게 안내한다(키 등록 / 잠깐 대기 /
 * 자정까지 대기 / 기간 축소). 이식 과정에서 문구를 다듬으면 그 안내가 틀려진다.
 */
@Component
public class G2bErrorTranslator {

	private static final int FLAGS = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;

	private static final Pattern AUTH = Pattern.compile("SERVICE_KEY|serviceKey|SERVICE KEY|unauthorized|권한", FLAGS);
	private static final Pattern RATE_LIMIT = Pattern.compile("429|Too Many Requests", FLAGS);
	private static final Pattern QUOTA = Pattern.compile("quota exceeded|LIMITED_NUMBER|일일|트래픽|초과", FLAGS);
	private static final Pattern RANGE = Pattern.compile("G2B \\[07\\]|입력범위값 초과|range", FLAGS);
	private static final Pattern TIMEOUT = Pattern.compile("timeout|timed out|ETIMEDOUT|ECONNABORTED", FLAGS);

	public boolean isAuthError(Throwable error) {
		return error instanceof G2bAuthException
				|| hasHttpStatus(error, 401)
				|| matches(AUTH, messageChain(error));
	}

	public boolean isRateLimitError(Throwable error) {
		return error instanceof G2bRateLimitException
				|| hasHttpStatus(error, 429)
				|| matches(RATE_LIMIT, messageChain(error));
	}

	/**
	 * 429 중에서도 "일일 트래픽(토큰) 한도 초과"인지 구분 — 이건 자정 초기화까지 안 풀린다
	 * (잠깐 대기 무의미).
	 *
	 * <p>주의: 패턴에 {@code 초과} 가 들어 있어 범위 초과 메시지("입력범위값 초과")에도 걸린다.
	 * 원본과 동일하게 <em>레이트리밋으로 판정된 뒤에만</em> 이 검사를 태워야 오분류가 안 난다.
	 */
	public boolean isQuotaError(Throwable error) {
		if (error instanceof G2bQuotaExceededException) {
			return true;
		}
		String body = error instanceof G2bException g2b && g2b.getResponseBody() != null
				? g2b.getResponseBody()
				: "";
		return matches(QUOTA, body + " " + messageChain(error));
	}

	public boolean isRangeError(Throwable error) {
		return error instanceof G2bRangeException
				|| (error instanceof G2bException g2b && "07".equals(g2b.getResultCode()))
				|| matches(RANGE, messageChain(error));
	}

	public boolean isTimeoutError(Throwable error) {
		for (Throwable t = error; t != null; t = nextCause(t)) {
			if (t instanceof G2bTimeoutException
					|| t instanceof SocketTimeoutException
					|| t instanceof TimeoutException
					|| t instanceof InterruptedByTimeoutException
					|| t instanceof java.net.http.HttpTimeoutException) {
				return true;
			}
		}
		return matches(TIMEOUT, messageChain(error));
	}

	/** 인증 실패는 503(우리 쪽 설정 문제), 레이트리밋은 429 그대로, 나머지는 500. */
	public HttpStatus errorStatus(Throwable error) {
		if (isAuthError(error)) {
			return HttpStatus.SERVICE_UNAVAILABLE;
		}
		if (isRateLimitError(error)) {
			return HttpStatus.TOO_MANY_REQUESTS;
		}
		return HttpStatus.INTERNAL_SERVER_ERROR;
	}

	/** 사용자에게 그대로 보여줄 문구. 원문 그대로 유지할 것. */
	public String errorMessage(Throwable error) {
		if (isAuthError(error)) {
			return "나라장터 API 인증에 실패했습니다. G2B_SERVICE_KEY 환경변수를 확인해 주세요.";
		}
		if (isRateLimitError(error)) {
			if (isQuotaError(error)) {
				return "나라장터 공고목록 API의 일일 호출 한도(트래픽)를 초과했습니다. 한도는 매일 자정 무렵 초기화됩니다 — 그때 다시 시도하거나, data.go.kr에서 트래픽 증량 신청 또는 새 서비스키를 발급해 주세요.";
			}
			return "나라장터 API 요청이 순간적으로 많아 잠시 제한되었습니다. 1~2분 뒤 다시 시도해 주세요.";
		}
		if (isRangeError(error)) {
			return "나라장터 API 조회 범위가 너무 큽니다. 서버가 자동 분할 조회를 시도했지만 일부 API가 거절했습니다. 기간을 더 짧게 줄여 다시 검색해 주세요.";
		}
		String message = error == null ? null : error.getMessage();
		return message != null && !message.isBlank() ? message : "알 수 없는 오류가 발생했습니다.";
	}

	/**
	 * 원본 {@code sendG2bError(res, error)} 에 대응. 컨트롤러 예외 처리기가 그대로 던지면
	 * 상태코드·문구가 원본과 같은 응답이 나간다.
	 */
	public ApiException toApiException(Throwable error) {
		String code = error instanceof G2bException g2b ? g2b.getResultCode() : null;
		return new ApiException(errorStatus(error), errorMessage(error), code);
	}

	// ── 내부 ────────────────────────────────────────────────────────────────

	private static boolean hasHttpStatus(Throwable error, int status) {
		for (Throwable t = error; t != null; t = nextCause(t)) {
			if (t instanceof G2bException g2b && g2b.getHttpStatus() != null && g2b.getHttpStatus() == status) {
				return true;
			}
		}
		return false;
	}

	/**
	 * 원인 사슬의 메시지를 모두 이어 붙인다.
	 *
	 * <p>원본은 {@code error.message} 하나만 봤지만, 자바에서는 RestClient/HttpClient 가
	 * 진짜 원인(SocketTimeoutException 등)을 몇 겹 감싸서 올려보낸다. 겉껍데기만 보면
	 * 타임아웃이 "알 수 없는 오류"로 떨어져 이분 분할이 발동하지 않는다.
	 *
	 * <p>클래스 이름은 일부러 넣지 않는다. 예외 타입 판정은 위쪽 {@code instanceof} 가 이미
	 * 하고 있고, 이름을 섞으면 'G2bRangeException' 같은 문자열이 정규식에 걸려 엉뚱한 분류를
	 * 만든다(예: range 패턴).
	 */
	private static String messageChain(Throwable error) {
		if (error == null) {
			return "";
		}
		StringBuilder sb = new StringBuilder();
		Map<Throwable, Boolean> seen = new IdentityHashMap<>();
		for (Throwable t = error; t != null && seen.put(t, Boolean.TRUE) == null; t = t.getCause()) {
			if (t.getMessage() != null) {
				sb.append(t.getMessage()).append(' ');
			}
		}
		return sb.toString();
	}

	/** 자기참조 원인 사슬(드물지만 존재)에서 무한 루프를 막는다. */
	private static Throwable nextCause(Throwable t) {
		Throwable cause = t.getCause();
		return cause == t ? null : cause;
	}

	private static boolean matches(Pattern pattern, String text) {
		return text != null && pattern.matcher(text).find();
	}
}
