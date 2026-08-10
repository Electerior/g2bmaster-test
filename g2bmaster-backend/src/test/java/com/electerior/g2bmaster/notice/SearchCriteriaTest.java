package com.electerior.g2bmaster.notice;

import static org.assertj.core.api.Assertions.assertThat;

import com.electerior.g2bmaster.common.G2bDates.DateWindow;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 검색 공통 파라미터 바인딩 ({@code docs/api-contract.md} §1.3).
 *
 * <p>원본에서는 탭마다 이 파싱을 손으로 복사했고, 그 과정에서 기본값이 조금씩 달라졌다
 * (발주계획만 {@code perPage} 를 안 읽어 항상 20건). 한 벌로 묶은 뒤에는 여기가 그 계약을 지킨다.
 */
class SearchCriteriaTest {

	private static SearchCriteria of(String perPage) {
		return new SearchCriteria(null, null, null, null, perPage,
				null, null, null, null, null, null, null, null);
	}

	@Test
	void perPage_는_1에서_500_사이로_잘린다() {
		assertThat(of("50").perPageValue()).isEqualTo(50);
		assertThat(of("9999").perPageValue()).isEqualTo(500);
		assertThat(of("0").perPageValue()).isEqualTo(20);       // 0은 미지정으로 본다
		assertThat(of("-3").perPageValue()).isEqualTo(20);
		assertThat(of("abc").perPageValue()).isEqualTo(20);
		assertThat(of(null).perPageValue()).isEqualTo(20);
	}

	@Test
	void perPage_all_은_사실상_전부를_뜻한다() {
		assertThat(of("all").perPageValue()).isEqualTo(SearchCriteria.ALL_PER_PAGE);
		assertThat(of("ALL").perPageValue()).isEqualTo(SearchCriteria.ALL_PER_PAGE);
	}

	@Test
	void pageNo_기본은_1이고_0은_페이징_없음을_뜻한다() {
		assertThat(SearchCriteria.empty().page()).isEqualTo(1);

		SearchCriteria all = new SearchCriteria(null, null, null, 0, null,
				null, null, null, null, null, null, null, null);
		assertThat(all.page()).isZero();
	}

	@Test
	void 검색어는_콤마로_나뉜다() {
		SearchCriteria criteria = new SearchCriteria("노트북, 워크스테이션", "gpu", "임대", null, null,
				null, null, null, null, null, null, null, null);

		assertThat(criteria.and()).containsExactly("노트북", "워크스테이션");
		assertThat(criteria.or()).containsExactly("gpu");
		assertThat(criteria.not()).containsExactly("임대");
		assertThat(criteria.queryTerms()).containsExactly("노트북", "워크스테이션", "gpu");
	}

	@Test
	void 상류에는_AND_첫_항만_보낸다() {
		// 나라장터는 공고명 부분일치 하나만 지원한다. 두 항을 같이 보내면 빈 결과가 온다.
		SearchCriteria and = new SearchCriteria("노트북,워크스테이션", "gpu,cpu", null, null, null,
				null, null, null, null, null, null, null, null);
		assertThat(and.upstreamTerms()).containsExactly("노트북");

		SearchCriteria or = new SearchCriteria(null, "gpu,cpu", null, null, null,
				null, null, null, null, null, null, null, null);
		assertThat(or.upstreamTerms()).containsExactly("gpu", "cpu");
	}

	@Test
	void 알_수_없는_사업구분은_필터_없음으로_떨어진다() {
		assertThat(criteriaWithType("물품").type()).isEqualTo("물품");
		assertThat(criteriaWithType("일반용역").type()).isEmpty();
		assertThat(criteriaWithType(null).type()).isEmpty();
	}

	@Test
	void 기본_조회_구간은_최근_7일이다() {
		DateWindow window = SearchCriteria.empty().dates();
		DateTimeFormatter ymd = DateTimeFormatter.ofPattern("yyyyMMdd");

		assertThat(window.from()).isEqualTo(LocalDate.now().minusDays(7).format(ymd) + "0000");
		assertThat(window.to()).isEqualTo(LocalDate.now().format(ymd) + "2359");
	}

	@Test
	void 종료일은_2359로_채워진다() {
		// 안 채우면 종료일 하루가 통째로 빠진다(G2B는 시각까지 비교한다).
		SearchCriteria criteria = new SearchCriteria(null, null, null, null, null,
				"2026-07-01", "2026-07-31", null, null, null, null, null, null);

		assertThat(criteria.dates().from()).isEqualTo("202607010000");
		assertThat(criteria.dates().to()).isEqualTo("202607312359");
	}

	@Test
	void 캐시_키_파라미터에는_페이징과_정렬이_들어가지_않는다() {
		// 넣으면 페이지를 넘길 때마다 상류를 다시 친다.
		SearchCriteria criteria = new SearchCriteria("노트북", null, null, 3, "50",
				null, null, null, "bidNtceDt", "desc", null, null, null);

		assertThat(criteria.cacheParams()).doesNotContainKeys("pageNo", "perPage", "sortKey", "sortDir");
		assertThat(criteria.cacheParams()).containsEntry("a", "노트북");
	}

	@Test
	void 캐시_키_파라미터에_탭별_추가값을_얹을_수_있다() {
		assertThat(SearchCriteria.empty().cacheParams("corpNm", "일렉테리어"))
				.containsEntry("corpNm", "일렉테리어");
	}

	@Test
	void 품목_모드와_활성만_보기_플래그() {
		SearchCriteria criteria = new SearchCriteria(null, null, null, null, null,
				null, null, null, null, null, "item", null, "true");

		assertThat(criteria.itemSearch()).isTrue();
		assertThat(criteria.activeOnlyEnabled()).isTrue();
	}

	@Test
	void 긴_기간은_조회_창으로_쪼개진다() {
		SearchCriteria criteria = new SearchCriteria(null, null, null, null, null,
				"2026-01-01", "2026-03-31", null, null, null, null, null, null);

		List<DateWindow> windows = criteria.dateWindows();
		assertThat(windows).hasSizeGreaterThan(1);
		assertThat(windows.get(0).from()).isEqualTo("202601010000");
		assertThat(windows.get(windows.size() - 1).to()).isEqualTo("202603312359");
	}

	private static SearchCriteria criteriaWithType(String bidType) {
		return new SearchCriteria(null, null, null, null, null,
				null, null, null, null, null, null, bidType, null);
	}
}
