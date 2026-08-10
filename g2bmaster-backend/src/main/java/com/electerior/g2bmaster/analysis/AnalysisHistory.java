package com.electerior.g2bmaster.analysis;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * 분석 결과 이력 — 내용 주소화(content-addressed) 캐시.
 *
 * <p>재사용 판정 키는 {@code (자연키, input_hash, prompt_version, analysis_mode, deep_mode)} 이고,
 * V6 의 생성 컬럼 {@code reuse_key_pr/ps/bn} + UNIQUE 가 그것을 강제한다. 여기서 그 컬럼을
 * 매핑하지 않는 이유는 <b>생성 컬럼이라 애플리케이션이 쓸 수 없기 때문</b>이다 — 읽을 일도 없다.
 *
 * <p>{@code result} 는 분석 응답 전문(JSON)이다. 문자열로 들고 있다가 컨트롤러에서 파싱한다 —
 * 스키마를 AI 저장소가 소유하므로 Java 타입으로 굳히면 프롬프트가 바뀔 때마다 백엔드를
 * 다시 배포해야 한다(→ {@code docs/ai-boundary.md}).
 */
@Entity
@Table(name = "analysis_history")
public class AnalysisHistory {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "bid_ntce_no", length = 64)
	private String bidNtceNo;

	@Column(name = "bid_ntce_ord", length = 64)
	private String bidNtceOrd = "";

	@Column(name = "bf_spec_rgst_no", length = 64)
	private String bfSpecRgstNo;

	@Column(name = "prcrmnt_req_no", length = 64)
	private String prcrmntReqNo;

	@Column(name = "title", columnDefinition = "text")
	private String title;

	@Column(name = "instt_nm", columnDefinition = "text")
	private String insttNm;

	@Column(name = "amount")
	private Long amount;

	@Column(name = "source", columnDefinition = "text")
	private String source;

	@Column(name = "spec_name", columnDefinition = "text")
	private String specName;

	@Column(name = "spec_confidence", columnDefinition = "text")
	private String specConfidence;

	@Column(name = "nested_tables", nullable = false)
	private int nestedTables;

	@Column(name = "blocking_excluded", nullable = false)
	private boolean blockingExcluded;

	/** 목록 미리보기용 요약. 원본이 4000자로 자른다({@code analysis-history.js:137}). */
	@Column(name = "summary", columnDefinition = "text")
	private String summary;

	@Column(name = "result", columnDefinition = "json")
	private String result;

	@Column(name = "deep_mode", nullable = false)
	private boolean deepMode;

	@Column(name = "analysis_mode", length = 64)
	private String analysisMode;

	@Column(name = "llm_model", columnDefinition = "text")
	private String llmModel;

	@Column(name = "input_hash", length = 64)
	private String inputHash;

	@Column(name = "prompt_version", length = 64)
	private String promptVersion;

	@Column(name = "created_at", insertable = false, updatable = false)
	private Instant createdAt;

	protected AnalysisHistory() {
	}

	public static AnalysisHistory of(AnalysisIdentity identity) {
		AnalysisHistory history = new AnalysisHistory();
		// 세 자연키 중 하나만 채운다 — 재사용 키 생성 컬럼이 그 전제로 만들어져 있다.
		switch (identity.type()) {
			case AnalysisIdentity.BID_NOTICE -> {
				history.bidNtceNo = identity.id();
				history.bidNtceOrd = identity.ord();
			}
			case AnalysisIdentity.PRE_SPEC -> history.bfSpecRgstNo = identity.id();
			case AnalysisIdentity.PROCUREMENT_REQUEST -> history.prcrmntReqNo = identity.id();
			default -> throw new IllegalArgumentException("알 수 없는 엔티티 타입: " + identity.type());
		}
		return history;
	}

	public Long getId() {
		return id;
	}

	public String getBidNtceNo() {
		return bidNtceNo;
	}

	public String getBidNtceOrd() {
		return bidNtceOrd;
	}

	public String getBfSpecRgstNo() {
		return bfSpecRgstNo;
	}

	public String getPrcrmntReqNo() {
		return prcrmntReqNo;
	}

	public String getTitle() {
		return title;
	}

	public void setTitle(String title) {
		this.title = title;
	}

	public String getInsttNm() {
		return insttNm;
	}

	public void setInsttNm(String insttNm) {
		this.insttNm = insttNm;
	}

	public Long getAmount() {
		return amount;
	}

	public void setAmount(Long amount) {
		this.amount = amount;
	}

	public String getSource() {
		return source;
	}

	public void setSource(String source) {
		this.source = source;
	}

	public String getSpecName() {
		return specName;
	}

	public void setSpecName(String specName) {
		this.specName = specName;
	}

	public String getSpecConfidence() {
		return specConfidence;
	}

	public void setSpecConfidence(String specConfidence) {
		this.specConfidence = specConfidence;
	}

	public int getNestedTables() {
		return nestedTables;
	}

	public void setNestedTables(int nestedTables) {
		this.nestedTables = nestedTables;
	}

	public boolean isBlockingExcluded() {
		return blockingExcluded;
	}

	public void setBlockingExcluded(boolean blockingExcluded) {
		this.blockingExcluded = blockingExcluded;
	}

	public String getSummary() {
		return summary;
	}

	/** 원본과 같이 4000자에서 자른다 — 미리보기용이라 전문은 {@code result} 에 남는다. */
	public void setSummary(String summary) {
		this.summary = summary == null ? null
				: summary.length() > 4000 ? summary.substring(0, 4000) : summary;
	}

	public String getResult() {
		return result;
	}

	public void setResult(String result) {
		this.result = result;
	}

	public boolean isDeepMode() {
		return deepMode;
	}

	public void setDeepMode(boolean deepMode) {
		this.deepMode = deepMode;
	}

	public String getAnalysisMode() {
		return analysisMode;
	}

	public void setAnalysisMode(String analysisMode) {
		this.analysisMode = analysisMode;
	}

	public String getLlmModel() {
		return llmModel;
	}

	public void setLlmModel(String llmModel) {
		this.llmModel = llmModel;
	}

	public String getInputHash() {
		return inputHash;
	}

	public void setInputHash(String inputHash) {
		this.inputHash = inputHash;
	}

	public String getPromptVersion() {
		return promptVersion;
	}

	public void setPromptVersion(String promptVersion) {
		this.promptVersion = promptVersion;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}
}
