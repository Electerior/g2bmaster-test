package com.electerior.g2bmaster.pricing;

import java.util.List;

/**
 * 가격 카탈로그 컨트롤러의 요청 본문들. 화면 대상 검증 문구는 서비스가 만든다.
 */
public final class PriceCatalogRequests {

	private PriceCatalogRequests() {
	}

	/** 수동 등록/upsert. {@code source}·{@code name} 필수. {@code priceKrw} null 이면 가격 미확인. */
	public record UpsertRequest(String source, String category, String name, String model,
			String spec, Long priceKrw, String url, String note) {}

	/** id 기반 부분 수정(PATCH). null 필드는 "미변경"이다 — 명시적 null 로 못 지운다. */
	public record UpdateRequest(String category, String name, String model, String spec,
			Long priceKrw, String url, String note) {}

	/**
	 * AI 리졸버로 시세를 긁어 적재. {@code query} 필수.
	 *
	 * @param sources 적재할 소스 화이트리스트. 비면 세 소스(danawa/itmaya/enuri) 전부.
	 */
	public record IngestRequest(String query, String category, List<String> sources) {}
}
