package com.electerior.g2bmaster.market;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.electerior.g2bmaster.common.GlobalExceptionHandler;
import com.electerior.g2bmaster.integration.g2b.G2bErrorTranslator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * 시장정보 엔드포인트의 계약 — 필수값 검증 문구와 "미공개는 오류가 아니다".
 *
 * <p>한국어 오류 문구는 화면에 그대로 렌더링되는 <b>사용자 대상 계약</b>이라
 * ({@code docs/api-contract.md} §1.1) 여기서 문자열째 고정한다.
 */
class MarketIntelControllerTest {

	private MarketIntelService service;
	private MockMvc mockMvc;

	@BeforeEach
	void setUp() {
		service = mock(MarketIntelService.class);
		MarketIntelController controller = new MarketIntelController(
				service,
				new com.electerior.g2bmaster.pricing.DealAnalysisService(),
				new com.electerior.g2bmaster.attachment.DocumentTextExtractor(),
				mock(com.electerior.g2bmaster.attachment.AttachmentFetcher.class),
				mock(com.electerior.g2bmaster.integration.ai.AiClient.class),
				mock(com.electerior.g2bmaster.pricing.DealAnalysisRepository.class),
				mock(com.electerior.g2bmaster.saved.SavedNoticeRepository.class),
				mock(com.electerior.g2bmaster.notice.BidResultService.class));
		mockMvc = MockMvcBuilders.standaloneSetup(controller)
				.setControllerAdvice(new GlobalExceptionHandler(new G2bErrorTranslator()))
				.build();
	}

	@Test
	void 개찰결과_미공개는_오류가_아니라_빈_배열이다() throws Exception {
		// 개찰 전 공고를 열어 본 것뿐이라 사용자가 잘못한 것이 없다.
		when(service.fetchOpeningResults(anyString(), any(), anyString())).thenReturn(List.of());

		mockMvc.perform(post("/api/bid-opening-results")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"bidNtceNo\":\"20260701234\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.bidNtceNo").value("20260701234"))
				.andExpect(jsonPath("$.participants.length()").value(0));
	}

	@Test
	void 개찰결과_조회에_공고번호가_없으면_400() throws Exception {
		mockMvc.perform(post("/api/bid-opening-results")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error").value("bidNtceNo 필수"));

		verify(service, never()).fetchOpeningResults(any(), any(), any());
	}

	@Test
	void 개찰결과는_참여업체_목록을_그대로_싣는다() throws Exception {
		Map<String, Object> participant = new LinkedHashMap<>();
		participant.put("bdrNm", "일렉테리어");
		participant.put("rank", "1");
		when(service.fetchOpeningResults(anyString(), any(), anyString())).thenReturn(List.of(participant));

		mockMvc.perform(post("/api/bid-opening-results")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"bidNtceNo\":\"N1\",\"bidNtceSqNo\":\"001\",\"type\":\"용역\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.bidNtceSqNo").value("001"))
				.andExpect(jsonPath("$.participants[0].bdrNm").value("일렉테리어"));

		verify(service).fetchOpeningResults("N1", "001", "용역");
	}

	@Test
	void 업체이력은_업체명이나_사업자번호_중_하나가_필요하다() throws Exception {
		mockMvc.perform(post("/api/company-history")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error").value("업체명 또는 사업자번호 필요"));
	}

	@Test
	void 사업자번호만_있어도_업체이력을_조회한다() throws Exception {
		when(service.companyHistory(any())).thenReturn(Map.of("corpNm", ""));

		mockMvc.perform(post("/api/company-history")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"brnNo\":\"123-45-67890\"}"))
				.andExpect(status().isOk());

		verify(service).companyHistory(any());
	}

	@Test
	void 담당자_조회는_발주기관명이_필수다() throws Exception {
		mockMvc.perform(post("/api/officer-search")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"insttNm\":\"  \"}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error").value("발주기관명 필요"));
	}

	@Test
	void 담합분석은_빈_배열이면_400() throws Exception {
		mockMvc.perform(post("/api/collusion-analysis")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"bids\":[]}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error").value("bids 배열 필수"));
	}

	@Test
	void 담합분석은_공고_배열을_그대로_넘긴다() throws Exception {
		when(service.collusionAnalysis(any())).thenReturn(Map.of("pairs", List.of(), "companies", List.of()));

		mockMvc.perform(post("/api/collusion-analysis")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"bids\":[{\"bidNtceNo\":\"N1\"},{\"bidNtceNo\":\"N2\"}]}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.pairs").isArray());

		verify(service).collusionAnalysis(any());
	}
}
