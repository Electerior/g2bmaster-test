package com.electerior.g2bmaster.integration.g2b;

import java.util.List;

/**
 * 적재 대상 오퍼레이션 하나.
 *
 * @param service    G2B 서비스명 (예: {@code BidPublicInfoService})
 * @param path       {@code baseUrl} 뒤에 붙는 경로 (예: {@code /ad/BidPublicInfoService})
 * @param op         오퍼레이션명 (예: {@code getBidPblancListInfoThng})
 * @param table      적재 대상 테이블. 전용 테이블이 없으면 {@code raw_generic_list}
 * @param pk         업서트 충돌 판정에 쓰는 기본키 컬럼들. 비어 있을 수 있다
 * @param inqryDivs  조회구분 값들. 같은 오퍼레이션을 구분값별로 각각 호출해야 전량이 모인다
 * @param windowed   {@code inqryBgnDt}/{@code inqryEndDt} 로 기간 조회가 되는가.
 *                   false 면 날짜 파라미터를 붙이면 안 된다(붙이면 빈 응답이 온다)
 */
public record G2bOperation(
		String service,
		String path,
		String op,
		String table,
		List<String> pk,
		List<String> inqryDivs,
		boolean windowed) {

	/** 이 오퍼레이션의 전체 URL. */
	public String url(String baseUrl) {
		return baseUrl + path + "/" + op;
	}
}
