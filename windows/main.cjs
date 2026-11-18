const {app,BrowserWindow,ipcMain,globalShortcut,desktopCapturer,screen,session}=require('electron');
const path=require('path');
const fs=require('fs');
let panel=null,bubble=null,answer=null,region=null,pendingMode=null;
const html=n=>path.join(__dirname,n);
const configPath=()=>path.join(app.getPath('userData'),'dominion-settings.json');
function readConfig(){try{return {...{floatingEnabled:false,invisiblePresentation:false},...JSON.parse(fs.readFileSync(configPath(),'utf8'))};}catch{return {floatingEnabled:false,invisiblePresentation:false};}}
function writeConfig(v){try{fs.writeFileSync(configPath(),JSON.stringify(v,null,2));}catch(e){console.error('Could not save settings',e);}}
function top(w){if(w&&!w.isDestroyed())w.setAlwaysOnTop(true,'screen-saver');}
function applyCaptureProtection(w){if(w&&!w.isDestroyed()&&typeof w.setContentProtection==='function'){try{w.setContentProtection(!!readConfig().invisiblePresentation);}catch(e){console.error('Capture protection could not be applied',e);}}}
function setInvisibleMode(enabled){const cfg=readConfig();cfg.invisiblePresentation=!!enabled;writeConfig(cfg);[panel,bubble,answer,region].forEach(applyCaptureProtection);[panel,bubble].forEach(w=>{if(w&&!w.isDestroyed())w.webContents.send('invisible-mode-changed',cfg.invisiblePresentation);});return cfg.invisiblePresentation;}
function toggleInvisibleMode(){return setInvisibleMode(!readConfig().invisiblePresentation);}
function showPanel(){if(!panel||panel.isDestroyed())createPanel();else{panel.show();panel.focus();}}
function sendMode(mode){if(!panel||panel.isDestroyed()){pendingMode=mode;showPanel();return;}if(panel.webContents.isLoading()){pendingMode=mode;panel.once('ready-to-show',()=>{if(pendingMode){panel.webContents.send('run-mode',pendingMode);pendingMode=null;}});return;}panel.webContents.send('run-mode',mode);}
function createPanel(){
 if(panel&&!panel.isDestroyed())return panel;
 panel=new BrowserWindow({width:940,height:650,minWidth:760,minHeight:560,show:true,title:'Dominion Ask AI',alwaysOnTop:true,backgroundColor:'#10131c',webPreferences:{preload:html('preload.cjs'),contextIsolation:true,nodeIntegration:false}});
 applyCaptureProtection(panel);panel.loadFile(html('index.html'));
 panel.webContents.on('did-finish-load',()=>{if(pendingMode){panel.webContents.send('run-mode',pendingMode);pendingMode=null;}});
 panel.on('closed',()=>{panel=null;});
 return panel;
}
function createBubble(){
 if(bubble&&!bubble.isDestroyed()){bubble.show();top(bubble);return bubble;}
 const area=screen.getPrimaryDisplay().workArea;
 bubble=new BrowserWindow({width:250,height:430,x:area.x+area.width-270,y:180,frame:false,transparent:true,resizable:false,alwaysOnTop:true,skipTaskbar:true,hasShadow:false,show:false,webPreferences:{preload:html('preload.cjs'),contextIsolation:true,nodeIntegration:false}});
 applyCaptureProtection(bubble);bubble.loadFile(html('bubble.html'));bubble.once('ready-to-show',()=>{bubble.show();top(bubble);});
 bubble.on('closed',()=>{bubble=null;});
 return bubble;
}
function setBubbleEnabled(enabled){const cfg=readConfig();cfg.floatingEnabled=!!enabled;writeConfig(cfg);if(enabled)createBubble();else if(bubble&&!bubble.isDestroyed())bubble.hide();}
function createAnswer(){if(answer&&!answer.isDestroyed())return answer;answer=new BrowserWindow({width:440,height:340,show:false,frame:false,transparent:true,resizable:true,alwaysOnTop:true,skipTaskbar:true,webPreferences:{preload:html('preload.cjs'),contextIsolation:true,nodeIntegration:false}});applyCaptureProtection(answer);answer.loadFile(html('answer.html'));top(answer);answer.on('closed',()=>answer=null);return answer;}
function createRegion(){if(region&&!region.isDestroyed()){region.focus();return;}const d=screen.getPrimaryDisplay();region=new BrowserWindow({x:d.bounds.x,y:d.bounds.y,width:d.bounds.width,height:d.bounds.height,frame:false,transparent:true,backgroundColor:'#00000000',alwaysOnTop:true,skipTaskbar:true,resizable:false,webPreferences:{preload:html('preload.cjs'),contextIsolation:true,nodeIntegration:false}});applyCaptureProtection(region);region.loadFile(html('region.html'));top(region);region.on('closed',()=>region=null);}
async function capture(){const d=screen.getPrimaryDisplay();const sources=await desktopCapturer.getSources({types:['screen'],thumbnailSize:{width:d.size.width,height:d.size.height}});const src=sources.find(s=>s.display_id===String(d.id))||sources[0];if(!src)throw Error('No screen source available');return src.thumbnail.toDataURL();}
ipcMain.handle('capture-screen',capture);
ipcMain.handle('capture-region',async(_,rect)=>{if(!rect||![rect.x,rect.y,rect.width,rect.height].every(Number.isFinite)||rect.width<2||rect.height<2)throw Error('Invalid screen selection');const d=screen.getPrimaryDisplay();const sources=await desktopCapturer.getSources({types:['screen'],thumbnailSize:{width:Math.round(d.size.width*d.scaleFactor),height:Math.round(d.size.height*d.scaleFactor)}});const src=sources.find(s=>s.display_id===String(d.id))||sources[0];if(!src)throw Error('No screen source available');const image=src.thumbnail;const f=d.scaleFactor;const x=Math.max(0,Math.round(rect.x*f)),y=Math.max(0,Math.round(rect.y*f));const width=Math.min(image.getSize().width-x,Math.round(rect.width*f)),height=Math.min(image.getSize().height-y,Math.round(rect.height*f));if(width<2||height<2)throw Error('Selected area is outside the captured display');return image.crop({x,y,width,height}).toDataURL();});
ipcMain.on('bubble-move',(_,dx,dy)=>{if(bubble&&!bubble.isDestroyed()){const b=bubble.getBounds();bubble.setPosition(b.x+Math.round(dx),b.y+Math.round(dy));}});
ipcMain.on('bubble-action',(_,action)=>{if(action==='open'){showPanel();}else if(action==='answer'){const w=createAnswer();const ready=()=>{if(!w.isDestroyed())w.webContents.send('answer-text','Answer window is ready. Choose Read Screen, Select Screen Region, or Listen to Questions to display results here.');};if(w.webContents.isLoading())w.webContents.once('did-finish-load',ready);else ready();w.show();top(w);}else if(action==='region'){createRegion();}else if(action==='invisible'){toggleInvisibleMode();}else if(action==='hide'){bubble?.hide();}else if(action==='read'){panel?.hide();sendMode('read');}else if(action==='stop-read'){sendMode('stop-read');}else if(action==='listen'||action==='transcribe'||action==='readlisten'){panel?.hide();sendMode(action);}});
ipcMain.on('set-floating-enabled',(_,enabled)=>setBubbleEnabled(enabled));
ipcMain.handle('get-invisible-mode',()=>!!readConfig().invisiblePresentation);
ipcMain.on('set-invisible-mode',(_,enabled)=>setInvisibleMode(enabled));
ipcMain.on('region-selected',(_,payload)=>{if(region&&!region.isDestroyed())region.close();if(payload)sendMode('region');if(panel&&!panel.isDestroyed())panel.webContents.send('region-selected',payload);});
ipcMain.on('show-answer',(_,text)=>{const w=createAnswer();const deliver=()=>{if(!w.isDestroyed())w.webContents.send('answer-text',text);};if(w.webContents.isLoading())w.webContents.once('did-finish-load',deliver);else deliver();w.show();top(w);});
ipcMain.on('answer-close',()=>answer?.hide());
ipcMain.on('panel-hide',()=>panel?.hide());
ipcMain.on('set-always-on-top',(_,v)=>{if(panel&&!panel.isDestroyed())panel.setAlwaysOnTop(!!v);});
ipcMain.on('minimize-window',()=>panel?.hide());
ipcMain.on('close-window',()=>panel?.hide());
const gotLock=app.requestSingleInstanceLock();
if(!gotLock){app.quit();}else{
 app.on('second-instance',()=>showPanel());
 app.whenReady().then(()=>{session.defaultSession.setDisplayMediaRequestHandler(async(_request,callback)=>{const sources=await desktopCapturer.getSources({types:['screen']});callback(sources.length?{video:sources[0],audio:'loopback'}:{});});createPanel();createAnswer();if(readConfig().floatingEnabled)createBubble();globalShortcut.register('CommandOrControl+Shift+Space',()=>{if(panel?.isVisible())panel.hide();else showPanel();});globalShortcut.register('CommandOrControl+Shift+B',()=>{if(bubble?.isVisible())bubble.hide();else if(readConfig().floatingEnabled)createBubble();});globalShortcut.register('CommandOrControl+Shift+R',()=>{panel?.hide();sendMode('read');});globalShortcut.register('CommandOrControl+Shift+I',()=>toggleInvisibleMode());});
 app.on('will-quit',()=>globalShortcut.unregisterAll());
 app.on('window-all-closed',e=>{});
}
