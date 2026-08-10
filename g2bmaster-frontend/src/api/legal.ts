/*
 * 확약서 위법 검토 · 콜드메일 초안 · 확약서 개정.
 *
 * 법령 판단은 법령 MCP 가 떠 있을 때만 나온다. 판단이 없는 것은 "문제 없음"이 아니다 —
 * 화면은 status 를 반드시 구분해 보여 줘야 하며, 그래서 status 를 문자열 union 으로 고정한다.
 */
import { useMutation } from '@tanstack/react-query';
import { post } from '@/lib/apiClient';
import type { FileEntry } from './types';

/* ─── 법률 검토 + 콜드메일 ────────────────────────────────────────────────── */

export interface LegalOutreachRequest {
  /** 서버는 최대 5건까지만 본다. */
  matches: Array<Record<string, unknown>>;
  /** '확약서 포함' 태그가 없으면 서버가 400 을 준다. */
  documentTags: string[];
  /** 30000자 컷. */
  documentText: string;
  title?: string;
  insttNm?: string;
  manager?: string;
  email?: string;
}

export type LegalReviewStatus = 'completed' | 'not_needed' | 'unavailable' | 'incomplete';

export type LegalFindingStatus = 'conflict_possible' | 'review_required' | 'no_conflict_found';

export interface LegalOutreachResponse {
  legalReview: {
    status: LegalReviewStatus;
    message?: string;
    disclaimer?: string;
    findings: Array<{
      status: LegalFindingStatus;
      clause: string;
      excerpt: string;
      reason: string;
      revision: string;
      laws: Array<{ title: string; article: string; url: string }>;
    }>;
  };
  draft: { subject: string; body: string };
  recipient: string;
  /** 문서 머리말에서 뽑았는지, 공고 담당자에서 왔는지 — 수신자 신뢰도가 다르다. */
  recipientSource: 'frontmatter' | 'notice';
  frontMatterRecipient: { name: string; email: string; organization: string };
}

export function requestLegalOutreach(body: LegalOutreachRequest): Promise<LegalOutreachResponse> {
  return post<LegalOutreachResponse>('/api/legal-outreach', body);
}

/* ─── 확약서 개정 ─────────────────────────────────────────────────────────── */

/**
 * 완료가 아닌 상태는 HTTP 422 로 온다(태그 없음은 400). 각각이 서로 다른 조치를 부르므로
 * 하나의 '실패'로 뭉뚱그리면 안 된다.
 */
export type PledgeRevisionStatus =
  | 'completed'
  | 'tag_missing'
  | 'document_unavailable'
  | 'gemma4_required'
  | 'llm_unavailable'
  | 'not_pledge'
  | 'law_review_incomplete'
  | 'revision_failed';

export interface PledgeRevisionRequest {
  /** 최대 20건. */
  fileEntries?: FileEntry[];
  documentTags?: string[];
  bidNtceNo?: string;
  bidNtceSqNo?: string;
  title?: string;
}

export interface PledgeRevisionResponse {
  status: PledgeRevisionStatus;
  message?: string;
  title?: string;
  sourceFile?: string;
  outputFilename?: string;
  model?: string;
  verification?: {
    status: string;
    isPledge: boolean;
    confidence: number;
    reason: string;
    model: string;
  };
  verificationLog?: unknown;
  legalReview?: LegalOutreachResponse['legalReview'];
  revisionApplied?: boolean;
  revisedMarkdown?: string;
  /** 업로드 경로(/upload)에는 이 둘이 없다. */
  files?: unknown[];
  failedFiles?: unknown[];
}

export function requestPledgeRevision(
  body: PledgeRevisionRequest,
): Promise<PledgeRevisionResponse> {
  return post<PledgeRevisionResponse>('/api/pledge-revision', body);
}

/** multipart — Content-Type 을 지워 axios 가 boundary 를 붙이게 한다. */
export function uploadPledgeRevision(file: File): Promise<PledgeRevisionResponse> {
  const form = new FormData();
  form.append('file', file);
  return post<PledgeRevisionResponse>('/api/pledge-revision/upload', form, {
    headers: { 'Content-Type': undefined },
  });
}

export function useLegalOutreach() {
  return useMutation({ mutationFn: requestLegalOutreach });
}

export function usePledgeRevision() {
  return useMutation({ mutationFn: requestPledgeRevision });
}

export function useUploadPledgeRevision() {
  return useMutation({ mutationFn: uploadPledgeRevision });
}
