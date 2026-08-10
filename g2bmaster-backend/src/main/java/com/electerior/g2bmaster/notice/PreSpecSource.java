package com.electerior.g2bmaster.notice;

import com.electerior.g2bmaster.common.G2bDates.DateWindow;
import java.util.List;
import java.util.Map;

/**
 * 사전규격 원자료를 어디서 가져올지 고르는 자리(seam).
 *
 * <p><b>지금은 구현이 하나뿐이다</b> — {@link LivePreSpecSource}(나라장터 실시간 API).
 * 이 인터페이스가 존재하는 이유는 다음 단계에서 <em>DB 우선 조회</em>와 <em>카나리 라우팅</em>
 * (원본 {@code PRE_SPEC_SOURCE} / {@code PRE_SPEC_DB_CANARY_PERCENT} / {@code recordPreSpecShadow})
 * 을 붙일 자리를 미리 못 박아 두기 위해서다. 그때 추가되는 것은
 * {@code DbFirstPreSpecSource} 와 두 소스를 고르는 라우터이며,
 * {@link PreSpecService} 는 손대지 않는다.
 *
 * <p>지금 단계에서 라우팅을 <em>구현하지 않은</em> 것은 의도다. 적재 테이블 스키마와 커버리지
 * 판정({@code hasPreSpecCoverage})이 아직 없어서, 지금 만들면 검증할 수 없는 분기만 늘어난다.
 */
public interface PreSpecSource {

	/**
	 * 기간·사업구분에 해당하는 사전규격 원자료.
	 *
	 * @param dates   조회 창 ({@code YYYYMMDDHHmm})
	 * @param bidType 사업 구분. 빈 문자열이면 전체
	 * @return 정규화 전 원자료. 비어 있을 수 있다
	 */
	List<Map<String, Object>> load(DateWindow dates, String bidType);
}
