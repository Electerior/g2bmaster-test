/*
 * 가운데 모달 — 원본 #settings-modal / #collusion-modal 이 공유하던 형태.
 * 서랍과 겹칠 수 있어 z-index 가 한 단 위다(tokens.css --z-modal).
 */
import { useEffect, type ReactNode } from 'react';
import './overlay.css';

interface ModalProps {
  open: boolean;
  onClose: () => void;
  title: ReactNode;
  children: ReactNode;
  /** 담합 매트릭스처럼 넓은 표가 들어가는 모달. */
  wide?: boolean;
  footer?: ReactNode;
}

export function Modal({ open, onClose, title, children, wide = false, footer }: ModalProps) {
  useEffect(() => {
    if (!open) return;
    const onKeyDown = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose();
    };
    document.addEventListener('keydown', onKeyDown);
    return () => document.removeEventListener('keydown', onKeyDown);
  }, [open, onClose]);

  if (!open) return null;

  return (
    <>
      <button
        type="button"
        className="overlay-scrim modal-level"
        aria-label="닫기"
        tabIndex={-1}
        onClick={onClose}
      />
      <div className={wide ? 'modal modal-wide' : 'modal'} role="dialog" aria-modal="true">
        <div className="modal-header">
          <h3>{title}</h3>
          <button type="button" className="drawer-close" onClick={onClose} aria-label="모달 닫기">
            ×
          </button>
        </div>
        <div className="modal-body">{children}</div>
        {footer ? <div className="modal-footer">{footer}</div> : null}
      </div>
    </>
  );
}
