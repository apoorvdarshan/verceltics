import React from 'react';
import {AbsoluteFill, Audio, OffthreadVideo, Sequence, staticFile, useCurrentFrame} from 'remotion';
import {Backdrop, C, Label, Line, Provider, TraceLogo, Wordmark, ease, lerp, markNames} from './shared';

// "Coming to Android" beta call: real Android recordings (sample data) in the launch film's style.
const GREEN = '#3ddc84';
const JOIN_URL = 'groups.google.com/g/verceltics-testers';
const PROMO_CODE = 'VERCELTICSBETA';

const Footer = ({light = false, index}: {light?: boolean; index?: string}) => <>
  <div style={{position: 'absolute', left: 90, bottom: 45, fontSize: 16, color: light ? '#596d88' : '#708199', letterSpacing: .6}}>Actual Android footage · Demo data</div>
  {index && <div style={{position: 'absolute', right: 90, bottom: 45, fontSize: 17, color: light ? '#596d88' : '#708199', letterSpacing: 2}}>{index}</div>}
</>;

// A generic Android handset: flat 20:9 display, even bezels, centered punch-hole camera.
const AndroidPhone = ({clip, width = 400, start = 0}: {clip: string; width?: number; start?: number}) => (
  <div style={{width, height: width * 2.222, padding: 8, borderRadius: width * .1, background: '#05060a',
    border: '2px solid #4a515f', boxShadow: '0 45px 90px #00000065, inset 0 0 0 3px #111724', position: 'relative'}}>
    <div style={{height: '100%', width: '100%', borderRadius: width * .085, overflow: 'hidden', position: 'relative', background: '#000'}}>
      <OffthreadVideo src={staticFile(`footage-android/${clip}.mp4`)} startFrom={start} muted style={{height: '100%', width: '100%', objectFit: 'cover'}} />
      <div style={{position: 'absolute', top: width * .028, left: '50%', width: width * .045, height: width * .045, marginLeft: -width * .0225,
        borderRadius: '50%', background: '#020203', boxShadow: '0 0 0 2px #0d0f14'}} />
    </div>
    <div style={{position: 'absolute', right: -4, top: width * .42, height: width * .22, width: 3, borderRadius: 3, background: '#525866'}} />
    <div style={{position: 'absolute', right: -4, top: width * .7, height: width * .12, width: 3, borderRadius: 3, background: '#525866'}} />
  </div>
);

const Pill = ({children, color = GREEN}: {children: React.ReactNode; color?: string}) => <div style={{display: 'inline-flex', alignItems: 'center', gap: 12,
  padding: '10px 20px', borderRadius: 40, border: `1px solid ${color}55`, background: `${color}14`, color, fontSize: 22, letterSpacing: 2.5, fontWeight: 600}}>
  <span style={{width: 9, height: 9, borderRadius: 9, background: color}} />{children}
</div>;

const Hook = () => {
  const f = useCurrentFrame();
  const converge = ease(f, 52, 88);
  const positions = [[170, 190], [1440, 140], [1450, 730], [290, 780], [1110, 85], [730, 830]];
  return <AbsoluteFill style={{color: C.white}}>
    <Backdrop accent={GREEN} />
    <div style={{position: 'absolute', left: 90, top: 64}}><Wordmark /></div>
    {positions.map(([x, y], i) => <div key={i} style={{position: 'absolute', left: x + (960 - x) * converge, top: y + (475 - y) * converge,
      opacity: 1 - converge, transform: `translateY(${Math.sin(f / 24 + i) * 16}px) rotate(${(i % 2 ? 1 : -1) * (9 + f * .05)}deg) scale(${1 - converge * .7})`}}>
      <Provider name={markNames[i]} size={105} />
    </div>)}
    <div style={{position: 'absolute', top: 310, width: '100%', textAlign: 'center', opacity: 1 - ease(f, 70, 86), transform: `scale(${1 + f * .00045})`}}>
      <div style={{opacity: ease(f, 0, 14), marginBottom: 34}}><Pill>ANDROID BETA</Pill></div>
      <Line text="Your whole stack." size={116} delay={2} />
      <Line text="Now on Android." size={116} delay={14} color={GREEN} />
    </div>
    <div style={{position: 'absolute', left: 830, top: 450, opacity: ease(f, 66, 88), transform: `scale(${.7 + converge * .3})`}}><TraceLogo size={260} delay={62} /></div>
  </AbsoluteFill>;
};

const Reveal = () => {
  const f = useCurrentFrame();
  const p = ease(f, 0, 24);
  return <AbsoluteFill style={{background: C.ink, color: C.white}}>
    <Backdrop accent={C.violet} />
    <div style={{position: 'absolute', left: 80, top: 310, fontSize: 210, fontWeight: 700, letterSpacing: -13, color: '#ffffff04', whiteSpace: 'nowrap', transform: `translateX(${-f * 3}px)`}}>ANDROID ANDROID ANDROID</div>
    <div style={{position: 'absolute', left: 830 + p * 40, top: 450 - p * 270, transform: `scale(${1.3 - p * .3})`}}><TraceLogo size={200} delay={-25} /></div>
    <div style={{position: 'absolute', top: 430, width: '100%', textAlign: 'center'}}>
      <div style={{fontSize: 22, letterSpacing: 8, color: C.muted, marginBottom: 20, opacity: p}}>COMING SOON TO GOOGLE PLAY</div>
      <Line text="Verceltics for Android" size={132} delay={5} />
      <div style={{fontSize: 27, color: '#bcc8da', marginTop: 35, opacity: ease(f, 22, 42)}}>Join the beta and help us launch.</div>
    </div>
    <div style={{position: 'absolute', left: 180, right: 180, bottom: 140, height: 1,
      background: `linear-gradient(90deg,transparent,${GREEN},${C.blue},transparent)`, transform: `scaleX(${p})`}} />
  </AbsoluteFill>;
};

const Feature = ({clip, start, label, accent, lines, sub, marks, index, light = false, phoneLeft = true}: {
  clip: string; start: number; label: string; accent: string; lines: [string, string]; sub: string;
  marks: string[]; index: string; light?: boolean; phoneLeft?: boolean;
}) => {
  const f = useCurrentFrame();
  const p = ease(f, 0, 32);
  const drift = ease(f, 60, 150);
  const phoneX = phoneLeft ? 330 + drift * 30 : 1230 - drift * 50;
  const textX = phoneLeft ? 900 : 115;
  return <AbsoluteFill style={{color: light ? C.ink : C.white}}>
    <Backdrop light={light} accent={accent} />
    <div style={{position: 'absolute', left: 90, top: 64}}><Wordmark /></div>
    <div style={{position: 'absolute', left: phoneX, top: 96 + Math.sin(f / 55) * 7,
      transform: `perspective(1900px) rotateY(${(phoneLeft ? 14 : -16) * (1 - p) + (phoneLeft ? 3 : -4)}deg) rotateZ(${(phoneLeft ? 4 : -5) * (1 - p)}deg) translateY(${(1 - p) * 160}px) scale(${.9 + .1 * p})`}}>
      <AndroidPhone clip={clip} width={398} start={start} />
    </div>
    <div style={{position: 'absolute', left: textX, top: 255, transform: `translateX(${(1 - p) * (phoneLeft ? 70 : -70)}px)`}}>
      <Label color={accent}>{label}</Label>
      <div style={{marginTop: 26}}><Line text={lines[0]} delay={5} size={104} /><Line text={lines[1]} delay={12} size={104} color={accent} /></div>
      <div style={{fontSize: 28, color: light ? '#596d88' : '#b0bed2', marginTop: 36, opacity: p}}>{sub}</div>
      <div style={{display: 'flex', gap: 16, marginTop: 50}}>{marks.map((name, i) => <div key={name} style={{opacity: ease(f, 18 + i * 7, 42 + i * 7),
        transform: `translateY(${(1 - ease(f, 18 + i * 7, 42 + i * 7)) * 35}px)`}}><Provider name={name} size={82} light={light} /></div>)}</div>
    </div>
    <Footer light={light} index={index} />
  </AbsoluteFill>;
};

const Integrations = () => {
  const f = useCurrentFrame();
  const p = ease(f, 0, 30);
  return <AbsoluteFill style={{color: C.white}}>
    <Backdrop accent={GREEN} />
    <div style={{position: 'absolute', left: 90, top: 64}}><Wordmark /></div>
    {markNames.map((name, i) => {
      const a = (i / markNames.length) * Math.PI * 2 - Math.PI / 2;
      const appear = ease(f, i * 1.5, 18 + i * 1.5);
      return <div key={name} style={{position: 'absolute', left: 1260 + Math.cos(a + f / 160) * 330 - 40, top: 500 + Math.sin(a + f / 160) * 380 - 40,
        opacity: appear * .9, transform: `scale(${appear})`}}><Provider name={name} size={80} /></div>;
    })}
    <div style={{position: 'absolute', left: 1080, top: 120 + Math.sin(f / 60) * 6, transform: `translateY(${(1 - p) * 140}px) rotate(${-4 + 4 * p}deg)`}}>
      <AndroidPhone clip="connect" width={370} start={20} />
    </div>
    <div style={{position: 'absolute', left: 115, top: 300}}>
      <div style={{fontSize: 170, lineHeight: 1, fontWeight: 700, letterSpacing: -10, transform: `scale(${.85 + ease(f, 0, 25) * .15})`, transformOrigin: 'left'}}>27</div>
      <div style={{fontSize: 44, fontWeight: 500, marginTop: 14}}>integrations. One app.</div>
      <div style={{fontSize: 26, color: C.muted, marginTop: 28, opacity: ease(f, 20, 40)}}>10 hosting · 8 registrars · 9 site services</div>
    </div>
    <Footer index="04 / CONNECT" />
  </AbsoluteFill>;
};

const Step = ({n, title, detail, delay, accent = C.blue}: {n: string; title: string; detail: React.ReactNode; delay: number; accent?: string}) => {
  const f = useCurrentFrame();
  const p = ease(f, delay, delay + 22);
  return <div style={{display: 'flex', gap: 26, alignItems: 'flex-start', opacity: p, transform: `translateX(${(1 - p) * -40}px)`}}>
    <div style={{width: 54, height: 54, flexShrink: 0, borderRadius: 54, border: `2px solid ${accent}`, color: accent, display: 'flex',
      alignItems: 'center', justifyContent: 'center', fontSize: 26, fontWeight: 700}}>{n}</div>
    <div>
      <div style={{fontSize: 36, fontWeight: 600, letterSpacing: -.8}}>{title}</div>
      <div style={{fontSize: 25, color: '#b8c5d8', marginTop: 8}}>{detail}</div>
    </div>
  </div>;
};

const Join = () => {
  const f = useCurrentFrame();
  const p = ease(f, 0, 28);
  const glow = .55 + Math.sin(f / 9) * .15;
  return <AbsoluteFill style={{color: C.white}}>
    <Backdrop accent={GREEN} />
    <div style={{position: 'absolute', left: 115, top: 90, transform: `translateY(${(1 - p) * 30}px)`}}>
      <Wordmark size={44} />
      <div style={{marginTop: 46}}><Line text="Join the" size={100} delay={4} /><Line text="Android beta." size={100} delay={11} color={GREEN} /></div>
      <div style={{display: 'flex', flexDirection: 'column', gap: 30, marginTop: 50}}>
        <Step n="1" delay={26} accent={GREEN} title="Join the tester group" detail={<span style={{color: C.white}}>{JOIN_URL}</span>} />
        <Step n="2" delay={36} accent={GREEN} title="Become a tester on Google Play" detail="Install Verceltics and keep it for 14 days" />
        <Step n="3" delay={46} accent={C.violet} title="Get a month of Pro free" detail={<span>Redeem code <span style={{color: C.white, fontWeight: 700, letterSpacing: 2,
          padding: '3px 12px', borderRadius: 8, background: `rgba(174,91,255,${glow * .35})`, border: '1px solid #ae5bff88'}}>{PROMO_CODE}</span> in Google Play</span>} />
      </div>
    </div>
    <div style={{position: 'absolute', left: 1460, top: 170, transform: `rotate(9deg) translateY(${(1 - p) * 100}px)`}}><AndroidPhone clip="sites" width={290} start={150} /></div>
    <div style={{position: 'absolute', left: 1160, top: 115 + Math.sin(f / 80) * 8, transform: `rotate(-6deg) translateY(${(1 - p) * 180}px)`}}><AndroidPhone clip="hosting" width={360} start={160} /></div>
    <div style={{position: 'absolute', left: 115, bottom: 44, fontSize: 18, color: '#708199', letterSpacing: .6, opacity: ease(f, 60, 80)}}>
      Free and open source · Also on iPhone and iPad · verceltics.com
    </div>
  </AbsoluteFill>;
};

export const AndroidPromo = () => <AbsoluteFill style={{fontFamily: '"Space Grotesk",sans-serif', background: C.ink}}>
  <style>{`@font-face{font-family:'Space Grotesk';src:url('${staticFile('space-grotesk.woff2')}') format('woff2');font-weight:300 700;}*{box-sizing:border-box;}`}</style>
  <Audio src={staticFile('launch-soundtrack.wav')} volume={f => lerp(f, [0, 12, 880, 929], [0, .9, .9, 0])} />
  <Sequence from={0} durationInFrames={90}><Hook /></Sequence>
  <Sequence from={90} durationInFrames={90}><Reveal /></Sequence>
  <Sequence from={180} durationInFrames={150}>
    <Feature clip="hosting" start={100} label="HOSTING" accent="#6baeff" lines={['Ship it.', 'Then check it.']}
      sub="Projects, deployments and analytics" marks={['VercelMark', 'CloudflareMark', 'NetlifyMark', 'RailwayMark']} index="01 / HOSTING" />
  </Sequence>
  <Sequence from={330} durationInFrames={150}>
    <Feature clip="registrars" start={30} label="DOMAINS" accent="#bd86ff" lines={['Every domain.', 'Every renewal.']}
      sub="Expiry, auto renew, locks and DNS" marks={['NameDotComMark', 'NamecheapMark', 'PorkbunMark']} index="02 / DOMAINS" phoneLeft={false} />
  </Sequence>
  <Sequence from={480} durationInFrames={150}>
    <Feature clip="sites" start={30} label="SITES" accent="#166bed" lines={['See what’s', 'working.']} light
      sub="Search, traffic, speed and uptime" marks={['GoogleSearchConsoleMark', 'GoogleAnalyticsMark', 'PageSpeedMark', 'UptimeRobotMark']} index="03 / SITES" />
  </Sequence>
  <Sequence from={630} durationInFrames={120}><Integrations /></Sequence>
  <Sequence from={750} durationInFrames={180}><Join /></Sequence>
</AbsoluteFill>;
