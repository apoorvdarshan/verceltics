#!/usr/bin/env python3
"""Record the isolated demo build, then normalize source footage for Remotion."""
import argparse,json,signal,subprocess,time
from pathlib import Path
from normalize import normalize
p=argparse.ArgumentParser();p.add_argument('--simulator',required=True);p.add_argument('--raw-dir',default='/tmp/verceltics-video-recordings');args=p.parse_args()
root=Path(__file__).resolve().parents[1];out=root/'public/footage';raw=Path(args.raw_dir);raw.mkdir(parents=True,exist_ok=True);out.mkdir(parents=True,exist_ok=True)
bundle='com.apoorvdarshan.verceltics.marketingdebug'
def sim(*parts,check=True):return subprocess.run(['xcrun','simctl',*parts],check=check,capture_output=True,text=True,timeout=30)
sim('status_bar',args.simulator,'override','--time','9:41','--dataNetwork','wifi','--wifiMode','active','--wifiBars','3','--batteryState','discharging','--batteryLevel','100')
sim('ui',args.simulator,'appearance','dark')
manifest=[]
for name,launch_args in [('vercel',[]),('cloudflare',['-cloudflare']),('registrars',['-registrars']),('sites',['-sites'])]:
    print(f'Recording {name}…',flush=True)
    sim('terminate',args.simulator,bundle,check=False)
    # Recording begins before launch, so the transition and the stable screen
    # are captured. The startup section is excluded from the final source clip.
    path=raw/f'{name}.mov'
    recording=subprocess.Popen(['xcrun','simctl','io',args.simulator,'recordVideo','--codec=h264','--force',str(path)],stderr=subprocess.PIPE,text=True)
    try:
        while True:
            line=recording.stderr.readline()
            if 'Recording started' in line:break
            if recording.poll() is not None:raise RuntimeError('Recording failed: '+line)
        sim('launch',args.simulator,bundle,'-hasShownOnboardingRatePrompt','YES','-app.appearance','dark','-autoCapture',*launch_args)
        time.sleep(22)
    finally:
        recording.send_signal(signal.SIGINT)
        recording.communicate(timeout=30)
    manifest.append(normalize(path,out,name))
    print(f'Finished {name}',flush=True)
(root/'capture/manifest.json').write_text(json.dumps(manifest,indent=2)+'\n')
print('Capture complete.',flush=True)
