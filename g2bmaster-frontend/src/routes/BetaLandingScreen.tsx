/*
 * 베타 테스터 모집 랜딩 (/beta).
 *
 * 이 화면만 App 셸(활동 바·배너·헤더·검색창·탭) 밖에 있다. router.tsx 에서도 <App/> 의
 * 자식이 아니라 형제로 등록돼 있다 — 로그인 전 방문자용 페이지에 앱 크롬이 얹히면
 * 랜딩이 아니라 앱의 한 탭처럼 보인다.
 *
 * 루트의 .beta-landing 클래스는 장식이 아니라 landing.css 전체의 스코프다. 지우면
 * 랜딩 스타일이 앱 전 화면으로 샌다.
 */
import { useCallback, useRef } from 'react';
import Nav from '@/features/beta/components/Nav';
import Hero from '@/features/beta/components/Hero';
import Decision from '@/features/beta/components/Decision';
import Features from '@/features/beta/components/Features';
import Results from '@/features/beta/components/Results';
import Feedback from '@/features/beta/components/Feedback';
import Benefits from '@/features/beta/components/Benefits';
import Apply from '@/features/beta/components/Apply';
import Footer from '@/features/beta/components/Footer';
import { useBetaStatus } from '@/api/beta';
import { FALLBACK_STATUS } from '@/features/beta/landing.config';
import {
  prefersReducedMotion,
  useCountdown,
  useHeroIntro,
  useReveal,
  useScrollFx,
} from '@/features/beta/useLandingMotion';
import '@/features/beta/landing.css';

export function BetaLandingScreen() {
  const rootRef = useRef<HTMLDivElement>(null);
  const heroInnerRef = useRef<HTMLDivElement>(null);
  const applyRef = useRef<HTMLElement>(null);

  // 서버가 정답이고, 아직 못 받았거나 실패했으면 대체값으로 그린다.
  // 모집 현황이 조금 틀린 것보다 페이지가 안 뜨는 게 더 큰 손해다.
  const { data } = useBetaStatus();
  const status = data ?? FALLBACK_STATUS;

  const countdown = useCountdown(status.deadline);
  const { navSolid, dockShown } = useScrollFx(heroInnerRef);

  // status 가 서버 응답으로 바뀌면 새로 그려진 노드도 다시 관찰합니다.
  useReveal(rootRef, [status.remaining, status.total]);
  useHeroIntro(rootRef);

  const scrollToApply = useCallback(() => {
    const reduced = prefersReducedMotion();
    applyRef.current?.scrollIntoView({ behavior: reduced ? 'auto' : 'smooth', block: 'start' });
    window.setTimeout(
      () => applyRef.current?.querySelector('input')?.focus({ preventScroll: true }),
      reduced ? 0 : 700,
    );
  }, []);

  return (
    <div className="beta-landing" ref={rootRef}>
      <div className="frame">
        <div className="grid-bg" aria-hidden="true" />
        <div className="aura" aria-hidden="true" style={{ left: '-10%', top: '4%', width: 720, height: 720, background: 'radial-gradient(circle,rgba(255,201,60,.16),transparent 62%)', animation: 'auraA 26s ease-in-out infinite alternate' }} />
        <div className="aura" aria-hidden="true" style={{ right: '-14%', top: '36%', width: 800, height: 800, background: 'radial-gradient(circle,rgba(255,138,0,.13),transparent 62%)', animation: 'auraB 32s ease-in-out infinite alternate' }} />
        <div className="aura" aria-hidden="true" style={{ left: '18%', bottom: '-6%', width: 660, height: 660, background: 'radial-gradient(circle,rgba(255,201,60,.1),transparent 62%)', animation: 'auraA 38s ease-in-out infinite alternate-reverse' }} />

        <Nav solid={navSolid} remaining={status.remaining} total={status.total} onApply={scrollToApply} />
        <Hero ref={heroInnerRef} total={status.total} countdown={countdown} onApply={scrollToApply} />
        <Decision onApply={scrollToApply} />
        <Features onApply={scrollToApply} />
        <Results />
        <Feedback />
        <Benefits />
        <Apply ref={applyRef} status={status} countdown={countdown} />
        <Footer />
      </div>

      <div className={`dock${dockShown ? ' show' : ''}`}>
        <span className="dock-t">
          선착순 <b>{status.total}개사</b> · 낙찰 시까지 수수료 0원
        </span>
        <button type="button" className="btn" onClick={scrollToApply}>
          베타 신청하기
        </button>
      </div>
    </div>
  );
}
