package com.electerior.g2bmaster.market;

import java.util.List;
import java.util.Map;

/**
 * 시장정보 4개 엔드포인트의 요청 본문 ({@code docs/api-contract.md} §2.C).
 *
 * <p>요청은 우리가 형태를 정하는 쪽이라 레코드로 굳혀도 안전하다. 반면 <b>응답 항목은
 * 여전히 {@code Map}</b> 이다 — 나라장터 개찰결과 스키마가 드리프트하고 필드가 통째로 빠지는
 * 일이 잦아, 레코드로 굳히면 응답 하나가 통째로 역직렬화 실패로 사라진다.
 */
public final class MarketIntelRequests {

	private MarketIntelRequests() {
	}

	/**
	 * 개찰결과 조회.
	 *
	 * @param bidNtceNo   공고번호(필수)
	 * @param bidNtceSqNo 공고차수. 없으면 {@code 000}
	 * @param type        사업 구분. 없으면 물품
	 */
	public record OpeningResultRequest(String bidNtceNo, String bidNtceSqNo, String type) {

		public String typeOrDefault() {
			return type == null || type.isBlank() ? "물품" : type;
		}

		public String sequenceOrDefault() {
			return bidNtceSqNo == null || bidNtceSqNo.isBlank() ? "000" : bidNtceSqNo;
		}
	}

	/**
	 * 업체 이력 조회. {@code corpNm} 또는 {@code brnNo} 중 하나는 있어야 한다.
	 *
	 * @param brnNo 사업자번호. 하이픈이 섞여 와도 되며 숫자만 추려 비교한다
	 */
	public record CompanyHistoryRequest(String corpNm, String brnNo, String fromDate, String toDate) {}

	/** 발주기관 담당자 조회. */
	public record OfficerSearchRequest(String insttNm, String fromDate, String toDate) {}

	/**
	 * 담합 정황 분석.
	 *
	 * @param bids 공고 목록. 각 항목은 최소한 {@code bidNtceNo} 를 가져야 하며,
	 *             {@code bidNtceSqNo}·{@code _type} 이 있으면 개찰결과 조회 정확도가 올라간다
	 */
	public record CollusionRequest(List<Map<String, Object>> bids) {}
}
