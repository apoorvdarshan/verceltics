import React from 'react';
import {AbsoluteFill, Audio, OffthreadVideo, Sequence, interpolate, spring, staticFile, useCurrentFrame} from 'remotion';
import {Provider, markNames} from './shared';

// Android beta launch film. 60 fps, spring-driven camera and type; every screen is the real
// Android recording (sample data) from public/footage-android. All motion derives from the frame.
export const FPS = 60;
export const DURATION = 1860;
const sec = (s: number) => Math.round(s * FPS);
const INK = '#04060b';
const GREEN = '#3ddc84';
const BLUE = '#1689ff';
const VIOLET = '#ae5bff';
const WHITE = '#f4f7fc';
const MUTED = '#8d9bb0';
const JOIN_URL = 'groups.google.com/g/verceltics-testers';
const CODE = 'VERCELTICSBETA';
const clampOpts = {extrapolateLeft: 'clamp', extrapolateRight: 'clamp'} as const;
const map = (v: number, a: number[], b: number[]) => interpolate(v, a, b, clampOpts);

// Critically damped for camera and type; softer spring for objects that should settle.
const glide = (f: number, at: number, stiffness = 70) => spring({frame: f - at, fps: FPS, config: {damping: 200, stiffness, mass: 1}});
const settle = (f: number, at: number) => spring({frame: f - at, fps: FPS, config: {damping: 19, stiffness: 70, mass: 1.1}});

const Grain = () => {
  const f = useCurrentFrame();
  return <AbsoluteFill style={{pointerEvents: 'none', mixBlendMode: 'overlay', opacity: .09}}>
    <svg width="1920" height="1080"><filter id="g"><feTurbulence type="fractalNoise" baseFrequency=".9" numOctaves="2" seed={f % 12} /></filter>
      <rect width="100%" height="100%" filter="url(#g)" /></svg>
  </AbsoluteFill>;
};

const Vignette = () => <AbsoluteFill style={{pointerEvents: 'none', background: 'radial-gradient(ellipse at 50% 45%, transparent 55%, #000000b0 100%)'}} />;

const Glow = ({x, y, size, color, opacity = 1}: {x: number; y: number; size: number; color: string; opacity?: number}) =>
  <div style={{position: 'absolute', left: x - size / 2, top: y - size / 2, width: size, height: size, borderRadius: '50%', opacity,
    background: `radial-gradient(circle, ${color}38 0%, ${color}10 40%, transparent 70%)`}} />;

// Word-by-word mask reveal with a focus pull: each word rises out of its own clip and sharpens.
const Words = ({text, at, size, color = WHITE, weight = 700, stagger = 5, tracking = -.045, align = 'left'}: {
  text: string; at: number; size: number; color?: string; weight?: number; stagger?: number; tracking?: number; align?: 'left' | 'center';
}) => {
  const f = useCurrentFrame();
  return <div style={{display: 'flex', flexWrap: 'wrap', justifyContent: align === 'center' ? 'center' : 'flex-start', gap: `0 ${size * .26}px`}}>
    {text.split(' ').map((w, i) => {
      const p = glide(f, at + i * stagger, 90);
      return <span key={i} style={{display: 'inline-block', overflow: 'hidden', paddingBottom: size * .1, marginBottom: -size * .1}}>
        <span style={{display: 'inline-block', fontSize: size, lineHeight: 1.02, fontWeight: weight, letterSpacing: size * tracking, color,
          transform: `translateY(${(1 - p) * 105}%) rotate(${(1 - p) * 4}deg)`, filter: `blur(${(1 - p) * 10}px)`, opacity: map(p, [0, .25], [0, 1])}}>{w}</span>
      </span>;
    })}
  </div>;
};

const Caption = ({children, at, color = MUTED, size = 26}: {children: React.ReactNode; at: number; color?: string; size?: number}) => {
  const p = glide(useCurrentFrame(), at, 60);
  return <div style={{fontSize: size, color, opacity: p, transform: `translateY(${(1 - p) * 24}px)`, filter: `blur(${(1 - p) * 4}px)`}}>{children}</div>;
};

const Eyebrow = ({children, at, color = GREEN}: {children: React.ReactNode; at: number; color?: string}) => {
  const p = glide(useCurrentFrame(), at, 80);
  return <div style={{display: 'flex', alignItems: 'center', gap: 14, fontSize: 19, letterSpacing: 5, fontWeight: 600, color, opacity: p}}>
    <span style={{height: 2, width: 46 * p, background: color, borderRadius: 2}} />{children}
  </div>;
};

// Generic Android handset: brushed metal edge, glass glare, centered punch-hole, real footage.
const Device = ({clip, start, width, glare = 0}: {clip: string; start: number; width: number; glare?: number}) => {
  const h = width * 2.222;
  return <div style={{width, height: h, borderRadius: width * .125, padding: width * .02, position: 'relative',
    background: 'linear-gradient(140deg,#5d6470 0%,#1a1d23 22%,#0d0f13 55%,#3b414c 100%)',
    boxShadow: `0 ${width * .14}px ${width * .3}px #000000a8, 0 0 0 1px #ffffff14, inset 0 0 0 1px #ffffff1c`}}>
    <div style={{width: '100%', height: '100%', borderRadius: width * .105, padding: width * .012, background: '#020203'}}>
      <div style={{width: '100%', height: '100%', borderRadius: width * .093, overflow: 'hidden', position: 'relative', background: '#000'}}>
        <OffthreadVideo src={staticFile(`footage-android/${clip}.mp4`)} startFrom={sec(start)} muted style={{width: '100%', height: '100%', objectFit: 'cover'}} />
        <div style={{position: 'absolute', top: width * .03, left: '50%', width: width * .042, height: width * .042, marginLeft: -width * .021,
          borderRadius: '50%', background: '#030304', boxShadow: '0 0 0 1.5px #15171c'}} />
        <div style={{position: 'absolute', inset: 0, background: `linear-gradient(115deg, transparent ${18 + glare * 60}%, #ffffff16 ${26 + glare * 60}%, transparent ${36 + glare * 60}%)`}} />
      </div>
    </div>
    <div style={{position: 'absolute', right: -3, top: h * .2, width: 3, height: h * .1, borderRadius: 3, background: '#5d6470'}} />
    <div style={{position: 'absolute', right: -3, top: h * .33, width: 3, height: h * .06, borderRadius: 3, background: '#5d6470'}} />
  </div>;
};

const FootageNote = () => <div style={{position: 'absolute', left: 96, bottom: 46, fontSize: 16, letterSpacing: .8, color: '#5f6e84'}}>Actual Android footage · Demo data</div>;

// Each scene eases in from a soft zoom and exits with a forward push and focus blur, so cuts land on the beat.
const Scene = ({dur, children, bg = INK}: {dur: number; children: React.ReactNode; bg?: string}) => {
  const f = useCurrentFrame();
  const enter = glide(f, 0, 140);
  const exit = map(f, [dur - 16, dur], [0, 1]);
  return <AbsoluteFill style={{background: bg, overflow: 'hidden'}}>
    <AbsoluteFill style={{opacity: enter * (1 - exit), transform: `scale(${1.04 - enter * .04 + exit * .06})`, filter: `blur(${exit * 14 + (1 - enter) * 6}px)`}}>
      {children}
    </AbsoluteFill>
  </AbsoluteFill>;
};

const ColdOpen = () => {
  const f = useCurrentFrame();
  const line = glide(f, 6, 60);
  const letters = 'Android.'.split('');
  const push = map(f, [0, 180], [1, 1.08]);
  return <AbsoluteFill style={{background: '#000', color: WHITE}}>
    <Glow x={960} y={600} size={1400 * line} color={GREEN} opacity={.9 * line} />
    <AbsoluteFill style={{transform: `scale(${push})`, display: 'flex', alignItems: 'center', justifyContent: 'center', flexDirection: 'column'}}>
      <div style={{fontSize: 22, letterSpacing: map(glide(f, 18), [0, 1], [28, 12]), fontWeight: 600, color: MUTED, opacity: glide(f, 18), marginBottom: 26}}>VERCELTICS IS COMING TO</div>
      <div style={{display: 'flex'}}>
        {letters.map((ch, i) => {
          const p = settle(f, 44 + i * 4);
          return <span key={i} style={{display: 'inline-block', overflow: 'hidden', paddingBottom: 30, marginBottom: -30}}>
            <span style={{display: 'inline-block', fontSize: 270, lineHeight: .95, fontWeight: 700, letterSpacing: -14,
              transform: `translateY(${(1 - p) * 110}%)`, opacity: map(p, [0, .2], [0, 1]),
              background: ch === '.' ? 'none' : `linear-gradient(180deg, #ffffff 0%, #c9ffe0 55%, ${GREEN} 100%)`,
              WebkitBackgroundClip: ch === '.' ? undefined : 'text', color: ch === '.' ? GREEN : 'transparent'}}>{ch}</span>
          </span>;
        })}
      </div>
      <div style={{marginTop: 36, height: 3, width: 760, borderRadius: 3, transform: `scaleX(${line})`,
        background: `linear-gradient(90deg, transparent, ${GREEN}, ${BLUE}, transparent)`, boxShadow: `0 0 30px ${GREEN}`}} />
    </AbsoluteFill>
    <AbsoluteFill style={{background: '#fff', opacity: map(f, [168, 174, 180], [0, .18, 0])}} />
  </AbsoluteFill>;
};

const Hero = () => {
  const f = useCurrentFrame();
  const rise = settle(f, 0);
  const cam = map(f, [0, 300], [0, 1]);
  const marks = ['VercelMark', 'CloudflareMark', 'NetlifyMark', 'NameDotComMark', 'GoogleSearchConsoleMark', 'GoogleAnalyticsMark'];
  return <AbsoluteFill style={{color: WHITE}}>
    <Glow x={1220} y={540} size={1300} color={GREEN} opacity={.7} />
    <Glow x={1500} y={260} size={900} color={BLUE} opacity={.6} />
    {marks.map((m, i) => {
      const a = i / marks.length * Math.PI * 2 + f / 220;
      const depth = .55 + (i % 3) * .2;
      const p = glide(f, 30 + i * 6, 50);
      return <div key={m} style={{position: 'absolute', left: 1320 + Math.cos(a) * 400 - 40, top: 520 + Math.sin(a) * 340 - 40,
        opacity: p * .85, transform: `scale(${depth * p})`, filter: `blur(${(1 - depth) * 6}px)`}}><Provider name={m} size={80} /></div>;
    })}
    <div style={{position: 'absolute', left: 1180 - 250, top: 70, perspective: 2200}}>
      <div style={{transform: `translateY(${(1 - rise) * 900}px) rotateX(${(1 - rise) * 55 + 6}deg) rotateY(${-14 + cam * 6}deg) rotateZ(${(1 - rise) * 8}deg) scale(${.98 + cam * .04})`, transformStyle: 'preserve-3d'}}>
        <Device clip="hosting" start={1.2} width={500} glare={map(f, [40, 160], [0, 1])} />
      </div>
    </div>
    <div style={{position: 'absolute', left: 120, top: 330, width: 900}}>
      <Eyebrow at={24}>ANDROID BETA</Eyebrow>
      <div style={{marginTop: 30}}><Words text="Your whole stack." at={34} size={90} /><Words text="Now on Android." at={46} size={90} color={GREEN} /></div>
      <div style={{marginTop: 34}}><Caption at={84}>Hosting, domains and site analytics in one native app.</Caption></div>
    </div>
    <FootageNote />
  </AbsoluteFill>;
};

const Macro = () => {
  const f = useCurrentFrame();
  const zoom = glide(f, 30, 38);
  const scale = 1 + zoom * 1.25;
  return <AbsoluteFill style={{color: WHITE}}>
    <Glow x={700} y={480} size={1500} color={BLUE} opacity={.55} />
    <div style={{position: 'absolute', left: 560 - zoom * 120, top: 60 + zoom * 120, perspective: 2400}}>
      <div style={{transform: `rotateY(${10 - zoom * 10}deg) rotateX(${4 - zoom * 4}deg) scale(${scale})`, transformOrigin: '50% 22%'}}>
        <Device clip="hosting" start={6.4} width={430} glare={map(f, [0, 270], [.2, .9])} />
      </div>
    </div>
    <AbsoluteFill style={{background: `linear-gradient(90deg, transparent 52%, ${INK}e6 68%, ${INK} 100%)`}} />
    <div style={{position: 'absolute', left: 1210, top: 330, width: 620}}>
      <Eyebrow at={40} color={BLUE}>01 · HOSTING</Eyebrow>
      <div style={{marginTop: 28}}><Words text="Every deploy." at={52} size={84} /><Words text="Every visitor." at={62} size={84} color="#6fb4ff" /></div>
      <div style={{marginTop: 30}}><Caption at={100}>Projects, deployments and Vercel Web Analytics.</Caption></div>
    </div>
    <FootageNote />
  </AbsoluteFill>;
};

const Trio = () => {
  const f = useCurrentFrame();
  const orbit = map(f, [0, 300], [9, -9]);
  const phones = [
    {clip: 'registrars', start: 3.7, label: 'Domains', x: -470, color: VIOLET},
    {clip: 'sites', start: 3.7, label: 'Search', x: 0, color: GREEN},
    {clip: 'connect', start: 4.3, label: 'Registrars', x: 470, color: BLUE},
  ];
  return <AbsoluteFill style={{color: WHITE}}>
    <Glow x={960} y={620} size={1700} color={VIOLET} opacity={.45} />
    <div style={{position: 'absolute', top: 70, width: '100%'}}>
      <Words text="Everything that keeps your site alive." at={8} size={64} align="center" stagger={4} />
    </div>
    <div style={{position: 'absolute', left: 960, top: 215, perspective: 2600}}>
      <div style={{transformStyle: 'preserve-3d', transform: `rotateY(${orbit}deg)`}}>
        {phones.map((p, i) => {
          const s = settle(f, 10 + i * 8);
          const side = p.x === 0 ? 0 : Math.sign(p.x);
          return <div key={p.clip} style={{position: 'absolute', left: p.x - 175, top: 0,
            transform: `translateZ(${side === 0 ? 120 : -60}px) rotateY(${-side * 18}deg) translateY(${(1 - s) * 700}px)`}}>
            <Device clip={p.clip} start={p.start} width={350} glare={map(f, [20 + i * 20, 200 + i * 20], [0, 1])} />
            <div style={{position: 'absolute', left: 0, right: 0, top: -54, textAlign: 'center', opacity: glide(f, 60 + i * 10)}}>
              <span style={{fontSize: 22, letterSpacing: 4, fontWeight: 600, color: p.color}}>{p.label.toUpperCase()}</span>
            </div>
          </div>;
        })}
      </div>
    </div>
    <FootageNote />
  </AbsoluteFill>;
};

const Integrations = () => {
  const f = useCurrentFrame();
  const count = Math.round(map(glide(f, 20, 30), [0, 1], [0, 27]));
  const cols = 4;
  return <AbsoluteFill style={{color: WHITE}}>
    <Glow x={1300} y={540} size={1500} color={GREEN} opacity={.5} />
    <div style={{position: 'absolute', left: 120, top: 300}}>
      <div style={{fontSize: 230, fontWeight: 700, letterSpacing: -14, lineHeight: .9, fontVariantNumeric: 'tabular-nums',
        background: `linear-gradient(180deg, #fff, ${GREEN})`, WebkitBackgroundClip: 'text', color: 'transparent'}}>{count}</div>
      <div style={{marginTop: 18}}><Words text="integrations. One app." at={30} size={58} weight={600} /></div>
      <div style={{marginTop: 26}}><Caption at={60}>10 hosting platforms · 8 registrars · 9 site services</Caption></div>
    </div>
    <div style={{position: 'absolute', left: 1010, top: 250}}>
      {markNames.map((name, i) => {
        const r = Math.floor(i / cols); const c = i % cols;
        const s = settle(f, 6 + i * 3);
        const wave = Math.sin((f - i * 6) / 14) * map(f, [120, 160], [0, 1]);
        const from = [(i * 137) % 900 - 450, (i * 251) % 700 - 350];
        return <div key={name} style={{position: 'absolute', left: c * 190, top: r * 190,
          transform: `translate(${(1 - s) * from[0]}px, ${(1 - s) * from[1] - wave * 10}px) scale(${.3 + s * .7}) rotate(${(1 - s) * (i % 2 ? 40 : -40)}deg)`,
          opacity: map(s, [0, .3], [0, 1]), filter: `blur(${(1 - s) * 8}px)`}}>
          <Provider name={name} size={150} />
        </div>;
      })}
    </div>
  </AbsoluteFill>;
};

const Step = ({n, title, detail, at, color = GREEN}: {n: string; title: string; detail: React.ReactNode; at: number; color?: string}) => {
  const p = glide(useCurrentFrame(), at, 70);
  return <div style={{display: 'flex', gap: 26, alignItems: 'center', padding: '22px 30px', borderRadius: 24, width: 820,
    background: 'linear-gradient(135deg, #ffffff0d, #ffffff04)', border: '1px solid #ffffff1a', boxShadow: '0 20px 50px #00000040',
    opacity: p, transform: `translateX(${(1 - p) * -60}px)`, filter: `blur(${(1 - p) * 6}px)`}}>
    <div style={{width: 56, height: 56, flexShrink: 0, borderRadius: 18, background: `${color}22`, border: `1px solid ${color}66`, color,
      display: 'flex', alignItems: 'center', justifyContent: 'center', fontSize: 26, fontWeight: 700}}>{n}</div>
    <div><div style={{fontSize: 31, fontWeight: 600, letterSpacing: -.6}}>{title}</div><div style={{fontSize: 23, color: '#a9b7cb', marginTop: 6}}>{detail}</div></div>
  </div>;
};

const Join = () => {
  const f = useCurrentFrame();
  const rise = settle(f, 20);
  const typed = Math.round(map(f, [sec(2.2), sec(3.1)], [0, CODE.length]));
  const shimmer = map(f % sec(2), [0, sec(2)], [-30, 130]);
  return <AbsoluteFill style={{color: WHITE}}>
    <Glow x={1450} y={560} size={1300} color={GREEN} opacity={.6} />
    <Glow x={300} y={200} size={900} color={VIOLET} opacity={.35} />
    <div style={{position: 'absolute', left: 120, top: 96}}>
      <Eyebrow at={6}>NOW RECRUITING TESTERS</Eyebrow>
      <div style={{marginTop: 26}}><Words text="Join the" at={14} size={92} /><Words text="Android beta." at={22} size={92} color={GREEN} /></div>
      <div style={{display: 'flex', flexDirection: 'column', gap: 18, marginTop: 40}}>
        <Step n="1" at={sec(1.0)} title="Join the tester group" detail={<span style={{color: WHITE}}>{JOIN_URL}</span>} />
        <Step n="2" at={sec(1.4)} title="Become a tester on Google Play" detail="Install Verceltics and keep it for 14 days" />
        <Step n="3" at={sec(1.8)} color={VIOLET} title="Get a month of Pro free" detail={<span>Redeem <span style={{position: 'relative', display: 'inline-block',
          color: WHITE, fontWeight: 700, letterSpacing: 3, padding: '2px 14px', borderRadius: 10, overflow: 'hidden',
          background: '#ae5bff30', border: '1px solid #ae5bffaa', minWidth: 250}}>{CODE.slice(0, typed)}<span style={{opacity: f % 40 < 20 && typed < CODE.length ? 1 : 0}}>|</span>
          <span style={{position: 'absolute', inset: 0, background: `linear-gradient(100deg, transparent ${shimmer - 20}%, #ffffff40 ${shimmer}%, transparent ${shimmer + 20}%)`}} /></span> on Google Play</span>} />
      </div>
    </div>
    <div style={{position: 'absolute', left: 1265, top: 95, perspective: 2200}}>
      <div style={{transform: `translateY(${(1 - rise) * 800}px) rotateY(${-18 + map(f, [0, 390], [0, 8])}deg) rotateZ(${4 - rise * 4 + Math.sin(f / 70) * 1.2}deg)`}}>
        <Device clip="sites" start={3.6} width={410} glare={map(f, [60, 300], [0, 1])} />
      </div>
    </div>
  </AbsoluteFill>;
};

const EndCard = () => {
  const f = useCurrentFrame();
  const draw = glide(f, 6, 50);
  const paths: [string, string, number][] = [[BLUE, 'M20 34H72C120 34 143 114 214 114H280', 34], [WHITE, 'M20 100H110', 100], [VIOLET, 'M20 169H88C125 169 155 139 176 128', 169]];
  return <AbsoluteFill style={{background: '#000', color: WHITE, display: 'flex', alignItems: 'center', justifyContent: 'center', flexDirection: 'column'}}>
    <Glow x={960} y={470} size={1200} color={GREEN} opacity={.45 * draw} />
    <svg width={260} height={176} viewBox="0 0 300 200" style={{overflow: 'visible'}}>
      {paths.map(([color, d, y], i) => <g key={d}>
        <path d={d} fill="none" stroke={color} strokeWidth="12" strokeLinecap="round" pathLength={1} strokeDasharray={1}
          strokeDashoffset={1 - glide(f, 6 + i * 6, 45)} />
        <circle cx={20} cy={y} r={9} fill={color} opacity={draw} />
      </g>)}
      <circle cx={280} cy={114} r={9} fill={BLUE} opacity={glide(f, 40)} />
    </svg>
    <div style={{marginTop: 34}}><Words text="Verceltics" at={30} size={110} align="center" /></div>
    <div style={{marginTop: 22}}><Caption at={56} size={28} color="#b8c5d8">Android beta · iPhone · iPad</Caption></div>
    <div style={{marginTop: 46, fontSize: 26, fontWeight: 600, color: GREEN, opacity: glide(f, 76)}}>verceltics.com</div>
    <div style={{position: 'absolute', bottom: 54, fontSize: 17, letterSpacing: 3, color: '#5f6e84', opacity: glide(f, 90)}}>FREE AND OPEN SOURCE</div>
    <AbsoluteFill style={{background: '#000', opacity: map(f, [150, 180], [0, 1])}} />
  </AbsoluteFill>;
};

const cuts: [React.FC, number][] = [[ColdOpen, 3], [Hero, 5], [Macro, 4.5], [Trio, 5], [Integrations, 4], [Join, 6.5], [EndCard, 3]];

export const AndroidPromo = () => {
  let at = 0;
  return <AbsoluteFill style={{fontFamily: '"Space Grotesk",sans-serif', background: INK}}>
    <style>{`@font-face{font-family:'Space Grotesk';src:url('${staticFile('space-grotesk.woff2')}') format('woff2');font-weight:300 700;}*{box-sizing:border-box;}`}</style>
    <Audio src={staticFile('launch-soundtrack.wav')} volume={fr => map(fr, [0, 20, DURATION - 90, DURATION], [0, .9, .9, 0])} />
    {cuts.map(([Comp, seconds], i) => {
      const from = at; const dur = sec(seconds); at += dur;
      return <Sequence key={i} from={from} durationInFrames={dur}>
        {i === 0 || i === cuts.length - 1 ? <Comp /> : <Scene dur={dur}><Comp /></Scene>}
      </Sequence>;
    })}
    <Vignette />
    <Grain />
  </AbsoluteFill>;
};

