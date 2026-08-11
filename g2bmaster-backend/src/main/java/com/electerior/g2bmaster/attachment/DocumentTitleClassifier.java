package com.electerior.g2bmaster.attachment;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * <b>문서 자신이 인쇄한 표제</b>로 조달문서의 종류를 판정한다.
 *
 * <p>{@link SpecFileSelector} 는 파일명 순위와 어휘·표 밀도로 후보를 고른다. 이 클래스는 그
 * 위에 얹는 <b>독립 신호</b>다 — 튜닝된 점수 상수를 건드리지 않는다.
 *
 * <p>표제를 1차 신호로 두는 근거는 외부 측정치다. 213건 블라인드 라벨링 정답셋에서
 * 파일명 시드의 정확도는 <b>0.786</b> 이 천장이었고, 손으로 튜닝한 어휘 밀도 규칙은
 * <b>단독 정확도 0.23</b> 이었다. 표제와 결합했을 때 나오는 높은 정확도는 규칙이 아니라
 * 표제의 기여였다. 그래서 새 문서종·새 기관은 규칙이 아니라 {@link #TITLE} 에
 * <b>별칭을 추가해서</b> 흡수한다.
 *
 * <p>표는 순회 순서가 의미를 갖는다 — {@code 용역지침서} 는 과업지시서 항목이 설명지침보다
 * 먼저 순회돼 선점한다.
 */
public final class DocumentTitleClassifier {

	/** 표제를 찾을 범위(앞에서 몇 줄까지). */
	public static final int HEAD_LINES = 15;
	/** 표제로 인정할 최대 줄 길이(자간 제거 후). 이보다 길면 본문 문장이다. */
	public static final int TITLE_MAX_LEN = 60;
	/** 별칭이 그 줄에서 차지해야 하는 최소 비율. 본문에 별칭이 스쳐 지나가는 오탐을 막는다. */
	public static final double MIN_COVER = 0.25;

	public static final String NOTICE = "공고문";
	public static final String SPEC = "규격서";
	public static final String PRODUCT_SPEC = "사양서";
	public static final String METHOD_SPEC = "시방서";
	public static final String BILL_OF_QUANTITIES = "내역서";
	public static final String STATEMENT_OF_WORK = "과업지시서";
	public static final String RFP = "제안요청서";
	public static final String CONTRACT_TERMS = "계약조건";
	public static final String GUIDE = "설명지침";
	public static final String SAFETY = "안전보건";
	public static final String PLEDGE = "서약서";
	public static final String SUBMISSION_FORM = "제출서식";
	public static final String JUSTIFICATION = "사유서";
	public static final String DRAWING = "도면";

	/** 문서종 하나와 그 표제 별칭들. */
	private record TitleEntry(String label, List<String> aliases) {}

	/**
	 * 표제 별칭표. <b>순서가 판정에 영향을 준다</b>(먼저 걸린 항목이 이긴다).
	 *
	 * <p>{@code 공사/사업/용역 + 설명서} 계열이 설명지침이 아니라 과업지시서에 있는 것은
	 * 실측 결과다 — 정답셋의 해당 문서들은 입찰절차 안내가 전무하고 과업 내용·시공기준·기간
	 * 지시만 담고 있었다. <b>경계 기준은 표제가 아니라 문서가 강제하는 행위</b>이며,
	 * 입찰절차를 안내하는 {@code 입찰설명서}·{@code 입찰유의서} 는 설명지침에 남는다.
	 */
	private static final List<TitleEntry> TITLE = List.of(
			new TitleEntry(BILL_OF_QUANTITIES, List.of("산출내역서", "공내역서", "물품내역서", "일위대가",
					"견적서", "물품명세서", "원가계산서", "내역서")),
			new TitleEntry(RFP, List.of("제안요청서", "제안요청")),
			new TitleEntry(STATEMENT_OF_WORK, List.of("과업지시서", "과업내용서", "과업설명서",
					"과업이행요청서", "과업안내", "과업개요",
					"공사설명서", "사업설명서", "용역설명서", "용역지침서")),
			new TitleEntry(METHOD_SPEC, List.of("공사시방서", "전문시방서", "표준시방서", "특기시방서", "시방서")),
			new TitleEntry(PRODUCT_SPEC, List.of("제품사양서", "사양서")),
			// 일부 기관은 표제 없이 'I 규격 개요' 로 시작한다. 별칭으로 흡수한다.
			new TitleEntry(SPEC, List.of("물품구매규격서", "구매규격서", "물품규격서", "품목규격서",
					"규격평가기준서", "규격개요", "규격서")),
			new TitleEntry(NOTICE, List.of("입찰공고", "취소공고", "재공고", "공고문", "공고서")),
			new TitleEntry(SAFETY, List.of("위험성평가서", "위험성평가", "안전관리계획서", "안전보건수준평가",
					"안전진단", "교육일지", "정보제공확인서", "안전이행계획서", "행동규범")),
			new TitleEntry(PLEDGE, List.of("청렴계약이행서약서", "근로자권리보호", "권리보호이행서약서",
					"이행서약서", "서약서", "확약서", "각서")),
			new TitleEntry(JUSTIFICATION, List.of("수의계약사유서", "긴급발주사유서", "긴급공고사유서", "사유서")),
			// 추출 스키마가 같은 서식류는 한 클래스로 묶는다.
			new TitleEntry(SUBMISSION_FORM, List.of("규격적합확인서", "실적증명서", "확인서", "증명서",
					"사용인감계", "위임장", "입찰참가신청서", "입찰서", "신청서")),
			// 입찰절차를 안내하는 것만 남긴다. 맨 `지침서` 는 위 과업지시서가 `용역지침서` 를 선점한다.
			// `심사기준`·`세부기준` 은 부처 고시(계약이행능력심사 세부기준 등)를 흡수한다 —
			// 실측에서 이런 고시가 표제를 못 찾아 규격서로 통과한 사례가 있었다.
			// `규격평가기준서` 는 위 규격서 항목이 먼저 순회돼 선점하므로 여기 걸리지 않는다.
			new TitleEntry(GUIDE, List.of("입찰유의서", "입찰설명서", "지침서", "방침서",
					"심사기준", "세부기준")),
			new TitleEntry(DRAWING, List.of("도면", "설계도", "평면도")),
			new TitleEntry(CONTRACT_TERMS, List.of("계약일반조건", "계약특수조건", "추가특수조건",
					"특약조건", "계약서", "계약심사")));

	/**
	 * 품목·규격·과업범위를 담고 있어 <b>규격서 대용으로 AI 에 넘길 수 있는</b> 문서종.
	 *
	 * <p>{@code SpecFileSelector.STRONG_SPEC}(파일명 강신호) 집합과 같은 범위로 맞췄다 —
	 * 표제 층이 파일명 층보다 넓게 통과시키면 튜닝된 기존 동작을 조용히 바꾸게 된다.
	 *
	 * <p>{@code 내역서} 는 품명·규격·수량 칸이 있는데도 뺐다. 실제 표본에서 대부분 공사 비용·
	 * 수량 산출 스프레드시트라 기술 규격과 무관했다({@link SpecFileSelector#specFilenameRank} 와 같은 근거).
	 */
	private static final Set<String> SPEC_BEARING =
			Set.of(SPEC, PRODUCT_SPEC, METHOD_SPEC, STATEMENT_OF_WORK, RFP);

	/** 자간이 벌어진 표기(`규 격 서`)를 붙이기 위한 공백 패턴. `\s` 는 NBSP 를 포함하지 않는다. */
	private static final Pattern SPACES = Pattern.compile("[\\s\\u00a0]+");

	/**
	 * 표제 판정 결과.
	 *
	 * @param label   문서종
	 * @param alias   실제로 걸린 별칭(자간 제거형)
	 * @param anchored 별칭이 줄 끝에 정박해 통과했으면 {@code true}, 커버리지로 통과했으면 {@code false}
	 */
	public record Title(String label, String alias, boolean anchored) {

		/** 이 문서종을 규격서 대용으로 AI 에 넘겨도 되는가. */
		public boolean specBearing() {
			return SPEC_BEARING.contains(label);
		}

		public String via() {
			return anchored ? "title/anchored" : "title/cover";
		}
	}

	private DocumentTitleClassifier() {
	}

	/** 문서종이 규격서 대용으로 쓸 수 있는 것인가. {@code null}·미상은 {@code false}. */
	public static boolean isSpecBearing(String label) {
		return label != null && SPEC_BEARING.contains(label);
	}

	/**
	 * 앞 {@value #HEAD_LINES} 줄에서 문서 표제를 찾는다. 못 찾으면 {@code null} — <b>이것은 실패가
	 * 아니라 정상 출력</b>이고, 호출부는 다른 신호로 판단한다.
	 *
	 * <p>별칭은 두 가지 방식으로 통과한다.
	 * <ul>
	 *   <li><b>커버리지</b> — 별칭이 그 줄의 {@value #MIN_COVER} 이상을 차지한다</li>
	 *   <li><b>줄 끝 정박</b> — 별칭이 줄의 마지막 토큰이다. {@code "2026년 … 하중 테스트 용역 지침서"}
	 *       처럼 {@code <사업명> + 문서종} 형태의 한 줄 표제는 국내 공고문에 흔한데 커버리지가
	 *       0.18 까지 떨어져 탈락했다. 임계를 낮추면 본문 아무 줄에나 별칭이 스쳐도 걸리므로
	 *       정박 쪽으로 푼다</li>
	 * </ul>
	 */
	public static Title classify(String text) {
		if (text == null || text.isBlank()) {
			return null;
		}
		// 본문 전체가 2MB 까지 올 수 있다 — 앞 몇 줄만 끊어 본다(limit 은 구분자를 세다 멈춘다).
		String[] lines = text.split("\n", HEAD_LINES + 1);
		int scan = Math.min(lines.length, HEAD_LINES);
		for (int i = 0; i < scan; i++) {
			String line = SPACES.matcher(lines[i]).replaceAll("");
			if (line.isEmpty() || line.length() > TITLE_MAX_LEN) {
				continue;
			}
			for (TitleEntry entry : TITLE) {
				for (String alias : entry.aliases()) {
					int at = line.indexOf(alias);
					if (at < 0) {
						continue;
					}
					boolean anchored = at + alias.length() == line.length();
					if (anchored || (double) alias.length() / line.length() >= MIN_COVER) {
						return new Title(entry.label(), alias, anchored);
					}
				}
			}
		}
		return null;
	}
}
