/*
 * 최상위 오류 경계.
 *
 * 원본은 렌더가 곧 innerHTML 문자열 조립이라 예외가 나면 그 영역만 비었다. React 는
 * 렌더 중 예외가 트리 전체를 언마운트하므로 — 화면이 완전한 백지가 된다 — 경계가 필요하다.
 * 여기서 잡는 것은 "렌더 버그"지 API 오류가 아니다. API 오류는 각 화면이 처리한다.
 */
import { Component, type ErrorInfo, type ReactNode } from 'react';

interface Props {
  children: ReactNode;
}

interface State {
  error: Error | null;
}

export class ErrorBoundary extends Component<Props, State> {
  state: State = { error: null };

  static getDerivedStateFromError(error: Error): State {
    return { error };
  }

  componentDidCatch(error: Error, info: ErrorInfo): void {
    // 사용자에게 스택을 보여 주지는 않지만, 콘솔에는 남겨야 재현 없이도 원인을 좁힐 수 있다.
    console.error('[G2B Masters] 화면 렌더 중 오류', error, info.componentStack);
  }

  private handleReload = () => {
    window.location.reload();
  };

  render(): ReactNode {
    const { error } = this.state;
    if (!error) return this.props.children;

    return (
      <div className="card" style={{ margin: '40px auto', maxWidth: 640 }} role="alert">
        <h2 style={{ fontSize: 18, marginBottom: 8 }}>화면을 표시하지 못했습니다</h2>
        <p className="meta" style={{ marginBottom: 12 }}>
          새로고침해도 같은 문제가 반복되면 관리자에게 알려 주세요.
        </p>
        <p className="meta" style={{ marginBottom: 16, wordBreak: 'break-all' }}>
          {error.message}
        </p>
        <button type="button" className="btn-home" onClick={this.handleReload}>
          새로고침
        </button>
      </div>
    );
  }
}
