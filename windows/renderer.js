const $=id=>document.getElementById(id);
const chat=$('chat'),status=$('statusText'),transcriptBox=$('transcript');
let history=[],recognition=null,listeningMode='off',screenImage=null,lastMode='read';
$('endpoint').value=localStorage.getItem('dominionEndpoint')||'';
$('save').onclick=()=>{localStorage.setItem('dominionEndpoint',$('endpoint').value.trim());setStatus('Backend URL saved');};
function setStatus(s,active=false){status.textContent=s;document.querySelector('.status').classList.toggle('listening',active);}
function addMessage(role,text){const empty=chat.querySelector('.welcome');if(empty)empty.remove();const d=document.createElement('div');d.className='message '+role;d.textContent=text;chat.appendChild(d);chat.scrollTop=chat.scrollHeight;}
async function ask(prompt,imageData){const endpoint=$('endpoint').value.trim();if(!endpoint){setStatus('Add your Netlify backend URL in settings');return;}
 addMessage('user',prompt);setStatus('Processing…',true);
 const payload={prompt,history:history.slice(-12)};if(imageData){const [head,data]=imageData.split(',');payload.images=[{mimeType:(head.match(/data:(.*?);/)||[])[1]||'image/png',data}];}
 try{const response=await fetch(endpoint,{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(payload)});const body=await response.json();if(!response.ok)throw new Error(body.message||body.error||'Request failed ('+response.status+')');const answer=body.answer||body.text||body.response||body.content||body.output||JSON.stringify(body);addMessage('assistant',answer);history.push({role:'user',text:prompt},{role:'model',text:answer});history=history.slice(-12);window.dominion.showAnswer(answer);setStatus('Ready');}
 catch(e){addMessage('assistant','I could not get an answer: '+e.message);setStatus('Connection error');}}
$('askForm').onsubmit=e=>{e.preventDefault();const p=$('prompt').value.trim();if(!p)return;$('prompt').value='';ask(p,screenImage);screenImage=null;};
async function readScreen(prompt='Please inspect this screen carefully and answer the visible question(s).'){try{setStatus('Capturing screen…',true);screenImage=await window.dominion.captureScreen();await ask(prompt,screenImage);screenImage=null;}catch(e){setStatus('Screen capture failed: '+e.message);}}
$('screenBtn').onclick=()=>readScreen();
function cropRegion(data,rect){return new Promise((resolve,reject)=>{const img=new Image();img.onload=()=>{const sx=img.naturalWidth/window.screen.width,sy=img.naturalHeight/window.screen.height;const c=document.createElement('canvas');c.width=Math.max(1,Math.round(rect.width*sx));c.height=Math.max(1,Math.round(rect.height*sy));c.getContext('2d').drawImage(img,Math.round(rect.x*sx),Math.round(rect.y*sy),c.width,c.height,0,0,c.width,c.height);resolve(c.toDataURL('image/png'));};img.onerror=reject;img.src=data;});}
window.dominion.onRegionSelected(async payload=>{if(!payload)return;try{const full=await window.dominion.captureScreen();const crop=await cropRegion(full,payload);await ask('Read and explain the selected screen region. Answer any question shown.',crop);}catch(e){setStatus('Could not read selected region: '+e.message);}});
function startRecognition(mode){const SR=window.SpeechRecognition||window.webkitSpeechRecognition;if(!SR){setStatus('Windows speech recognition unavailable in this build. Check microphone permissions or use Windows voice typing.');return;}
 if(recognition){try{recognition.stop();}catch{}recognition=null;}listeningMode=mode;recognition=new SR();recognition.lang=$('language').value;recognition.continuous=true;recognition.interimResults=true;
 recognition.onstart=()=>setStatus(mode==='transcribe'?'Transcribing — speak now':'Listening — speak now',true);
 recognition.onerror=e=>setStatus('Microphone: '+e.error);
 recognition.onresult=e=>{let final='',interim='';for(let i=e.resultIndex;i<e.results.length;i++){const t=e.results[i][0].transcript;if(e.results[i].isFinal)final+=t;else interim+=t;}if(interim){transcriptBox.hidden=false;transcriptBox.textContent=interim;}if(final.trim()){transcriptBox.hidden=false;transcriptBox.textContent=final;if(mode==='transcribe')addMessage('user','Transcript: '+final);else if(mode==='listen')ask('I am listening to the surrounding audio. Interpret this spoken content and relate it to the screen if relevant: '+final,screenImage);else ask(final,screenImage);}};
 recognition.onend=()=>{if(listeningMode!=='off'){try{recognition.start();}catch{setStatus('Listening paused');}}else setStatus('Ready');};try{recognition.start();}catch(e){setStatus('Could not start microphone: '+e.message);}}
function stopRecognition(){listeningMode='off';if(recognition){try{recognition.stop();}catch{}recognition=null;}setStatus('Ready');}
$('micBtn').onclick=()=>listeningMode==='voice'?stopRecognition():startRecognition('voice');
$('transcribeBtn').onclick=()=>listeningMode==='transcribe'?stopRecognition():startRecognition('transcribe');
window.dominion.onToggleMic(()=>listeningMode==='voice'?stopRecognition():startRecognition('voice'));
window.dominion.onRunMode(async mode=>{lastMode=mode;if(mode==='read')await readScreen();else if(mode==='region')window.dominion.action('region');else if(mode==='listen'||mode==='readlisten'){await readScreen('Inspect the screen. I will also provide spoken audio; connect the spoken content to what is visible.');startRecognition('listen');}else if(mode==='transcribe')startRecognition('transcribe');});
$('pin').onclick=()=>{window.dominion.setAlwaysOnTop(true);setStatus('Always on top enabled');};
$('min').onclick=()=>window.dominion.minimize();$('close').onclick=()=>window.dominion.close();
