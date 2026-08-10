package com.electerior.g2bmaster.notice;

import com.electerior.g2bmaster.common.MapLimit;
import com.electerior.g2bmaster.common.SourceError;
import com.electerior.g2bmaster.search.NoticeSearchSupport;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 누리장터(민간 공고) 팬아웃 ({@code server.js} 의 {@code privateG2bFetchBidAnnounce} 이식).
 *
 * <p>누리장터는 공공기관이 아닌 발주처의 공고를 담는 별도 서비스({@code PrvtBidNtceService})다.
 * 필드명이 나라장터와 미묘하게 어긋나 ({@code ntceNm} vs {@code bidNtceNm}) 그대로 섞으면
 * 화면에서 제목이 빈칸으로 나온다 — {@link NoticeSearchSupport#normalizePrivateNotice} 가 흡수한다.
 *
 * <p>구분이 <b>넷</b>인 것에 주의: 물품·용역·공사에 더해 '기타'가 있다. 사용자가 사업 구분을
 * 고르지 않으면 넷 다 훑어야 하고, '기타'를 빼면 민간 공고의 상당수가 사라진다.
 */
@Service
public class PrivateNoticeService {

	private static final Logger log = LoggerFactory.getLogger(PrivateNoticeService.class);

	/** 조회 결과 봉투. 부분 실패는 예외가 아니라 데이터로 올린다. */
	public record PrivateResult(List<Map<String, Object>> items, List<SourceError> errors) {

		public static PrivateResult empty() {
			return new PrivateResult(List.of(), List.of());
		}
	}

	private final G2bEndpoints endpoints;
	private final NoticeFetchSupport support;

	public PrivateNoticeService(G2bEndpoints endpoints, NoticeFetchSupport support) {
		this.endpoints = endpoints;
		this.support = support;
	}

	public PrivateResult fetchBidAnnounce(String keyword, String fromDate, String toDate,
			String bidType, String insttNm) {
		Map<String, String> operations = endpoints.privateNotice();
		List<String> types = (bidType == null || bidType.isEmpty())
				? List.copyOf(operations.keySet())
				: List.of(bidType);

		List<TypeOutcome> results = MapLimit.map(types, 4,
				type -> fetchType(type, operations.get(type), keyword, fromDate, toDate, insttNm));

		List<Map<String, Object>> items = new ArrayList<>();
		List<SourceError> errors = new ArrayList<>();
		for (TypeOutcome outcome : results) {
			items.addAll(outcome.items());
			if (outcome.error() != null) {
				errors.add(new SourceError("private-g2b", outcome.operation(), outcome.error()));
			}
		}
		// 누리장터도 정정공고가 차수만 바꿔 여러 건 온다. 출처 안에서 먼저 접어 둔다.
		return new PrivateResult(NoticeSearchSupport.selectLatestNoticeRevisions(items), errors);
	}

	private TypeOutcome fetchType(String type, String url, String keyword, String fromDate,
			String toDate, String insttNm) {
		if (url == null) {
			return new TypeOutcome(type, List.of(), null);
		}
		Map<String, Object> params = new LinkedHashMap<>();
		params.put("inqryDiv", 1);
		if (keyword != null && !keyword.isEmpty()) {
			params.put("bidNtceNm", keyword);
		}
		if (fromDate != null && !fromDate.isEmpty()) {
			params.put("inqryBgnDt", fromDate);
		}
		if (toDate != null && !toDate.isEmpty()) {
			params.put("inqryEndDt", toDate);
		}
		if (insttNm != null && !insttNm.isEmpty()) {
			params.put("ntceInsttNm", insttNm);
		}

		try {
			List<Map<String, Object>> rows = support.fetchPaged(url, params);
			List<Map<String, Object>> normalized = new ArrayList<>(rows.size());
			for (Map<String, Object> row : rows) {
				normalized.add(BidEnrichment.enrichBidNotice(
						new LinkedHashMap<>(NoticeSearchSupport.normalizePrivateNotice(row, type))));
			}
			return new TypeOutcome(type, normalized, null);
		}
		catch (RuntimeException ex) {
			log.debug("누리장터 조회 실패 {}: {}", type, ex.getMessage());
			return new TypeOutcome(type, List.of(), ex.getMessage());
		}
	}

	private record TypeOutcome(String operation, List<Map<String, Object>> items, String error) {}
}
