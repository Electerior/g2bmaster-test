package com.electerior.g2bmaster.attachment;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import kr.dogfoot.hwpxlib.object.HWPXFile;
import kr.dogfoot.hwpxlib.reader.HWPXReader;
import kr.dogfoot.hwpxlib.tool.textextractor.TextExtractMethod;
import kr.dogfoot.hwpxlib.tool.textextractor.TextExtractor;
import kr.dogfoot.hwpxlib.tool.textextractor.TextMarks;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.stereotype.Component;

/**
 * 규격서 첨부(HWPX·PDF)를 {@link ParsedDocument} 하나의 규격으로 뽑는다.
 *
 * <p>원본 {@code lib/files.js}(pdf-parse)와 {@code lib/hwp.js} 의 이식 자리다. 원본은
 * "버퍼 in / 텍스트 out 의 순수 함수"였고, 여기도 바이트를 받아 텍스트를 낸다 — 상태를 갖지 않는다.
 *
 * <p><b>형식별 파서가 달라도 출력 규격은 하나다.</b> 그래야 {@link SpecFileSelector} 의
 * 규격서 선택도, AI {@code extract/specs} 의 부품 추출도 형식을 몰라도 된다.
 *
 * <p>지원 형식: <b>HWPX · HWP(5.0 바이너리) · PDF · XLSX/XLS</b>. 규격서가 hwpx/pdf 만 오는 게
 * 아니라 <b>엑셀(품목·수량·규격 표)</b>이나 <b>구형 .hwp</b> 로도 자주 온다 — 이 둘을 못 읽으면
 * 규격서를 통째로 건너뛰고 공고명만으로 단가를 추정해 엉뚱한 결과가 난다(실제 사고). HWP 는
 * hwplib 으로, 엑셀은 POI 로 뽑는다. hwplib 의 표 추출이 hwpxlib 과 완벽히 같진 않지만
 * ({@code porting-status.md}), "못 읽음"보다 낫다 — 텍스트만 뽑아 채점·부품추출에 쓴다.
 *
 * <p><b>ZIP 는 텍스트가 아니라 컨테이너다.</b> 나라장터는 규격서를 종종 여러 파일을 묶은
 * zip 으로 올린다(도면·규격서·내역서 한 묶음). {@link #expandArchive}가 zip 을 풀어 안의
 * HWPX/PDF 를 각각 {@link ParsedDocument} 로 내면, 선택 로직({@link SpecFileSelector})이
 * <b>zip 안의 진짜 규격서를 파일명·내용으로 다시 고른다.</b> zip 을 통째로 규격서로 보지 않는다.
 */
@Component
public class DocumentTextExtractor {

	/**
	 * 텍스트 상한. 잘린 텍스트로 단가를 확정하면 안 되므로, 넘으면 자르되 {@code truncated=true}
	 * 로 표시한다. 규격서 본문은 대개 수만 자이고, LLM 컨텍스트 클램프는 상류(AI)가 따로 한다.
	 */
	static final int MAX_CHARS = 2_000_000;

	/**
	 * 파일명으로 형식을 판정해 알맞은 파서로 보낸다.
	 *
	 * @throws UnsupportedDocumentException 확장자로 형식을 알 수 없을 때. 조용히 빈 결과를
	 *         내면 "규격서가 비었다"와 "형식을 모른다"가 구분되지 않는다
	 * @throws DocumentParseException       파일이 깨졌거나 형식과 내용이 다를 때
	 */
	public ParsedDocument extract(String filename, byte[] bytes) {
		String lower = filename == null ? "" : filename.toLowerCase();
		if (lower.endsWith(".hwpx")) {
			return extractHwpx(filename, bytes);
		}
		if (lower.endsWith(".hwp")) {
			return extractHwp(filename, bytes);
		}
		if (lower.endsWith(".pdf")) {
			return extractPdf(filename, bytes);
		}
		if (lower.endsWith(".xlsx") || lower.endsWith(".xls")) {
			return extractXlsx(filename, bytes);
		}
		throw new UnsupportedDocumentException(
				"지원하지 않는 형식입니다(HWPX·HWP·PDF·XLSX 만): " + filename);
	}

	public boolean supports(String filename) {
		String lower = filename == null ? "" : filename.toLowerCase();
		return lower.endsWith(".hwpx") || lower.endsWith(".hwp") || lower.endsWith(".pdf")
				|| lower.endsWith(".xlsx") || lower.endsWith(".xls");
	}

	public boolean isArchive(String filename) {
		return filename != null && filename.toLowerCase().endsWith(".zip");
	}

	// ── ZIP ──────────────────────────────────────────────────────────────────
	/** zip 하나에서 뽑을 최대 문서 수. 규격서 묶음이 이보다 많으면 첨부를 잘못 고른 것에 가깝다. */
	private static final int MAX_ARCHIVE_ENTRIES = 50;
	/** 압축 해제 총량 상한(zip bomb 가드). 개별 엔트리는 여기까지만 읽고 더 크면 건너뛴다. */
	private static final long MAX_ARCHIVE_UNCOMPRESSED = 200L * 1024 * 1024;

	/**
	 * zip 을 풀어 안의 HWPX/PDF 를 각각 텍스트로 뽑는다. 깨진 엔트리 하나가 나머지를 막지 않는다.
	 *
	 * <p>엔트리 이름을 {@link ParsedDocument#filename()} 으로 그대로 쓴다 — 선택 로직이 그 이름으로
	 * "규격서"인지 다시 랭크·채점한다. 중첩 zip 은 한 겹만 따라 들어간다(zip-in-zip 은 드물고,
	 * 무한 재귀와 zip bomb 를 막기 위해 그 이상은 무시한다).
	 *
	 * @return 텍스트를 뽑은 문서들. 지원 형식이 없으면 빈 목록(예외 아님 — 다른 첨부로 넘어간다)
	 */
	public List<ParsedDocument> expandArchive(String filename, byte[] bytes) {
		List<ParsedDocument> docs = new ArrayList<>();
		expandInto(filename, bytes, docs, 1);
		return docs;
	}

	private void expandInto(String archiveName, byte[] bytes, List<ParsedDocument> out, int depth) {
		if (bytes == null || bytes.length == 0) {
			return;
		}
		long budget = MAX_ARCHIVE_UNCOMPRESSED;
		try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
			ZipEntry entry;
			while ((entry = zip.getNextEntry()) != null) {
				if (out.size() >= MAX_ARCHIVE_ENTRIES) {
					break;
				}
				if (entry.isDirectory()) {
					continue;
				}
				String name = innerName(entry.getName());
				boolean nested = depth < 2 && name.toLowerCase().endsWith(".zip");
				if (!supports(name) && !nested) {
					continue;   // HWPX·PDF·(한 겹의)zip 만 관심 대상
				}
				byte[] inner = readEntry(zip, budget);
				if (inner == null) {
					continue;   // 크기 초과 등 — 건너뛴다
				}
				budget -= inner.length;
				if (nested) {
					expandInto(name, inner, out, depth + 1);
					continue;
				}
				try {
					out.add(extract(name, inner));
				}
				catch (RuntimeException e) {
					// 깨진 문서 하나가 묶음 전체를 막지 않는다 — 다음 엔트리로.
				}
			}
		}
		catch (IOException e) {
			throw new DocumentParseException("ZIP 해제 실패: " + archiveName, e);
		}
	}

	/** zip 엔트리 이름에서 경로를 떼고 파일명만. 압축기가 넣은 디렉터리 접두를 무시한다. */
	private static String innerName(String entryName) {
		if (entryName == null) {
			return "";
		}
		String n = entryName.replace('\\', '/');
		int slash = n.lastIndexOf('/');
		return slash >= 0 ? n.substring(slash + 1) : n;
	}

	/** 현재 엔트리를 읽되 남은 예산까지만. 초과하면 {@code null}(zip bomb 가드). */
	private static byte[] readEntry(ZipInputStream zip, long budget) {
		try {
			ByteArrayOutputStream buffer = new ByteArrayOutputStream();
			byte[] chunk = new byte[8192];
			int read;
			long total = 0;
			while ((read = zip.read(chunk)) != -1) {
				total += read;
				if (total > budget) {
					return null;
				}
				buffer.write(chunk, 0, read);
			}
			return buffer.toByteArray();
		}
		catch (IOException e) {
			return null;
		}
	}

	// ── HWPX ─────────────────────────────────────────────────────────────────
	private ParsedDocument extractHwpx(String filename, byte[] bytes) {
		java.io.File temp = writeTemp(bytes, ".hwpx");
		try {
			HWPXFile hwpx = HWPXReader.fromFile(temp);
			// 문단 사이에 줄바꿈, 표 셀 사이에 탭 — specContentScore 가 줄/탭으로 구조를 읽는다.
			TextMarks marks = new TextMarks()
					.paraSeparatorAnd("\n")
					.lineBreakAnd("\n")
					.tabAnd("\t");
			// 컨트롤(표·그림 등) 텍스트도 문단 사이에 끼워 뽑는다 — 규격서의 표 안 값이 핵심이다.
			String text = TextExtractor.extract(hwpx, TextExtractMethod.InsertControlTextBetweenParagraphText, true, marks);
			int sectionCount = hwpx.sectionXMLFileList() == null ? 0 : hwpx.sectionXMLFileList().count();
			return clampInto(filename, ParsedDocument.DocumentFormat.HWPX, text, sectionCount);
		}
		catch (Exception e) {
			throw new DocumentParseException("HWPX 파싱 실패: " + filename, e);
		}
		finally {
			deleteQuietly(temp);
		}
	}

	// ── PDF ──────────────────────────────────────────────────────────────────
	private ParsedDocument extractPdf(String filename, byte[] bytes) {
		try (PDDocument pdf = Loader.loadPDF(bytes)) {
			PDFTextStripper stripper = new PDFTextStripper();
			stripper.setSortByPosition(true);   // 2단 편집·표에서 읽는 순서를 좌→우, 위→아래로
			String text = stripper.getText(pdf);
			return clampInto(filename, ParsedDocument.DocumentFormat.PDF, text, pdf.getNumberOfPages());
		}
		catch (IOException e) {
			throw new DocumentParseException("PDF 파싱 실패: " + filename, e);
		}
	}

	// ── HWP 5.0 (바이너리) ─────────────────────────────────────────────────────
	// hwplib 은 hwpxlib 과 클래스 이름이 겹친다(TextExtractor·TextExtractMethod) — 그래서
	// 여기선 전부 완전수식명으로 쓴다. HWP 내부는 UCS-2 라 hwplib 이 디코딩해 자바 String(유니코드)로
	// 준다 — 한글/UTF-8 은 그대로 보존된다.
	private ParsedDocument extractHwp(String filename, byte[] bytes) {
		try {
			kr.dogfoot.hwplib.object.HWPFile hwp =
					kr.dogfoot.hwplib.reader.HWPReader.fromInputStream(new ByteArrayInputStream(bytes));
			// 컨트롤(표·글상자) 텍스트도 문단 사이에 끼워 뽑는다 — 규격서의 표 안 값이 핵심이다.
			String text = kr.dogfoot.hwplib.tool.textextractor.TextExtractor.extract(
					hwp, kr.dogfoot.hwplib.tool.textextractor.TextExtractMethod.InsertControlTextBetweenParagraphText);
			return clampInto(filename, ParsedDocument.DocumentFormat.HWP, text, sectionCount(hwp));
		}
		catch (Exception e) {   // hwplib 은 checked Exception 을 던진다
			throw new DocumentParseException("HWP 파싱 실패: " + filename, e);
		}
	}

	/** 구역 수(모르면 0). 버전에 따라 접근자가 달라 실패해도 0 으로 넘긴다 — pageCount 는 부가정보다. */
	private static int sectionCount(kr.dogfoot.hwplib.object.HWPFile hwp) {
		try {
			return hwp.getBodyText().getSectionList().size();
		}
		catch (RuntimeException e) {
			return 0;
		}
	}

	// ── XLSX / XLS (엑셀) ──────────────────────────────────────────────────────
	// 규격서가 엑셀 표(품목·수량·규격)로 오는 경우가 흔하다. 셀을 탭, 행을 줄바꿈으로 펴면
	// SpecFileSelector 의 표 밀도(탭 행)와 LLM 부품 추출이 그대로 읽는다. WorkbookFactory 가
	// xlsx(OOXML)·xls(OLE2)를 자동 판별한다.
	private ParsedDocument extractXlsx(String filename, byte[] bytes) {
		try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
			DataFormatter formatter = new DataFormatter();
			StringBuilder sb = new StringBuilder();
			int sheetCount = wb.getNumberOfSheets();
			for (int s = 0; s < sheetCount; s++) {
				Sheet sheet = wb.getSheetAt(s);
				if (sheet == null) {
					continue;
				}
				if (s > 0) {
					sb.append("\n\n");
				}
				for (Row row : sheet) {
					int last = row.getLastCellNum();
					if (last <= 0) {
						continue;
					}
					StringBuilder line = new StringBuilder();
					for (int c = 0; c < last; c++) {
						if (c > 0) {
							line.append('\t');
						}
						Cell cell = row.getCell(c);
						if (cell != null) {
							// 셀 안의 탭/줄바꿈은 공백으로 — 표 구조(탭=열, 줄=행)를 깨지 않게.
							line.append(formatter.formatCellValue(cell).replace('\t', ' ').replace('\n', ' '));
						}
					}
					if (!line.toString().isBlank()) {
						sb.append(line).append('\n');
					}
				}
			}
			return clampInto(filename, ParsedDocument.DocumentFormat.XLSX, sb.toString(), sheetCount);
		}
		catch (IOException | RuntimeException e) {
			throw new DocumentParseException("XLSX 파싱 실패: " + filename, e);
		}
	}

	// ── 공통 ─────────────────────────────────────────────────────────────────
	private ParsedDocument clampInto(String filename, ParsedDocument.DocumentFormat format,
			String rawText, int pageCount) {
		String text = rawText == null ? "" : rawText;
		boolean truncated = text.length() > MAX_CHARS;
		if (truncated) {
			text = text.substring(0, MAX_CHARS);
		}
		// 캐리지리턴 정규화만 한다. 좌표계를 훼손하는 변형은 하지 않는다 — 인용 대조가 깨진다.
		text = text.replace("\r\n", "\n").replace("\r", "\n");
		return new ParsedDocument(filename, format, text, pageCount, truncated);
	}

	private static java.io.File writeTemp(byte[] bytes, String suffix) {
		try {
			java.io.File temp = java.io.File.createTempFile("g2b-spec-", suffix);
			java.nio.file.Files.write(temp.toPath(), bytes);
			return temp;
		}
		catch (IOException e) {
			throw new UncheckedIOException("임시 파일 생성 실패", e);
		}
	}

	private static void deleteQuietly(java.io.File file) {
		if (file != null) {
			//noinspection ResultOfMethodCallIgnored
			file.delete();
		}
	}

	/** 확장자로 형식을 알 수 없음. 4xx 로 다뤄야 한다(재시도해도 결과가 같다). */
	public static class UnsupportedDocumentException extends RuntimeException {
		public UnsupportedDocumentException(String message) {
			super(message);
		}
	}

	/** 파일이 깨졌거나 형식과 내용이 다름. */
	public static class DocumentParseException extends RuntimeException {
		public DocumentParseException(String message, Throwable cause) {
			super(message, cause);
		}
	}
}
