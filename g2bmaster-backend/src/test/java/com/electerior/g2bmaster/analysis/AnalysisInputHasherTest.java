package com.electerior.g2bmaster.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * 교차 언어 고정값(fixture) 테스트 — <b>이 저장소에서 가장 깨지면 안 되는 테스트다.</b>
 *
 * <p>기대값은 Node 원본 {@code g2bmastersopen/lib/analysis-history.js} 의
 * {@code analysisInputHash} 를 그대로 호출해 뽑았다. 재생성 방법:
 *
 * <pre>{@code
 * cd g2bmastersopen
 * node -e "const {analysisInputHash}=require('./lib/analysis-history');
 *          console.log(analysisInputHash(<아래 payload 와 같은 객체>))"
 * }</pre>
 *
 * <p>이 값이 어긋나면 이관 시점에 {@code analysis_history} 의 기존 결과가 전부 재사용되지 않는다.
 * 실패했을 때 <b>기대값을 고치는 것은 답이 아니다</b> — Java 구현을 원본에 맞춰야 한다.
 */
class AnalysisInputHasherTest {

	private static final ObjectMapper MAPPER = JsonMapper.builder().build();

	private static JsonNode json(String text) {
		return MAPPER.readTree(text);
	}

	// ── 고정값 4종 ──────────────────────────────────────────────────────────

	@Test
	@DisplayName("고정값 — 공고번호 하나뿐인 최소 입력")
	void minimal() {
		JsonNode input = json("""
				{ "bidNtceNo": "20260801234" }
				""");

		assertThat(AnalysisIdentity.resolve(input))
				.isEqualTo(new AnalysisIdentity("bid_notice", "20260801234", ""));
		assertThat(AnalysisInputHasher.hash(input))
				.isEqualTo("138d0854252805b972a8282ffa213ca2c73d4ff71a4fdc7625cb9cfe07ed7643");
	}

	/**
	 * 정규화의 위험 지점을 한 번에 밟는 입력이다:
	 * 휘발성 필드 제거({@code __rowId}/{@code _cached}/{@code _opportunityScore}/{@code _searchScore}/{@code _analysis}),
	 * {@code __} 접두 제거, 중첩 객체에서의 문맥 전파, <b>배열 안에서의 문맥 초기화</b>,
	 * 한글 키 정렬, 숫자·불리언·null 표기, 이스케이프, 첨부 정렬.
	 */
	@Test
	@DisplayName("고정값 — 전 필드 + 휘발성 필드 + 중첩/배열 + 한글 + 이스케이프")
	void full() {
		JsonNode input = json("""
				{
				  "entityType": "bid_notice",
				  "bidNtceNo": "20260801234",
				  "bidNtceSqNo": "01",
				  "title": "GPU 서버 구매(재공고)",
				  "insttNm": "한국전자통신연구원",
				  "itemName": "컴퓨터서버",
				  "amount": 123456789,
				  "cntrctMthdNm": "제한경쟁",
				  "type": "물품",
				  "companyProfile": "중소기업 / 직접생산확인",
				  "deep": "true",
				  "context": "견적 비교용",
				  "specText": "CPU 12Core 이상, DDR5 128GB",
				  "preferFile": "규격서.hwp",
				  "analysisMode": "deep",
				  "fileEntries": [
				    { "name": "규격서.hwp", "url": "https://www.g2b.go.kr/b.hwp" },
				    { "name": "공고서.pdf", "url": "https://www.g2b.go.kr/a.pdf" },
				    { "name": "가격.xlsx",  "url": "https://www.g2b.go.kr/a.pdf" }
				  ],
				  "rawFields": {
				    "bidNtceNo": "20260801234",
				    "bidNtceOrd": "01",
				    "__rowId": "row-42",
				    "_cached": true,
				    "_opportunityScore": 88,
				    "_searchScore": 0.5,
				    "_analysis": { "status": "pending" },
				    "__internal": "x",
				    "presmptPrce": "123456789",
				    "ratio": 1.5,
				    "zero": 0,
				    "nullField": null,
				    "flag": false,
				    "한글키": "한글값",
				    "nested": { "__rowId": "nested-row", "b": 2, "a": 1 },
				    "list": [
				      { "__rowId": "in-array", "_cached": false, "name": "품목1" },
				      { "name": "품목2" }
				    ],
				    "quote\\"and\\\\backslash": "tab\\there\\nnewline"
				  }
				}
				""");

		assertThat(AnalysisIdentity.resolve(input))
				.isEqualTo(new AnalysisIdentity("bid_notice", "20260801234", "01"));
		assertThat(AnalysisInputHasher.hash(input))
				.isEqualTo("3cc9e7c99fca101f48e9513fd751d39d23c69c700e38a323587eb9965a18ca12");
	}

	@Test
	@DisplayName("고정값 — 발주계획(ord 는 항상 빈 문자열)")
	void procurementRequest() {
		JsonNode input = json("""
				{
				  "entityType": "procurement_request",
				  "prcrmntReqNo": "PR-2026-0001",
				  "title": "전산장비 구매",
				  "amount": "5000000",
				  "rawFields": { "prcrmntReqNo": "PR-2026-0001" }
				}
				""");

		assertThat(AnalysisIdentity.resolve(input))
				.isEqualTo(new AnalysisIdentity("procurement_request", "PR-2026-0001", ""));
		assertThat(AnalysisInputHasher.hash(input))
				.isEqualTo("66afaf9ba25fbdac6a844dcd23dd3b7c6bb8f5a6f2c6586925e500cce96bc8d2");
	}

	@Test
	@DisplayName("고정값 — 사전규격, deep='no' 는 거짓")
	void preSpec() {
		JsonNode input = json("""
				{
				  "entityType": "pre_spec",
				  "bfSpecRgstNo": "BS-2026-99",
				  "deep": "no",
				  "rawFields": {}
				}
				""");

		assertThat(AnalysisIdentity.resolve(input))
				.isEqualTo(new AnalysisIdentity("pre_spec", "BS-2026-99", ""));
		assertThat(AnalysisInputHasher.hash(input))
				.isEqualTo("65751d424cb63020630af64f19f6fe35eb442fbd63f777041c2309a0c078d503");
	}

	// ── 정규화 규칙 단위 검증 ────────────────────────────────────────────────

	@Test
	@DisplayName("정규화 JSON — 키는 UTF-16 코드유닛 순, 휘발성 필드는 빠지고, 배열 안에서는 안 빠진다")
	void canonicalJsonShape() {
		JsonNode input = json("""
				{
				  "bidNtceNo": "1",
				  "rawFields": {
				    "_cached": true,
				    "nested": { "__rowId": "x", "b": 2, "a": 1 },
				    "list": [ { "_cached": false, "name": "n" } ],
				    "한글": "값"
				  }
				}
				""");
		String canonical = JsCanonicalJson.stringify(
				AnalysisInputHasher.payload(input, AnalysisIdentity.resolve(input)));

		assertThat(canonical).contains("\"rawFields\":{\"list\":[{\"_cached\":false,\"name\":\"n\"}],"
				+ "\"nested\":{\"a\":1,\"b\":2},\"한글\":\"값\"}");
		assertThat(canonical).doesNotContain("__rowId");
		// 최상위 키도 정렬된다 — amount 가 맨 앞, type 이 맨 뒤.
		assertThat(canonical).startsWith("{\"amount\":\"\",\"analysisMode\":\"\"");
		assertThat(canonical).endsWith("\"type\":\"\"}");
	}

	@Test
	@DisplayName("숫자 표기 — JS Number::toString 규칙")
	void numberFormatting() {
		assertThat(JsCanonicalJson.formatNumber(0)).isEqualTo("0");
		assertThat(JsCanonicalJson.formatNumber(-0.0)).isEqualTo("0");
		assertThat(JsCanonicalJson.formatNumber(1.0)).isEqualTo("1");        // Java 라면 "1.0"
		assertThat(JsCanonicalJson.formatNumber(1.5)).isEqualTo("1.5");
		assertThat(JsCanonicalJson.formatNumber(-2.25)).isEqualTo("-2.25");
		assertThat(JsCanonicalJson.formatNumber(123456789)).isEqualTo("123456789");
		assertThat(JsCanonicalJson.formatNumber(0.5)).isEqualTo("0.5");
		assertThat(JsCanonicalJson.formatNumber(1e-6)).isEqualTo("0.000001");
		assertThat(JsCanonicalJson.formatNumber(1e-7)).isEqualTo("1e-7");    // Java 라면 "1.0E-7"
		assertThat(JsCanonicalJson.formatNumber(1e21)).isEqualTo("1e+21");
		assertThat(JsCanonicalJson.formatNumber(1e20)).isEqualTo("100000000000000000000");
		assertThat(JsCanonicalJson.formatNumber(1.2345e-8)).isEqualTo("1.2345e-8");
	}

	@Test
	@DisplayName("deep 판정 — 원본 정규식 그대로")
	void deepFlag() {
		assertThat(AnalysisInputHasher.isDeep(json("{\"d\":true}").path("d"))).isTrue();
		assertThat(AnalysisInputHasher.isDeep(json("{\"d\":1}").path("d"))).isTrue();
		assertThat(AnalysisInputHasher.isDeep(json("{\"d\":\" DEEP \"}").path("d"))).isTrue();
		assertThat(AnalysisInputHasher.isDeep(json("{\"d\":\"full\"}").path("d"))).isTrue();
		assertThat(AnalysisInputHasher.isDeep(json("{\"d\":\"no\"}").path("d"))).isFalse();
		assertThat(AnalysisInputHasher.isDeep(json("{\"d\":null}").path("d"))).isFalse();
		assertThat(AnalysisInputHasher.isDeep(json("{}").path("d"))).isFalse();
	}

	@Test
	@DisplayName("자연키 — entityType 이 지목한 값이 비어 있으면 값이 있는 쪽으로 떨어진다")
	void identityFallsBackWhenRequestedTypeIsEmpty() {
		JsonNode input = json("""
				{ "entityType": "pre_spec", "bidNtceNo": "20260801234" }
				""");
		assertThat(AnalysisIdentity.resolve(input))
				.isEqualTo(new AnalysisIdentity("bid_notice", "20260801234", ""));
	}

	@Test
	@DisplayName("자연키 — 어느 것도 없으면 null (분석 대상 아님)")
	void identityMissing() {
		assertThat(AnalysisIdentity.resolve(json("{\"title\":\"제목만\"}"))).isNull();
	}

	@Test
	@DisplayName("자연키 — rawFields 의 snake_case 도 본다")
	void identityFromRawFields() {
		assertThat(AnalysisIdentity.resolve(json("""
				{ "rawFields": { "bid_ntce_no": "999", "bid_ntce_ord": "02" } }
				""")))
				.isEqualTo(new AnalysisIdentity("bid_notice", "999", "02"));
	}
}
