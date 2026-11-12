const $=id=>document.getElementById(id);
const chat=$('chat'),status=$('statusText'),transcriptBox=$('transcript');
let history=[],recognition=null,listeningMode='off',screenImage=null,lastMode='read',loopStream=null,loopRecorder=null,loopChunks=[],externalStream=null,externalRecorder=null,externalChunks=[];
$('endpoint').value=localStorage.getItem('dominionEndpoint')||'';
$('save').onclick=()=>{const value=$('endpoint').value.trim();if(!value){setStatus('Enter a backend URL before saving');$('endpoint').focus();return;}localStorage.setItem('dominionEndpoint',value);setStatus('Backend URL saved on this computer');$('save').textContent='Saved ✓';setTimeout(()=>{$('save').textContent='Save';},1800);};
function setStatus(s,active=false){status.textContent=s;document.querySelector('.status').classList.toggle('listening',active);}
function speakAnswer(text){if(!('speechSynthesis' in window))return;window.speechSynthesis.cancel();const utterance=new SpeechSynthesisUtterance(text);utterance.lang=$('language')?.value||'en-NG';utterance.rate=1;window.speechSynthesis.speak(utterance);}
function addMessage(role,text){const empty=chat.querySelector('.welcome');if(empty)empty.remove();const d=document.createElement('div');d.className='message '+role;d.textContent=text;chat.appendChild(d);chat.scrollTop=chat.scrollHeight;}
async function ask(prompt,imageData,audioData,options={}){const endpoint=$('endpoint').value.trim();if(!endpoint){setStatus('Add your Netlify backend URL in settings');return;}
 if(!options.silent)addMessage('user',prompt);setStatus(options.silent?'Checking for new screen content…':'Processing…',true);
 const payload={prompt,history:history.slice(-12)};if(imageData){const [head,data]=imageData.split(',');payload.images=[{mimeType:(head.match(/data:(.*?);/)||[])[1]||'image/png',data}];}if(audioData)payload.audio=audioData;
 try{const response=await fetch(endpoint,{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(payload)});const body=await response.json();if(!response.ok)throw new Error(body.message||body.error||'Request failed ('+response.status+')');const answer=body.answer||body.text||body.response||body.content||body.output||JSON.stringify(body);if(String(answer).trim().toUpperCase()==='NO_NEW_QUESTION'){setStatus(options.silent?'Monitoring screen for changes':'Watching screen for the next question',true);return;}if(!options.silent)addMessage('assistant',answer);else addMessage('assistant','New screen update: '+answer);speakAnswer(answer);history.push({role:'user',text:prompt},{role:'model',text:answer});history=history.slice(-12);window.dominion.showAnswer(answer);setStatus('Answer displayed');}
 catch(e){addMessage('assistant','I could not get an answer: '+e.message);setStatus('Connection error');}}
$('askForm').onsubmit=e=>{e.preventDefault();const p=$('prompt').value.trim();if(!p)return;$('prompt').value='';ask(p,screenImage);screenImage=null;};
async function readScreen(prompt='Please inspect this screen carefully and answer the visible question(s).'){try{setStatus('Capturing screen…',true);screenImage=await window.dominion.captureScreen();await ask(prompt,screenImage);screenImage=null;}catch(e){setStatus('Screen capture failed: '+e.message);}}
$('screenBtn').onclick=()=>readScreen();
$('systemAudioBtn').onclick=()=>loopRecorder&&loopRecorder.state==='recording'?stopSystemAudio():startSystemAudio();

let screenMonitorTimer=null,lastScreenSnapshot='',screenMonitorBusy=false,firstScreenScan=true;
async function startContinuousScreenRead(){
 if(screenMonitorTimer)return;
 firstScreenScan=true;lastScreenSnapshot='';
 setStatus('Continuous screen reading started',true);
 const scan=async()=>{
  if(screenMonitorBusy)return;
  screenMonitorBusy=true;
  try{
   const shot=await window.dominion.captureScreen();
   if(shot===lastScreenSnapshot){setStatus('Monitoring screen — no change detected',true);return;}
   const initial=firstScreenScan;
   lastScreenSnapshot=shot;firstScreenScan=false;
   const prompt=initial
    ?'Read and describe the entire visible screen. Identify every visible question, explain relevant on-screen content, and answer all questions clearly. If there is no question, describe the important screen content.'
    :'Compare this screen with the previous screen context. Focus on newly appeared or changed content. If a new question is visible, answer it. If there is no new question or meaningful new content, return exactly NO_NEW_QUESTION.';
   await ask(prompt,shot,null,{silent:!initial});
  }catch(e){setStatus('Continuous screen reading error: '+e.message);stopContinuousScreenRead();}
  finally{screenMonitorBusy=false;}
 };
 await scan();
 screenMonitorTimer=setInterval(scan,2200);
}
function stopContinuousScreenRead(){
 if(screenMonitorTimer){clearInterval(screenMonitorTimer);screenMonitorTimer=null;}
 screenMonitorBusy=false;firstScreenScan=true;lastScreenSnapshot='';
 setStatus('Continuous screen reading stopped');
}


// External audio input: supports USB microphones, virtual microphones and AudioRelay-style devices.
async function refreshAudioInputs(){
 const select=$('audioInput'); if(!select)return;
 try{
  const devices=await navigator.mediaDevices.enumerateDevices();
  const inputs=devices.filter(d=>d.kind==='audioinput');
  select.innerHTML='';
  if(!inputs.length){select.add(new Option('No microphone detected',''));return;}
  inputs.forEach((d,i)=>select.add(new Option(d.label||('Audio input '+(i+1)),d.deviceId)));
  const saved=localStorage.getItem('dominionAudioInput');
  if(saved&&inputs.some(d=>d.deviceId===saved))select.value=saved;
 }catch(e){select.innerHTML='<option value="">Audio devices unavailable</option>';setStatus('Cannot list audio inputs: '+e.message);}
}
if(navigator.mediaDevices?.addEventListener)navigator.mediaDevices.addEventListener('devicechange',refreshAudioInputs);
$('audioInput')?.addEventListener('change',()=>{localStorage.setItem('dominionAudioInput',$('audioInput').value);setStatus('Audio input selected.');});
refreshAudioInputs();
$('externalAudioBtn').onclick=async()=>{
 if(externalRecorder&&externalRecorder.state==='recording'){externalRecorder.stop();$('externalAudioBtn').textContent='🎧 Use external audio';return;}
 try{
  const deviceId=$('audioInput').value;
  if(!deviceId){setStatus('Select an audio input first. Connect AudioRelay or a USB microphone, then refresh devices.');await refreshAudioInputs();return;}
  externalStream=await navigator.mediaDevices.getUserMedia({audio:{deviceId:{exact:deviceId},echoCancellation:false,noiseSuppression:false,autoGainControl:false}});
  const mimeType=MediaRecorder.isTypeSupported('audio/webm;codecs=opus')?'audio/webm;codecs=opus':'audio/webm';
  externalRecorder=new MediaRecorder(externalStream,{mimeType});externalChunks=[];
  externalRecorder.ondataavailable=e=>{if(e.data.size)externalChunks.push(e.data);};
  externalRecorder.onerror=e=>setStatus('External audio error: '+(e.error?.message||'recording failed'));
  externalRecorder.onstop=async()=>{
   try{
    const blob=new Blob(externalChunks,{type:'audio/webm'});
    const data=await new Promise((resolve,reject)=>{const fr=new FileReader();fr.onload=()=>resolve(String(fr.result).split(',')[1]);fr.onerror=()=>reject(new Error('Could not prepare audio'));fr.readAsDataURL(blob);});
    if(data.length>3800000)throw new Error('Audio is too large; record a shorter segment.');
    await ask('Transcribe and explain the audio captured from my selected external audio input.',null,{mimeType:'audio/webm',data});
   }catch(e){setStatus('External audio failed: '+e.message);}
   finally{externalStream?.getTracks().forEach(t=>t.stop());externalStream=null;externalRecorder=null;externalChunks=[];}
  };
  externalRecorder.start(500);$('externalAudioBtn').textContent='■ Stop external audio';setStatus('Recording from selected external audio input…',true);
  setTimeout(()=>{if(externalRecorder?.state==='recording'){setStatus('Audio segment complete — processing…',true);externalRecorder.stop();$('externalAudioBtn').textContent='🎧 Use external audio';}},15000);
 }catch(e){setStatus('Cannot access selected audio input: '+e.message+'. Check Windows microphone privacy permissions and close other apps using it.');}
};

function cropRegion(data,rect){return new Promise((resolve,reject)=>{const img=new Image();img.onload=()=>{const sx=img.naturalWidth/window.screen.width,sy=img.naturalHeight/window.screen.height;const c=document.createElement('canvas');c.width=Math.max(1,Math.round(rect.width*sx));c.height=Math.max(1,Math.round(rect.height*sy));c.getContext('2d').drawImage(img,Math.round(rect.x*sx),Math.round(rect.y*sy),c.width,c.height,0,0,c.width,c.height);resolve(c.toDataURL('image/png'));};img.onerror=reject;img.src=data;});}
window.dominion.onRegionSelected(async payload=>{if(!payload)return;try{setStatus('Capturing selected region…',true);const crop=await window.dominion.captureRegion(payload);await ask('Read and explain the selected screen region. Answer any question shown.',crop);}catch(e){setStatus('Could not read selected region: '+e.message);}});
function startRecognition(mode){const SR=window.SpeechRecognition||window.webkitSpeechRecognition;if(!SR){setStatus('Windows speech recognition unavailable in this build. Check microphone permissions or use Windows voice typing.');return;}
 if(recognition){try{recognition.stop();}catch{}recognition=null;}listeningMode=mode;recognition=new SR();recognition.lang=$('language').value;recognition.continuous=true;recognition.interimResults=true;
 recognition.onstart=()=>setStatus(mode==='transcribe'?'Transcribing — speak now':'Listening — speak now',true);
 recognition.onerror=e=>setStatus('Microphone: '+e.error);
 recognition.onresult=e=>{let final='',interim='';for(let i=e.resultIndex;i<e.results.length;i++){const t=e.results[i][0].transcript;if(e.results[i].isFinal)final+=t;else interim+=t;}if(interim){transcriptBox.hidden=false;transcriptBox.textContent=interim;}if(final.trim()){transcriptBox.hidden=false;transcriptBox.textContent=final;if(mode==='transcribe')addMessage('user','Transcript: '+final);else if(mode==='listen')ask('I am listening to the surrounding audio. Interpret this spoken content and relate it to the screen if relevant: '+final,screenImage);else ask(final,screenImage);}};
 recognition.onend=()=>{if(listeningMode!=='off'){try{recognition.start();}catch{setStatus('Listening paused');}}else setStatus('Ready');};try{recognition.start();}catch(e){setStatus('Could not start microphone: '+e.message);}}
const MAX_PC_AUDIO_MS=15000;
let pcAudioTimer=null;
async function startSystemAudio(){try{loopStream=await navigator.mediaDevices.getDisplayMedia({video:true,audio:true});const audioTracks=loopStream.getAudioTracks();if(!audioTracks.length){setStatus('Windows did not provide system audio. Select a screen and enable audio sharing.');loopStream.getTracks().forEach(t=>t.stop());return;}const mimeType=MediaRecorder.isTypeSupported('audio/webm;codecs=opus')?'audio/webm;codecs=opus':'audio/webm';loopRecorder=new MediaRecorder(new MediaStream(audioTracks),{mimeType});loopChunks=[];loopRecorder.ondataavailable=e=>{if(e.data.size)loopChunks.push(e.data);};loopRecorder.onerror=e=>{setStatus('PC audio recording error: '+(e.error?.message||'unknown error'));stopSystemAudio();};loopRecorder.onstop=async()=>{clearTimeout(pcAudioTimer);pcAudioTimer=null;try{const blob=new Blob(loopChunks,{type:'audio/webm'});if(!blob.size){setStatus('No PC audio was captured. Try again and check Windows audio sharing.');return;}const data=await new Promise((resolve,reject)=>{const fr=new FileReader();fr.onload=()=>resolve(String(fr.result).split(',')[1]);fr.onerror=()=>reject(new Error('Could not prepare audio'));fr.readAsDataURL(blob);});if(data.length>3_800_000){setStatus('Audio is too large. Record a shorter segment and try again.');return;}await ask('Transcribe and explain the audio playing on my Windows computer.',null,{mimeType:'audio/webm',data});}catch(e){setStatus('Could not send PC audio: '+e.message);}finally{loopStream?.getTracks().forEach(t=>t.stop());loopStream=null;loopRecorder=null;loopChunks=[];$('systemAudioBtn').textContent='♫ Listen to PC audio';}};loopRecorder.start(1000);setStatus('Capturing PC audio — maximum 15 seconds per segment',true);$('systemAudioBtn').textContent='■ Stop PC audio';pcAudioTimer=setTimeout(()=>{if(loopRecorder?.state==='recording'){setStatus('15-second audio segment complete — processing…',true);loopRecorder.stop();}},MAX_PC_AUDIO_MS);}catch(e){setStatus('PC audio capture: '+e.message);$('systemAudioBtn').textContent='♫ Listen to PC audio';}}
function stopSystemAudio(){clearTimeout(pcAudioTimer);pcAudioTimer=null;if(loopRecorder&&loopRecorder.state!=='inactive')loopRecorder.stop();else{$('systemAudioBtn').textContent='♫ Listen to PC audio';}}
function stopRecognition(){listeningMode='off';if(recognition){try{recognition.stop();}catch{}recognition=null;}setStatus('Ready');}
$('micBtn').onclick=()=>listeningMode==='voice'?stopRecognition():startRecognition('voice');
$('transcribeBtn').onclick=()=>listeningMode==='transcribe'?stopRecognition():startRecognition('transcribe');
window.dominion.onToggleMic(()=>listeningMode==='voice'?stopRecognition():startRecognition('voice'));
window.dominion.onRunMode(async mode=>{lastMode=mode;if(mode==='read')startContinuousScreenRead();else if(mode==='stop-read')stopContinuousScreenRead();else if(mode==='region'){}else if(mode==='listen')startRecognition('listen');else if(mode==='readlisten'){startContinuousScreenRead();startRecognition('listen');}else if(mode==='transcribe')startRecognition('transcribe');});
const bubbleToggle=$('enableBubble');if(bubbleToggle){bubbleToggle.checked=localStorage.getItem('dominionFloatingEnabled')==='true';bubbleToggle.addEventListener('change',()=>{localStorage.setItem('dominionFloatingEnabled',String(bubbleToggle.checked));window.dominion.setFloatingEnabled(bubbleToggle.checked);setStatus(bubbleToggle.checked?'One floating bubble enabled. You can minimize this window.':'Floating bubble disabled.');});}
$('pin').onclick=()=>{window.dominion.setAlwaysOnTop(true);setStatus('Always on top enabled');};
$('min').onclick=()=>window.dominion.minimize();$('close').onclick=()=>window.dominion.close();
