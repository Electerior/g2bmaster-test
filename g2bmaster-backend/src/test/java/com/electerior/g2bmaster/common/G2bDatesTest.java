package com.electerior.g2bmaster.common;

import static org.assertj.core.api.Assertions.assertThat;

import com.electerior.g2bmaster.common.G2bDates.DateWindow;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * {@code lib/dates.js} 이식 검증.
 *
 * <p>기대값은 원본 JS를 그대로 돌려 뽑은 것이다(node 로 각 함수를 호출한 결과). 이 파일이
 * 하는 일은 "자바가 JS와 같은 창을 만든다"를 못 박는 것 — 창 하나가 어긋나면 그 기간의
 * 공고가 통째로 누락되는데, 그건 조회 결과만 봐서는 절대 안 보인다.
 */
class G2bDatesTest {

	@Nested
	@DisplayName("toG2bDt")
	class ToG2bDt {

		@Test
		void 시작일은_0000_종료일은_2359를_붙인다() {
			assertThat(G2bDates.toG2bDt("2026-08-05", false)).isEqualTo("202608050000");
			assertThat(G2bDates.toG2bDt("2026-08-05", true)).isEqualTo("202608052359");
		}

		@Test
		void 빈값은_null() {
			assertThat(G2bDates.toG2bDt(null, false)).isNull();
			assertThat(G2bDates.toG2bDt("", true)).isNull();
		}
	}

	@Nested
	@DisplayName("clampTo30Days")
	class ClampTo30Days {

		@Test
		void 삼십일을_넘으면_뒤에서_삼십일로_자른다() {
			DateWindow w = G2bDates.clampTo30Days("202601010000", "202603012359");
			assertThat(w.from()).isEqualTo("202601300000");
			assertThat(w.to()).isEqualTo("202603012359");
		}

		@Test
		void 삼십일_이내는_그대로_둔다() {
			DateWindow w = G2bDates.clampTo30Days("202602010000", "202602152359");
			assertThat(w.from()).isEqualTo("202602010000");
			assertThat(w.to()).isEqualTo("202602152359");
		}

		@Test
		void 한쪽이_비면_손대지_않는다() {
			assertThat(G2bDates.clampTo30Days(null, "202602152359").to()).isEqualTo("202602152359");
			assertThat(G2bDates.clampTo30Days("202602010000", null).from()).isEqualTo("202602010000");
		}
	}

	@Nested
	@DisplayName("parseG2bDt / formatG2bDt")
	class ParseFormat {

		@Test
		void 왕복하면_원본과_같다() {
			assertThat(G2bDates.formatG2bDt(G2bDates.parseG2bDt("202601021530"))).isEqualTo("202601021530");
		}

		@Test
		void 시분이_없으면_0000으로_채운다() {
			assertThat(G2bDates.formatG2bDt(G2bDates.parseG2bDt("20260102"))).isEqualTo("202601020000");
		}

		@Test
		void 구분자가_섞여도_읽는다() {
			assertThat(G2bDates.formatG2bDt(G2bDates.parseG2bDt("2026-01-02 15:30"))).isEqualTo("202601021530");
		}

		@Test
		void 여덟자리가_안_되면_null() {
			assertThat(G2bDates.parseG2bDt("2026")).isNull();
			assertThat(G2bDates.parseG2bDt(null)).isNull();
		}
	}

	@Nested
	@DisplayName("splitG2bDateRange")
	class SplitRange {

		@Test
		void 기본_31일_분할은_달력_월에_정렬된다() {
			// 월을 걸치는 창은 길이와 무관하게 [07]로 거절당한다. 그래서 3개월 요청은
			// 1·2·3월 세 창이 되어야 한다(31일씩 자른 임의 경계가 아니라).
			List<DateWindow> windows = G2bDates.splitG2bDateRange("202601010000", "202603312359");
			assertThat(windows).containsExactly(
					new DateWindow("202601010000", "202601312359"),
					new DateWindow("202602010000", "202602282359"),
					new DateWindow("202603010000", "202603312359"));
		}

		@Test
		void 월_중간에서_시작하면_첫_창은_월말에서_끊긴다() {
			List<DateWindow> windows = G2bDates.splitG2bDateRange("202601150000", "202602202359");
			assertThat(windows).containsExactly(
					new DateWindow("202601150000", "202601312359"),
					new DateWindow("202602010000", "202602202359"));
		}

		@Test
		void maxDays를_줄이면_그만큼_잘게_쪼갠다() {
			List<DateWindow> windows = G2bDates.splitG2bDateRange("202601010000", "202601312359", 7);
			assertThat(windows).containsExactly(
					new DateWindow("202601010000", "202601072359"),
					new DateWindow("202601080000", "202601142359"),
					new DateWindow("202601150000", "202601212359"),
					new DateWindow("202601220000", "202601282359"),
					new DateWindow("202601290000", "202601312359"));
		}

		@Test
		void 창들은_빈틈도_겹침도_없다() {
			List<DateWindow> windows = G2bDates.splitG2bDateRange("202601010000", "202604302359");
			for (int i = 1; i < windows.size(); i++) {
				String prevEnd = windows.get(i - 1).to();
				String currStart = windows.get(i).from();
				// 이전 창의 끝(23:59) 다음날 00:00 이 정확히 다음 창의 시작이어야 한다.
				assertThat(currStart)
						.isEqualTo(G2bDates.formatG2bDt(
								G2bDates.parseG2bDt(prevEnd).toLocalDate().plusDays(1).atStartOfDay()));
			}
			assertThat(windows.get(0).from()).isEqualTo("202601010000");
			assertThat(windows.get(windows.size() - 1).to()).isEqualTo("202604302359");
		}

		@Test
		void 기본_maxDays는_30이_아니라_31이다() {
			// 30으로 바꾸면 한 달 조회가 항상 두 창으로 갈라져 호출 수가 배로 는다.
			assertThat(G2bDates.DEFAULT_MAX_DAYS).isEqualTo(31);
			assertThat(G2bDates.splitG2bDateRange("202601010000", "202601312359")).hasSize(1);
		}

		@Test
		void 한쪽이_비면_통짜_창_하나를_돌려준다() {
			assertThat(G2bDates.splitG2bDateRange(null, "202601312359")).hasSize(1);
		}
	}

	@Nested
	@DisplayName("g2bRangeDays")
	class RangeDays {

		@Test
		void 원본의_계산식을_그대로_따른다() {
			// ceil(ms차/하루)+1 이라 1월 한 달이 32, 하루 창이 2로 나온다. '더 쪼갤 수 있는가'
			// 판정에만 쓰이는 값이므로 원본 수치를 유지한다.
			assertThat(G2bDates.g2bRangeDays("202601010000", "202601312359")).isEqualTo(32);
			assertThat(G2bDates.g2bRangeDays("202601010000", "202601012359")).isEqualTo(2);
			assertThat(G2bDates.g2bRangeDays("202601010000", "202601010000")).isEqualTo(1);
		}
	}

	@Nested
	@DisplayName("splitG2bRangeHalf")
	class SplitHalf {

		@Test
		void 한_달_창을_반으로_가른다() {
			assertThat(G2bDates.splitG2bRangeHalf("202601010000", "202601312359")).containsExactly(
					new DateWindow("202601010000", "202601162359"),
					new DateWindow("202601170000", "202601312359"));
		}

		@Test
		void 이틀_창도_하루씩_갈린다() {
			assertThat(G2bDates.splitG2bRangeHalf("202601010000", "202601022359")).containsExactly(
					new DateWindow("202601010000", "202601012359"),
					new DateWindow("202601020000", "202601022359"));
		}

		@Test
		void 사흘_창은_이틀_하루로_갈린다() {
			assertThat(G2bDates.splitG2bRangeHalf("202601010000", "202601032359")).containsExactly(
					new DateWindow("202601010000", "202601022359"),
					new DateWindow("202601030000", "202601032359"));
		}

		@Test
		void 하루짜리는_더_못_쪼갠다() {
			// 이게 이분 분할 재귀의 실질적 바닥이다. null 을 안 주면 무한 재귀가 된다.
			assertThat(G2bDates.splitG2bRangeHalf("202601010000", "202601012359")).isNull();
			assertThat(G2bDates.splitG2bRangeHalf("202601010000", "202601010000")).isNull();
			assertThat(G2bDates.splitG2bRangeHalf(null, "202601012359")).isNull();
		}
	}

	@Nested
	@DisplayName("defaultDates / daysUntil")
	class Defaults {

		@Test
		void 기본은_30일_구간이고_끝은_오늘_2359다() {
			DateWindow w = G2bDates.defaultDates();
			assertThat(w.to()).endsWith("2359");
			assertThat(w.from()).endsWith("0000");
			assertThat(G2bDates.parseG2bDt(w.from()).toLocalDate())
					.isEqualTo(LocalDate.now().minusDays(30));
			assertThat(G2bDates.parseG2bDt(w.to()).toLocalDate()).isEqualTo(LocalDate.now());
		}

		@Test
		void 검색화면이_쓰는_7일도_같은_규칙이다() {
			assertThat(G2bDates.parseG2bDt(G2bDates.defaultDates(7).from()).toLocalDate())
					.isEqualTo(LocalDate.now().minusDays(7));
		}

		@Test
		void 영이나_음수는_삼십일로_되돌린다() {
			assertThat(G2bDates.parseG2bDt(G2bDates.defaultDates(0).from()).toLocalDate())
					.isEqualTo(LocalDate.now().minusDays(30));
		}

		@Test
		void 남은_일수는_오늘_기준이며_읽을_수_없으면_null() {
			LocalDate target = LocalDate.now().plusDays(5);
			String dt = target.toString().replace("-", "") + "1800";
			assertThat(G2bDates.daysUntil(dt)).isEqualTo(5);
			assertThat(G2bDates.daysUntil("")).isNull();
			assertThat(G2bDates.daysUntil(null)).isNull();
			// 0은 '오늘 마감'이라는 뜻이므로 null 과 구분돼야 한다.
			assertThat(G2bDates.daysUntil(LocalDate.now().toString().replace("-", ""))).isZero();
		}
	}
}
