package com.electerior.g2bmaster.trend;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 트렌드 3종의 키워드 그룹 ({@code lib/bid-trends.js} 의 {@code TREND_PROFILES} 이식).
 *
 * <p>그룹은 <b>두 가지 일을 동시에</b> 한다.
 * <ol>
 *   <li><b>집계 축</b> — 공고를 12개 묶음으로 세어 "이번 주에 뭐가 많이 나왔나"를 보여준다.</li>
 *   <li><b>검색어 확장</b> — 사용자가 그룹 라벨({@code AI})을 그대로 치면 그 그룹의
 *       키워드 목록으로 펴서 조회한다. 라벨은 상류 API에 그대로 보내면 0건이 오는 말이므로,
 *       확장하지 않으면 화면이 빈다.</li>
 * </ol>
 *
 * <p><b>키워드 목록은 실무에서 튜닝된 값이다.</b> 예컨대 물품의 'GPU/AI장비' 에 {@code rtx}·
 * {@code nvidia} 같은 제품명이 들어 있는 것은, 공고명이 분류코드 대신 모델명으로만 적히는
 * 경우가 많기 때문이다. "정리"하면 그 공고들이 집계에서 통째로 사라진다.
 */
public final class TrendProfiles {

	/** 트렌드 프로파일 하나. */
	public record TrendProfile(
			String key,
			String cacheName,
			String typeName,
			List<KeywordGroup> keywordGroups) {}

	/** 그룹 라벨과 그에 속한 키워드들. */
	public record KeywordGroup(String label, List<String> keywords) {}

	private static final List<KeywordGroup> SERVICE_GROUPS = List.of(
			group("AI", "ai", "인공지능", "생성형", "llm", "머신러닝", "딥러닝"),
			group("데이터", "데이터", "빅데이터", "data", "db"),
			group("클라우드", "클라우드", "cloud", "클라우드네이티브", "saas"),
			group("SW개발", "개발", "구축", "고도화", "소프트웨어", "sw", "시스템"),
			group("유지보수", "유지보수", "운영", "위탁운영", "관리용역"),
			group("보안", "보안", "정보보호", "취약점", "인증", "암호"),
			group("교육", "교육", "연수", "훈련", "콘텐츠", "강의"),
			group("컨설팅", "컨설팅", "자문", "전략", "ISP", "감리"),
			group("홈페이지", "홈페이지", "웹사이트", "포털", "누리집"),
			group("플랫폼", "플랫폼", "서비스 플랫폼", "통합 플랫폼"),
			group("R&D", "연구", "r&d", "실증", "시범사업"),
			group("GPU/인프라", "gpu", "서버", "인프라", "전산", "컴퓨팅"));

	private static final List<KeywordGroup> PRODUCT_GROUPS = List.of(
			group("PC/노트북", "pc", "컴퓨터", "데스크톱", "데스크탑", "노트북", "워크스테이션", "일체형"),
			group("모니터/전자칠판", "모니터", "디스플레이", "전자칠판", "사이니지", "tv", "텔레비전"),
			group("서버/스토리지", "서버", "스토리지", "nas", "rack", "랙", "hdd", "ssd", "nvme", "저장장치"),
			group("GPU/AI장비", "gpu", "그래픽", "rtx", "nvidia", "ai", "딥러닝", "인공지능"),
			group("네트워크/보안장비", "스위치", "라우터", "방화벽", "네트워크", "poe", "무선", "ap", "wifi"),
			group("프린터/복합기", "프린터", "복합기", "스캐너", "토너", "잉크"),
			group("태블릿/모바일", "태블릿", "아이패드", "ipad", "갤럭시탭", "스마트폰", "휴대폰"),
			group("가전/생활", "냉장고", "세탁기", "청소기", "정수기", "에어컨", "공기청정기"),
			group("영상/음향", "카메라", "프로젝터", "마이크", "스피커", "음향", "방송", "영상"),
			group("소프트웨어", "라이선스", "소프트웨어", "sw", "office", "한컴", "백신"),
			group("가구/사무", "책상", "의자", "가구", "캐비닛", "사무용"),
			group("실험/계측", "실험", "계측", "분석기", "현미경", "센서", "측정"));

	private static final List<KeywordGroup> CONSTRUCTION_GROUPS = List.of(
			group("건축", "건축", "신축", "증축", "개축", "리모델링", "대수선"),
			group("토목", "토목", "도로", "포장", "교량", "하천", "상하수도", "배수로"),
			group("전기", "전기", "전력", "수배전", "조명", "태양광", "전기공사"),
			group("정보통신", "정보통신", "통신", "네트워크", "cctv", "방송", "구내통신"),
			group("소방", "소방", "화재", "스프링클러", "소화", "방재"),
			group("기계설비", "기계설비", "설비", "냉난방", "공조", "환기", "배관"),
			group("실내건축", "실내건축", "인테리어", "내장", "천장", "바닥", "칸막이"),
			group("조경", "조경", "수목", "녹지", "공원", "식재"),
			group("학교/교육시설", "학교", "교육청", "교실", "체육관", "급식실"),
			group("관급시설", "청사", "주민센터", "복지관", "도서관", "공공시설"),
			group("유지보수", "보수", "보강", "유지관리", "개선", "정비", "복구"),
			group("철거/해체", "철거", "해체", "폐기물", "석면"));

	private static final Map<String, TrendProfile> PROFILES = profiles();

	private TrendProfiles() {
	}

	/** {@code product} · {@code service} · {@code construction}. 모르는 키는 {@code null}. */
	public static TrendProfile of(String key) {
		return key == null ? null : PROFILES.get(key.toLowerCase(Locale.ROOT));
	}

	public static Map<String, TrendProfile> all() {
		return PROFILES;
	}

	private static Map<String, TrendProfile> profiles() {
		Map<String, TrendProfile> map = new LinkedHashMap<>();
		map.put("product", new TrendProfile("product", "trend-product", "물품", PRODUCT_GROUPS));
		map.put("service", new TrendProfile("service", "trend-service", "용역", SERVICE_GROUPS));
		map.put("construction",
				new TrendProfile("construction", "trend-construction", "공사", CONSTRUCTION_GROUPS));
		return Map.copyOf(map);
	}

	private static KeywordGroup group(String label, String... keywords) {
		return new KeywordGroup(label, List.of(keywords));
	}
}
