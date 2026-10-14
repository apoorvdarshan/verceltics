import React from 'react';
import {AbsoluteFill, Audio, Img, OffthreadVideo, Sequence, interpolate, spring, staticFile, useCurrentFrame} from 'remotion';
import {Provider} from './shared';

// Vertical (9:16) launch film for Reels, Shorts, TikTok and X. Whole product, Android as the hero.
// 60 fps; every screen is a real recording or screenshot with demo data. All motion derives from the frame.
export const FPS = 60;
const sec = (s: number) => Math.round(s * FPS);
const W = 1080;
const H = 1920;
const INK = '#04060b';
const GREEN = '#3ddc84';
const BLUE = '#1689ff';
const VIOLET = '#ae5bff';
const AMBER = '#ffb547';
const WHITE = '#f4f7fc';
const MUTED = '#8d9bb0';
const clampOpts = {extrapolateLeft: 'clamp', extrapolateRight: 'clamp'} as const;
const map = (v: number, a: number[], b: number[]) => interpolate(v, a, b, clampOpts);

// Critically damped for camera and type; a softer spring for objects that should land.
const glide = (f: number, at: number, stiffness = 70) => spring({frame: f - at, fps: FPS, config: {damping: 200, stiffness, mass: 1}});
const settle = (f: number, at: number) => spring({frame: f - at, fps: FPS, config: {damping: 18, stiffness: 75, mass: 1.1}});

const LOGO_PATHS: [string, string, number][] = [
  [BLUE, 'M20 34H72C120 34 143 114 214 114H280', 34],
  [WHITE, 'M20 100H110', 100],
  [VIOLET, 'M20 169H88C125 169 155 139 176 128', 169],
];

const Grain = () => {
  const f = useCurrentFrame();
  return <AbsoluteFill style={{pointerEvents: 'none', mixBlendMode: 'overlay', opacity: .08}}>
    <svg width={W} height={H}><filter id="pg"><feTurbulence type="fractalNoise" baseFrequency=".9" numOctaves="2" seed={f % 12} /></filter>
      <rect width="100%" height="100%" filter="url(#pg)" /></svg>
  </AbsoluteFill>;
};

const Vignette = () => <AbsoluteFill style={{pointerEvents: 'none', background: 'radial-gradient(ellipse 80% 60% at 50% 45%, transparent 50%, #000000b8 100%)'}} />;

const Glow = ({x, y, size, color, opacity = 1}: {x: number; y: number; size: number; color: string; opacity?: number}) =>
  <div style={{position: 'absolute', left: x - size / 2, top: y - size / 2, width: size, height: size, borderRadius: '50%', opacity,
    background: `radial-gradient(circle, ${color}3a 0%, ${color}12 40%, transparent 70%)`}} />;

// Geometric field: slow counter-rotating orbit rings and a masked dot lattice.
const Field = ({accent = BLUE, second = VIOLET, cy = 980}: {accent?: string; second?: string; cy?: number}) => {
  const f = useCurrentFrame();
  return <AbsoluteFill style={{background: INK, overflow: 'hidden'}}>
    <Glow x={540 + Math.sin(f / 90) * 90} y={cy - 200} size={1700} color={accent} opacity={.6} />
    <Glow x={180 + Math.cos(f / 110) * 60} y={1560} size={1200} color={second} opacity={.35} />
    <AbsoluteFill style={{opacity: .55, backgroundImage: 'radial-gradient(#8ea3c433 1.6px, transparent 1.8px)', backgroundSize: '54px 54px',
      maskImage: `radial-gradient(ellipse 70% 45% at 50% ${cy / H * 100}%, black, transparent 80%)`, transform: `translateY(${-f * .15}px)`}} />
    <svg width={W} height={H} style={{position: 'absolute', inset: 0}}>
      {[330, 500, 690, 900].map((r, i) => <circle key={r} cx={540} cy={cy} r={r} fill="none" stroke={i % 2 ? second : accent}
        strokeOpacity={i % 2 ? .22 : .16} strokeWidth={i % 2 ? 2 : 1.4} strokeDasharray={i % 2 ? '3 16' : '220 40'}
        transform={`rotate(${f * (i % 2 ? .1 : -.07) + i * 30} 540 ${cy})`} />)}
    </svg>
  </AbsoluteFill>;
};

// Word-by-word mask reveal with a focus pull.
const Words = ({text, at, size, color = WHITE, weight = 700, stagger = 5, tracking = -.045, align = 'left'}: {
  text: string; at: number; size: number; color?: string; weight?: number; stagger?: number; tracking?: number; align?: 'left' | 'center';
}) => {
  const f = useCurrentFrame();
  return <div style={{display: 'flex', flexWrap: 'wrap', justifyContent: align === 'center' ? 'center' : 'flex-start', gap: `0 ${size * .24}px`}}>
    {text.split(' ').map((w, i) => {
      const p = glide(f, at + i * stagger, 90);
      return <span key={i} style={{display: 'inline-block', overflow: 'hidden', paddingBottom: size * .12, marginBottom: -size * .12}}>
        <span style={{display: 'inline-block', fontSize: size, lineHeight: 1.02, fontWeight: weight, letterSpacing: size * tracking, color,
          transform: `translateY(${(1 - p) * 105}%) rotate(${(1 - p) * 4}deg)`, filter: `blur(${(1 - p) * 10}px)`, opacity: map(p, [0, .25], [0, 1])}}>{w}</span>
      </span>;
    })}
  </div>;
};

const Caption = ({children, at, color = MUTED, size = 34, align = 'left'}: {children: React.ReactNode; at: number; color?: string; size?: number; align?: 'left' | 'center'}) => {
  const p = glide(useCurrentFrame(), at, 60);
  return <div style={{fontSize: size, lineHeight: 1.35, color, textAlign: align, opacity: p, transform: `translateY(${(1 - p) * 24}px)`, filter: `blur(${(1 - p) * 4}px)`}}>{children}</div>;
};

const Eyebrow = ({children, at, color = GREEN}: {children: React.ReactNode; at: number; color?: string}) => {
  const p = glide(useCurrentFrame(), at, 80);
  return <div style={{display: 'flex', alignItems: 'center', gap: 16, fontSize: 25, letterSpacing: 6, fontWeight: 600, color, opacity: p}}>
    <span style={{height: 3, width: 54 * p, background: color, borderRadius: 3}} />{children}
  </div>;
};

const FootageNote = () => <div style={{position: 'absolute', left: 80, top: 92, fontSize: 21, letterSpacing: 1, color: '#66758c'}}>Actual app footage · Demo data</div>;

const Glare = ({p, radius}: {p: number; radius: number}) => <div style={{position: 'absolute', inset: 0, borderRadius: radius, pointerEvents: 'none',
  background: `linear-gradient(115deg, transparent ${14 + p * 64}%, #ffffff17 ${22 + p * 64}%, transparent ${32 + p * 64}%)`}} />;

// Generic Android handset: brushed metal edge, centered punch-hole, real footage.
const Android = ({clip, start, width, glare = 0}: {clip: string; start: number; width: number; glare?: number}) => {
  const h = width * 2.222;
  return <div style={{width, height: h, borderRadius: width * .125, padding: width * .02, position: 'relative',
    background: 'linear-gradient(140deg,#5d6470 0%,#1a1d23 22%,#0d0f13 55%,#3b414c 100%)',
    boxShadow: `0 ${width * .14}px ${width * .3}px #000000b0, 0 0 0 1px #ffffff14, inset 0 0 0 1px #ffffff1c`}}>
    <div style={{width: '100%', height: '100%', borderRadius: width * .105, padding: width * .012, background: '#020203'}}>
      <div style={{width: '100%', height: '100%', borderRadius: width * .093, overflow: 'hidden', position: 'relative', background: '#000'}}>
        <OffthreadVideo src={staticFile(`footage-android/${clip}.mp4`)} startFrom={sec(start)} muted style={{width: '100%', height: '100%', objectFit: 'cover'}} />
        <div style={{position: 'absolute', top: width * .03, left: '50%', width: width * .042, height: width * .042, marginLeft: -width * .021,
          borderRadius: '50%', background: '#030304', boxShadow: '0 0 0 1.5px #15171c'}} />
        <Glare p={glare} radius={width * .093} />
      </div>
    </div>
    <div style={{position: 'absolute', right: -3, top: h * .2, width: 3, height: h * .1, borderRadius: 3, background: '#5d6470'}} />
    <div style={{position: 'absolute', right: -3, top: h * .33, width: 3, height: h * .06, borderRadius: 3, background: '#5d6470'}} />
  </div>;
};

// iPhone-style handset with titanium edge and Dynamic Island over the real iOS recording.
const IPhone = ({clip, start, width, glare = 0}: {clip: string; start: number; width: number; glare?: number}) => {
  const h = width * 2.17;
  return <div style={{width, height: h, borderRadius: width * .165, padding: width * .022, position: 'relative',
    background: 'linear-gradient(150deg,#9aa0a8 0%,#3a3e45 20%,#1b1d21 55%,#7b818b 100%)',
    boxShadow: `0 ${width * .14}px ${width * .3}px #000000b0, inset 0 0 0 1px #ffffff26`}}>
    <div style={{width: '100%', height: '100%', borderRadius: width * .145, padding: width * .022, background: '#020203'}}>
      <div style={{width: '100%', height: '100%', borderRadius: width * .125, overflow: 'hidden', position: 'relative', background: '#000'}}>
        <OffthreadVideo src={staticFile(`footage/${clip}.mp4`)} startFrom={sec(start)} muted style={{width: '100%', height: '100%', objectFit: 'cover'}} />
        <div style={{position: 'absolute', top: width * .03, left: '50%', width: width * .3, height: width * .085, marginLeft: -width * .15,
          borderRadius: width, background: '#000'}} />
        <Glare p={glare} radius={width * .125} />
      </div>
    </div>
  </div>;
};

const IPad = ({still, width, glare = 0}: {still: string; width: number; glare?: number}) => {
  const pad = width * .032;
  const h = (width - pad * 2) / (1640 / 2360) + pad * 2;
  return <div style={{width, height: h, borderRadius: width * .065, padding: pad, position: 'relative',
    background: 'linear-gradient(150deg,#8d939c 0%,#2e3137 18%,#141518 60%,#666c75 100%)',
    boxShadow: `0 ${width * .1}px ${width * .22}px #000000b0, inset 0 0 0 1px #ffffff22`}}>
    <div style={{width: '100%', height: '100%', borderRadius: width * .038, overflow: 'hidden', position: 'relative', background: '#000'}}>
      <Img src={staticFile(`footage-ipad/${still}.png`)} style={{width: '100%', height: '100%', objectFit: 'cover'}} />
      <Glare p={glare} radius={width * .038} />
    </div>
  </div>;
};

// Floating glass stat card. Values mirror the demo data visible in the recording behind it.
const Chip = ({at, x, y, accent, value, label, delta, from = 1, i = 0}: {
  at: number; x: number; y: number; accent: string; value: string; label: string; delta?: string; from?: number; i?: number;
}) => {
  const f = useCurrentFrame();
  const p = settle(f, at);
  return <div style={{position: 'absolute', left: x, top: y + Math.sin(f / 46 + i * 1.7) * 9, opacity: map(p, [0, .25], [0, 1]),
    transform: `translateX(${(1 - p) * from * 300}px) scale(${.82 + .18 * p}) rotate(${(1 - p) * from * 6}deg)`, filter: `blur(${(1 - p) * 8}px)`,
    padding: '22px 30px', borderRadius: 30, background: 'linear-gradient(140deg,#161f31f2,#0a0f1af2)', border: `1.5px solid ${accent}66`,
    boxShadow: `0 34px 70px #000000a0, 0 0 50px ${accent}26`, color: WHITE}}>
    <div style={{display: 'flex', alignItems: 'baseline', gap: 14}}>
      <span style={{fontSize: 54, fontWeight: 700, letterSpacing: -2}}>{value}</span>
      {delta && <span style={{fontSize: 26, fontWeight: 600, color: accent}}>{delta}</span>}
    </div>
    <div style={{fontSize: 22, letterSpacing: 3, fontWeight: 600, color: '#9fb0c7', marginTop: 4}}>{label}</div>
  </div>;
};

// Each scene eases in from a soft zoom and exits with a forward push and focus blur.
const Scene = ({dur, children}: {dur: number; children: React.ReactNode}) => {
  const f = useCurrentFrame();
  const enter = glide(f, 0, 140);
  const exit = map(f, [dur - 16, dur], [0, 1]);
  return <AbsoluteFill style={{background: INK, overflow: 'hidden'}}>
    <AbsoluteFill style={{opacity: enter * (1 - exit), transform: `scale(${1.05 - enter * .05 + exit * .07})`, filter: `blur(${exit * 14 + (1 - enter) * 6}px)`}}>
      {children}
    </AbsoluteFill>
  </AbsoluteFill>;
};

// Brand transition: the logo's three strokes sweep across the frame, covering the cut at their midpoint.
const WIPE = 36;
const StrokeWipe = () => {
  const f = useCurrentFrame();
  const band = H / 3 + 30;
  return <AbsoluteFill style={{pointerEvents: 'none', overflow: 'hidden'}}>
    {LOGO_PATHS.map(([color], i) => {
      const inP = glide(f, i * 2, 230);
      const outP = glide(f, 19 + i * 2, 230);
      const x = map(inP, [0, 1], [-2.15 * W, -.5 * W]) + map(outP, [0, 1], [0, 2.3 * W]);
      return <div key={color} style={{position: 'absolute', top: i * (H / 3) - 15, left: 0, width: 2 * W, height: band, borderRadius: band,
        transform: `translateX(${x}px)`, background: color, boxShadow: `0 0 80px ${color}88`}} />;
    })}
  </AbsoluteFill>;
};

const TABS: [string, string][] = [
  ['VercelMark', 'vercel.com/dashboard'], ['CloudflareMark', 'dash.cloudflare.com'], ['NamecheapMark', 'namecheap.com/domains'],
  ['GoogleSearchConsoleMark', 'search.google.com/search-console'], ['GoogleAnalyticsMark', 'analytics.google.com'],
  ['NetlifyMark', 'app.netlify.com'], ['RailwayMark', 'railway.com/dashboard'], ['PorkbunMark', 'porkbun.com/account'],
  ['RenderMark', 'dashboard.render.com'], ['PageSpeedMark', 'pagespeed.web.dev'], ['UptimeRobotMark', 'dashboard.uptimerobot.com'],
  ['NameDotComMark', 'name.com/account/domains'], ['FirebaseMark', 'console.firebase.google.com'], ['HerokuMark', 'dashboard.heroku.com'],
];
const jitter = (i: number, k: number) => Math.sin(i * 12.9898 + k * 78.233) * 43758.5453 % 1;

const Hook = () => {
  const f = useCurrentFrame();
  const landAt = (i: number) => 14 + i * 8;
  const landed = TABS.filter((_, i) => f >= landAt(i) + 6).length;
  const collapse = (i: number) => glide(f, 150 + (TABS.length - 1 - i) * 2, 120);
  const headOut = glide(f, 150, 120);
  const ring = map(f, [178, 230], [0, 1]);
  return <AbsoluteFill style={{color: WHITE}}>
    <Field accent={BLUE} second={VIOLET} cy={1060} />
    <div style={{position: 'absolute', left: 80, top: 210, opacity: 1 - headOut, filter: `blur(${headOut * 12}px)`, transform: `translateY(${-headOut * 60}px)`}}>
      <Eyebrow at={4} color={BLUE}>EVERY SINGLE DAY</Eyebrow>
      <div style={{display: 'flex', alignItems: 'baseline', gap: 26, marginTop: 22}}>
        <div style={{fontSize: 230, fontWeight: 700, letterSpacing: -12, lineHeight: .9, fontVariantNumeric: 'tabular-nums', minWidth: 250,
          background: `linear-gradient(180deg,#fff,${BLUE})`, WebkitBackgroundClip: 'text', color: 'transparent', opacity: glide(f, 10)}}>{landed}</div>
        <Words text="tabs open." at={16} size={92} />
      </div>
      <div style={{marginTop: 22}}><Caption at={40}>Just to check on your own websites.</Caption></div>
    </div>
    {TABS.map(([mark, url], i) => {
      const p = settle(f, landAt(i));
      const c = collapse(i);
      const x = 130 + jitter(i, 1) * 70;
      const y = 690 + i * 60;
      const rot = jitter(i, 2) * 5;
      return <div key={url} style={{position: 'absolute', left: x + (540 - 410 - x) * c, top: y + (1060 - 52 - y) * c, width: 820, height: 104,
        transform: `translateY(${(1 - p) * 1400}px) rotate(${rot * (1 - c) - (1 - p) * 14}deg) scale(${1 - c * .96})`,
        opacity: map(p, [0, .15], [0, 1]) * (1 - map(c, [.7, 1], [0, 1])), filter: `blur(${c * 10}px)`,
        display: 'flex', alignItems: 'center', gap: 22, padding: '0 26px', borderRadius: 26,
        background: 'linear-gradient(180deg,#141b2b,#0d1320)', border: '1.5px solid #27324a', boxShadow: '0 24px 50px #000000a0'}}>
        <Provider name={mark} size={62} light />
        <span style={{flex: 1, fontSize: 31, fontWeight: 500, color: '#cdd7e7', whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis'}}>{url}</span>
        <span style={{fontSize: 34, color: '#5d6b82'}}>×</span>
      </div>;
    })}
    <svg width={W} height={H} style={{position: 'absolute', inset: 0}}>
      <circle cx={540} cy={1060} r={40 + ring * 700} fill="none" stroke={BLUE} strokeWidth={3 * (1 - ring)} opacity={(1 - ring) * (f > 176 ? 1 : 0)} />
    </svg>
    <div style={{position: 'absolute', top: 960, width: '100%'}}><Words text="One app." at={180} size={190} align="center" stagger={7} /></div>
  </AbsoluteFill>;
};

const BigLogo = ({at, width}: {at: number; width: number}) => {
  const f = useCurrentFrame();
  const draw = glide(f, at, 50);
  return <svg width={width} height={width * .68} viewBox="0 0 300 200" style={{overflow: 'visible'}}>
    {LOGO_PATHS.map(([color, d, y], i) => <g key={d}>
      <path d={d} fill="none" stroke={color} strokeWidth="12" strokeLinecap="round" pathLength={1} strokeDasharray={1}
        strokeDashoffset={1 - glide(f, at + i * 6, 45)} style={{filter: `drop-shadow(0 0 14px ${color}aa)`}} />
      <circle cx={20} cy={y} r={9} fill={color} opacity={draw} />
    </g>)}
    <circle cx={280} cy={114} r={9} fill={BLUE} opacity={glide(f, at + 34)} />
  </svg>;
};

const Brand = () => {
  const f = useCurrentFrame();
  const ring = map(f, [0, 150], [0, 1]);
  return <AbsoluteFill style={{background: '#000', color: WHITE}}>
    <Glow x={540} y={760} size={1500} color={BLUE} opacity={.7} />
    <Glow x={540} y={1200} size={1100} color={VIOLET} opacity={.45} />
    <svg width={W} height={H} style={{position: 'absolute', inset: 0}}>
      {[0, 1, 2].map(i => <circle key={i} cx={540} cy={760} r={260 + i * 160 + ring * 120} fill="none" stroke={[BLUE, WHITE, VIOLET][i]}
        strokeOpacity={.14} strokeWidth={1.5} />)}
    </svg>
    <div style={{position: 'absolute', left: 540 - 260, top: 760 - 190, transform: `scale(${1.08 - glide(f, 0, 40) * .08})`}}><BigLogo at={4} width={520} /></div>
    <div style={{position: 'absolute', top: 1080, width: '100%'}}><Words text="Verceltics" at={26} size={168} align="center" /></div>
    <div style={{position: 'absolute', top: 1300, width: '100%', padding: '0 90px'}}>
      <Caption at={52} align="center" size={44} color="#c3cee0">Your whole web stack.</Caption>
      <Caption at={62} align="center" size={44} color="#c3cee0">One native app.</Caption>
    </div>
  </AbsoluteFill>;
};

const ORBIT = ['VercelMark', 'CloudflareMark', 'NetlifyMark', 'NameDotComMark', 'GoogleSearchConsoleMark', 'GoogleAnalyticsMark', 'RailwayMark', 'NamecheapMark'];

const AndroidHero = () => {
  const f = useCurrentFrame();
  const rise = settle(f, 26);
  const cam = map(f, [0, 300], [0, 1]);
  const letters = 'Android.'.split('');
  return <AbsoluteFill style={{color: WHITE}}>
    <Field accent={GREEN} second={BLUE} cy={1300} />
    {ORBIT.map((m, i) => {
      const a = i / ORBIT.length * Math.PI * 2 + f / 200;
      const depth = Math.sin(a) > 0 ? 1 : .6;
      const p = glide(f, 50 + i * 5, 50);
      return <div key={m} style={{position: 'absolute', left: 540 + Math.cos(a) * 470 - 46, top: 1320 + Math.sin(a) * 160 - 46,
        zIndex: depth === 1 ? 2 : 0, opacity: p * (depth === 1 ? .95 : .55), transform: `scale(${depth * p})`, filter: `blur(${(1 - depth) * 8}px)`}}>
        <Provider name={m} size={92} />
      </div>;
    })}
    <div style={{position: 'absolute', left: 540 - 285, top: 640, perspective: 2400, zIndex: 1}}>
      <div style={{transform: `translateY(${(1 - rise) * 1300}px) rotateX(${(1 - rise) * 50 + 8 - cam * 4}deg) rotateY(${-10 + cam * 18}deg) rotateZ(${(1 - rise) * -8}deg)`}}>
        <Android clip="hosting" start={1.2} width={570} glare={map(f, [60, 260], [0, 1])} />
      </div>
    </div>
    <div style={{position: 'absolute', left: 80, top: 190, zIndex: 3}}>
      <Eyebrow at={4}>NOW ON</Eyebrow>
      <div style={{display: 'flex', marginTop: 4}}>
        {letters.map((ch, i) => {
          const p = settle(f, 10 + i * 3);
          return <span key={i} style={{display: 'inline-block', overflow: 'hidden', paddingBottom: 30, marginBottom: -30}}>
            <span style={{display: 'inline-block', fontSize: 228, lineHeight: .95, fontWeight: 700, letterSpacing: -11,
              transform: `translateY(${(1 - p) * 110}%)`, opacity: map(p, [0, .2], [0, 1]),
              background: ch === '.' ? 'none' : `linear-gradient(180deg,#ffffff 0%,#c9ffe0 55%,${GREEN} 100%)`,
              WebkitBackgroundClip: ch === '.' ? undefined : 'text', color: ch === '.' ? GREEN : 'transparent'}}>{ch}</span>
          </span>;
        })}
      </div>
      <div style={{marginTop: 22}}><Caption at={46} size={36} color="#bac6d8">Native. Fast. Built with Jetpack Compose.</Caption></div>
    </div>
    <FootageNote />
  </AbsoluteFill>;
};

const Hosting = () => {
  const f = useCurrentFrame();
  const zoom = glide(f, 24, 32);
  return <AbsoluteFill style={{color: WHITE}}>
    <Field accent={BLUE} second={GREEN} cy={1250} />
    <div style={{position: 'absolute', left: 540 - 300, top: 690, perspective: 2400}}>
      <div style={{transform: `rotateY(${-12 + zoom * 12}deg) rotateX(${6 - zoom * 6}deg) scale(${1 + zoom * .55}) translateY(${zoom * 150}px)`, transformOrigin: '50% 30%'}}>
        <Android clip="hosting" start={5.9} width={600} glare={map(f, [0, 240], [.1, .9])} />
      </div>
    </div>
    <AbsoluteFill style={{background: `linear-gradient(180deg, ${INK} 0%, ${INK}f0 30%, transparent 44%)`}} />
    <div style={{position: 'absolute', left: 80, top: 190}}>
      <Eyebrow at={10} color={BLUE}>01 · HOSTING</Eyebrow>
      <div style={{marginTop: 24}}><Words text="Every deploy." at={18} size={116} /><Words text="Every visitor." at={28} size={116} color="#6fb4ff" /></div>
    </div>
    <Chip at={70} x={612} y={1080} accent={GREEN} value="12.5K" delta="+14%" label="VISITORS" from={1} i={0} />
    <Chip at={84} x={70} y={1400} accent={BLUE} value="18.3K" delta="+19%" label="PAGE VIEWS" from={-1} i={1} />
    <FootageNote />
  </AbsoluteFill>;
};

const Domains = () => {
  const f = useCurrentFrame();
  const rise = settle(f, 8);
  const drift = map(f, [0, 240], [0, 1]);
  return <AbsoluteFill style={{color: WHITE}}>
    <Field accent={VIOLET} second={BLUE} cy={1250} />
    <div style={{position: 'absolute', left: 300, top: 640, perspective: 2400}}>
      <div style={{transform: `translateY(${(1 - rise) * 1100}px) rotateY(${-16 + drift * 8}deg) rotateZ(${4 - drift * 2}deg)`}}>
        <Android clip="registrars" start={3.6} width={560} glare={map(f, [20, 220], [0, 1])} />
      </div>
    </div>
    <AbsoluteFill style={{background: `linear-gradient(180deg, ${INK} 0%, ${INK}e8 27%, transparent 40%)`}} />
    <div style={{position: 'absolute', left: 80, top: 190}}>
      <Eyebrow at={10} color={VIOLET}>02 · DOMAINS</Eyebrow>
      <div style={{marginTop: 24}}><Words text="Never miss" at={18} size={116} /><Words text="a renewal." at={28} size={116} color="#c493ff" /></div>
    </div>
    <Chip at={64} x={60} y={1040} accent={GREEN} value="196 days" label="DOCS-STUDIO.EXAMPLE" from={-1} i={0} />
    <Chip at={76} x={60} y={1250} accent={BLUE} value="92 days" label="EDGE-TOOLS.EXAMPLE" from={-1} i={1} />
    <Chip at={88} x={60} y={1460} accent={AMBER} value="18 days" label="STUDIO-DESIGN.EXAMPLE" from={-1} i={2} />
    <FootageNote />
  </AbsoluteFill>;
};

const Sites = () => {
  const f = useCurrentFrame();
  const rise = settle(f, 8);
  const drift = map(f, [0, 240], [0, 1]);
  return <AbsoluteFill style={{color: WHITE}}>
    <Field accent={GREEN} second={VIOLET} cy={1250} />
    <div style={{position: 'absolute', left: 90, top: 640, perspective: 2400}}>
      <div style={{transform: `translateY(${(1 - rise) * 1100}px) rotateY(${16 - drift * 8}deg) rotateZ(${-4 + drift * 2}deg)`}}>
        <Android clip="sites" start={1.9} width={560} glare={map(f, [20, 220], [0, 1])} />
      </div>
    </div>
    <AbsoluteFill style={{background: `linear-gradient(180deg, ${INK} 0%, ${INK}e8 27%, transparent 40%)`}} />
    <div style={{position: 'absolute', left: 80, top: 190}}>
      <Eyebrow at={10}>03 · SITES</Eyebrow>
      <div style={{marginTop: 24}}><Words text="Search. Speed." at={18} size={116} /><Words text="Uptime." at={30} size={116} color={GREEN} /></div>
    </div>
    <Chip at={64} x={600} y={1060} accent={BLUE} value="8.8K" label="CLICKS" from={1} i={0} />
    <Chip at={76} x={600} y={1270} accent={VIOLET} value="176.7K" label="IMPRESSIONS" from={1} i={1} />
    <Chip at={88} x={600} y={1480} accent={GREEN} value="5.0%" label="CTR" from={1} i={2} />
    <FootageNote />
  </AbsoluteFill>;
};

const GROUPS: {label: string; color: string; marks: string[]}[] = [
  {label: 'HOSTING', color: BLUE, marks: ['VercelMark', 'CloudflareMark', 'NetlifyMark', 'RailwayMark', 'RenderMark', 'DigitalOceanMark', 'HerokuMark', 'FlyMark', 'FirebaseMark', 'AWSAmplifyMark']},
  {label: 'REGISTRARS', color: VIOLET, marks: ['NameDotComMark', 'NamecheapMark', 'PorkbunMark', 'SpaceshipMark', 'DynadotMark', 'NameSiloMark', 'GandiMark', 'GoDaddyMark']},
  {label: 'SITE SERVICES', color: GREEN, marks: ['GoogleSearchConsoleMark', 'GoogleAnalyticsMark', 'PageSpeedMark', 'BingWebmasterMark', 'MicrosoftClarityMark', 'PlausibleMark', 'UmamiMark', 'UptimeRobotMark', 'BetterStackMark']},
];

const Integrations = () => {
  const f = useCurrentFrame();
  const count = Math.round(map(glide(f, 8, 26), [0, 1], [0, 27]));
  const tile = 128; const gap = 22; const cols = 6;
  const left = (W - (cols * tile + (cols - 1) * gap)) / 2;
  let order = 0;
  let top = 560;
  return <AbsoluteFill style={{color: WHITE}}>
    <Field accent={BLUE} second={GREEN} cy={1150} />
    <div style={{position: 'absolute', top: 150, width: '100%', textAlign: 'center'}}>
      <div style={{fontSize: 250, fontWeight: 700, letterSpacing: -14, lineHeight: .9, fontVariantNumeric: 'tabular-nums',
        background: `linear-gradient(180deg,#fff,${GREEN})`, WebkitBackgroundClip: 'text', color: 'transparent'}}>{count}</div>
      <div style={{marginTop: 16}}><Words text="integrations. One app." at={20} size={64} weight={600} align="center" /></div>
    </div>
    {GROUPS.map(g => {
      const groupTop = top;
      top += 52 + Math.ceil(g.marks.length / cols) * (tile + gap) + 26;
      return <div key={g.label} style={{position: 'absolute', left, top: groupTop}}>
        <div style={{display: 'flex', alignItems: 'center', gap: 14, fontSize: 23, letterSpacing: 5, fontWeight: 600, color: g.color, opacity: glide(f, 20 + order * 2)}}>
          <span style={{width: 10, height: 10, borderRadius: 10, background: g.color}} />{g.marks.length} {g.label}
        </div>
        {g.marks.map((m, j) => {
          const k = order++;
          const s = settle(f, 14 + k * 2.2);
          const wave = Math.sin((f - k * 5) / 13) * map(f, [130, 170], [0, 1]);
          const from = [(k * 137) % 700 - 350, (k * 251) % 900 - 450];
          return <div key={m} style={{position: 'absolute', left: (j % cols) * (tile + gap), top: 52 + Math.floor(j / cols) * (tile + gap),
            transform: `translate(${(1 - s) * from[0]}px, ${(1 - s) * from[1] - wave * 8}px) scale(${.3 + s * .7}) rotate(${(1 - s) * (k % 2 ? 50 : -50)}deg)`,
            opacity: map(s, [0, .3], [0, 1]), filter: `blur(${(1 - s) * 8}px)`}}>
            <Provider name={m} size={tile} light />
          </div>;
        })}
      </div>;
    })}
  </AbsoluteFill>;
};

const Everywhere = () => {
  const f = useCurrentFrame();
  const pad = settle(f, 10); const ios = settle(f, 26); const and = settle(f, 42);
  const drift = map(f, [0, 240], [0, 1]);
  const words: [string, string, number][] = [['iPhone.', WHITE, 30], ['iPad.', '#6fb4ff', 46], ['Android.', GREEN, 62]];
  return <AbsoluteFill style={{color: WHITE}}>
    <Field accent={VIOLET} second={GREEN} cy={1180} />
    <div style={{position: 'absolute', left: 80, top: 190}}>
      {words.map(([w, c, at]) => <Words key={w} text={w} at={at} size={124} color={c} />)}
      <div style={{marginTop: 20}}><Caption at={90} size={36}>One native app on every screen you own.</Caption></div>
    </div>
    <div style={{position: 'absolute', left: 540 - 350, top: 700, perspective: 2600}}>
      <div style={{transform: `translateY(${(1 - pad) * 1200}px) rotateX(${8 - drift * 4}deg) scale(${.98 + drift * .03})`}}>
        <IPad still="hosting" width={700} glare={map(f, [20, 220], [0, 1])} />
      </div>
    </div>
    <div style={{position: 'absolute', left: 28, top: 930, perspective: 2400}}>
      <div style={{transform: `translateY(${(1 - ios) * 1200}px) rotateY(${18 - drift * 6}deg) rotateZ(${-5 + drift * 2}deg)`}}>
        <IPhone clip="vercel" start={0} width={322} glare={map(f, [30, 230], [0, 1])} />
      </div>
    </div>
    <div style={{position: 'absolute', left: 718, top: 900, perspective: 2400}}>
      <div style={{transform: `translateY(${(1 - and) * 1200}px) rotateY(${-18 + drift * 6}deg) rotateZ(${5 - drift * 2}deg)`}}>
        <Android clip="connect" start={0.4} width={334} glare={map(f, [40, 240], [0, 1])} />
      </div>
    </div>
    <FootageNote />
  </AbsoluteFill>;
};

const EndCard = () => {
  const f = useCurrentFrame();
  const pill = glide(f, 70, 80);
  return <AbsoluteFill style={{background: '#000', color: WHITE}}>
    <Glow x={540} y={720} size={1400} color={GREEN} opacity={.5 * glide(f, 0, 30)} />
    <Glow x={540} y={1150} size={1000} color={BLUE} opacity={.4} />
    <div style={{position: 'absolute', left: 540 - 220, top: 560}}><BigLogo at={4} width={440} /></div>
    <div style={{position: 'absolute', top: 900, width: '100%'}}><Words text="Verceltics" at={28} size={150} align="center" /></div>
    <div style={{position: 'absolute', top: 1105, width: '100%', display: 'flex', justifyContent: 'center'}}>
      <div style={{display: 'flex', alignItems: 'center', gap: 16, padding: '16px 32px', borderRadius: 60, opacity: pill,
        transform: `scale(${.85 + pill * .15})`, background: `${GREEN}1f`, border: `1.5px solid ${GREEN}88`, boxShadow: `0 0 50px ${GREEN}33`}}>
        <span style={{width: 14, height: 14, borderRadius: 14, background: GREEN, boxShadow: `0 0 18px ${GREEN}`, opacity: .6 + .4 * Math.sin(f / 9)}} />
        <span style={{fontSize: 34, fontWeight: 600, color: '#d8ffe9'}}>Android beta now open</span>
      </div>
    </div>
    <div style={{position: 'absolute', top: 1230, width: '100%'}}><Caption at={52} align="center" size={36} color="#b8c5d8">iPhone · iPad · Android</Caption></div>
    <div style={{position: 'absolute', top: 1330, width: '100%', textAlign: 'center', fontSize: 44, fontWeight: 700, color: GREEN, opacity: glide(f, 92)}}>verceltics.com</div>
    <div style={{position: 'absolute', top: 1420, width: '100%', textAlign: 'center', fontSize: 22, letterSpacing: 6, color: '#66758c', opacity: glide(f, 104)}}>OPEN SOURCE</div>
    <AbsoluteFill style={{background: '#000', opacity: map(f, [180, 210], [0, 1])}} />
  </AbsoluteFill>;
};

// [scene, seconds, wipe into this scene]. Cuts land on the 120 BPM soundtrack's accents.
const cuts: [React.FC, number, boolean][] = [
  [Hook, 4, false], [Brand, 2.5, true], [AndroidHero, 5, true], [Hosting, 4, false], [Domains, 4, false],
  [Sites, 4, false], [Integrations, 4, true], [Everywhere, 4, false], [EndCard, 3.5, true],
];
export const DURATION = cuts.reduce((t, [, s]) => t + sec(s), 0);

export const PortraitPromo = () => {
  let at = 0;
  const starts = cuts.map(([, s]) => { const from = at; at += sec(s); return from; });
  return <AbsoluteFill style={{fontFamily: '"Space Grotesk",sans-serif', background: INK}}>
    <style>{`@font-face{font-family:'Space Grotesk';src:url('${staticFile('space-grotesk.woff2')}') format('woff2');font-weight:300 700;}*{box-sizing:border-box;}`}</style>
    <Audio src={staticFile('portrait-soundtrack.wav')} volume={fr => map(fr, [0, 20, DURATION - 90, DURATION], [0, .9, .9, 0])} />
    {cuts.map(([Comp, seconds, wipe], i) => {
      const dur = sec(seconds);
      const bare = i === 0 || wipe || cuts[i + 1]?.[2];
      return <Sequence key={i} from={starts[i]} durationInFrames={dur}>
        {bare ? <Comp /> : <Scene dur={dur}><Comp /></Scene>}
      </Sequence>;
    })}
    <Vignette />
    {cuts.map(([, , wipe], i) => wipe && <Sequence key={`w${i}`} from={starts[i] - WIPE / 2} durationInFrames={WIPE}><StrokeWipe /></Sequence>)}
    <Grain />
  </AbsoluteFill>;
};
