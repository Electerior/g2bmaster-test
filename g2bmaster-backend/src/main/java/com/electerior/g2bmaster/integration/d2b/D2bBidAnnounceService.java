package com.electerior.g2bmaster.integration.d2b;

import com.electerior.g2bmaster.common.MapLimit;
import com.electerior.g2bmaster.common.SourceError;
import com.electerior.g2bmaster.notice.BidEnrichment;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * D2B 입찰공고 팬아웃 ({@code server.js} 의 {@code d2bFetchBidAnnounce} 이식).
 *
 * <p>네 오퍼레이션을 모두 훑는다 — 국내/시설 × 경쟁입찰/공개수의. 하나로 합쳐진 목록
 * 오퍼레이션이 없기 때문이고, 빠뜨리면 그 조합의 공고가 검색에서 통째로 사라진다.
 *
 * <p><b>부분 실패는 예외가 아니다.</b> 결과 봉투에 {@code errors} 로 담아 올려, 화면이
 * "국방 출처 일부 실패"를 표시하면서도 나머지 결과는 그대로 보여줄 수 있게 한다.
 */
@Service
public class D2bBidAnnounceService {

	private static final Logger log = LoggerFactory.getLogger(D2bBidAnnounceService.class);

	private static final List<String> OPERATIONS = List.of(
			"getDmstcCmpetBidPblancList",
			"getFcltyCmpetBidPblancList",
			"getDmstcOthbcVltrnNtatPlanList",
			"getFcltyOthbcVltrnNtatPlanList");

	/** 조회 결과 봉투. */
	public record D2bResult(List<Map<String, Object>> items, List<SourceError> errors) {

		public static D2bResult empty() {
			return new D2bResult(List.of(), List.of());
		}
	}

	private final D2bClient client;

	public D2bBidAnnounceService(D2bClient client) {
		this.client = client;
	}

	/**
	 * 키워드·기간으로 D2B 공고를 모은다.
	 *
	 * <p>공개수의 오퍼레이션은 날짜 파라미터를 받지 않으므로, 서버측 필터를 못 거는 대신
	 * 응답을 받은 뒤 {@link D2bNormalizer#isDateInRange} 로 다시 거른다.
	 */
	public D2bResult fetchBidAnnounce(String keyword, String fromDate, String toDate) {
		List<OperationResult> results = MapLimit.map(OPERATIONS, 4,
				operation -> runOperation(operation, keyword, fromDate, toDate));

		List<Map<String, Object>> items = new ArrayList<>();
		List<SourceError> errors = new ArrayList<>();
		for (OperationResult result : results) {
			items.addAll(result.items());
			if (result.error() != null) {
				errors.add(new SourceError("d2b", result.operation(), result.error()));
			}
		}
		return new D2bResult(items, errors);
	}

	private OperationResult runOperation(String operation, String keyword, String fromDate, String toDate) {
		try {
			List<Map<String, Object>> rows = client.call(operation,
					D2bNormalizer.d2bParamsForOperation(operation, keyword, fromDate, toDate));
			List<Map<String, Object>> normalized = new ArrayList<>(rows.size());
			for (Map<String, Object> row : rows) {
				Map<String, Object> item = BidEnrichment.enrichBidNotice(
						D2bNormalizer.normalizeD2bItem(new LinkedHashMap<>(row), operation));
				if (D2bNormalizer.isDateInRange(item.get("bidNtceDt"), fromDate, toDate)) {
					normalized.add(item);
				}
			}
			return new OperationResult(operation, normalized, null);
		}
		catch (RuntimeException ex) {
			log.debug("D2B 오퍼레이션 실패 {}: {}", operation, ex.getMessage());
			return new OperationResult(operation, List.of(), ex.getMessage());
		}
	}

	private record OperationResult(String operation, List<Map<String, Object>> items, String error) {}
}
