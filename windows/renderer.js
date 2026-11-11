const $=id=>document.getElementById(id);
const chat=$('chat'), status=$('statusText'), dot=$('dot'), transcriptBox=$('transcript');
let history=[], recognition=null, listeningMode='off', lastSpeechAt=0, screenImage=null;
const storedEndpoint=localStorage.getItem('dominionEndpoint')||'';
$('endpoint').value=storedEndpoint;
$('save').onclick=()=>{localStorage.setItem('dominionEndpoint',$('endpoint').value.trim());setStatus('Backend URL saved');};
function setStatus(s,active=false){status.textContent=s;document.querySelector('.status').classList.toggle('listening',active);}
function addMessage(role,text){const empty=chat.querySelector('.welcome');if(empty)empty.remove();const d=document.createElement('div');d.className='message '+role;d.textContent=text;chat.appendChild(d);chat.scrollTop=chat.scrollHeight;}
async function ask(prompt,imageData){const endpoint=$('endpoint').value.trim();if(!endpoint){setStatus('Add your Netlify backend URL in settings');return;}
 addMessage('user',prompt);setStatus('Thinking…');
 const payload={prompt,history:history.slice(-12)};if(imageData){const [head,data]=imageData.split(',');payload.images=[{mimeType:head.match(/data:(.*?);/)[1],data}];}
 try{const response=await fetch(endpoint,{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(payload)});const body=await response.json();if(!response.ok)throw new Error(body.message||body.error||'Request failed ('+response.status+')');const answer=body.answer||body.text||body.response||body.content||JSON.stringify(body);addMessage('assistant',answer);history.push({role:'user',text:prompt},{role:'model',text:answer});history=history.slice(-12);setStatus('Ready');}
 catch(e){addMessage('assistant','I could not get an answer: '+e.message);setStatus('Connection error');}}
$('askForm').onsubmit=e=>{e.preventDefault();const p=$('prompt').value.trim();if(!p)return;$('prompt').value='';ask(p,screenImage);screenImage=null;};
$('screenBtn').onclick=async()=>{try{const sources=await window.dominion.captureScreen();if(!sources.length)throw new Error('No screen found');screenImage=sources[0].dataUrl;await ask('Please inspect this screen carefully and answer the visible question(s).',screenImage);screenImage=null;}catch(e){setStatus('Screen capture failed: '+e.message);}};
function startRecognition(mode){const SR=window.SpeechRecognition||window.webkitSpeechRecognition;if(!SR){setStatus('Speech recognition is unavailable. Use Windows voice typing or a supported Chromium speech service.');return;}
 if(recognition){recognition.stop();recognition=null;}listeningMode=mode;recognition=new SR();recognition.lang=$('language').value;recognition.continuous=true;recognition.interimResults=true;
 recognition.onstart=()=>setStatus(mode==='transcribe'?'Transcribing — speak now':'Listening — speak your question',true);
 recognition.onerror=e=>{setStatus('Microphone: '+e.error);listeningMode='off';};
 recognition.onresult=e=>{let final='';let interim='';for(let i=e.resultIndex;i<e.results.length;i++){const t=e.results[i][0].transcript;if(e.results[i].isFinal)final+=t;else interim+=t;}if(interim){transcriptBox.hidden=false;transcriptBox.textContent=interim;}if(final.trim()){lastSpeechAt=Date.now();transcriptBox.hidden=false;transcriptBox.textContent=final;if(mode==='transcribe'){addMessage('user','Transcript: '+final);}else{ask(final);}}};
 recognition.onend=()=>{if(listeningMode!=='off'){try{recognition.start();}catch{setStatus('Listening paused');}}else setStatus('Ready');};
 try{recognition.start();}catch(e){setStatus('Could not start microphone: '+e.message);}
}
function stopRecognition(){listeningMode='off';if(recognition){recognition.stop();recognition=null;}setStatus('Ready');}
$('micBtn').onclick=()=>{if(listeningMode==='voice')stopRecognition();else startRecognition('voice');};
$('transcribeBtn').onclick=()=>{if(listeningMode==='transcribe')stopRecognition();else startRecognition('transcribe');};
window.dominion.onToggleMic(()=>{if(listeningMode==='voice')stopRecognition();else startRecognition('voice');});
$('pin').onclick=()=>{window.dominion.setAlwaysOnTop(true);setStatus('Always on top enabled');};
$('min').onclick=()=>window.dominion.minimize();$('close').onclick=()=>window.dominion.close();