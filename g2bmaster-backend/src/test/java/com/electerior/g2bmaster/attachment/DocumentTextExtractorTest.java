package com.electerior.g2bmaster.attachment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import kr.dogfoot.hwpxlib.object.HWPXFile;
import kr.dogfoot.hwpxlib.object.content.section_xml.SectionXMLFile;
import kr.dogfoot.hwpxlib.tool.blankfilemaker.BlankFileMaker;
import kr.dogfoot.hwpxlib.writer.HWPXWriter;
import org.junit.jupiter.api.Test;

/**
 * 규격서 파서 실증. HWPX·PDF 를 <b>같은 규격</b>({@link ParsedDocument})으로 뽑는지 본다.
 *
 * <p>PDF 는 실제 파일(회사소개서)로, HWPX 는 hwpxlib 로 만든 문서를 다시 읽어(왕복) 확인한다.
 * 형식마다 다른 타입이 나오면 규격서 선택·부품 추출을 형식마다 다시 짜야 하므로, 두 경로가
 * 정말 한 가지 모양을 내는지가 이 파이프라인의 첫 전제다.
 */
class DocumentTextExtractorTest {

	private final DocumentTextExtractor extractor = new DocumentTextExtractor();

	@Test
	void HWPX_를_읽어_넣은_텍스트를_그대로_돌려준다() throws Exception {
		byte[] hwpx = makeHwpx(
				"규격서: GPU 서버 구매",
				"NVIDIA H200 141GB NVL 3개, 3년 무상보증",
				"메모리 DDR5 512GB, 전원 이중화");

		ParsedDocument doc = extractor.extract("규격서.hwpx", hwpx);

		assertThat(doc.format()).isEqualTo(ParsedDocument.DocumentFormat.HWPX);
		assertThat(doc.filename()).isEqualTo("규격서.hwpx");
		// 넣은 세 문단의 텍스트가 다 살아 있어야 한다 — 부품 추출이 이 문자열을 읽는다.
		assertThat(doc.text()).contains("NVIDIA H200 141GB NVL");
		assertThat(doc.text()).contains("DDR5 512GB");
		assertThat(doc.text()).contains("GPU 서버 구매");
		// 문단 사이는 줄바꿈으로 갈려야 specContentScore 가 구조를 읽는다.
		assertThat(doc.text()).contains("\n");
		assertThat(doc.truncated()).isFalse();
		assertThat(doc.pageCount()).isGreaterThanOrEqualTo(1);
	}

	@Test
	void PDF_를_읽어_텍스트를_뽑는다() throws Exception {
		byte[] pdf = getClass().getResourceAsStream("/sample-spec.pdf").readAllBytes();

		ParsedDocument doc = extractor.extract("sample-spec.pdf", pdf);

		assertThat(doc.format()).isEqualTo(ParsedDocument.DocumentFormat.PDF);
		assertThat(doc.pageCount()).isGreaterThan(0);
		assertThat(doc.isEmpty()).isFalse();
		assertThat(doc.length()).isGreaterThan(100);
	}

	@Test
	void 두_형식이_같은_규격을_돌려준다() throws Exception {
		ParsedDocument fromHwpx = extractor.extract("a.hwpx", makeHwpx("공통 규격 확인"));
		ParsedDocument fromPdf = extractor.extract("sample-spec.pdf",
				getClass().getResourceAsStream("/sample-spec.pdf").readAllBytes());

		// 같은 record, 같은 접근자. 호출부는 형식을 몰라도 된다.
		for (ParsedDocument doc : new ParsedDocument[] {fromHwpx, fromPdf}) {
			assertThat(doc.text()).isNotNull();
			assertThat(doc.filename()).isNotBlank();
			assertThat(doc.format()).isNotNull();
			assertThat(doc.text()).doesNotContain("\r");   // 캐리지리턴은 정규화됐다
		}
	}

	@Test
	void 지원하지_않는_형식은_조용히_넘기지_않고_거부한다() {
		assertThatThrownBy(() -> extractor.extract("규격서.txt", new byte[] {1, 2, 3}))
				.isInstanceOf(DocumentTextExtractor.UnsupportedDocumentException.class)
				.hasMessageContaining("HWPX·HWP·PDF·XLSX");
	}

	@Test
	void 형식과_내용이_다르면_파싱_실패로_알린다() {
		// .pdf 라는데 내용이 PDF 가 아니다 — 빈 결과가 아니라 실패여야 한다.
		assertThatThrownBy(() -> extractor.extract("가짜.pdf", "이건 PDF 가 아니다".getBytes()))
				.isInstanceOf(DocumentTextExtractor.DocumentParseException.class);
	}

	@Test
	void 지원_형식_판정() {
		assertThat(extractor.supports("규격서.HWPX")).isTrue();
		assertThat(extractor.supports("규격서.hwp")).isTrue();     // 구형 HWP 5.0
		assertThat(extractor.supports("도면.pdf")).isTrue();
		assertThat(extractor.supports("규격서.xlsx")).isTrue();    // 엑셀 규격서
		assertThat(extractor.supports("내역.XLS")).isTrue();
		assertThat(extractor.supports("사진.jpg")).isFalse();
		assertThat(extractor.supports(null)).isFalse();
	}

	@Test
	void XLSX_규격서를_읽어_탭_구분_표로_뽑는다() throws Exception {
		// 규격서가 엑셀로 오는 실제 사례(규격서_….xlsx). 한글·수량·단위가 살아 있어야 한다.
		byte[] xlsx = makeXlsx(
				new String[] {"품명", "규격", "수량", "단위"},
				new String[] {"워크스테이션", "제온 16코어 이상, DDR5 128GB", "2", "대"},
				new String[] {"노트북", "16인치, RAM 32GB", "3", "대"},
				new String[] {"전력 파형 측정용 보드", "오실로스코프 연동", "1", "식"});

		ParsedDocument doc = extractor.extract("규격서_암호 알고리즘 워크스테이션.xlsx", xlsx);

		assertThat(doc.format()).isEqualTo(ParsedDocument.DocumentFormat.XLSX);
		assertThat(doc.isEmpty()).isFalse();
		assertThat(doc.text()).contains("워크스테이션").contains("노트북").contains("전력 파형 측정용 보드");
		assertThat(doc.text()).contains("DDR5 128GB");   // UTF-8 한글+영숫자 보존
		assertThat(doc.text()).contains("\t");            // 셀은 탭으로 — 표 밀도가 읽는다
		// 표 밀도 신호가 실제로 잡히는지(규격서 선택이 이걸 쓴다)
		assertThat(SpecFileSelector.density(doc.text()).tableRowDensity()).isPositive();
	}

	@Test
	void HWP_확장자는_HWP_파서로_보낸다() {
		// 실제 .hwp 바이너리 픽스처는 없지만, .hwp 가 '미지원'이 아니라 HWP 파서로 라우팅되는지
		// (깨진 입력 → UnsupportedDocument 가 아니라 DocumentParseException) 를 확인한다.
		assertThat(extractor.supports("규격서.hwp")).isTrue();
		assertThatThrownBy(() -> extractor.extract("규격서.hwp", "이건 HWP 가 아니다".getBytes()))
				.isInstanceOf(DocumentTextExtractor.DocumentParseException.class);
	}

	// ── 테스트용 xlsx 생성 ─────────────────────────────────────────────────────
	private static byte[] makeXlsx(String[]... rows) throws Exception {
		try (org.apache.poi.xssf.usermodel.XSSFWorkbook wb = new org.apache.poi.xssf.usermodel.XSSFWorkbook();
				ByteArrayOutputStream out = new ByteArrayOutputStream()) {
			org.apache.poi.ss.usermodel.Sheet sheet = wb.createSheet("규격");
			for (int r = 0; r < rows.length; r++) {
				org.apache.poi.ss.usermodel.Row row = sheet.createRow(r);
				for (int c = 0; c < rows[r].length; c++) {
					row.createCell(c).setCellValue(rows[r][c]);
				}
			}
			wb.write(out);
			return out.toByteArray();
		}
	}

	@Test
	void ZIP_판정() {
		assertThat(extractor.isArchive("규격서묶음.ZIP")).isTrue();
		assertThat(extractor.isArchive("규격서.hwpx")).isFalse();
		assertThat(extractor.isArchive(null)).isFalse();
	}

	@Test
	void ZIP_을_풀어_안의_HWPX_를_각각_뽑는다() throws Exception {
		// 나라장터가 규격서를 zip 으로 묶어 올린 경우 — 안의 문서를 각각 후보로 내야 한다.
		byte[] zip = makeZip(
				"규격서.hwpx", makeHwpx("규격서: GPU 서버", "NVIDIA H200 141GB 8개"),
				"안내문.hwpx", makeHwpx("입찰 유의사항 안내"),
				"사진.jpg", new byte[] {1, 2, 3});   // 지원 안 하는 엔트리는 조용히 건너뛴다

		List<ParsedDocument> docs = extractor.expandArchive("붙임.zip", zip);

		// HWPX 두 개만 나오고 jpg 는 빠진다. 이름은 엔트리명 그대로 — 선택 로직이 다시 랭크한다.
		assertThat(docs).hasSize(2);
		assertThat(docs).extracting(ParsedDocument::filename)
				.containsExactlyInAnyOrder("규격서.hwpx", "안내문.hwpx");
		ParsedDocument spec = docs.stream()
				.filter(d -> d.filename().equals("규격서.hwpx")).findFirst().orElseThrow();
		assertThat(spec.text()).contains("NVIDIA H200 141GB");
	}

	@Test
	void ZIP_이_아니면_빈_목록을_돌려주고_파이프라인을_막지_않는다() {
		// zip 서명이 없는 바이트는 엔트리 0개다 — 예외로 분석 전체를 세우지 않고 다음 첨부로 넘긴다.
		assertThat(extractor.expandArchive("가짜.zip", "이건 zip 이 아니다".getBytes())).isEmpty();
		assertThat(extractor.expandArchive("빈.zip", new byte[0])).isEmpty();
	}

	/** 이름/바이트 쌍을 zip 으로 묶는다. */
	private static byte[] makeZip(Object... nameThenBytes) throws Exception {
		try (ByteArrayOutputStream out = new ByteArrayOutputStream();
				ZipOutputStream zip = new ZipOutputStream(out)) {
			for (int i = 0; i < nameThenBytes.length; i += 2) {
				zip.putNextEntry(new ZipEntry((String) nameThenBytes[i]));
				zip.write((byte[]) nameThenBytes[i + 1]);
				zip.closeEntry();
			}
			zip.finish();
			return out.toByteArray();
		}
	}

	// ── 테스트용 hwpx 생성 ─────────────────────────────────────────────────────
	// hwpxlib 로 빈 문서를 만들고 문단마다 텍스트를 넣는다. 실제 규격서 대신 왕복으로
	// "쓴 텍스트가 그대로 다시 읽히는가"를 확인한다.
	private static byte[] makeHwpx(String... paragraphs) throws Exception {
		HWPXFile file = BlankFileMaker.make();
		SectionXMLFile section = file.sectionXMLFileList().get(0);
		for (String line : paragraphs) {
			section.addNewPara()
					.idAnd("0").paraPrIDRefAnd("0").styleIDRefAnd("0")
					.pageBreakAnd(false).columnBreakAnd(false).mergedAnd(false)
					.addNewRun()
					.charPrIDRefAnd("0")
					.addNewT()
					.addText(line);
		}
		try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
			HWPXWriter.toStream(file, out);
			return out.toByteArray();
		}
	}
}
