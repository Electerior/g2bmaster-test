package com.electerior.g2bmaster.attachment;

/**
 * 첨부(규격서) 한 건을 텍스트로 뽑은 결과. <b>파서가 다르더라도 이 규격은 같다.</b>
 *
 * <p>HWPX 든 PDF 든 호출부({@link SpecFileSelector}, deal-analysis 오케스트레이터, AI
 * {@code extract/specs})는 이 한 가지 모양만 본다. 파일 형식마다 다른 타입을 내면 규격서
 * 선택 휴리스틱과 부품 추출을 형식마다 다시 짜야 한다.
 *
 * <p>{@code text} 는 마크다운에 준하는 평문이다 — 원본 {@code lib/files.js} 가 pdf/hwp 를
 * 뽑아 {@code SpecFileSelector.specContentScore} 가 읽던 그 문자열과 같은 자리다. 표는
 * 줄바꿈·탭으로 편다(완벽한 표 복원은 목표가 아니다 — 점수 매김과 LLM 추출에 쓰기 위한 것).
 *
 * @param filename  원본 파일명(확장자 포함). 형식 판정과 로깅에 쓴다
 * @param format    {@link DocumentFormat}. 어떤 파서가 뽑았는지
 * @param text      추출된 평문/마크다운
 * @param pageCount 페이지/구역 수. 모르면 0
 * @param truncated {@code true} 면 상한(문자 수)에 걸려 잘렸다. 잘린 텍스트로 단가를 확정하면
 *                  안 되므로 상류가 이 플래그를 봐야 한다
 */
public record ParsedDocument(
		String filename,
		DocumentFormat format,
		String text,
		int pageCount,
		boolean truncated) {

	public enum DocumentFormat {
		HWPX,
		HWP,
		PDF,
		XLSX
	}

	public boolean isEmpty() {
		return text == null || text.isBlank();
	}

	public int length() {
		return text == null ? 0 : text.length();
	}
}
