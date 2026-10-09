import React from 'react';
import {AbsoluteFill, Easing, Img, interpolate, staticFile, useCurrentFrame} from 'remotion';

// All motion is derived from the frame: preview and headless export are identical.
export const C = {ink: '#070b14', blue: '#1689ff', violet: '#ae5bff', white: '#f5f7fc', muted: '#98a7bd'};
export const clamp = {extrapolateLeft: 'clamp', extrapolateRight: 'clamp'} as const;
export const cinematic = Easing.bezier(0.22, 1, 0.36, 1);
export const ease = (f: number, a: number, b: number) => interpolate(f, [a, b], [0, 1], {...clamp, easing: cinematic});
export const lerp = (f: number, range: number[], values: number[]) => interpolate(f, range, values, clamp);
export const markNames = ['VercelMark', 'CloudflareMark', 'NamecheapMark', 'NameDotComMark',
  'GoogleSearchConsoleMark', 'GoogleAnalyticsMark', 'NetlifyMark', 'RailwayMark',
  'RenderMark', 'PageSpeedMark', 'UptimeRobotMark', 'PorkbunMark'];

export const Provider = ({name, size = 88, light = false}: {name: string; size?: number; light?: boolean}) => (
  <div style={{width: size, height: size, borderRadius: size * .26, display: 'flex', alignItems: 'center', justifyContent: 'center',
    background: light ? '#fff' : '#101725', border: `1px solid ${light ? '#dde2ea' : '#28334a'}`,
    boxShadow: '0 16px 40px #00000025'}}>
    <Img src={staticFile(`providers/${name}.svg`)} style={{width: size * .55, height: size * .55, objectFit: 'contain',
      filter: !light && ['VercelMark', 'RailwayMark', 'RenderMark'].includes(name) ? 'invert(1)' : undefined}} />
  </div>
);

export const Wordmark = ({size = 32}: {size?: number}) => <div style={{display: 'flex', alignItems: 'center', gap: size * .42}}>
  <Img src={staticFile('logo.png')} style={{width: size * 1.8, height: size * 1.8, objectFit: 'contain'}} />
  <span style={{fontSize: size, fontWeight: 700, letterSpacing: -size * .045}}>Verceltics</span>
</div>;

export const Backdrop = ({accent = C.blue, light = false}: {accent?: string; light?: boolean}) => {
  const f = useCurrentFrame();
  return <AbsoluteFill style={{background: light ? '#edf2fa' : C.ink, overflow: 'hidden'}}>
    <div style={{position: 'absolute', width: 1400, height: 1200, left: 600 + Math.sin(f / 100) * 60, top: -250,
      background: `radial-gradient(ellipse, ${accent}${light ? '22' : '24'} 0%, transparent 64%)`}} />
    <div style={{position: 'absolute', inset: 0, opacity: light ? .45 : .24,
      backgroundImage: `linear-gradient(${light ? '#9baac51c' : '#697ca019'} 1px, transparent 1px), linear-gradient(90deg, ${light ? '#9baac51c' : '#697ca019'} 1px, transparent 1px)`,
      backgroundSize: '120px 120px', maskImage: 'radial-gradient(ellipse at 70% 50%, black, transparent 75%)'}} />
    {[0, 1, 2].map(i => <div key={i} style={{position: 'absolute', width: 950 + i * 260, height: 950 + i * 260,
      border: `1px solid ${accent}${light ? '18' : '20'}`, borderRadius: '50%', left: 960 - i * 130, top: 20 - i * 130,
      transform: `translateY(${Math.sin(f / 90 + i) * 20}px)`}} />)}
  </AbsoluteFill>;
};

export const Label = ({children, color = C.blue}: {children: React.ReactNode; color?: string}) => <div style={{display: 'flex', alignItems: 'center', gap: 13, color, fontSize: 20, letterSpacing: 3.4, fontWeight: 600}}>
  <span style={{width: 8, height: 8, background: color, borderRadius: 20}} />{children}
</div>;

export const Line = ({text, delay = 0, color, size = 100}: {text: string; delay?: number; color?: string; size?: number}) => {
  const p = ease(useCurrentFrame(), delay, delay + 24);
  return <div style={{overflow: 'hidden', paddingBottom: 8, marginBottom: -8}}>
    <div style={{fontSize: size, lineHeight: 1.02, letterSpacing: -size * .048, fontWeight: 700, color,
      transform: `translateY(${(1-p)*110}%)`, opacity: lerp(p, [0, .3, 1], [0, 1, 1])}}>{text}</div>
  </div>;
};

export const TraceLogo = ({size = 280, delay = 0}: {size?: number; delay?: number}) => {
  const f = useCurrentFrame();
  const draw = ease(f, delay, delay + 34);
  return <svg width={size} height={size * .68} viewBox="0 0 300 200" style={{overflow: 'visible'}}>
    {[[C.blue, 'M20 34H72C120 34 143 114 214 114H280'], ['#fff', 'M20 100H110'], [C.violet, 'M20 169H88C125 169 155 139 176 128']].map(([color, d], i) => <g key={d}>
      <path d={d} fill="none" stroke={color} strokeWidth="12" strokeLinecap="round" pathLength={1}
        strokeDasharray={1} strokeDashoffset={1-ease(f, delay+i*4, delay+34+i*4)} />
      <circle cx={20} cy={[34, 100, 169][i]} r={9} fill={color} style={{opacity: draw}} />
    </g>)}
    <circle cx={280} cy={114} r={9} fill={C.blue} opacity={ease(f, delay+27, delay+40)} />
  </svg>;
};

