import React from 'react';
import {AbsoluteFill, Audio, Composition, Img, OffthreadVideo, Sequence, interpolate, registerRoot, spring, staticFile, useCurrentFrame, useVideoConfig} from 'remotion';

const blue = '#1689ff';
const purple = '#af5bff';
const ink = '#10131b';
const paper = '#f5f3ee';
const clamp = {extrapolateLeft: 'clamp', extrapolateRight: 'clamp'} as const;

const Brand = ({dark = false, large = false}: {dark?: boolean; large?: boolean}) => <div style={{display:'flex',alignItems:'center',gap:large?28:16}}>
  <div style={{width:large?110:64,height:large?110:64,background:'#07080b',borderRadius:large?28:17,display:'flex',alignItems:'center',justifyContent:'center'}}><Img src={staticFile('logo.png')} style={{width:'88%',height:'88%',objectFit:'contain'}}/></div>
  <span style={{fontSize:large?62:30,fontWeight:700,letterSpacing:-1.8,color:dark?'white':ink}}>Verceltics</span>
</div>;

const Phone = ({clip,width=408,still,rotation=0}: {clip?:string;width?:number;still?:string;rotation?:number}) => {
  const f=useCurrentFrame();
  const {fps}=useVideoConfig();
  const enter=spring({frame:f,fps,config:{damping:22,stiffness:90}});
  return <div style={{width,height:width*2.166,background:'#060608',borderRadius:width*.13,padding:9,border:'2px solid #474a53',boxShadow:'0 38px 85px #00000045, inset 0 0 0 3px #17191f',transform:`translateY(${(1-enter)*70}px) rotate(${rotation}deg) scale(${.96+enter*.04})`,opacity:enter}}>
    <div style={{width:'100%',height:'100%',position:'relative',borderRadius:width*.11,overflow:'hidden',background:'#000'}}>
      {clip ? <OffthreadVideo src={staticFile(`footage/${clip}.mp4`)} muted style={{width:'100%',height:'100%',objectFit:'cover'}}/> : <Img src={staticFile(`footage/${still}.png`)} style={{width:'100%',height:'100%',objectFit:'cover'}}/>}
    </div>
  </div>;
};

const FlowLines = ({dark=false}: {dark?:boolean}) => {
  const f=useCurrentFrame();
  const draw=interpolate(f,[0,75],[1,0],clamp);
  return <svg width="1920" height="1080" viewBox="0 0 1920 1080" style={{position:'absolute',inset:0,opacity:dark?.16:.12}}>
    {[[blue,'M-40 320H280C570 320 680 780 1050 780H1970'],[dark?'white':'#738092','M-40 540H620'],[purple,'M-40 790H310C570 790 660 650 820 540']].map(([stroke,d],i)=><path key={i} d={d} fill="none" stroke={stroke} strokeWidth="36" strokeLinecap="round" pathLength="1" strokeDasharray="1" strokeDashoffset={draw}/>) }
  </svg>;
};

const Intro = () => {
  const f=useCurrentFrame(); const {fps}=useVideoConfig();
  const reveal=spring({frame:f-8,fps,config:{damping:24}});
  return <AbsoluteFill style={{background:paper,color:ink,padding:110}}>
    <FlowLines/>
    <div style={{position:'relative',zIndex:2}}><Brand/></div>
    <div style={{position:'absolute',left:110,top:310,opacity:reveal,transform:`translateY(${(1-reveal)*30}px)`}}>
      <div style={{fontSize:116,lineHeight:1.02,fontWeight:700,letterSpacing:-7}}>Your stack.<br/><span style={{color:'#1469ed'}}>In your pocket.</span></div>
      <p style={{fontSize:30,color:'#5c6471',marginTop:34}}>Hosting, domains & analytics on iPhone and iPad.</p>
    </div>
    <div style={{position:'absolute',right:130,top:190,transform:'rotate(5deg)'}}><Phone still="vercel" width={340}/></div>
    <div style={{position:'absolute',left:114,bottom:100,display:'flex',gap:16,fontSize:20,fontWeight:500}}>{['27 integrations','Native SwiftUI','Open source'].map(t=><span key={t} style={{padding:'13px 22px',border:'1px solid #c8ccd0',borderRadius:99,background:'#ffffffa0'}}>{t}</span>)}</div>
  </AbsoluteFill>;
};

const chapters = [
  {number:'01',category:'HOSTING',title:['Keep every','project close.'],description:'Vercel projects, deployments and web analytics.',clip:'vercel',accent:blue,dark:true,tags:['Projects','Traffic','Deployments'],from:90,duration:180},
  {number:'02',category:'CLOUDFLARE',title:['See the edge.','Stay in control.'],description:'Zones, DNS, Pages and Workers in one workspace.',clip:'cloudflare',accent:'#ed862c',dark:false,tags:['Zones','DNS','Workers'],from:270,duration:180},
  {number:'03',category:'DOMAINS',title:['Your domains.','No surprises.'],description:'Check expiry, auto renewal and domain privacy.',clip:'registrars',accent:'#ad65fd',dark:true,tags:['Namecheap','Name.com','Expiry health'],from:450,duration:180},
  {number:'04',category:'SITES',title:['Know how your','sites are doing.'],description:'Search, analytics, speed and uptime at a glance.',clip:'sites',accent:'#1469ed',dark:false,tags:['Search Console','PageSpeed','Uptime'],from:630,duration:150},
];

type ChapterData = typeof chapters[number];
const Chapter = ({data}:{data:ChapterData}) => {
  const f=useCurrentFrame();const {fps}=useVideoConfig();const p=spring({frame:f-6,fps,config:{damping:25}});
  const dark=data.dark;const color=dark?'#f7f8fc':ink;
  return <AbsoluteFill style={{background:dark?'#0c101a':paper,color}}>
    <FlowLines dark={dark}/>
    <div style={{position:'absolute',left:110,top:66}}><Brand dark={dark}/></div>
    <div style={{position:'absolute',left:112,top:253,opacity:p,transform:`translateX(${(1-p)*-35}px)`}}>
      <div style={{display:'flex',alignItems:'center',gap:14,fontSize:20,letterSpacing:4,fontWeight:700,color:data.accent}}><span style={{height:9,width:9,borderRadius:10,background:data.accent}}/>{data.category}</div>
      <h1 style={{fontSize:100,lineHeight:1.06,fontWeight:700,letterSpacing:-5,margin:'29px 0 0'}}>{data.title[0]}<br/>{data.title[1]}</h1>
      <p style={{fontSize:29,lineHeight:1.5,maxWidth:735,marginTop:28,color:dark?'#a7afc2':'#606b79'}}>{data.description}</p>
      <div style={{display:'flex',gap:12,marginTop:38,fontSize:19}}>{data.tags.map(tag=><span key={tag} style={{border:`1px solid ${dark?'#343c50':'#c8cdd5'}`,borderRadius:99,padding:'11px 19px',background:dark?'#151c2a':'#ffffff80'}}>{tag}</span>)}</div>
    </div>
    <div style={{position:'absolute',left:1260,top:103}}><Phone clip={data.clip}/></div>
    <div style={{position:'absolute',left:114,bottom:92,fontSize:17,letterSpacing:1,color:dark?'#8b96aa':'#747e8d'}}>iOS 2.1 preview · Demo data</div>
    <div style={{position:'absolute',right:84,bottom:93,fontSize:22,color:data.accent,fontWeight:600}}>{data.number} / 04</div>
    <div style={{position:'absolute',bottom:0,left:0,height:5,width:`${interpolate(f,[0,data.duration],[0,100],clamp)}%`,background:data.accent}}/>
  </AbsoluteFill>;
};

const Outro = () => {
  const f=useCurrentFrame();const {fps}=useVideoConfig();const p=spring({frame:f,fps,config:{damping:24}});
  return <AbsoluteFill style={{background:'#0b0e16',color:'white'}}>
    <FlowLines dark/>
    <div style={{position:'absolute',left:110,top:180,opacity:p,transform:`translateY(${(1-p)*24}px)`}}>
      <Brand dark large/>
      <h1 style={{fontSize:99,lineHeight:1.07,letterSpacing:-5,marginTop:45}}>Close the laptop.<br/><span style={{color:'#6eb1ff'}}>Keep the context.</span></h1>
      <div style={{fontSize:38,marginTop:48,display:'flex',alignItems:'center',gap:22}}>verceltics.com <span style={{color:blue}}>↗</span></div>
      <div style={{fontSize:23,color:'#a8b1c4',marginTop:24}}>For iPhone & iPad · Open source</div>
    </div>
    <div style={{position:'absolute',left:1410,top:120}}><Phone still="sites" width={310} rotation={8}/></div>
    <div style={{position:'absolute',left:1180,top:240}}><Phone still="analytics" width={335} rotation={-7}/></div>
    <div style={{position:'absolute',left:112,bottom:92,fontSize:17,color:'#8b96aa'}}>iOS 2.1 preview · Demo data</div>
  </AbsoluteFill>;
};

const Fade = ({children,duration}:{children:React.ReactNode;duration:number}) => {
 const frame=useCurrentFrame();
 return <AbsoluteFill style={{opacity:interpolate(frame,[0,9,duration-9,duration],[0,1,1,0],clamp)}}>{children}</AbsoluteFill>;
};

const Promo = () => <AbsoluteFill style={{background:ink,fontFamily:'"Space Grotesk", sans-serif'}}>
  <style>{`@font-face{font-family:'Space Grotesk';src:url('${staticFile('space-grotesk.woff2')}') format('woff2');font-weight:300 700;}`}</style>
  <Audio src={staticFile('soundtrack.wav')} volume={(f)=>interpolate(f,[0,24,840,899],[0,.8,.8,0],clamp)}/>
  <Sequence from={0} durationInFrames={90}><Fade duration={90}><Intro/></Fade></Sequence>
  {chapters.map(data=><Sequence key={data.number} from={data.from} durationInFrames={data.duration}><Fade duration={data.duration}><Chapter data={data}/></Fade></Sequence>)}
  <Sequence from={780} durationInFrames={120}><Fade duration={120}><Outro/></Fade></Sequence>
</AbsoluteFill>;
const Root = () => <Composition id="VercelticsLandscape" component={Promo} durationInFrames={900} fps={30} width={1920} height={1080}/>;
registerRoot(Root);
