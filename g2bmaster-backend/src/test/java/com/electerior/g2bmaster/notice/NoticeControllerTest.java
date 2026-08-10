package com.electerior.g2bmaster.notice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.electerior.g2bmaster.cache.SearchResultCache;
import com.electerior.g2bmaster.common.SourceError;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * 검색 4탭의 HTTP 계약 — 질의 파라미터 바인딩과 응답 봉투.
 *
 * <p>조회 로직이 아니라 <b>계약</b>을 지킨다. 특히 {@code SearchCriteria} 가 레코드라
 * 스프링의 생성자 바인딩에 의존하는데, 이건 컴파일이 잡아 주지 않는다 — 바인딩이 깨지면
 * 모든 검색이 조용히 "조건 없음"으로 돌아 전량을 긁는다.
 */
class NoticeControllerTest {

	private BidAnnounceService bidAnnounceService;
	private BidResultService bidResultService;
	private BidPlanService bidPlanService;
	private PreSpecService preSpecService;
	private MockMvc mockMvc;

	@BeforeEach
	void setUp() {
		bidAnnounceService = mock(BidAnnounceService.class);
		bidResultService = mock(BidResultService.class);
		bidPlanService = mock(BidPlanService.class);
		preSpecService = mock(PreSpecService.class);
		mockMvc = MockMvcBuilders.standaloneSetup(new NoticeController(
				bidAnnounceService, bidResultService, bidPlanService, preSpecService)).build();
	}

	private static Map<String, Object> notice(String no, String name, String source, int score) {
		Map<String, Object> item = new LinkedHashMap<>();
		item.put("bidNtceNo", no);
		item.put("bidNtceNm", name);
		item.put("bidNtceDt", "20260701");
		item.put("_source", source);
		item.put("_opportunityScore", score);
		return item;
	}

	@Test
	void 입찰공고_응답에_출처_집계와_상태가_붙는다() throws Exception {
		List<Map<String, Object>> items = List.of(
				notice("N1", "노트북 구매", "g2b", 70),
				notice("D1", "장비 구매", "d2b", 50));
		when(bidAnnounceService.search(any(), anyBoolean())).thenReturn(
				new SearchResultCache.Cached<>(new BidAnnounceService.AnnounceResult(items,
						List.of(new SourceError("d2b", "getDmstcCmpetBidPblancList", "타임아웃"))), true));

		mockMvc.perform(get("/api/bid-announce").param("andTerms", "노트북"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.totalCount").value(2))
				.andExpect(jsonPath("$.pageNo").value(1))
				.andExpect(jsonPath("$._cached").value(true))
				.andExpect(jsonPath("$.sourceCounts.g2b").value(1))
				.andExpect(jsonPath("$.sourceCounts.d2b").value(1))
				.andExpect(jsonPath("$.sourceCounts.['private-g2b']").value(0))
				.andExpect(jsonPath("$.sourceCoverage.length()").value(3))
				.andExpect(jsonPath("$.sourceErrors[0].source").value("d2b"))
				.andExpect(jsonPath("$.sourceStatus.d2b").value("partial"))
				.andExpect(jsonPath("$.sourceStatus.g2b").value("ok"));
	}

	@Test
	void 질의_파라미터가_SearchCriteria_로_바인딩된다() throws Exception {
		when(bidAnnounceService.search(any(), anyBoolean())).thenReturn(
				new SearchResultCache.Cached<>(new BidAnnounceService.AnnounceResult(List.of(), List.of()), false));

		mockMvc.perform(get("/api/bid-announce")
						.param("andTerms", "노트북,워크스테이션")
						.param("orTerms", "gpu")
						.param("notTerms", "임대")
						.param("pageNo", "3")
						.param("perPage", "all")
						.param("fromDate", "2026-07-01")
						.param("toDate", "2026-07-31")
						.param("insttNm", "○○청")
						.param("sortKey", "bidNtceDt")
						.param("sortDir", "desc")
						.param("searchField", "item")
						.param("bidType", "물품")
						.param("activeOnly", "true"))
				.andExpect(status().isOk());

		ArgumentCaptor<SearchCriteria> captor = ArgumentCaptor.forClass(SearchCriteria.class);
		verify(bidAnnounceService).search(captor.capture(), anyBoolean());
		SearchCriteria criteria = captor.getValue();

		assertThat(criteria.and()).containsExactly("노트북", "워크스테이션");
		assertThat(criteria.or()).containsExactly("gpu");
		assertThat(criteria.not()).containsExactly("임대");
		assertThat(criteria.page()).isEqualTo(3);
		assertThat(criteria.perPageValue()).isEqualTo(SearchCriteria.ALL_PER_PAGE);
		assertThat(criteria.dates().from()).isEqualTo("202607010000");
		assertThat(criteria.dates().to()).isEqualTo("202607312359");
		assertThat(criteria.insttNm()).isEqualTo("○○청");
		assertThat(criteria.sortKey()).isEqualTo("bidNtceDt");
		assertThat(criteria.sortDir()).isEqualTo("desc");
		assertThat(criteria.itemSearch()).isTrue();
		assertThat(criteria.type()).isEqualTo("물품");
		assertThat(criteria.activeOnlyEnabled()).isTrue();
	}

	@Test
	void 파라미터가_하나도_없어도_기본값으로_동작한다() throws Exception {
		when(bidAnnounceService.search(any(), anyBoolean())).thenReturn(
				new SearchResultCache.Cached<>(new BidAnnounceService.AnnounceResult(List.of(), List.of()), false));

		mockMvc.perform(get("/api/bid-announce")).andExpect(status().isOk());

		ArgumentCaptor<SearchCriteria> captor = ArgumentCaptor.forClass(SearchCriteria.class);
		verify(bidAnnounceService).search(captor.capture(), anyBoolean());
		assertThat(captor.getValue().page()).isEqualTo(1);
		assertThat(captor.getValue().perPageValue()).isEqualTo(20);
		assertThat(captor.getValue().type()).isEmpty();
	}

	@Test
	void fileScan_이면_캐시를_우회한다() throws Exception {
		when(bidAnnounceService.search(any(), anyBoolean())).thenReturn(
				new SearchResultCache.Cached<>(new BidAnnounceService.AnnounceResult(List.of(), List.of()), false));

		mockMvc.perform(get("/api/bid-announce").param("fileScan", "true")).andExpect(status().isOk());

		verify(bidAnnounceService).search(any(), org.mockito.ArgumentMatchers.eq(true));
	}

	@Test
	void 공고번호_직접조회는_페이징하지_않는다() throws Exception {
		when(bidAnnounceService.findByNoticeNumbers(any(), anyString()))
				.thenReturn(List.of(notice("N1", "노트북 구매", "g2b", 70)));

		mockMvc.perform(get("/api/bid-announce").param("bidNtceNo", "20260701234-01"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items.length()").value(1))
				.andExpect(jsonPath("$.numOfRows").value(1));
	}

	@Test
	void 마감_경과와_취소_공고는_activeOnly_에서_빠진다() throws Exception {
		Map<String, Object> expired = notice("N1", "지난 공고", "g2b", 90);
		expired.put("bidClseDt", "20200101");
		Map<String, Object> cancelled = notice("N2", "취소 공고", "g2b", 80);
		cancelled.put("bidClseDt", "20990101");
		cancelled.put("_isCancelled", true);
		Map<String, Object> live = notice("N3", "살아있는 공고", "g2b", 70);
		live.put("bidClseDt", "20990101");

		List<Map<String, Object>> items = new ArrayList<>(List.of(expired, cancelled, live));
		when(bidAnnounceService.search(any(), anyBoolean())).thenReturn(
				new SearchResultCache.Cached<>(new BidAnnounceService.AnnounceResult(items, List.of()), false));

		mockMvc.perform(get("/api/bid-announce").param("activeOnly", "true"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.totalCount").value(1))
				.andExpect(jsonPath("$.items[0].bidNtceNo").value("N3"));
	}

	@Test
	void 입찰결과는_공고번호_중복_없이_페이징만_한다() throws Exception {
		when(bidResultService.search(any(), any())).thenReturn(new SearchResultCache.Cached<>(
				List.of(notice("N1", "낙찰1", "g2b", 0), notice("N2", "낙찰2", "g2b", 0)), false));

		mockMvc.perform(get("/api/bid-result").param("corpNm", "일렉테리어").param("perPage", "1"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items.length()").value(1))
				.andExpect(jsonPath("$.totalCount").value(2))
				.andExpect(jsonPath("$.numOfRows").value(1))
				.andExpect(jsonPath("$._cached").value(false));

		verify(bidResultService).search(any(), org.mockito.ArgumentMatchers.eq("일렉테리어"));
	}

	@Test
	void 발주계획은_수주기회_점수_순으로_정렬된다() throws Exception {
		when(bidPlanService.search(any())).thenReturn(new SearchResultCache.Cached<>(
				List.of(notice("P1", "낮은 점수", "g2b", 10), notice("P2", "높은 점수", "g2b", 90)), false));

		mockMvc.perform(get("/api/bid-plan"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items[0].bidNtceNo").value("P2"));
	}

	@Test
	void 사전규격도_같은_봉투를_쓴다() throws Exception {
		when(preSpecService.search(any(), anyBoolean())).thenReturn(
				new SearchResultCache.Cached<>(List.of(notice("S1", "사전규격", "g2b", 80)), true));

		mockMvc.perform(get("/api/pre-spec"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.totalCount").value(1))
				.andExpect(jsonPath("$._cached").value(true));
	}

	@Test
	void pageNo_0은_페이징_없이_전부를_돌려준다() throws Exception {
		when(bidPlanService.search(any())).thenReturn(new SearchResultCache.Cached<>(
				List.of(notice("P1", "a", "g2b", 1), notice("P2", "b", "g2b", 2)), false));

		mockMvc.perform(get("/api/bid-plan").param("pageNo", "0").param("perPage", "1"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items.length()").value(2))
				.andExpect(jsonPath("$.pageNo").value(0));
	}
}
