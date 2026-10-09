import React from 'react';
import {
  AbsoluteFill, Audio, Composition, Img, OffthreadVideo, Sequence,
  registerRoot, staticFile, useCurrentFrame,
} from 'remotion';
import {Backdrop, C, Label, Line, Provider, TraceLogo, Wordmark, ease, lerp, markNames} from './shared';
import {AndroidPromo, DURATION as ANDROID_DURATION, FPS as ANDROID_FPS} from './android';

const Footer = ({light = false, index}: {light?: boolean; index?: string}) => <>
  <div style={{position: 'absolute', left: 90, bottom: 45, fontSize: 16, color: light ? '#596d88' : '#708199', letterSpacing: .6}}>Actual iOS footage · Demo data</div>
  {index && <div style={{position: 'absolute', right: 90, bottom: 45, fontSize: 17, color: light ? '#596d88' : '#708199', letterSpacing: 2}}>{index}</div>}
</>;

const Phone = ({clip, still, width = 400, videoStart = 0}: {clip?: string; still?: string; width?: number; videoStart?: number}) => (
  <div style={{width, height: width * 2.166, padding: 9, borderRadius: width * .13, background: '#06070a',
    border: '2px solid #48505e', boxShadow: '0 45px 90px #00000065, inset 0 0 0 3px #111724', position: 'relative'}}>
    <div style={{height: '100%', width: '100%', borderRadius: width * .108, overflow: 'hidden', position: 'relative', background: '#000'}}>
      {clip ? <OffthreadVideo src={staticFile(`footage/${clip}.mp4`)} startFrom={videoStart} muted style={{height: '100%', width: '100%', objectFit: 'cover'}} />
        : <Img src={staticFile(`footage/${still}.png`)} style={{height: '100%', width: '100%', objectFit: 'cover'}} />}
    </div>
    <div style={{position: 'absolute', left: -4, top: width * .43, height: width * .2, width: 3, borderRadius: 3, background: '#525866'}} />
    <div style={{position: 'absolute', right: -4, top: width * .55, height: width * .27, width: 3, borderRadius: 3, background: '#525866'}} />
  </div>
);

// A magnified crop of the actual recording, never a recreated dashboard.
const Detail = ({clip, start = 0, top, width = 720, height = 370, sourceWidth = 810}: {clip: string; start?: number; top: number; width?: number; height?: number; sourceWidth?: number}) => <div style={{width, height, position: 'relative', overflow: 'hidden', borderRadius: 28,
  border: '1px solid #485772', boxShadow: '0 26px 70px #00000080', background: '#000'}}>
  <div style={{position: 'absolute', left: (width - sourceWidth) / 2, top: -top * sourceWidth * 2.1733, width: sourceWidth, height: sourceWidth * 2.1733}}>
    <OffthreadVideo src={staticFile(`footage/${clip}.mp4`)} startFrom={start} muted style={{width: '100%', height: '100%'}} />
  </div>
</div>;

const Hook = () => {
  const f = useCurrentFrame();
  const converge = ease(f, 52, 90);
  const positions = [[180,200],[1430,145],[1440,720],[300,770],[1120,90],[740,825]];
  return <AbsoluteFill style={{color: C.white}}>
    <Backdrop />
    <div style={{position: 'absolute', left: 90, top: 64}}><Wordmark /></div>
    {positions.map(([x,y], i) => <div key={i} style={{position: 'absolute', left: x+(960-x)*converge, top: y+(475-y)*converge,
      opacity: 1-converge, transform: `translateY(${Math.sin(f/24+i)*16}px) rotate(${(i%2?1:-1)*(9+f*.05)}deg) scale(${1-converge*.7})`}}>
      <Provider name={markNames[i]} size={105} />
    </div>)}
    <div style={{position: 'absolute', top: 335, width: '100%', textAlign: 'center', opacity: 1-ease(f, 67, 85), transform: `scale(${1+f*.00045})`}}>
      <Line text="Your entire stack." size={116} delay={0} />
      <Line text="One pocket." size={116} delay={13} color="#69adff" />
      <div style={{fontSize: 27, color: C.muted, marginTop: 32, opacity: ease(f, 22, 40)}}>Hosting. Domains. Analytics.</div>
    </div>
    <div style={{position: 'absolute', left: 830, top: 450, opacity: ease(f, 65, 90), transform: `scale(${.7+converge*.3})`}}><TraceLogo size={260} delay={62} /></div>
  </AbsoluteFill>;
};

const Reveal = () => {
  const f = useCurrentFrame();
  const p = ease(f, 0, 24);
  return <AbsoluteFill style={{background: C.ink, color: C.white}}>
    <Backdrop accent={C.violet} />
    <div style={{position: 'absolute', left: 80, top: 310, fontSize: 210, fontWeight: 700, letterSpacing: -13, color: '#ffffff04', whiteSpace: 'nowrap', transform: `translateX(${-f*3}px)`}}>VERCELTICS VERCELTICS</div>
    <div style={{position: 'absolute', left: 830+p*40, top: 450-p*270, transform: `scale(${1.3-p*.3})`}}><TraceLogo size={200} delay={-25} /></div>
    <div style={{position: 'absolute', top: 430, width: '100%', textAlign: 'center'}}>
      <div style={{fontSize: 22, letterSpacing: 8, color: C.muted, marginBottom: 20, opacity: p}}>INTRODUCING</div>
      <Line text="Verceltics" size={148} delay={5} />
      <div style={{fontSize: 26, color: '#bcc8da', marginTop: 35, opacity: ease(f, 22, 42)}}>A native workspace for iPhone & iPad.</div>
    </div>
    <div style={{position: 'absolute', left: 180, right: 180, bottom: 140, height: 1,
      background: 'linear-gradient(90deg,transparent,#1689ff,#ae5bff,transparent)', transform: `scaleX(${p})`}} />
  </AbsoluteFill>;
};

const Hosting = () => {
  const f = useCurrentFrame();
  const p = ease(f, 0, 34);
  const focus = ease(f, 78, 105);
  const float = Math.sin(f / 55) * 7;
  return <AbsoluteFill style={{color: C.white}}>
    <Backdrop />
    <div style={{position: 'absolute', left: 90, top: 64}}><Wordmark /></div>
    <div style={{position: 'absolute', left: 110, top: 265, transform: `translateX(${(1-p)*-70}px)`}}>
      <Label>HOSTING</Label>
      <div style={{marginTop: 26}}><Line text="Stay close" delay={5} /><Line text="to what you ship." delay={12} color="#6baeff" /></div>
      <div style={{display: 'flex', alignItems: 'center', gap: 20, marginTop: 38, opacity: p}}><Provider name="VercelMark" size={70} /><span style={{fontSize: 26, color: '#b6c4d8'}}>Vercel projects & web analytics</span></div>
    </div>
    <div style={{position: 'absolute', left: 1240-focus*110, top: 105+float, transform: `perspective(1800px) rotateY(${-16+18*p-4*focus}deg) rotateZ(${-5+5*p}deg) translateY(${(1-p)*170}px) scale(${.88+.12*p})`}}>
      <Phone clip="vercel" width={394} />
    </div>
    <Sequence from={96} durationInFrames={84}>
      <MetricSpotlight />
    </Sequence>
    <Footer index="01 / HOSTING" />
  </AbsoluteFill>;
};

const MetricSpotlight = () => {
  const f = useCurrentFrame(); const p = ease(f, 0, 20);
  return <div style={{position: 'absolute', left: 180, top: 670, opacity: p,
    transform: `perspective(1800px) translateY(${(1-p)*75}px) rotateY(${(1-p)*8}deg) scale(${.92+p*.08})`}}>
    <Detail clip="vercel" start={108} top={.26} width={740} height={244} sourceWidth={760} />
    <div style={{position: 'absolute', left: 24, top: -16, padding: '7px 13px', background: C.blue, borderRadius: 20, color: '#fff', fontSize: 15, letterSpacing: 1}}>A CLOSER LOOK</div>
  </div>;
};

const Cloudflare = () => {
  const f = useCurrentFrame(); const p = ease(f, 0, 34); const shift = ease(f, 72, 112);
  return <AbsoluteFill style={{color: C.ink}}>
    <Backdrop light accent="#ff852f" />
    <div style={{position: 'absolute', left: 90, top: 64}}><Wordmark /></div>
    <div style={{position: 'absolute', left: 315+shift*35, top: 108, transform: `perspective(2000px) rotateY(${13-13*p}deg) rotateZ(${4-4*p}deg) translateY(${(1-p)*140}px)`}}><Phone clip="cloudflare" width={396} /></div>
    <div style={{position: 'absolute', left: 930, top: 247}}>
      <Label color="#d76c14">CLOUDFLARE</Label>
      <div style={{marginTop: 24}}><Line text="Your edge." delay={3} size={110} /><Line text="Under control." delay={10} size={110} color="#cf6710" /></div>
      <div style={{display: 'flex', gap: 18, alignItems: 'center', marginTop: 32, opacity: p}}><Provider name="CloudflareMark" size={76} light /><span style={{fontSize: 27, color: '#64718a'}}>Zones. DNS. Pages. Workers.</span></div>
      <div style={{marginTop: 72, width: 670, borderTop: '1px solid #cdd5e3', paddingTop: 30, opacity: ease(f, 45, 75), transform: `translateY(${(1-ease(f,45,75))*30}px)`}}>
        <div style={{fontSize: 24, color: '#59677d'}}>Get the context.</div>
        <div style={{fontSize: 49, fontWeight: 600, letterSpacing: -1.5, marginTop: 10}}>Without the laptop.</div>
      </div>
    </div>
    <div style={{position: 'absolute', left: 160, top: 195, transform: `translateY(${Math.sin(f/38)*14}px) scale(${p})`}}><Provider name="CloudflareMark" size={110} light /></div>
    <Footer light index="02 / EDGE" />
  </AbsoluteFill>;
};

const Domains = () => {
  const f = useCurrentFrame(); const p = ease(f, 0, 32);
  return <AbsoluteFill style={{color: C.white}}>
    <Backdrop accent={C.violet} />
    <div style={{position: 'absolute', left: 90, top: 64}}><Wordmark /></div>
    <div style={{position: 'absolute', left: 115, top: 255}}>
      <Label color="#bd86ff">DOMAINS</Label>
      <div style={{marginTop: 28}}><Line text="Never lose" delay={3} size={105} /><Line text="track of a domain." delay={11} size={105} color="#bd86ff" /></div>
      <div style={{fontSize: 27, color: '#acb9cd', marginTop: 36, opacity: p}}>Expiry. Renewal. Privacy.</div>
      <div style={{display: 'flex', gap: 30, marginTop: 58, opacity: p}}>
        {[['NamecheapMark','Namecheap'],['NameDotComMark','Name.com']].map(([mark,label],i) => <div key={mark} style={{display: 'flex', alignItems: 'center', gap: 14, transform: `translateY(${(1-ease(f,18+i*9,44+i*9))*35}px)`}}><Provider name={mark} size={65} /><span style={{fontSize: 24}}>{label}</span></div>)}
      </div>
      <div style={{marginTop: 52, fontSize: 19, letterSpacing: 2, color: '#70839d', opacity: ease(f, 50, 75)}}>ONE PORTFOLIO. A CLEARER PICTURE.</div>
    </div>
    <div style={{position: 'absolute', left: 1385, top: 150, transform: `perspective(2000px) rotateY(-12deg) rotateZ(9deg) translateX(${(1-p)*160}px)`}}><Phone still="registrars" width={340} /></div>
    <div style={{position: 'absolute', left: 1110-ease(f,75,120)*25, top: 120+Math.sin(f/70)*6, transform: `perspective(2000px) rotateY(${-10+10*p}deg) rotateZ(-5deg) translateY(${(1-p)*130}px)`}}><Phone clip="registrars" width={387} /></div>
    <Footer index="03 / DOMAINS" />
  </AbsoluteFill>;
};

const Sites = () => {
  const f = useCurrentFrame(); const p = ease(f, 0, 32); const push = ease(f, 62, 120);
  return <AbsoluteFill style={{color: C.ink}}>
    <Backdrop light accent={C.blue} />
    <div style={{position: 'absolute', left: 90, top: 64}}><Wordmark /></div>
    <div style={{position: 'absolute', left: 115, top: 252}}>
      <Label>SITES</Label>
      <div style={{marginTop: 24}}><Line text="See what’s" delay={3} size={110} /><Line text="working." delay={10} size={110} color="#166bed" /></div>
      <div style={{fontSize: 28, color: '#596d88', marginTop: 35, opacity: p}}>Search. Traffic. Performance.</div>
      <div style={{display: 'flex', gap: 16, marginTop: 55}}>{['GoogleSearchConsoleMark','GoogleAnalyticsMark','PageSpeedMark','UptimeRobotMark'].map((name,i) => <div key={name} style={{opacity: ease(f,20+i*7,44+i*7), transform: `translateY(${(1-ease(f,20+i*7,44+i*7))*35}px)`}}><Provider name={name} size={86} light /></div>)}</div>
      <div style={{fontSize: 20, color: '#7e8ea7', marginTop: 32, opacity: ease(f,45,65)}}>The metrics that matter, wherever you are.</div>
    </div>
    <div style={{position: 'absolute', left: 1210-push*60, top: 112, transform: `perspective(1800px) rotateY(${18-18*p}deg) rotateZ(${5-4*p}deg) translateY(${(1-p)*150}px) scale(${1+push*.03})`}}><Phone clip="sites" width={393} /></div>
    <div style={{position: 'absolute', left: 1595, top: 540, opacity: ease(f,25,55), transform: `translateY(${Math.sin(f/45)*15}px)`}}><Provider name="GoogleAnalyticsMark" size={140} light /></div>
    <Footer light index="04 / SITES" />
  </AbsoluteFill>;
};

const Ecosystem = () => {
  const f = useCurrentFrame(); const gather = ease(f, 58, 90);
  const center = [960, 480];
  return <AbsoluteFill style={{color: C.white}}>
    <Backdrop accent={C.violet} />
    {markNames.map((name,i) => {
      const a = (i/markNames.length)*Math.PI*2-Math.PI/2;
      const x = center[0]+Math.cos(a)*690;
      const y = center[1]+Math.sin(a)*315;
      const appear=ease(f,i*1.6,18+i*1.6);
      return <div key={name} style={{position: 'absolute', left: x+(center[0]-x)*gather-46, top: y+(center[1]-y)*gather-46,
        opacity: appear*(1-gather), transform: `rotate(${Math.sin(f/35+i)*5}deg) scale(${appear*(1-gather*.8)})`}}><Provider name={name} size={92} /></div>;
    })}
    <div style={{position: 'absolute', width: '100%', top: 348, textAlign: 'center', opacity: 1-gather}}>
      <div style={{fontSize: 128, lineHeight: 1, fontWeight: 700, letterSpacing: -8, transform: `scale(${.85+ease(f,0,25)*.15})`}}>27</div>
      <div style={{fontSize: 38, fontWeight: 500, marginTop: 13}}>integrations. One workspace.</div>
    </div>
    <div style={{position: 'absolute', left: 870, top: 398, opacity: gather}}><TraceLogo size={180} delay={55} /></div>
    <div style={{position: 'absolute', width: '100%', bottom: 80, textAlign: 'center', color: C.muted, fontSize: 20, letterSpacing: 4}}>BUILT FOR PEOPLE WHO BUILD.</div>
  </AbsoluteFill>;
};

const Outro = () => {
  const f = useCurrentFrame(); const p = ease(f, 0, 28);
  return <AbsoluteFill style={{color: C.white}}>
    <Backdrop />
    <div style={{position: 'absolute', left: 115, top: 180, transform: `translateY(${(1-p)*30}px)`}}>
      <Wordmark size={51} />
      <div style={{marginTop: 55}}><Line text="Your stack." size={109} delay={4} /><Line text="In your pocket." size={109} delay={12} color="#6baeff" /></div>
      <div style={{display: 'flex', alignItems: 'center', gap: 27, marginTop: 55, opacity: ease(f,26,45)}}>
        <Img src={staticFile('app-store-badge.svg')} style={{width: 215, height: 72, objectFit: 'contain'}} />
        <div><div style={{fontSize: 30, fontWeight: 500}}>verceltics.com <span style={{color: C.blue}}>↗</span></div><div style={{fontSize: 18, color: C.muted, marginTop: 8}}>For iPhone & iPad · Open source</div></div>
      </div>
    </div>
    <div style={{position: 'absolute', left: 1480, top: 165, transform: `rotate(10deg) translateY(${(1-p)*100}px)`}}><Phone still="sites" width={304} /></div>
    <div style={{position: 'absolute', left: 1170, top: 125+Math.sin(f/80)*8, transform: `rotate(-7deg) translateY(${(1-p)*180}px)`}}><Phone still="analytics" width={380} /></div>
    <Footer />
  </AbsoluteFill>;
};

const Promo = () => <AbsoluteFill style={{fontFamily: '"Space Grotesk",sans-serif', background: C.ink}}>
  <style>{`@font-face{font-family:'Space Grotesk';src:url('${staticFile('space-grotesk.woff2')}') format('woff2');font-weight:300 700;}*{box-sizing:border-box;}`}</style>
  <Audio src={staticFile('launch-soundtrack.wav')} volume={f => lerp(f, [0, 12, 1035, 1079], [0, .9, .9, 0])} />
  <Sequence from={0} durationInFrames={90}><Hook /></Sequence>
  <Sequence from={90} durationInFrames={90}><Reveal /></Sequence>
  <Sequence from={180} durationInFrames={180}><Hosting /></Sequence>
  <Sequence from={360} durationInFrames={180}><Cloudflare /></Sequence>
  <Sequence from={540} durationInFrames={180}><Domains /></Sequence>
  <Sequence from={720} durationInFrames={180}><Sites /></Sequence>
  <Sequence from={900} durationInFrames={90}><Ecosystem /></Sequence>
  <Sequence from={990} durationInFrames={90}><Outro /></Sequence>
</AbsoluteFill>;

const Root = () => <>
  <Composition id="VercelticsLandscape" component={Promo} width={1920} height={1080} fps={30} durationInFrames={1080} />
  <Composition id="VercelticsAndroid" component={AndroidPromo} width={1920} height={1080} fps={ANDROID_FPS} durationInFrames={ANDROID_DURATION} />
</>;
registerRoot(Root);
