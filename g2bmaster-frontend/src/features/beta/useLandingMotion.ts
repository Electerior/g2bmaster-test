import { useEffect, useRef, useState } from 'react';
import type { CSSProperties, RefObject } from 'react';

export const prefersReducedMotion = () =>
  typeof window !== 'undefined' &&
  window.matchMedia('(prefers-reduced-motion: reduce)').matches;

/** style={d(120)} 로 스태거 지연을 넣습니다. CSS 변수라 TS 캐스팅이 필요합니다. */
export const d = (ms: number) => ({ '--d': `${ms}ms` }) as CSSProperties;

/**
 * [1] 스크롤 진입 시 blur-in.
 * 원본 HTML과 같은 방식으로, 루트 안의 [data-reveal] 을 한 번에 관찰합니다.
 * 요소가 조건부 렌더링되면 deps 에 그 값을 넣어 재관찰시키세요.
 */
export function useReveal(rootRef: RefObject<HTMLElement | null>, deps: unknown[] = []) {
  useEffect(() => {
    const root = rootRef.current;
    if (!root) return;
    if (prefersReducedMotion()) {
      root.querySelectorAll('[data-reveal],[data-hero]').forEach((el) => el.classList.add('is-in'));
      return;
    }
    const io = new IntersectionObserver(
      (entries) => {
        entries.forEach((e) => {
          if (!e.isIntersecting) return;
          e.target.classList.add('is-in');
          io.unobserve(e.target);
        });
      },
      { threshold: 0.15, rootMargin: '0px 0px -8% 0px' },
    );
    root.querySelectorAll('[data-reveal]').forEach((el) => io.observe(el));
    return () => io.disconnect();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, deps);
}

/** [2] 히어로는 스크롤을 기다리지 않고 마운트 직후 순차 재생합니다. */
export function useHeroIntro(rootRef: RefObject<HTMLElement | null>) {
  useEffect(() => {
    const root = rootRef.current;
    if (!root) return;
    const play = () => root.querySelectorAll('[data-hero]').forEach((el) => el.classList.add('is-in'));
    const raf = requestAnimationFrame(play);
    const timer = window.setTimeout(play, 1500); // 폰트 지연 대비 안전장치
    return () => {
      cancelAnimationFrame(raf);
      window.clearTimeout(timer);
    };
  }, [rootRef]);
}

/**
 * [3] 스크롤 연동 — 히어로가 blur+fade 로 물러나고, 내비 배경과 하단 CTA 가 켜집니다.
 * 리렌더를 유발하지 않도록 히어로는 ref 로 직접 조작하고, 불리언 두 개만 상태로 둡니다.
 */
export function useScrollFx(heroInnerRef: RefObject<HTMLElement | null>) {
  const [navSolid, setNavSolid] = useState(false);
  const [dockShown, setDockShown] = useState(false);

  useEffect(() => {
    const reduced = prefersReducedMotion();
    let ticking = false;
    const onScroll = () => {
      if (ticking) return;
      ticking = true;
      requestAnimationFrame(() => {
        const y = window.scrollY;
        const hero = heroInnerRef.current;
        if (hero && !reduced) {
          const p = Math.min(y / 460, 1);
          hero.style.opacity = String(1 - p);
          hero.style.filter = `blur(${(p * 9).toFixed(1)}px)`;
          hero.style.transform = `translateY(${(p * -34).toFixed(1)}px)`;
        }
        setNavSolid(y > 40);
        setDockShown(y > window.innerHeight * 0.55);
        ticking = false;
      });
    };
    window.addEventListener('scroll', onScroll, { passive: true });
    onScroll();
    return () => window.removeEventListener('scroll', onScroll);
  }, [heroInnerRef]);

  return { navSolid, dockShown };
}

/**
 * [4] 자동 순환 탭.
 * 화면 밖으로 나가면 멈추고, 마우스를 올려도 멈춥니다. 탭을 직접 누르면 그 탭부터 다시 셉니다.
 */
export function useAutoRotate(count: number, duration: number) {
  const [index, setIndex] = useState(0);
  const [paused, setPaused] = useState(false);
  const [visible, setVisible] = useState(false);
  const containerRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    const el = containerRef.current;
    if (!el) return;
    const io = new IntersectionObserver((e) => setVisible(e[0].isIntersecting), { threshold: 0.25 });
    io.observe(el);
    return () => io.disconnect();
  }, []);

  useEffect(() => {
    if (!visible || paused || prefersReducedMotion()) return;
    const t = window.setInterval(() => setIndex((i) => (i + 1) % count), duration);
    return () => window.clearInterval(t);
  }, [visible, paused, count, duration, index]);

  return {
    index,
    select: setIndex,
    running: visible && !paused,
    containerProps: {
      ref: containerRef,
      onMouseEnter: () => setPaused(true),
      onMouseLeave: () => setPaused(false),
    },
  };
}

/** [5] 화면에 들어오면 숫자를 0부터 올립니다. */
export function useCountUp<T extends HTMLElement = HTMLElement>(target: number, decimals: number, suffix: string) {
  const ref = useRef<T>(null);
  const [text, setText] = useState(() => (0).toFixed(decimals) + suffix);

  useEffect(() => {
    const el = ref.current;
    if (!el) return;
    if (prefersReducedMotion()) {
      setText(target.toFixed(decimals) + suffix);
      return;
    }
    const io = new IntersectionObserver(
      (entries) => {
        if (!entries[0].isIntersecting) return;
        io.disconnect();
        const t0 = performance.now();
        const step = (t: number) => {
          const p = Math.min((t - t0) / 1100, 1);
          setText((target * (1 - Math.pow(1 - p, 3))).toFixed(decimals) + suffix);
          if (p < 1) requestAnimationFrame(step);
        };
        requestAnimationFrame(step);
      },
      { threshold: 0.4 },
    );
    io.observe(el);
    return () => io.disconnect();
  }, [target, decimals, suffix]);

  return { ref, text };
}

/** [6] 화면에 들어오면 폭/높이를 0에서 목표치까지 늘립니다. (막대그래프·슬롯 게이지) */
export function useGrow<T extends HTMLElement>(delay = 0) {
  const ref = useRef<T>(null);
  const [grown, setGrown] = useState(false);

  useEffect(() => {
    const el = ref.current;
    if (!el) return;
    if (prefersReducedMotion()) {
      setGrown(true);
      return;
    }
    const io = new IntersectionObserver(
      (entries) => {
        if (!entries[0].isIntersecting) return;
        io.disconnect();
        window.setTimeout(() => setGrown(true), delay);
      },
      { threshold: 0.4 },
    );
    io.observe(el);
    return () => io.disconnect();
  }, [delay]);

  return { ref, grown };
}

/**
 * [8] 잔여 자리 카운트다운. 정원에서 시작해 현재 잔여까지 굴러떨어집니다.
 *
 * useCountUp 과 두 가지가 다릅니다.
 *
 * 하나, 화면 진입을 기다리지 않고 마운트 직후 재생합니다. 이 숫자는 내비에 붙어 있어
 * 첫 화면에 이미 떠 있고, "얼마나 줄었는지"를 보여주는 게 목적이라 방문한 순간에
 * 재생돼야 의미가 있습니다.
 *
 * 둘, 목표가 바뀌면 처음(정원)으로 되돌아가지 않고 지금 보이는 숫자에서 이어 갑니다.
 * 잔여 자리는 대체값으로 먼저 그렸다가 서버 응답이 오면 한 번 바뀝니다 — 그때마다 50 으로
 * 튀어 올랐다 다시 떨어지면 숫자를 잘못 본 것처럼 보입니다.
 */
export function useCountDown(target: number, from: number, duration = 1400) {
  const [value, setValue] = useState(from);
  const shown = useRef(from);
  shown.current = value;

  useEffect(() => {
    if (!Number.isFinite(target)) return;

    const start = shown.current;
    if (prefersReducedMotion() || start === target) {
      setValue(target);
      return;
    }

    let raf = 0;
    const t0 = performance.now();
    const step = (t: number) => {
      const p = Math.min((t - t0) / duration, 1);
      // useCountUp 과 같은 ease-out cubic. 두 숫자가 같은 리듬으로 움직여야 한다.
      setValue(Math.round(start + (target - start) * (1 - Math.pow(1 - p, 3))));
      if (p < 1) raf = requestAnimationFrame(step);
    };
    raf = requestAnimationFrame(step);
    // 시작값은 ref 로 읽는다. 의존성에 넣으면 매 프레임 효과가 다시 돌아 애니메이션이 멈춘다.
    return () => cancelAnimationFrame(raf);
  }, [target, duration]);

  return value;
}

/** [7] 마감까지 남은 시간. 30초마다 갱신합니다. */
export function useCountdown(deadline: string) {
  const [label, setLabel] = useState('—');

  useEffect(() => {
    const end = new Date(deadline).getTime();
    if (Number.isNaN(end)) return;
    const tick = () => {
      const diff = end - Date.now();
      if (diff <= 0) return setLabel('모집 마감');
      const days = Math.floor(diff / 86_400_000);
      const hours = Math.floor((diff % 86_400_000) / 3_600_000);
      const mins = Math.floor((diff % 3_600_000) / 60_000);
      setLabel(`${days}일 ${hours}시간 ${mins}분`);
    };
    tick();
    const t = window.setInterval(tick, 30_000);
    return () => window.clearInterval(t);
  }, [deadline]);

  return label;
}
