#!/usr/bin/env python3
"""Trim Simulator launch frames and hold the last frame of sparse recordings."""
import json,re,subprocess
from pathlib import Path

def normalize(path: Path, output: Path, name: str) -> dict:
    # Simulator recordVideo writes variable-rate frames only when the display
    # changes. Its last timestamp can precede the wall-clock recording stop.
    result=subprocess.run(['ffmpeg','-i',str(path),'-vf',
        'fps=5,scale=64:140,crop=64:116:0:18,signalstats,metadata=print:key=lavfi.signalstats.YAVG',
        '-f','null','-'],capture_output=True,text=True,check=True)
    levels=[(float(t),float(y)) for t,y in re.findall(r'pts_time:([\d.]+).*?YAVG=([\d.]+)',result.stderr,re.S)]
    saw_launch=False; ready=None
    for i,(t,y) in enumerate(levels):
        if y < 17.2:saw_launch=True
        if saw_launch and y > 32 and i+2 < len(levels) and all(v > 32 for _,v in levels[i:i+3]):
            ready=t;break
    if ready is None:raise RuntimeError(f'No populated dashboard detected in {path}; inspect the capture.')
    trim=ready+0.8
    output.mkdir(parents=True,exist_ok=True)
    edits_file=Path(__file__).with_name('edits.json')
    cuts=json.loads(edits_file.read_text()).get(name,[]) if edits_file.exists() else []
    filters=f'fps=30,trim=start={trim},setpts=PTS-STARTPTS'
    if cuts:
        expression='+'.join(f'between(t,{a},{b})' for a,b in cuts)
        filters+=f",select='not({expression})',setpts=N/(30*TB)"
    filters+=',scale=900:-2,tpad=stop_mode=clone:stop_duration=7'
    subprocess.run(['ffmpeg','-y','-loglevel','error','-i',str(path),'-t','7',
        '-vf',filters,
        '-c:v','libx264','-crf','20','-pix_fmt','yuv420p','-an','-movflags','+faststart',str(output/f'{name}.mp4')],check=True)
    subprocess.run(['ffmpeg','-y','-loglevel','error','-ss','0.75','-i',str(output/f'{name}.mp4'),'-frames:v','1',str(output/f'{name}.png')],check=True)
    if name=='vercel':
        subprocess.run(['ffmpeg','-y','-loglevel','error','-ss','6','-i',str(output/f'{name}.mp4'),'-frames:v','1',str(output/'analytics.png')],check=True)
    return {'name':name,'source':path.name,'trimStartSeconds':round(trim,3),'durationSeconds':7,'removedWaitRangesSeconds':cuts,'fictionalData':True}

if __name__=='__main__':
    import argparse
    p=argparse.ArgumentParser();p.add_argument('raw_dir',type=Path);args=p.parse_args()
    root=Path(__file__).resolve().parents[1]
    manifest=[normalize(args.raw_dir/f'{name}.mov',root/'public/footage',name) for name in ['vercel','cloudflare','registrars','sites']]
    (root/'capture/manifest.json').write_text(json.dumps(manifest,indent=2)+'\n')
    print(json.dumps(manifest,indent=2))
