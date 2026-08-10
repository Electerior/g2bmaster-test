package com.electerior.g2bmaster.saved;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 저장 공고 저장소.
 *
 * <p>JPA 대신 네이티브 SQL 인 지점이 셋이다:
 * <ul>
 *   <li><b>저장</b> — upsert 다. 원본 {@code ON CONFLICT (pk) DO UPDATE} →
 *       MySQL {@code ON DUPLICATE KEY UPDATE}. JPA {@code save()} 로 하면
 *       SELECT 한 번이 더 나가고, 그 사이 다른 요청이 끼면 갱신이 하나 사라진다.</li>
 *   <li><b>목록</b> — {@code q} 낱말 수만큼 {@code LIKE} 절이 붙는 동적 SQL 이다.</li>
 *   <li><b>단건</b> — {@code SELECT *} 를 그대로 내려주는 계약이라(프론트가 snake_case 키를
 *       그대로 읽는다) 엔티티 프로젝션이 오히려 계약을 흐린다.</li>
 * </ul>
 */
@Repository
public class SavedNoticeRepository {

	/** 원본 {@code terms.slice(0, 8)} — 낱말이 많아질수록 훑기가 급격히 비싸진다. */
	static final int MAX_TERMS = 8;

	private final NamedParameterJdbcTemplate jdbc;

	public SavedNoticeRepository(NamedParameterJdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	/** 저장(신규/갱신). 원본과 같이 모든 값을 통째로 덮어쓴다. */
	public void upsert(SavedNoticeRow row) {
		jdbc.update("""
				INSERT INTO saved_notice (
				  bid_ntce_no, bid_ntce_ord, title, instt_nm, bid_clse_dt,
				  amount, real_estimate, summary, price_rows, price_total, raw, note, search_text
				) VALUES (
				  :bidNtceNo, :bidNtceOrd, :title, :insttNm, :bidClseDt,
				  :amount, :realEstimate, :summary, :priceRows, :priceTotal, :raw, :note, :searchText
				) AS new
				ON DUPLICATE KEY UPDATE
				  title = new.title, instt_nm = new.instt_nm, bid_clse_dt = new.bid_clse_dt,
				  amount = new.amount, real_estimate = new.real_estimate, summary = new.summary,
				  price_rows = new.price_rows, price_total = new.price_total, raw = new.raw,
				  note = new.note, search_text = new.search_text, updated_at = NOW(6)
				""", new MapSqlParameterSource()
						.addValue("bidNtceNo", row.bidNtceNo())
						.addValue("bidNtceOrd", row.bidNtceOrd())
						.addValue("title", row.title())
						.addValue("insttNm", row.insttNm())
						.addValue("bidClseDt", row.bidClseDt())
						.addValue("amount", row.amount())
						.addValue("realEstimate", row.realEstimate())
						.addValue("summary", row.summary())
						.addValue("priceRows", row.priceRows())
						.addValue("priceTotal", row.priceTotal())
						.addValue("raw", row.raw())
						.addValue("note", row.note())
						.addValue("searchText", row.searchText()));
	}

	/**
	 * 목록 + 그 안에서의 검색.
	 *
	 * <p>낱말은 <b>전부 포함</b>해야 한다(AND). 하나만으로 훑으면 결과가 너무 넓어 쓸모가 없다.
	 *
	 * <p>원본은 {@code ILIKE} 였다. MySQL 의 기본 콜레이션 {@code utf8mb4_0900_ai_ci} 가
	 * 이미 대소문자를 무시하므로 {@code LIKE} 가 정확한 번역이다
	 * (→ {@code docs/migration-notes.md} §14).
	 *
	 * <p>낱말 안의 {@code %}/{@code _} 를 escape 하지 않는 것은 원본 그대로다. 사용자가 친
	 * {@code _} 가 한 글자 와일드카드가 되어 결과가 조금 넓어질 뿐이라 실질적인 해가 없고,
	 * 여기서 조이면 기존 사용자의 검색 결과가 소리 없이 줄어든다.
	 */
	public List<Map<String, Object>> search(List<String> terms, int limit) {
		MapSqlParameterSource params = new MapSqlParameterSource().addValue("limit", limit);
		StringBuilder where = new StringBuilder();
		for (int i = 0; i < terms.size(); i++) {
			where.append(i == 0 ? " WHERE " : " AND ").append("search_text LIKE :t").append(i);
			params.addValue("t" + i, "%" + terms.get(i) + "%");
		}

		return jdbc.queryForList("""
				SELECT bid_ntce_no, bid_ntce_ord, title, instt_nm, bid_clse_dt, amount, real_estimate,
				       price_total, note, saved_at, updated_at,
				       LEFT(COALESCE(summary, ''), 300) AS summary_preview,
				       JSON_LENGTH(COALESCE(price_rows, JSON_ARRAY())) AS price_row_count
				  FROM saved_notice
				 %s
				 ORDER BY updated_at DESC
				 LIMIT :limit
				""".formatted(where), params);
	}

	/**
	 * 일괄 딜 분석용 목록 — {@code raw}(공고 원본 JSON, 첨부 URL 포함)를 함께 준다.
	 *
	 * <p>목록 조회({@link #list})는 화면 표시용이라 raw 를 빼지만, 분석은 공고 객체 원본이
	 * 있어야 규격서 첨부를 열 수 있다.
	 */
	public List<Map<String, Object>> findAllForAnalysis(int limit) {
		return jdbc.queryForList("""
				SELECT bid_ntce_no, bid_ntce_ord, title, amount, real_estimate, raw
				  FROM saved_notice
				 ORDER BY updated_at DESC
				 LIMIT :limit
				""", new MapSqlParameterSource("limit", Math.max(1, limit)));
	}

	/** 단건 전문(요약본 + 가격표 전체). 목록은 미리보기만 주므로 열 때 이걸 부른다. */
	public Map<String, Object> findOne(String bidNtceNo, String bidNtceOrd) {
		List<Map<String, Object>> rows = jdbc.queryForList(
				"SELECT * FROM saved_notice WHERE bid_ntce_no = :no AND bid_ntce_ord = :ord",
				new MapSqlParameterSource().addValue("no", bidNtceNo).addValue("ord", bidNtceOrd));
		return rows.isEmpty() ? null : new LinkedHashMap<>(rows.get(0));
	}

	/** @return 지운 행 수. 없던 건이면 0 — 원본과 같이 오류가 아니다. */
	public int delete(String bidNtceNo, String bidNtceOrd) {
		return jdbc.update("DELETE FROM saved_notice WHERE bid_ntce_no = :no AND bid_ntce_ord = :ord",
				new MapSqlParameterSource().addValue("no", bidNtceNo).addValue("ord", bidNtceOrd));
	}

	/** 공백으로 나눈 낱말. 최대 {@value #MAX_TERMS} 개. */
	static List<String> parseTerms(String query) {
		if (query == null || query.isBlank()) {
			return List.of();
		}
		List<String> terms = new ArrayList<>();
		for (String term : query.trim().split("\\s+")) {
			if (!term.isEmpty()) {
				terms.add(term);
				if (terms.size() == MAX_TERMS) {
					break;
				}
			}
		}
		return terms;
	}

	/**
	 * 저장 행. 컬럼과 1:1 이라 엔티티 대신 쓴다 — upsert 는 엔티티 수명주기를 타지 않는다.
	 *
	 * @param priceRows JSON 배열 문자열
	 * @param raw       JSON 객체 문자열
	 */
	public record SavedNoticeRow(
			String bidNtceNo, String bidNtceOrd, String title, String insttNm, String bidClseDt,
			Long amount, Long realEstimate, String summary, String priceRows, Long priceTotal,
			String raw, String note, String searchText) {
	}
}
