package com.electerior.g2bmaster.integration.ai;

/**
 * AI 서비스에 닿지 못했거나, 닿았지만 추론을 수행하지 못했음을 알린다.
 *
 * <p><b>이 예외를 그대로 5xx 로 흘려보내면 안 되는 경로가 있다.</b> 원본 계약상
 * AI 분석 엔드포인트는 LLM 이 실패해도 HTTP 200 에 {@code aiFallback: true} 와
 * {@code aiError} 를 실어 보낸다 — 첨부 문서에서 뽑아낸 문서 태그·법령 검토·규격 원문은
 * LLM 과 무관하게 유효하고, 사용자는 그것만으로도 판단을 이어갈 수 있기 때문이다.
 * 따라서 AI 컨트롤러는 이 예외를 잡아 폴백 응답으로 바꾼다.
 */
public class AiUnavailableException extends RuntimeException {

	public AiUnavailableException(String message) {
		super(message);
	}

	public AiUnavailableException(String message, Throwable cause) {
		super(message, cause);
	}
}
