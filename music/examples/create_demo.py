from pathlib import Path
import json
out=Path('/mnt/data/gemma-multitrack')
chords=['C','G','Am','F','C','G','F','G']
plan={'title':'Eine neue Straße erwacht (Demo)','mood':'warm, playful, hopeful','tempoBpm':108,'key':'C major','timeSignature':'4/4','bars':8,'chords':chords,'arrangementNotes':'Original cozy city fanfare with repeated question-and-answer motif; synthetic preview generated locally, not by Gemma.'}
melody=[]
def emit(bar, notes):
 for offset,duration,pitch in notes:melody.append({'beat':bar*4+offset,'duration':duration,'pitch':pitch,'velocity':83})
emit(0,[(0,.5,72),(.5,.5,76),(1,1,79),(2,.5,76),(2.5,.5,74),(3,1,72)])
emit(1,[(0,1,71),(1,.5,74),(1.5,.5,79),(2,1,81),(3,1,79)])
emit(2,[(0,.5,76),(.5,.5,72),(1,1,69),(2,1,72),(3,1,76)])
emit(3,[(0,.5,77),(.5,.5,76),(1,1,72),(2,2,69)])
emit(4,[(0,.5,72),(.5,.5,76),(1,1,79),(2,.5,76),(2.5,.5,74),(3,1,72)])
emit(5,[(0,1,71),(1,.5,74),(1.5,.5,79),(2,1,83),(3,1,79)])
emit(6,[(0,.5,77),(.5,.5,81),(1,1,84),(2,1,81),(3,1,77)])
emit(7,[(0,.5,79),(.5,.5,74),(1,1,71),(2,1,74),(3,1,79)])
triads={'C':[60,64,67],'G':[59,62,67],'Am':[60,64,69],'F':[60,65,69]}
harmony=[]
for bar,chord in enumerate(chords):
 for p in triads[chord]:harmony.append({'beat':bar*4,'duration':3.8,'pitch':p,'velocity':48})
roots={'C':36,'G':43,'Am':45,'F':41}
bass=[]
for bar,chord in enumerate(chords):
 r=roots[chord]
 bass.extend([{'beat':bar*4,'duration':1.7,'pitch':r,'velocity':74},
              {'beat':bar*4+2,'duration':1.5,'pitch':r+7,'velocity':67}])
drums=[]
for bar in range(8):
 for b in range(4):
  drums.append({'beat':bar*4+b,'duration':.25,'pitch':42,'velocity':43 if b%2 else 51})
  if b%2==0:drums.append({'beat':bar*4+b,'duration':.25,'pitch':36,'velocity':62})
  else:drums.append({'beat':bar*4+b,'duration':.25,'pitch':38,'velocity':45})
parts=[('melody',10,0,melody),('harmony',48,1,harmony),('bass',32,2,bass),('drums',0,9,drums)]
lines=['tempo\t108']
for name,prog,channel,notes in parts:
 lines.append(f'track\t{name}\t{prog}\t{channel}')
 for n in notes:
  lines.append(f'note\t{name}\t{int(n["beat"]*480)}\t{int(n["duration"]*480)}\t{n["pitch"]}\t{n["velocity"]}')
(out/'demo-arrangement.tsv').write_text('\n'.join(lines)+'\n',encoding='utf-8-sig')
(out/'demo-composition.json').write_text(json.dumps({'composition':plan,'tracks':{name:{'part':name,'program':prog,'notes':notes} for name,prog,ch,notes in parts}},indent=2),encoding='utf-8')
print('tracks',[(k,len(v))for k,_,_,v in parts])
