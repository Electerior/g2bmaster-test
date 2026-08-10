/*
 * markdown 렌더 — 원본 renderMd(app.js:3180) 의 자리.
 *
 * 이 컴포넌트가 다루는 문자열은 **비신뢰 입력**이다: 규격서·확약서 첨부는 외부 호스트에서
 * 받은 파일이고, 그것을 파서와 LLM 이 가공한 결과가 여기로 온다.
 *
 * 원본은 CDN 의 marked + DOMPurify 를 쓰고, 둘 중 하나라도 못 받으면 "이스케이프한 평문"으로
 * 조용히 내려앉았다(mdReady()). 즉 **위생 처리가 네트워크 사정에 달려 있었다.** CDN 이 뜨고
 * DOMPurify 만 실패하는 경우가 없다는 보장이 없으므로 그 설계는 안전하지 않다.
 * → react-markdown + rehype-sanitize 로 번들에 넣는다. 위생 처리는 선택이 아니라 필수다.
 *
 * remark-gfm 이 함께 필요한 이유: HWP/HWPX 파서가 규격서 표를 GFM 파이프 표로 복원한다.
 * 그것이 없으면 표가 통짜 문단으로 뭉개져 규격서를 읽을 수 없다.
 */
import ReactMarkdown from 'react-markdown';
import rehypeSanitize from 'rehype-sanitize';
import remarkGfm from 'remark-gfm';
import './markdown.css';

interface MarkdownProps {
  children: string;
  /** 규격서 본문처럼 긴 것 — 높이를 제한하고 안에서 스크롤시킨다. */
  clamp?: boolean;
  className?: string;
}

export function Markdown({ children, clamp = false, className }: MarkdownProps) {
  const classes = ['md', 'md-scroll', clamp ? 'md-clamp' : '', className ?? '']
    .filter(Boolean)
    .join(' ');
  return (
    <div className={classes}>
      <ReactMarkdown remarkPlugins={[remarkGfm]} rehypePlugins={[rehypeSanitize]}>
        {children}
      </ReactMarkdown>
    </div>
  );
}

/**
 * AI 요약 전용 축약 렌더.
 *
 * 원본은 요약을 markdown 으로 돌리지 않고 `escHtml → \n을 <br> → **굵게**` 만 처리했다
 * (app.js:5358 등 네 곳이 같은 코드를 복사해 갖고 있었다). 서버 프롬프트가 그 두 가지만
 * 쓰도록 돼 있어서다. 여기서는 같은 결과를 markdown 파서로 얻는다 — 규칙을 손으로 흉내 내면
 * 서버가 목록이나 표를 쓰기 시작한 날 화면만 깨진다.
 */
export function SummaryMarkdown({ children }: { children: string }) {
  return (
    <div className="summary-text">
      <Markdown>{children}</Markdown>
    </div>
  );
}
