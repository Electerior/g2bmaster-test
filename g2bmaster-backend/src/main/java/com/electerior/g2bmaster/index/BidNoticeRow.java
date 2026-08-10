package com.electerior.g2bmaster.index;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * {@code bid_notice} 한 행 — ERD 컬럼과 1:1.
 *
 * <p>JPA 엔티티가 아니라 레코드인 이유는 {@link BidNoticeIndexRepository} 주석에 있다(요약:
 * 적재는 배치 upsert 고, 조회는 동적 SQL 이라 엔티티 수명주기를 탈 일이 없다).
 *
 * <p>{@code productList}·{@code priceDetail}·{@code attachmentUrls} 는 <b>직렬화된 JSON 문자열</b>
 * 이다. 매퍼가 만들어 넣고 저장소는 그대로 바인딩한다 — 저장소가 다시 Jackson 을 부르면
 * 배치 한 건마다 직렬화가 반복된다.
 *
 * <p>{@code aiSummary} 가 없는 것은 실수가 아니다. AI 요약은 적재기의 소관이 아니고,
 * upsert 가 그 컬럼을 건드리지 않아 재적재해도 살아남는다.
 */
public record BidNoticeRow(
		String id,
		String noticeOrder,
		String noticeName,
		NoticeCategory category,
		NoticeState state,
		BusinessDivision businessDivision,
		String region,
		String demandInstitutionCode,
		/** 원본 응답의 기관명. 사전규격처럼 코드가 없는 출처도 이 값은 온다(V8 주석 참고). */
		String demandInstitutionName,
		String noticeInstitutionCode,
		String noticeInstitutionName,
		String beforeSpecRgstNo,
		/** JSON 배열 문자열 {@code [{seq,code,name}]}, 없으면 null */
		String productList,
		String detailProductCode,
		/** 백분율 그대로(88.000 = 88%). {@code DECIMAL(5,3)} 범위를 넘으면 매퍼가 null 로 만든다. */
		BigDecimal lowestBidRate,
		/** JSON 객체 문자열, 없으면 null */
		String priceDetail,
		LocalDateTime createdDate,
		LocalDateTime closeDate,
		String officerName,
		String officerContact,
		String noticeBody,
		/** JSON 배열 문자열 {@code [{name,url}]}, 없으면 null */
		String attachmentUrls,
		String sourceUrl) {

	/** ENUM 컬럼에 바인딩할 문자열. null 이면 그대로 null(= 상태 없음). */
	public String categoryName() {
		return category == null ? null : category.name();
	}

	public String stateName() {
		return state == null ? null : state.name();
	}

	public String businessDivisionName() {
		return businessDivision == null ? null : businessDivision.name();
	}
}
