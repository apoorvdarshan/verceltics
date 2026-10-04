#!/usr/bin/env python3
"""Original deterministic 30-second ambient electronic cue; no sampled music."""
import math, random, struct, wave
from pathlib import Path
RATE, LENGTH = 44100, 30
rng = random.Random(42)
# F minor → D-flat → A-flat → E-flat, with sparse plucked upper notes.
chords = [(174.614,207.652,261.626),(138.591,174.614,207.652),(207.652,261.626,311.127),(155.563,195.998,233.082)]
beat = 60 / 100
output = Path(__file__).resolve().parents[1] / 'public/soundtrack.wav'
with wave.open(str(output),'wb') as wav:
    wav.setnchannels(2);wav.setsampwidth(2);wav.setframerate(RATE)
    buffer = bytearray()
    for i in range(RATE*LENGTH):
        t=i/RATE
        chord = chords[min(3,int(t / 7.5))]
        pad = sum(math.sin(2*math.pi*freq*t)+.3*math.sin(2*math.pi*(freq*2+.25)*t) for freq in chord)*.026
        pulse_t=t % beat
        bass=math.sin(2*math.pi*chord[0]/2*t)*math.exp(-pulse_t*5)*.065
        kick=.11*math.sin(2*math.pi*(55*pulse_t+8*(1-math.exp(-pulse_t*25))))*math.exp(-pulse_t*20)
        sub=t % (beat/2)
        note=chord[int(t/(beat/2))%3]*2
        pluck=(math.sin(2*math.pi*note*t)+.25*math.sin(2*math.pi*note*2*t))*math.exp(-sub*10)*.038
        hat=rng.uniform(-1,1)*math.exp(-sub*110)*.012
        envelope=min(1,t/1.1,(30-t)/1.7)
        value=(pad+bass+kick+pluck+hat)*max(0,envelope)
        pan=.13*math.sin(t*.7)
        left=int(max(-1,min(1,value*(1-pan)))*32767)
        right=int(max(-1,min(1,value*(1+pan)))*32767)
        buffer.extend(struct.pack('<hh',left,right))
        if len(buffer)>131072:
            wav.writeframes(buffer);buffer.clear()
    wav.writeframes(buffer)
print(output)
