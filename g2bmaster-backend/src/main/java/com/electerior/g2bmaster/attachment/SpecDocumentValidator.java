package com.electerior.g2bmaster.attachment;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 고른 규격서를 <b>AI 로 넘기기 전에 검증하는 마지막 관문</b>.
 *
 * <p>{@link SpecFileSelector#chooseSpecHybrid} 는 기권하지 않는다 — 후보가 하나라도 있으면
 * 점수가 낮아도 {@code estimated} 를 달아 최고점을 돌려준다. 그 문서가 그대로 부품 추출 →
 * 단가 조회 → 원가 추정으로 흘러가면 <b>엉뚱한 문서에서 나온 확신에 찬 숫자</b>가 만들어진다.
 * 이 클래스가 그 앞을 막는다. 판정 원칙은 가격 계약과 같다 —
 * <b>틀린 단가는 없는 단가보다 나쁘다.</b>
 *
 * <p>검증은 세 층이다.
 * <ol>
 *   <li><b>추출 품질</b> — 우리 파서가 성공했는가. 첨부 자체는 발주기관이 법적 검토를 거쳐
 *       올린 문서이므로 문서를 의심하지 않는다. 의심하는 것은 HWP 바이너리·PDF 텍스트 레이어를
 *       뽑아낸 <b>우리 쪽 추출 결과</b>다</li>
 *   <li><b>문서종</b> — {@link DocumentTitleClassifier} 가 읽은 표제가 규격서 계열인가.
 *       공고문·서약서·제출서식을 규격서로 집어 온 경우가 여기서 걸린다</li>
 *   <li><b>선택 확신</b> — 표제가 없을 때만 본다. 점수만으로 뽑힌 문서는 통과 기준을 올린다</li>
 * </ol>
 *
 * <p>거부는 <b>AI 인계만 막는다.</b> 문서 자체는 응답에 남아 사용자가 직접 확인할 수 있다 —
 * 조용히 사라지면 왜 분석이 안 됐는지 화면에서 알 수 없다.
 */
public final class SpecDocumentValidator {

	/** 이보다 짧으면 판정하지 않는다. 추출이 사실상 실패한 것으로 본다. */
	public static final int MIN_TEXT_CHARS = 200;
	/** 목차 점선을 걷어낸 뒤 남아야 하는 최소 글자 수. */
	public static final int TOC_DUST_MIN_CHARS = 40;
	/** 한글 비율 하한. 미만이면서 라틴 문자도 드물면 인코딩이 무너진 것으로 본다. */
	public static final double HANGUL_FLOOR = 0.05;
	/**
	 * 라틴 문자(영숫자) 비율 하한. <b>한글 비율만 보면 순수 영문 데이터시트가 오탐된다</b> —
	 * 조달 첨부에는 제조사 영문 사양서가 섞여 들어온다. 둘 다 바닥일 때만 붕괴로 본다.
	 */
	public static final double LATIN_FLOOR = 0.20;
	/** 문자 비율을 셀 때 훑는 최대 글자 수. 2MB 전체를 셀 이유가 없다. */
	private static final int RATIO_SAMPLE_CHARS = 20_000;

	public static final String REASON_EMPTY = "extract-empty";
	public static final String REASON_TOO_SHORT = "extract-too-short";
	public static final String REASON_TOC_DUST = "extract-toc-dust";
	public static final String REASON_ENCODING = "extract-encoding-collapsed";
	public static final String REASON_WRONG_CLASS = "class-not-spec";
	public static final String REASON_LOW_SCORE = "score-below-floor";
	public static final String REASON_TRUNCATED = "text-truncated";
	public static final String REASON_MAY_EMBED_SPEC = "class-may-embed-spec";
	public static final String REASON_NO_TITLE = "no-title";
	/** 사람이 이 첨부를 규격서로 직접 지목했다. 문서종 거부를 덮는다. */
	public static final String REASON_USER_SELECTED = "user-selected";

	/**
	 * 규격서는 아니지만 본문에 규격을 품고 있을 수 있는 문서종 — {@link Disposition#FALLBACK} 대상.
	 *
	 * <p>{@code 공고문} 은 실측 근거가 분명하다(거부 시 단가 8건 손실). {@code 내역서} 는 이번
	 * 표본에 없어 측정하지 못했지만 품명·규격·단위·수량 칸을 갖는 구조라 하드 거부는 과하다고
	 * 보고 여기 둔다 — fallback 은 더 나은 후보가 없을 때만 쓰이므로 잘못돼도 대가가 작다.
	 *
	 * <p>나머지(계약조건·설명지침·서약서·제출서식·안전보건·사유서·공문·도면)는 뺐다.
	 * 실측 15건이 단가를 낸 적이 없고, 구조적으로도 부품 규격을 담지 않는다.
	 */
	private static final Set<String> MAY_EMBED_SPEC = Set.of(
			DocumentTitleClassifier.NOTICE,
			DocumentTitleClassifier.BILL_OF_QUANTITIES);

	/**
	 * 문서를 AI 로 넘길지에 대한 세 갈래 판정.
	 *
	 * <p>이분법으로는 실측이 설명되지 않는다. 저장된 딜 분석 115건을 되짚어 보면 문서종에 따라
	 * 거부의 대가가 완전히 다르다.
	 *
	 * <table border="1">
	 *   <caption>거부했을 때의 손실 (실측 115건)</caption>
	 *   <tr><th>문서종</th><th>단가 나옴</th><th>못 나옴</th></tr>
	 *   <tr><td>계약조건·설명지침·서약서·제출서식</td><td>0</td><td>15</td></tr>
	 *   <tr><td>공고문</td><td>8</td><td>35</td></tr>
	 *   <tr><td>표제 없음</td><td>1</td><td>2</td></tr>
	 * </table>
	 *
	 * <p>공고문은 규격서가 아니지만 <b>본문에 규격을 품고 있는 경우가 있다</b>(소액수의계약
	 * 견적제출 안내공고가 대표적이다). 하드 거부하면 실제로 나오던 단가 8건이 사라진다.
	 * 반면 계약조건·서약서 계열은 15건 전부 단가를 낸 적이 없다 — 막아도 잃을 것이 없다.
	 */
	public enum Disposition {
		/** 규격서로 신뢰하고 넘긴다. */
		ACCEPT,
		/**
		 * 규격서는 아니지만 규격을 품고 있을 수 있다. <b>더 나은 후보가 없을 때만</b> 넘기고,
		 * 규격이 실제로 들어 있는지는 LLM 판단에 맡긴다.
		 */
		FALLBACK,
		/** 넘기지 않는다. */
		REJECT
	}

	/**
	 * 인계 판정.
	 *
	 * @param disposition   {@link Disposition}
	 * @param documentClass 표제로 읽은 문서종. 표제를 못 찾았으면 {@code null}
	 * @param via           어느 층이 결론을 냈는지({@code title/anchored}, {@code score/no-title} …)
	 * @param reasons       거부 사유 또는 통과했지만 남겨 둘 경고. 비어 있을 수 있다
	 */
	public record Verdict(Disposition disposition, String documentClass, String via, List<String> reasons) {

		public Verdict {
			reasons = List.copyOf(reasons);
		}

		/** 규격서로 신뢰할 수 있는가. */
		public boolean accepted() {
			return disposition == Disposition.ACCEPT;
		}

		/** 넘길 수는 있는가({@code ACCEPT} 또는 {@code FALLBACK}). */
		public boolean usable() {
			return disposition != Disposition.REJECT;
		}

		private static Verdict of(Disposition d, String via, String documentClass, String... reasons) {
			return new Verdict(d, documentClass, via, List.of(reasons));
		}
	}

	private SpecDocumentValidator() {
	}

	/**
	 * 고른 문서를 검증한다.
	 *
	 * @param doc    선택된 첨부의 추출 결과. {@code null} 이면 거부
	 * @param choice {@link SpecFileSelector#chooseSpecHybrid} 의 판정. 표제가 없을 때만 참조한다
	 */
	public static Verdict validate(ParsedDocument doc, SpecFileSelector.Choice choice) {
		if (doc == null || doc.isEmpty()) {
			return Verdict.of(Disposition.REJECT, "extract", null, REASON_EMPTY);
		}
		String text = doc.text();

		// ── 1층: 추출 품질. 파서가 실패한 텍스트를 LLM 에 태우면 비용만 나가고 결과는 쓰레기다.
		//        여기서 걸린 것은 fallback 대상도 아니다 — 읽을 것이 없는 문서는 LLM 도 못 읽는다.
		if (text.strip().length() < MIN_TEXT_CHARS) {
			return Verdict.of(Disposition.REJECT, "extract", null, REASON_TOO_SHORT);
		}
		if (isTocDust(text)) {
			return Verdict.of(Disposition.REJECT, "extract", null, REASON_TOC_DUST);
		}
		if (isEncodingCollapsed(text)) {
			return Verdict.of(Disposition.REJECT, "extract", null, REASON_ENCODING);
		}

		List<String> warnings = new ArrayList<>();
		if (doc.truncated()) {
			// 상한에 걸려 잘렸다. 뒤쪽 품목표가 통째로 날아갔을 수 있다 — 막지는 않되 남긴다.
			warnings.add(REASON_TRUNCATED);
		}

		// ── 2층: 문서종. 표제가 있으면 그것이 결론이다(파일명·점수보다 강한 신호).
		DocumentTitleClassifier.Title title = DocumentTitleClassifier.classify(text);
		if (title != null) {
			if (title.specBearing()) {
				return new Verdict(Disposition.ACCEPT, title.label(), title.via(), warnings);
			}
			if (MAY_EMBED_SPEC.contains(title.label())) {
				return new Verdict(Disposition.FALLBACK, title.label(), title.via(),
						List.of(REASON_MAY_EMBED_SPEC));
			}
			return new Verdict(Disposition.REJECT, title.label(), title.via(), List.of(REASON_WRONG_CLASS));
		}

		// ── 3층: 표제가 없다. <b>여기서는 ACCEPT 가 나오지 않는다.</b>
		//
		// 표제를 못 읽었다는 것은 문서종을 확인하지 못했다는 뜻이고, 그런 문서에 `specTrusted=true`
		// 를 붙이는 것은 하지 않은 검증을 했다고 말하는 것이다. 실측에서 부처 고시(계약이행능력심사
		// 세부기준)가 이 경로로 "신뢰됨" 판정을 받아 규격서 행세를 했다.
		//
		// 넘기기는 한다 — 점수가 선 문서는 규격을 담고 있을 수 있고, 부품이 없으면 LLM 이
		// matched:false 로 답한다. 바뀌는 것은 차단 여부가 아니라 신뢰 표시뿐이다.
		String confidence = choice == null ? "none" : choice.confidence();
		boolean scored = "confirmed".equals(confidence) || "heuristic".equals(confidence);
		List<String> reasons = new ArrayList<>(warnings);
		reasons.add(scored ? REASON_NO_TITLE : REASON_LOW_SCORE);
		return new Verdict(Disposition.FALLBACK, null, "score/no-title", reasons);
	}

	/**
	 * 검증 대상 하나 — 추출 결과와 그 후보의 선택 확신 등급({@code heuristic}/{@code estimated} …).
	 */
	public record Reviewable(ParsedDocument doc, String confidence) {}

	/**
	 * 순위를 걸어 내려간 결과.
	 *
	 * @param index    고른 후보의 {@code ranked} 내 위치. 아무것도 못 골랐으면 {@code -1}
	 * @param verdict  고른 후보의 판정. 못 골랐으면 {@code null}
	 * @param examined 실제로 검증한 후보 수
	 */
	public record Walk(int index, Verdict verdict, int examined) {

		public boolean found() {
			return index >= 0;
		}
	}

	/**
	 * 순위대로 걸어 내려가며 넘길 문서를 고른다. <b>규격서 파악의 fallback 사슬 전체</b>가 여기 있다.
	 *
	 * <ol>
	 *   <li>{@link Disposition#ACCEPT} 를 만나면 <b>즉시 멈춘다</b> — 규격서를 찾았다</li>
	 *   <li>끝까지 없으면 가장 순위가 높은 {@link Disposition#FALLBACK} 을 쓴다 —
	 *       규격서는 못 찾았지만 규격을 품고 있을 수 있는 문서이고, 실제로 들어 있는지는
	 *       LLM 판단에 맡긴다</li>
	 *   <li>그것도 없으면 {@code index=-1} — 넘길 것이 없다. 호출부는 공고 메타로만 진행한다</li>
	 * </ol>
	 *
	 * <p>최고점 하나만 보면 안 되는 이유는 실측에 있다 — 최고점이 공고문·계약일반조건인 경우가
	 * 흔하고, 그때 진짜 규격서는 두세 번째 후보에 있다.
	 */
	public static Walk walk(List<Reviewable> ranked) {
		return walk(ranked, false);
	}

	/**
	 * {@code userSelected} 면 <b>문서종 때문에 거부하지 않는다.</b>
	 *
	 * <p>사람이 첨부를 직접 지목했다는 것은 공고를 읽고 판단했다는 뜻이다. 표제 분류는
	 * 휴리스틱이고, 사람의 지목이 그보다 강한 신호다 — 그래서 {@code class-not-spec} 을
	 * {@link Disposition#FALLBACK} 으로 낮춘다(넘기되 신뢰 표시는 하지 않는다).
	 *
	 * <p>다만 <b>추출 품질 거부는 뒤집지 않는다.</b> 본문을 못 뽑은 파일은 사람이 골랐어도
	 * LLM 이 읽을 것이 없다 — 이건 판단의 문제가 아니라 파일의 문제다.
	 */
	public static Walk walk(List<Reviewable> ranked, boolean userSelected) {
		if (ranked == null || ranked.isEmpty()) {
			return new Walk(-1, null, 0);
		}
		int fallbackIndex = -1;
		Verdict fallbackVerdict = null;
		int examined = 0;

		for (int i = 0; i < ranked.size(); i++) {
			Reviewable item = ranked.get(i);
			examined++;
			Verdict v = validate(item.doc(), new SpecFileSelector.Choice(null, item.confidence()));
			if (userSelected && v.disposition() == Disposition.REJECT
					&& v.reasons().contains(REASON_WRONG_CLASS)) {
				List<String> reasons = new ArrayList<>(v.reasons());
				reasons.add(REASON_USER_SELECTED);
				v = new Verdict(Disposition.FALLBACK, v.documentClass(), v.via(), reasons);
			}
			if (v.accepted()) {
				return new Walk(i, v, examined);
			}
			if (fallbackIndex < 0 && v.usable()) {
				fallbackIndex = i;
				fallbackVerdict = v;
			}
		}
		return new Walk(fallbackIndex, fallbackVerdict, examined);
	}

	/**
	 * 목차 점선을 걷어내면 남는 글자가 거의 없는가. 목차만 뽑히고 본문 추출이 실패한 경우다.
	 *
	 * <p>{@value #TOC_DUST_MIN_CHARS} 자를 채우는 즉시 빠져나오므로 긴 문서에서도 값싸다.
	 */
	static boolean isTocDust(String text) {
		int kept = 0;
		for (int i = 0; i < text.length(); i++) {
			char c = text.charAt(i);
			if (c == '·' || c == '.' || c == '‧' || c == '…' || Character.isWhitespace(c)) {
				continue;
			}
			if (++kept >= TOC_DUST_MIN_CHARS) {
				return false;
			}
		}
		return true;
	}

	/**
	 * 한글도 영숫자도 거의 없으면 추출 인코딩이 무너진 것으로 본다.
	 *
	 * <p>한글 비율만 보던 원 규칙을 라틴 비율과 함께 보도록 고쳤다 — 그대로 두면 제조사 영문
	 * 데이터시트가 전부 붕괴로 판정된다.
	 */
	static boolean isEncodingCollapsed(String text) {
		int hangul = 0;
		int latin = 0;
		int total = 0;
		for (int i = 0; i < text.length() && total < RATIO_SAMPLE_CHARS; i++) {
			char c = text.charAt(i);
			if (Character.isWhitespace(c)) {
				continue;
			}
			total++;
			if (c >= '가' && c <= '힣') {
				hangul++;
			}
			else if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) {
				latin++;
			}
		}
		if (total < 50) {
			return false;   // 표본이 너무 적어 비율을 믿을 수 없다
		}
		return (double) hangul / total < HANGUL_FLOOR && (double) latin / total < LATIN_FLOOR;
	}
}
