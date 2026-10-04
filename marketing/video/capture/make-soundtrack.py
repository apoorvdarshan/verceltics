#!/usr/bin/env python3
"""Original 36-second launch cue: 120 BPM, cut accents, no third-party samples."""
import math,random,struct,wave
from pathlib import Path
RATE,LENGTH=44100,36
rng=random.Random(42)
beat=.5
chords=[(174.614,207.652,261.626),(207.652,261.626,311.127),
        (155.563,195.998,233.082),(138.591,174.614,207.652),
        (174.614,207.652,261.626),(207.652,261.626,311.127)]
accents=[3,6,12,18,24,30,33]
output=Path(__file__).resolve().parents[1]/'public/launch-soundtrack.wav'
with wave.open(str(output),'wb') as wav:
 wav.setnchannels(2);wav.setsampwidth(2);wav.setframerate(RATE)
 buffer=bytearray();previous_noise=0.0
 for i in range(RATE*LENGTH):
  t=i/RATE;chord=chords[min(5,int(t/6))]
  phase=t%beat;eighth=t%(beat/2)
  sidechain=.6+.4*(1-math.exp(-phase*14))
  pad=sum(math.sin(2*math.pi*freq*t)+.2*math.sin(2*math.pi*(freq*2+.35)*t) for freq in chord)*.029*sidechain
  bass_freq=chord[0]/2
  bass=(math.sin(2*math.pi*bass_freq*t)+.22*math.sin(2*math.pi*bass_freq*2*t))*math.exp(-phase*5.5)*.12
  kick=.24*math.sin(2*math.pi*(45*phase+11*(1-math.exp(-phase*32))))*math.exp(-phase*22)
  n=rng.uniform(-1,1);high_noise=n-previous_noise;previous_noise=n
  hat=high_noise*.015*math.exp(-eighth*120)
  clap_phase=(t-.5)%1
  clap=high_noise*.046*math.exp(-clap_phase*40)
  notes=[0,2,1,2,0,1,2,1]
  freq=chord[notes[int(t/(beat/2))%8]]*2
  pluck=(math.sin(2*math.pi*freq*t)+.23*math.sin(2*math.pi*freq*2*t))*math.exp(-eighth*16)*.062
  rhythm=min(1,t/4.5)
  value=pad+bass*rhythm+(kick+hat+clap)*rhythm+pluck*.8
  for at in accents:
   d=t-at
   if -.35 < d < 0:
    value+=high_noise*.08*((d+.35)/.35)**2
   elif 0 <= d < .5:
    value+=.19*math.sin(2*math.pi*(39*d+5*(1-math.exp(-d*28))))*math.exp(-d*12)
    value+=.037*math.sin(2*math.pi*1046.5*d)*math.exp(-d*8)
  fade=min(1,t/.35,(36-t)/1.3)
  value*=max(0,fade)*1.24
  pan=.11*math.sin(t*.85)
  # Soft saturation leaves headroom for the transition impacts.
  left=int(math.tanh(value*(1-pan))*32767)
  right=int(math.tanh(value*(1+pan))*32767)
  buffer.extend(struct.pack('<hh',left,right))
  if len(buffer)>131072:wav.writeframes(buffer);buffer.clear()
 wav.writeframes(buffer)
print(output)
