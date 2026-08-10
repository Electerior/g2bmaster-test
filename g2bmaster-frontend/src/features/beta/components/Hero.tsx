import { forwardRef } from 'react';
import { d } from '@/features/beta/useLandingMotion';
import ProductMock from './ProductMock';

interface Props {
  total: number;
  countdown: string;
  onApply: () => void;
}

/** ref 는 스크롤 연동 fade/blur 대상인 .hero-inner 입니다. */
const Hero = forwardRef<HTMLDivElement, Props>(function Hero({ total, countdown, onApply }, ref) {
  return (
    <header className="hero">
      <div className="hero-inner" ref={ref}>
        <div className="eyebrow" data-hero style={d(0)}>
          <em>Closed Beta</em>
          <s>선착순 {total}개사</s>
        </div>

        <h1 className="hero-h">
          <span className="ln" data-hero style={d(120)}>
            연 200조 입찰 시장,
          </span>
          <span className="ln" data-hero style={d(260)}>
            공고를 찾는 데
          </span>
          <span className="ln hl" data-hero style={d(400)}>
            더 이상 아침을 쓰지 마세요
          </span>
        </h1>

        <p className="hero-sub" data-hero style={d(540)}>
          나라장터 공고를 대신 훑고, 우리 회사에 맞는 것만 골라, 첨부파일까지 요약해 드립니다.
          담당자는 투찰 결정부터 시작하면 됩니다.
        </p>

        <div className="hero-cta" data-hero style={d(660)}>
          <button type="button" className="btn" onClick={onApply}>
            베타 신청하기
          </button>
          <span className="fee0">
            낙찰 시까지 <b>수수료 0원</b>
          </span>
        </div>

        <div className="countdown" data-hero style={d(780)}>
          모집 마감까지 <b>{countdown}</b>
        </div>
      </div>

      <div className="hero-stage">
        <div className="hero-stage-in is-in">
          <ProductMock />
        </div>
      </div>
    </header>
  );
});

export default Hero;
