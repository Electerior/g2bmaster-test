/*
 * 서랍 AI 요약 영역의 공통 조각 — 원본 runAnalysis / runPlanAnalysis / runSpecAnalysis /
 * runItemAnalysis 가 각자 복사해 갖고 있던 것들.
 *
 * 네 함수는 문구만 다르고 하는 일이 같았다(출처 라벨 → 요약 본문 → 분석한 파일 목록).
 * 복사본이 넷이라 한쪽만 고쳐지는 일이 실제로 있었다 — 여기 하나로 모은다.
 */
import { Fragment, type ReactNode } from 'react';
import { Collapsible } from '@/components/common/Collapsible';
import { Markdown } from '@/components/markdown/Markdown';
import { Spinner } from '@/components/feedback/Spinner';
import type { AiFallbackFlags } from '@/api';

/** 서버가 준 parsedFiles 항목 — 이름과 추출 길이만 쓴다. */
interface ParsedFileLike {
  name?: string;
  textLength?: number;
}

function asParsedFiles(value: unknown): ParsedFileLike[] {
  return Array.isArray(value) ? (value as ParsedFileLike[]) : [];
}

interface SourceLabelProps {
  children: ReactNode;
}

export function SourceLabel({ children }: SourceLabelProps) {
  return <div className="source-label">{children}</div>;
}

/**
 * AI 없이 만든 요약이라는 표시.
 *
 * 이 플래그들은 **HTTP 200 과 함께** 온다(api/types.ts 주석 참고). 오류로 취급해 본문을
 * 감추면 안 된다 — 메타데이터 기반 요약이라도 사용자에게는 쓸모가 있다. 본문은 그대로 두고
 * "무엇이 빠졌는지"만 덧붙인다.
 */
export function AiFallbackNote({ flags }: { flags: AiFallbackFlags }) {
  if (flags.aiDisabled) {
    return <div className="summary-error">AI 백엔드가 꺼져 있어 기본정보로만 요약했습니다.</div>;
  }
  if (flags.aiTimeout) {
    return <div className="summary-error">AI 응답이 비어 기본정보로만 요약했습니다.</div>;
  }
  if (flags.aiFallback) {
    return (
      <div className="summary-error">
        AI 분석에 실패해 기본정보로만 요약했습니다.
        {flags.aiError ? ` (${flags.aiError})` : ''}
      </div>
    );
  }
  return null;
}

interface ParsedFilesProps {
  parsedFiles: unknown;
}

export function ParsedFiles({ parsedFiles }: ParsedFilesProps) {
  const files = asParsedFiles(parsedFiles);
  if (!files.length) return null;
  return (
    <Collapsible summary="분석한 파일 보기">
      <div className="raw-grid">
        {files.map((file, i) => (
          <Fragment key={`${file.name ?? ''}-${i}`}>
            <div className="raw-key">{file.name ?? '-'}</div>
            <div className="raw-val">{Number(file.textLength ?? 0).toLocaleString()}자 추출</div>
          </Fragment>
        ))}
      </div>
    </Collapsible>
  );
}

interface SummaryStateProps {
  isPending: boolean;
  error: Error | null;
  onRetry: () => void;
  /** 대기 중에 보여줄 문구 — 화면마다 다르다. */
  pendingText: string;
  children: ReactNode;
}

export function SummaryState({
  isPending,
  error,
  onRetry,
  pendingText,
  children,
}: SummaryStateProps) {
  if (isPending) {
    return (
      <div className="summary-text muted">
        <Spinner small /> {pendingText}
      </div>
    );
  }
  if (error) {
    return (
      <>
        <div className="summary-error">{error.message}</div>
        <button type="button" className="btn-ai" onClick={onRetry}>
          다시 시도
        </button>
      </>
    );
  }
  return <>{children}</>;
}

interface SummaryBodyProps {
  summary: string;
}

/** 요약 본문. 서버가 마크다운(굵게·목록·표)을 섞어 보내므로 위생 처리된 렌더를 거친다. */
export function SummaryBody({ summary }: SummaryBodyProps) {
  return (
    <div className="summary-text">
      <Markdown>{summary}</Markdown>
    </div>
  );
}
