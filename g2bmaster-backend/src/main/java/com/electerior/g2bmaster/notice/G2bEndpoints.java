package com.electerior.g2bmaster.notice;

import com.electerior.g2bmaster.config.G2bProperties;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 검색 계열이 쓰는 나라장터 오퍼레이션 URL ({@code server.js} 의 {@code EP} 이식).
 *
 * <p>{@code G2bOperationCatalog} 는 <b>일괄 적재용</b> 189개 오퍼레이션 목록이고, 여기는
 * <b>검색 화면이 실시간으로 두드리는</b> 소수의 엔드포인트다. 둘을 합치지 않는 이유는
 * 선택 기준이 다르기 때문이다 — 적재는 "전용 테이블이 있는가", 검색은 "이 탭이 쓰는가".
 *
 * <p>물품/용역/공사가 <b>각각 다른 오퍼레이션</b>인 것이 이 API의 근본 제약이다. 한 번에
 * 훑을 수 없어 모든 검색이 3중 팬아웃이 되고, 그게 호출량 문제의 출발점이다.
 *
 * <p>맵 순서는 물품 → 용역 → 공사로 고정한다({@link LinkedHashMap}). 응답 순서가
 * 동점 정렬의 사실상 기본값이 되므로, 순서가 흔들리면 같은 검색이 매번 다르게 보인다.
 */
@Component
public class G2bEndpoints {

	private static final String BID_ANNOUNCE = "/ad/BidPublicInfoService/getBidPblancListInfo";
	private static final String BID_RESULT = "/as/ScsbidInfoService/getScsbidListSttus";
	private static final String OPENG_RESULT = "/ao/OpengResultInfoService/getOpengResultList";
	private static final String BID_PLAN = "/ao/PrcrmntReqInfoService/getPrcrmntReqInfoList";
	private static final String PRE_SPEC = "/ao/HrcspSsstndrdInfoService/getPublicPrcureThngInfo";
	private static final String PRIVATE_NOTICE = "/ao/PrvtBidNtceService/getPrvtBidPblancListInfo";

	/** 사용자가 고를 수 있는 사업 구분. */
	public static final List<String> BID_TYPES = List.of("물품", "용역", "공사");

	private final Map<String, String> bidAnnounce;
	private final Map<String, String> bidResult;
	private final Map<String, String> opengResult;
	private final Map<String, String> privateNotice;
	private final List<Map.Entry<String, String>> bidPlan;
	private final List<Map.Entry<String, String>> preSpec;

	@Autowired
	public G2bEndpoints(G2bProperties properties) {
		this(properties.openapi().baseUrl());
	}

	/** 테스트가 임의의 베이스 URL 로 만들 수 있게 열어 둔 생성자. */
	public G2bEndpoints(String baseUrl) {
		String base = trimTrailingSlash(baseUrl);
		this.bidAnnounce = frozen(ordered(
				"물품", base + BID_ANNOUNCE + "ThngPPSSrch",
				"용역", base + BID_ANNOUNCE + "ServcPPSSrch",
				"공사", base + BID_ANNOUNCE + "CnstwkPPSSrch"));
		this.bidResult = frozen(ordered(
				"물품", base + BID_RESULT + "ThngPPSSrch",
				"용역", base + BID_RESULT + "ServcPPSSrch",
				"공사", base + BID_RESULT + "CnstwkPPSSrch"));
		this.opengResult = frozen(ordered(
				"물품", base + OPENG_RESULT + "Thng",
				"용역", base + OPENG_RESULT + "Servc",
				"공사", base + OPENG_RESULT + "Cnstwk"));
		// 누리장터는 '기타' 구분이 하나 더 있다 — 민간 공고에는 3분류에 안 들어가는 것이 섞인다.
		Map<String, String> privateMap = ordered(
				"물품", base + PRIVATE_NOTICE + "ThngPPSSrch",
				"용역", base + PRIVATE_NOTICE + "ServcPPSSrch",
				"공사", base + PRIVATE_NOTICE + "CnstwkPPSSrch");
		privateMap.put("기타", base + PRIVATE_NOTICE + "EtcPPSSrch");
		this.privateNotice = frozen(privateMap);
		// 발주계획은 구분이 넷이다 — 용역이 '일반용역'으로, 외자가 별도로 있다.
		// 입찰공고의 3분류와 이름이 어긋나므로 bidTypeMatches 로 걸러야 한다.
		this.bidPlan = List.of(
				Map.entry("물품", base + BID_PLAN + "Thng"),
				Map.entry("일반용역", base + BID_PLAN + "GnrlServc"),
				Map.entry("공사", base + BID_PLAN + "Cnstwk"),
				Map.entry("외자", base + BID_PLAN + "Frgcpt"));
		this.preSpec = List.of(
				Map.entry("물품", base + PRE_SPEC + "Thng"),
				Map.entry("일반용역", base + PRE_SPEC + "Servc"),
				Map.entry("공사", base + PRE_SPEC + "Cnstwk"));
	}

	/** 사업 구분 → 입찰공고 URL (물품·용역·공사 순). */
	public Map<String, String> bidAnnounce() {
		return bidAnnounce;
	}

	/** 사업 구분 → 낙찰정보 URL. */
	public Map<String, String> bidResult() {
		return bidResult;
	}

	/** 사업 구분 → 개찰결과 URL. */
	public Map<String, String> opengResult() {
		return opengResult;
	}

	/** 알 수 없는 구분은 물품으로 떨어진다 — 원본과 같다. */
	public String opengResultOf(String type) {
		String url = opengResult.get(type);
		return url != null ? url : opengResult.get("물품");
	}

	/** 발주계획(조달요청) 오퍼레이션 — 구분명이 입찰공고와 다르다. */
	public List<Map.Entry<String, String>> bidPlan() {
		return bidPlan;
	}

	/** 사전규격 오퍼레이션. */
	public List<Map.Entry<String, String>> preSpec() {
		return preSpec;
	}

	/** 누리장터(민간 공고) 구분 → URL. */
	public Map<String, String> privateNotice() {
		return privateNotice;
	}

	/** URL 에서 사업 구분을 되읽는다. 응답 항목에 {@code _type} 을 붙일 때 쓴다. */
	public static String typeOfUrl(String url) {
		if (url == null) {
			return "물품";
		}
		if (url.contains("Cnstwk")) {
			return "공사";
		}
		return url.contains("Servc") ? "용역" : "물품";
	}

	/** 발주계획·사전규격의 '일반용역'이 사용자 필터 '용역'에 걸리게 한다. */
	public static boolean bidTypeMatches(String endpointType, String bidType) {
		return bidType == null || bidType.isEmpty()
				|| (endpointType != null && endpointType.contains(bidType));
	}

	private static Map<String, String> frozen(Map<String, String> map) {
		return Collections.unmodifiableMap(map);
	}

	private static Map<String, String> ordered(String k1, String v1, String k2, String v2,
			String k3, String v3) {
		Map<String, String> map = new LinkedHashMap<>();
		map.put(k1, v1);
		map.put(k2, v2);
		map.put(k3, v3);
		return map;
	}

	private static String trimTrailingSlash(String value) {
		String url = value == null ? "" : value;
		return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
	}
}
