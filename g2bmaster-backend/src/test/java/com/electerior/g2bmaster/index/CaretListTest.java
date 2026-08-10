package com.electerior.g2bmaster.index;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 캐럿 목록 파서.
 *
 * <p>입력 문자열은 전부 <b>2026-07-01 실제 응답에서 그대로 뽑은 것</b>이다. 지어낸 예제로
 * 테스트하면 진짜 함정(품명 안의 콤마)을 못 만난다.
 */
class CaretListTest {

	@Test
	@DisplayName("단일 품목")
	void single() {
		List<CaretList.Entry> entries = CaretList.parse("[1^4110412701^실험실용공급기기]");

		assertThat(entries).containsExactly(new CaretList.Entry("1", "4110412701", "실험실용공급기기"));
	}

	@Test
	@DisplayName("복수 품목 — 구분자는 '],[' 다")
	void multiple() {
		List<CaretList.Entry> entries = CaretList.parse(
				"[1^2010170702^파쇄기설비],[1^2010179901^선회해머분쇄기],[1^2410172201^체인컨베이어]");

		assertThat(entries).extracting(CaretList.Entry::name)
				.containsExactly("파쇄기설비", "선회해머분쇄기", "체인컨베이어");
	}

	/**
	 * 이 테스트가 이 클래스의 존재 이유다.
	 *
	 * <p>해군 '밸브,앵글형 1종' 처럼 <b>품명 안에 콤마</b>가 든 건이 실제로 있다. 콤마로 자르면
	 * 품목 하나가 '(긴급) 밸브' 와 '앵글형 1종' 둘로 쪼개져, 존재하지 않는 품명이 색인에 들어간다.
	 */
	@Test
	@DisplayName("품명에 콤마가 들어 있어도 쪼개지지 않는다")
	void commaInsideName() {
		List<CaretList.Entry> entries = CaretList.parse("[1^^(긴급) 밸브,앵글형 1종]");

		assertThat(entries).hasSize(1);
		assertThat(entries.get(0).name()).isEqualTo("(긴급) 밸브,앵글형 1종");
		assertThat(entries.get(0).code()).isEmpty();
	}

	@Test
	@DisplayName("빈 값·null 은 빈 목록")
	void empty() {
		assertThat(CaretList.parse(null)).isEmpty();
		assertThat(CaretList.parse("")).isEmpty();
		assertThat(CaretList.parse("   ")).isEmpty();
		assertThat(CaretList.parse("[]")).isEmpty();
	}

	/**
	 * 캐럿이 없는 문자열은 품목이 아니다 — 번호도 품명도 못 뽑으므로 버린다.
	 * 통째로 seq 에 밀어 넣으면 품명이 빈 품목이 색인에 쌓여 화면에 빈 줄로 나온다.
	 */
	@Test
	@DisplayName("깨진 입력에도 예외를 던지지 않는다 — 못 읽는 조각은 버린다")
	void malformed() {
		assertThat(CaretList.parse("품명만 있고 구분자가 없음")).isEmpty();
		// 품명이 살아 있으면 번호가 없어도 담는다(사전규격이 실제로 이 모양이다).
		assertThat(CaretList.parse("[1^^품명만]"))
				.containsExactly(new CaretList.Entry("1", "", "품명만"));
		// 닫는 대괄호가 없는 깨진 값도 읽을 수 있는 만큼 읽는다.
		assertThat(CaretList.parse("[1^2^품명")).hasSize(1);
	}

	/**
	 * 단가계약 공고는 같은 품명을 수십 줄 반복한다(실측: 혈액대용제 10회).
	 * 표시용 목록에는 그대로 남기되, 검색 본문에서는 한 번만 세야 관련도가 부풀지 않는다.
	 */
	@Test
	@DisplayName("검색 본문용 품명은 중복을 없앤다")
	void distinctNamesForBody() {
		String repeated = "[1^5128030101^혈액대용제],[1^5128030101^혈액대용제],[1^5128030101^혈액대용제]";
		List<CaretList.Entry> entries = CaretList.parse(repeated);

		assertThat(entries).hasSize(3);
		assertThat(CaretList.distinctNames(entries)).containsExactly("혈액대용제");
	}

	@Test
	@DisplayName("품목 수 상한을 넘기지 않는다")
	void capped() {
		String many = String.join(",", java.util.Collections.nCopies(500, "[1^1234567890^품목]"));

		assertThat(CaretList.parse(many)).hasSizeLessThanOrEqualTo(100);
	}
}
