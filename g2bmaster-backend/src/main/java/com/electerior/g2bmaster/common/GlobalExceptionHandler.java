package com.electerior.g2bmaster.common;

import com.electerior.g2bmaster.integration.g2b.G2bErrorTranslator;
import com.electerior.g2bmaster.integration.g2b.G2bException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

/**
 * 오류 응답을 한 곳에서 만든다.
 *
 * <p>기존 서버에는 전역 오류 처리기가 없었고, multer 오류 미들웨어가 <b>라우트 사이</b>
 * (server.js:2250)에 등록돼 있어 그 뒤에 선언된 업로드 경로
 * ({@code POST /api/pledge-revision/upload}) 는 잡히지 않았다. 여기서는 전역이라 그 틈이 없다.
 *
 * <p><b>주의 — AI 경로는 예외다.</b> 원본은 LLM 이 실패해도 HTTP 200 에
 * {@code aiFallback: true} + {@code aiError} 를 실어 보낸다. 문서 태그·법령 검토 결과를
 * 화면에 남기기 위한 의도적 계약이므로, AI 서비스 호출 실패를 여기서 5xx 로 바꾸면 안 된다.
 * 해당 처리는 각 AI 컨트롤러가 직접 한다.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

	private final G2bErrorTranslator g2bErrorTranslator;

	public GlobalExceptionHandler(G2bErrorTranslator g2bErrorTranslator) {
		this.g2bErrorTranslator = g2bErrorTranslator;
	}

	@ExceptionHandler(ApiException.class)
	public ResponseEntity<ErrorResponse> handleApi(ApiException e) {
		return ResponseEntity.status(e.getStatus())
				.body(ErrorResponse.of(e.getMessage(), e.getCode()));
	}

	/**
	 * 나라장터 상류 실패 — {@code sendG2bError(res, e)} 의 이식.
	 *
	 * <p>인증 실패·호출량 초과·조회범위 초과·타임아웃은 사용자가 할 수 있는 조치가 서로 다르다.
	 * 뭉뚱그려 500 으로 보내면 화면이 "서버 오류"만 띄우고, 실제로는 서비스키가 만료됐거나
	 * 일일 할당량이 소진된 것을 아무도 알 수 없다.
	 */
	@ExceptionHandler(G2bException.class)
	public ResponseEntity<ErrorResponse> handleG2b(G2bException e) {
		ApiException translated = g2bErrorTranslator.toApiException(e);
		log.warn("나라장터 호출 실패: {} (resultCode={})", e.getMessage(), e.getResultCode());
		return ResponseEntity.status(translated.getStatus())
				.body(ErrorResponse.of(translated.getMessage(), translated.getCode()));
	}

	/** 업로드 상한 초과 — 원본과 같은 413 + {@code UPLOAD_TOO_LARGE}. */
	@ExceptionHandler(MaxUploadSizeExceededException.class)
	public ResponseEntity<ErrorResponse> handleUploadTooLarge(MaxUploadSizeExceededException e) {
		return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
				.body(ErrorResponse.of("업로드 용량이 너무 큽니다.", "UPLOAD_TOO_LARGE"));
	}

	@ExceptionHandler({MethodArgumentNotValidException.class, MissingServletRequestParameterException.class})
	public ResponseEntity<ErrorResponse> handleValidation(Exception e) {
		return ResponseEntity.badRequest().body(ErrorResponse.of(firstMessage(e)));
	}

	@ExceptionHandler(IllegalArgumentException.class)
	public ResponseEntity<ErrorResponse> handleIllegalArgument(IllegalArgumentException e) {
		return ResponseEntity.badRequest().body(ErrorResponse.of(e.getMessage()));
	}

	@ExceptionHandler(Exception.class)
	public ResponseEntity<ErrorResponse> handleUnexpected(Exception e, HttpServletRequest request) {
		log.error("처리되지 않은 오류: {} {}", request.getMethod(), request.getRequestURI(), e);
		return ResponseEntity.internalServerError()
				.body(ErrorResponse.of("서버 오류가 발생했습니다."));
	}

	private String firstMessage(Exception e) {
		if (e instanceof MethodArgumentNotValidException invalid) {
			return invalid.getBindingResult().getFieldErrors().stream()
					.findFirst()
					.map(error -> error.getField() + ": " + error.getDefaultMessage())
					.orElse("요청 값이 올바르지 않습니다.");
		}
		return e.getMessage() != null ? e.getMessage() : "요청 값이 올바르지 않습니다.";
	}
}
