/*
 * 연락처 알약. 링크형(a)과 복사형(button) 두 가지가 같은 모양을 쓴다.
 *
 * 복사형은 누르면 라벨이 1600ms 동안 '…복사됨' 으로 바뀐다 — 원본 handleCopyContact
 * (app.js:5502)의 동작 그대로. 토스트를 띄우지 않는 이유는 배너가 화면 맨 위라
 * 버튼 자신이 가장 눈에 잘 띄는 피드백 자리이기 때문.
 */
import { useCallback, useEffect, useRef, useState } from 'react';
import './layout.css';

export type ContactPillVariant =
  | 'default'
  | 'kakao'
  | 'download'
  | 'home'
  | 'service'
  | 'header';

const VARIANT_CLASS: Record<ContactPillVariant, string> = {
  default: '',
  kakao: 'contact-pill--kakao',
  download: 'contact-pill--download',
  home: 'contact-pill--home',
  service: 'contact-pill--service',
  // 파란 통합 헤더 위에 얹히는 반투명 흰 칩.
  header: 'contact-pill--header',
};

const COPIED_MS = 1600;

function classFor(variant: ContactPillVariant): string {
  const extra = VARIANT_CLASS[variant];
  return extra ? `contact-pill ${extra}` : 'contact-pill';
}

/** 클립보드 API 는 보안 컨텍스트에서만 산다 — 아니면 옛 execCommand 경로로 내려간다. */
async function copyTextValue(text: string): Promise<void> {
  if (navigator.clipboard && window.isSecureContext) {
    await navigator.clipboard.writeText(text);
    return;
  }
  const ta = document.createElement('textarea');
  ta.value = text;
  ta.setAttribute('readonly', '');
  ta.style.position = 'fixed';
  ta.style.left = '-9999px';
  document.body.appendChild(ta);
  ta.select();
  document.execCommand('copy');
  document.body.removeChild(ta);
}

interface LinkPillProps {
  variant?: ContactPillVariant;
  href: string;
  children: React.ReactNode;
  /** 외부 사이트면 새 탭 + noopener. */
  external?: boolean;
  download?: boolean;
  'aria-label'?: string;
}

export function ContactLinkPill({
  variant = 'default',
  href,
  children,
  external = false,
  download = false,
  'aria-label': ariaLabel,
}: LinkPillProps) {
  return (
    <a
      className={classFor(variant)}
      href={href}
      aria-label={ariaLabel}
      {...(external ? { target: '_blank', rel: 'noopener noreferrer' } : {})}
      {...(download ? { download: '' } : {})}
    >
      {children}
    </a>
  );
}

interface CopyPillProps {
  variant?: ContactPillVariant;
  /** 클립보드에 넣을 값. */
  value: string;
  /** '이메일 복사됨' 의 앞부분. 원본 data-copy-label 과 같다. */
  copyLabel?: string;
  children: React.ReactNode;
}

export function ContactCopyPill({
  variant = 'default',
  value,
  copyLabel = '연락처',
  children,
}: CopyPillProps) {
  const [copied, setCopied] = useState(false);
  const timerRef = useRef<number | null>(null);

  // 복사 직후 다른 화면으로 넘어가면 타이머가 사라진 DOM 을 건드린다.
  useEffect(
    () => () => {
      if (timerRef.current !== null) window.clearTimeout(timerRef.current);
    },
    [],
  );

  const handleClick = useCallback(() => {
    if (!value) return;
    void copyTextValue(value)
      .then(() => setCopied(true))
      // 복사에 실패해도 값은 보여 준다 — 사용자가 직접 긁어 갈 수 있어야 한다.
      .catch(() => setCopied(false))
      .finally(() => {
        if (timerRef.current !== null) window.clearTimeout(timerRef.current);
        timerRef.current = window.setTimeout(() => setCopied(false), COPIED_MS);
      });
  }, [value]);

  return (
    <button type="button" className={classFor(variant)} onClick={handleClick}>
      {copied ? `${copyLabel} 복사됨` : children}
    </button>
  );
}
