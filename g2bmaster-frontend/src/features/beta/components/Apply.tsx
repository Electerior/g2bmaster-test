import { forwardRef, useState } from 'react';
import { useBetaSignup } from '@/api/beta';
import { ApiError } from '@/lib/apiClient';
import { d, useCountUp, useGrow } from '@/features/beta/useLandingMotion';
import { INDUSTRY_SUGGESTIONS } from '@/features/beta/landing.config';
import type { BetaStatus, SignupState } from '@/api/beta';

interface Props {
  status: BetaStatus;
  countdown: string;
}

const EMPTY = { name: '', organization: '', industry: '', email: '', phone: '', website: '' };

const Apply = forwardRef<HTMLElement, Props>(function Apply({ status, countdown }, ref) {
  const [form, setForm] = useState(EMPTY);
  const [agreed, setAgreed] = useState(false);
  const [state, setState] = useState<SignupState>({ status: 'idle' });
  // 접수 성공 시 현황 쿼리를 무효화해 잔여 자리를 다시 읽는다 — useBetaSignup 참고.
  const signup = useBetaSignup();

  const filled = status.total - status.remaining;
  const { ref: numRef, text: remainingText } = useCountUp<HTMLSpanElement>(status.remaining, 0, '');
  const { ref: barRef, grown } = useGrow<HTMLDivElement>(0);

  const set = (k: keyof typeof EMPTY) => (e: React.ChangeEvent<HTMLInputElement>) =>
    setForm((f) => ({ ...f, [k]: e.target.value }));

  /**
   * 낙관적 반영 — 네트워크를 기다리지 않고 성공 카드를 먼저 띄운다.
   *
   * 접수처가 구글 Apps Script 라 왕복이 아무리 빨라도 2초, 나쁠 때 10~20초다. 그 시간을
   * 사용자에게 떠넘기면 다 채워 놓고 창을 닫는다. 그래서 브라우저 검증만 통과하면 즉시
   * 성공으로 넘기고, 실제 전송은 뒤에서 돌린다. 화면 반응은 렌더 한 프레임이다.
   *
   * 실패하면 되돌린다. 성공 카드(.ok)는 폼 카드를 덮는 오버레이라 되돌리면 입력값이 그대로
   * 다시 보이고, 잔여 자리도 useBetaSignup 이 캐시를 원래대로 돌려놓는다.
   *
   * 감수하는 것: 3번 다 실패하는 0.3% 미만의 경우에 "접수됨"을 봤다가 오류로 바뀐다.
   * 그 반대(모두가 2~20초를 기다리는 것)가 훨씬 비싸다고 판단했다. 중복 이메일도 뒤늦게
   * 바뀌지만, 그때 뜨는 문구가 "이미 신청되었으니 안내를 기다리라"는 것이라 결과는 같다.
   */
  function handleSubmit(e: React.FormEvent<HTMLFormElement>) {
    e.preventDefault();
    if (!e.currentTarget.checkValidity()) {
      e.currentTarget.reportValidity();
      return;
    }

    setState({ status: 'success' });

    signup.mutate(
      { ...form, privacyAgreed: agreed },
      {
        onError: (err) => {
          // 서버의 한국어 오류 문구는 사용자 대상 계약(§1.1)이므로 그대로 씁니다.
          // 이 저장소의 apiClient 는 연결이 끊긴 경우도 ApiError(status 0) 로 넘겨줍니다.
          const message =
            err instanceof ApiError
              ? err.message
              : '접수에 실패했습니다. 잠시 후 다시 시도해 주세요.';
          setState({ status: 'error', message });
        },
      },
    );
  }

  const locked = !status.open;

  return (
    <section className="sec" id="apply" ref={ref} style={{ paddingTop: 'clamp(40px,6vw,80px)' }}>
      <div className="wrap">
        <div className="apply">
          <div className="slots" data-reveal>
            <span className="kicker">Apply</span>
            <h2 style={{ marginTop: 6 }}>{status.total}개사만 받습니다</h2>
            <p className="lead">
              담당자가 직접 응대하는 구조라 인원을 늘릴 수 없습니다. 신청 순서대로 검토해 개별
              안내드립니다.
            </p>
            <div style={{ marginTop: 14 }}>
              <div className="slots-n">
                <span ref={numRef}>{remainingText}</span>
                <s>/ {status.total}개사 남음</s>
              </div>
              <div className="slots-bar" style={{ marginTop: 14 }} ref={barRef}>
                <i style={{ width: grown ? `${(filled / status.total) * 100}%` : 0 }} />
              </div>
            </div>
            <p className="form-note" style={{ marginTop: 6 }}>
              모집 마감 <span style={{ color: 'var(--gold-soft)', fontFamily: 'var(--mono)' }}>{countdown}</span>
            </p>
          </div>

          <form className="card form-card" onSubmit={handleSubmit} noValidate data-reveal style={d(120)}>
            <div>
              <h3 style={{ margin: '0 0 5px', fontSize: 20, fontWeight: 800 }}>베타 신청</h3>
              <p className="form-note">모집 결과와 테스트 일정은 이메일로 안내합니다.</p>
            </div>

            <div className="fields">
              <label className="f">
                <span>이름</span>
                <input type="text" value={form.name} onChange={set('name')} placeholder="홍길동" required disabled={locked} />
              </label>
              <label className="f">
                <span>연락처</span>
                <input type="tel" value={form.phone} onChange={set('phone')} placeholder="010-0000-0000" required disabled={locked} />
              </label>
              <label className="f" style={{ gridColumn: '1/-1' }}>
                <span>소속 기관 · 기업</span>
                <input type="text" value={form.organization} onChange={set('organization')} placeholder="○○주식회사" required disabled={locked} />
              </label>
              {/*
                업종은 자유 입력이되 datalist 로 예시를 띄운다. select 로 막으면 목록에 없는
                업종이 전부 '기타' 로 뭉쳐 선정에 쓸 수 없고, select 는 landing.css 가
                input 만 꾸미고 있어 어두운 폼 위에 브라우저 기본 모양으로 튄다.
              */}
              <label className="f" style={{ gridColumn: '1/-1' }}>
                <span>업종</span>
                <input
                  type="text" list="beta-industries"
                  value={form.industry} onChange={set('industry')}
                  placeholder="예: IT장비 납품" required disabled={locked}
                />
              </label>
              <datalist id="beta-industries">
                {INDUSTRY_SUGGESTIONS.map((v) => (
                  <option key={v} value={v} />
                ))}
              </datalist>
              <label className="f" style={{ gridColumn: '1/-1' }}>
                <span>이메일</span>
                <input type="email" value={form.email} onChange={set('email')} placeholder="name@company.co.kr" required disabled={locked} />
              </label>
            </div>

            {/* 봇 유인용. 사람 눈에는 보이지 않고, 값이 차 있으면 서버가 조용히 버립니다. */}
            <input
              type="text" tabIndex={-1} autoComplete="off" aria-hidden="true"
              value={form.website} onChange={set('website')}
              style={{ position: 'absolute', left: '-9999px', width: 1, height: 1, opacity: 0 }}
            />

            <label className="agree">
              <input type="checkbox" checked={agreed} onChange={(e) => setAgreed(e.target.checked)} required disabled={locked} />
              <span>개인정보 수집·이용에 동의합니다.</span>
            </label>

            {state.status === 'error' && (
              <p className="form-note" role="alert" style={{ color: '#FF9B6A' }}>
                {state.message}
              </p>
            )}

            {/*
              전송 중에도 문구를 바꾸지 않는다. 성공 카드가 이미 폼을 덮고 있어 이 버튼은
              보이지 않고, 되돌아왔을 때는 다시 누를 수 있어야 한다.
            */}
            <button type="submit" className="btn" style={{ width: '100%' }} disabled={locked}>
              {locked ? '모집이 마감되었습니다' : '베타 신청하기'}
            </button>

            {state.status === 'success' && (
              <div className="ok show" role="status">
                <svg viewBox="0 0 60 60">
                  <circle cx="30" cy="30" r="27" />
                  <path d="M18 31l8.5 8.5L42 23" />
                </svg>
                <b>신청이 접수되었습니다</b>
                <p>
                  영업일 기준 2일 내에 입력하신 이메일로
                  <br />
                  선정 결과와 테스트 일정을 보내드립니다.
                </p>
              </div>
            )}
          </form>
        </div>
      </div>
    </section>
  );
});

export default Apply;
