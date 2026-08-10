package com.electerior.g2bmaster.saved;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;

/**
 * 사람이 담아 둔 공고 — 저장 공고.
 *
 * <p>{@code analysis_history} 와 무엇이 다른가: 그쪽은 AI 가 돌린 결과의 캐시고(공고를 열면
 * 자동으로 쌓인다), 이쪽은 <b>영업이 확정한 것</b>이다. 특히 가격표가 다르다 — 사람이 표에서
 * 고친 단가·수량·출처 링크는 지금까지 브라우저 안에서만 살아 있었고 새로고침하면 사라졌다.
 * 웹 단가를 다시 조회해도 같은 값이 나온다는 보장이 없으니(판매가는 바뀐다) 복구가 불가능했다.
 *
 * <p>복합 PK {@code (bid_ntce_no, bid_ntce_ord)}. 차수는 <b>NULL 이 아니라 빈 문자열</b>이다 —
 * MySQL 은 PK 컬럼에 NOT NULL 을 강제하고, 원본도 항상 {@code String(b.bidNtceOrd || '')} 로 넣었다.
 *
 * <p><b>읽는 코드가 없어도 지우지 말 것.</b> 이 엔티티의 일은 질의가 아니라 <b>스키마 계약</b>이다 —
 * {@code spring.jpa.hibernate.ddl-auto=validate} 가 기동할 때마다 이 매핑을 Flyway 가 만든 실제
 * 테이블과 대조한다. 마이그레이션에서 컬럼 이름이나 타입을 바꾸면 그 순간 기동이 실패해서
 * 드러난다. 그 대조가 없으면 {@link SavedNoticeRepository} 의 네이티브 SQL 이 런타임에야 깨진다.
 * (조회 3종이 snake_case 원본 행을 그대로 내려주는 계약이라 JPA 프로젝션으로는 옮길 수 없다.)
 */
@Entity
@Table(name = "saved_notice")
public class SavedNotice {

	@EmbeddedId
	private Key key;

	@Column(name = "title", columnDefinition = "text")
	private String title;

	@Column(name = "instt_nm", columnDefinition = "text")
	private String insttNm;

	/** 입찰마감 원문 문자열 그대로. 표기가 제각각이라 파싱하지 않는다(원본 주석 그대로). */
	@Column(name = "bid_clse_dt", columnDefinition = "text")
	private String bidClseDt;

	/** 추정가격(부가세 별도). */
	@Column(name = "amount")
	private Long amount;

	/** 실추정가 = 추정가 × 1.1. 견적 합계와 견줄 값이라 저장 시점에 계산해 굳힌다. */
	@Column(name = "real_estimate")
	private Long realEstimate;

	@Column(name = "summary", columnDefinition = "text")
	private String summary;

	@Column(name = "price_rows", columnDefinition = "json")
	private String priceRows;

	@Column(name = "price_total")
	private Long priceTotal;

	@Column(name = "raw", columnDefinition = "json")
	private String raw;

	@Column(name = "note", columnDefinition = "text")
	private String note;

	@Column(name = "search_text", columnDefinition = "mediumtext")
	private String searchText;

	@Column(name = "saved_at", insertable = false, updatable = false)
	private Instant savedAt;

	@Column(name = "updated_at", insertable = false, updatable = false)
	private Instant updatedAt;

	protected SavedNotice() {
	}

	public Key getKey() {
		return key;
	}

	public String getTitle() {
		return title;
	}

	public String getInsttNm() {
		return insttNm;
	}

	public String getBidClseDt() {
		return bidClseDt;
	}

	public Long getAmount() {
		return amount;
	}

	public Long getRealEstimate() {
		return realEstimate;
	}

	public String getSummary() {
		return summary;
	}

	public String getPriceRows() {
		return priceRows;
	}

	public Long getPriceTotal() {
		return priceTotal;
	}

	public String getRaw() {
		return raw;
	}

	public String getNote() {
		return note;
	}

	public String getSearchText() {
		return searchText;
	}

	public Instant getSavedAt() {
		return savedAt;
	}

	public Instant getUpdatedAt() {
		return updatedAt;
	}

	/** 복합 기본키. */
	@Embeddable
	public record Key(
			@Column(name = "bid_ntce_no", length = 64) String bidNtceNo,
			@Column(name = "bid_ntce_ord", length = 64) String bidNtceOrd) implements Serializable {

		public Key {
			Objects.requireNonNull(bidNtceNo, "bidNtceNo");
			bidNtceOrd = bidNtceOrd == null ? "" : bidNtceOrd;
		}
	}
}
