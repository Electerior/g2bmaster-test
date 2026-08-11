package com.electerior.g2bmaster.attachment;

import static org.assertj.core.api.Assertions.assertThat;

import com.electerior.g2bmaster.attachment.DocumentTitleClassifier.Title;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DocumentTitleClassifierTest {

	@Test
	@DisplayName("자간이 벌어진 표제도 잡는다 — 한글 문서는 '규 격 서' 로 인쇄되는 일이 흔하다")
	void spacedTitle() {
		Title title = DocumentTitleClassifier.classify("규 격 서\n\n품명 규격 단위 수량");
		assertThat(title).isNotNull();
		assertThat(title.label()).isEqualTo(DocumentTitleClassifier.SPEC);
		assertThat(title.specBearing()).isTrue();
	}

	@Test
	@DisplayName("줄 끝 정박 — '<사업명> + 문서종' 한 줄 표제는 커버리지가 미달해도 통과한다")
	void anchoredAtLineEnd() {
		// 정규화 28자에 별칭 5자 → 커버리지 0.18 로 임계(0.25) 미달. 정박으로 구제된다.
		String line = "2026년 무대기계 제어시스템 브레이크 하중 테스트 용역 지침서";
		Title title = DocumentTitleClassifier.classify(line + "\n\n과업 내용 및 기간");
		assertThat(title).isNotNull();
		assertThat(title.anchored()).isTrue();
		assertThat(title.via()).isEqualTo("title/anchored");
	}

	@Test
	@DisplayName("'용역지침서'는 설명지침이 아니라 과업지시서 — 경계는 표제가 아니라 강제하는 행위다")
	void serviceGuidelineIsStatementOfWork() {
		Title title = DocumentTitleClassifier.classify("용역 지침서\n\n과업 범위와 산출물");
		assertThat(title).isNotNull();
		assertThat(title.label()).isEqualTo(DocumentTitleClassifier.STATEMENT_OF_WORK);
	}

	@Test
	@DisplayName("입찰절차를 안내하는 문서는 설명지침에 남고 규격서 대용이 아니다")
	void bidGuideIsNotSpecBearing() {
		Title title = DocumentTitleClassifier.classify("입찰유의서\n\n입찰 참가 시 유의사항");
		assertThat(title).isNotNull();
		assertThat(title.label()).isEqualTo(DocumentTitleClassifier.GUIDE);
		assertThat(title.specBearing()).isFalse();
	}

	@Test
	@DisplayName("공고문·서약서·제출서식은 규격서 대용이 아니다")
	void nonSpecClasses() {
		assertThat(DocumentTitleClassifier.classify("입찰공고\n\n개찰일시").specBearing()).isFalse();
		assertThat(DocumentTitleClassifier.classify("청렴계약이행서약서\n\n서명").specBearing()).isFalse();
		assertThat(DocumentTitleClassifier.classify("사용인감계\n\n대표자").specBearing()).isFalse();
	}

	@Test
	@DisplayName("규격서 계열 5종은 규격서 대용으로 통과한다")
	void specBearingClasses() {
		assertThat(DocumentTitleClassifier.isSpecBearing(DocumentTitleClassifier.SPEC)).isTrue();
		assertThat(DocumentTitleClassifier.isSpecBearing(DocumentTitleClassifier.PRODUCT_SPEC)).isTrue();
		assertThat(DocumentTitleClassifier.isSpecBearing(DocumentTitleClassifier.METHOD_SPEC)).isTrue();
		assertThat(DocumentTitleClassifier.isSpecBearing(DocumentTitleClassifier.STATEMENT_OF_WORK)).isTrue();
		assertThat(DocumentTitleClassifier.isSpecBearing(DocumentTitleClassifier.RFP)).isTrue();
		assertThat(DocumentTitleClassifier.isSpecBearing(DocumentTitleClassifier.BILL_OF_QUANTITIES)).isFalse();
		assertThat(DocumentTitleClassifier.isSpecBearing(null)).isFalse();
	}

	@Test
	@DisplayName("본문에 별칭이 스치기만 하면 잡지 않는다 — 커버리지 미달 + 미정박")
	void aliasGrazingBodyTextIsIgnored() {
		String line = "본 규격서 사본은 담당자에게 요청하여 별도로 수령할 수 있습니다";
		assertThat(DocumentTitleClassifier.classify(line)).isNull();
	}

	@Test
	@DisplayName("표제 범위를 벗어난 줄은 보지 않는다")
	void onlyScansHead() {
		String padded = "안내\n".repeat(DocumentTitleClassifier.HEAD_LINES + 2) + "규격서\n";
		assertThat(DocumentTitleClassifier.classify(padded)).isNull();
	}

	@Test
	@DisplayName("긴 줄은 표제가 아니다 — 본문 문장으로 본다")
	void longLineIsNotTitle() {
		String long_ = "가".repeat(DocumentTitleClassifier.TITLE_MAX_LEN) + "규격서";
		assertThat(DocumentTitleClassifier.classify(long_)).isNull();
	}

	@Test
	@DisplayName("표제가 없으면 null — 실패가 아니라 정상 출력이다")
	void noTitleReturnsNull() {
		assertThat(DocumentTitleClassifier.classify("품명 수량 단위\n서버 2 대")).isNull();
		assertThat(DocumentTitleClassifier.classify("")).isNull();
		assertThat(DocumentTitleClassifier.classify(null)).isNull();
	}
}
