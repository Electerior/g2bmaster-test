package com.electerior.g2bmaster.index;

import com.electerior.g2bmaster.common.ApiException;
import com.electerior.g2bmaster.common.PagedResponse;
import com.electerior.g2bmaster.config.OpenApiConfig;
import com.electerior.g2bmaster.security.RequireAppAuth;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 통합 공고 검색 — 계획 · 사전규격 · 입찰 · 마감을 한 번에.
 *
 * <p>기존 검색 4탭({@code /api/bid-announce} 등)과 <b>공존한다</b>. 그쪽은 요청마다 나라장터를
 * 팬아웃해 D2B·누리장터까지 훑고, 이쪽은 로컬 색인만 본다. 둘은 대체 관계가 아니라 용도가
 * 다르다 — 넓게 훑어야 할 때는 팬아웃이, 빠르게 좁혀야 할 때는 색인이 맞다.
 *
 * <p>응답 봉투는 {@code {items, totalCount, pageNo, numOfRows}} 로 기존 검색과 같다
 * ({@code docs/api-contract.md} §1.1 의 첫 번째 모양). 프론트의 페이징 컴포넌트를 그대로
 * 쓰기 위해서다.
 */
@RestController
@RequestMapping("/api/search/notices")
@Tag(name = OpenApiConfig.TAG_INDEX_SEARCH)
public class NoticeSearchController {

	private final BidNoticeSearchService searchService;
	private final BidNoticeSyncScheduler scheduler;
	private final BidNoticeIndexRepository repository;
	private final BidNoticeIngestService ingestService;

	public NoticeSearchController(BidNoticeSearchService searchService, BidNoticeSyncScheduler scheduler,
			BidNoticeIndexRepository repository, BidNoticeIngestService ingestService) {
		this.searchService = searchService;
		this.scheduler = scheduler;
		this.repository = repository;
		this.ingestService = ingestService;
	}

	@Operation(summary = "공고 통합 검색",
			description = "로컬 색인(bid_notice)만 조회한다. 나라장터를 호출하지 않으므로 응답이 일정하다. "
					+ "검색어가 있으면 관련도 순, 없으면 최신순이 기본 정렬이다.")
	@GetMapping
	public PagedResponse<Map<String, Object>> search(@ModelAttribute NoticeSearchRequest request) {
		return searchService.search(request);
	}

	@Operation(summary = "검색 패싯",
			description = "같은 조건에서 분류·업종·지역·상태별 건수. 필터 칩에 건수를 붙이는 데 쓴다.")
	@GetMapping("/facets")
	public Map<String, Object> facets(@ModelAttribute NoticeSearchRequest request) {
		return searchService.facets(request);
	}

	/**
	 * 색인 현황.
	 *
	 * <p>검색 앞에 둔 이유는 경로 충돌 때문이 아니라 <b>순서</b> 때문이다 — 스프링은 더 구체적인
	 * 패턴을 먼저 고르지만, {@code /{id}} 가 위에 있으면 읽는 사람이 헷갈린다.
	 */
	@Operation(summary = "색인 현황",
			description = "출처별 워터마크·마지막 결과와 분류별 색인 건수. 색인이 언제 것인지 화면에 표시한다.")
	@GetMapping("/status")
	public Map<String, Object> status() {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("sources", repository.syncStates());
		body.put("summary", repository.indexSummary());
		body.put("knownSources", ingestService.sourceKeys());
		return body;
	}

	@Operation(summary = "공고 상세", description = "공고 본문 전문을 포함한다.")
	@GetMapping("/{id}")
	public Map<String, Object> detail(@PathVariable("id") String id) {
		Map<String, Object> found = searchService.findOne(id);
		if (found == null) {
			// 문구는 화면에 그대로 뜬다 — docs/api-contract.md §1.1 참고.
			throw ApiException.notFound("색인에 없는 공고입니다: " + id);
		}
		return found;
	}

	/**
	 * 수동 적재.
	 *
	 * <p>쓰기이자 비용(나라장터 쿼터)이 드는 경로라 앱 키를 요구한다. 초기 구축과 장애 복구용이고,
	 * 평시에는 스케줄러가 알아서 돈다.
	 */
	@Operation(summary = "색인 수동 적재",
			description = "나라장터에서 즉시 받아와 색인한다. backfillDays 로 거슬러 올라갈 기간을 지정한다. "
					+ "이미 적재가 돌고 있으면 409 를 돌려준다.")
	@PostMapping("/sync")
	@RequireAppAuth
	public Map<String, Object> sync(
			@RequestParam(name = "backfillDays", required = false, defaultValue = "0") int backfillDays) {
		BidNoticeIngestService.IngestResult result = scheduler.runNow(backfillDays);
		if (result == null) {
			throw new ApiException(org.springframework.http.HttpStatus.CONFLICT,
					"이미 색인 적재가 진행 중입니다. 잠시 후 다시 시도하세요.");
		}
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("ok", true);
		body.put("totalIndexed", result.totalIndexed());
		body.put("sweptToClosed", result.sweptToClosed());
		body.put("sources", result.sources());
		return body;
	}
}
