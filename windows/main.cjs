const {app,BrowserWindow,ipcMain,globalShortcut,desktopCapturer,screen,Tray,Menu,nativeImage,session}=require('electron');
const path=require('path');
let panel,bubble,answer,region,tray;
const html=n=>path.join(__dirname,n);
function top(w){if(w&&!w.isDestroyed())w.setAlwaysOnTop(true,'screen-saver');}
function createPanel(){panel=new BrowserWindow({width:460,height:760,minWidth:380,minHeight:520,show:true,title:'Dominion Ask AI',alwaysOnTop:true,backgroundColor:'#10131c',webPreferences:{preload:html('preload.cjs'),contextIsolation:true,nodeIntegration:false}});
panel.loadFile(html('index.html'));panel.once('ready-to-show',()=>{panel.show();setTimeout(()=>{if(!bubble||bubble.isDestroyed())createBubble();else bubble.show();},700);});panel.on('closed',()=>panel=null);}
function createBubble(){bubble=new BrowserWindow({width:250,height:430,x:screen.getPrimaryDisplay().workArea.x+screen.getPrimaryDisplay().workArea.width-270,y:180,frame:false,transparent:true,resizable:false,alwaysOnTop:true,skipTaskbar:true,hasShadow:false,webPreferences:{preload:html('preload.cjs'),contextIsolation:true,nodeIntegration:false}});
bubble.loadFile(html('bubble.html'));top(bubble);}
function createAnswer(){answer=new BrowserWindow({width:440,height:340,show:false,frame:false,transparent:true,resizable:true,alwaysOnTop:true,skipTaskbar:true,webPreferences:{preload:html('preload.cjs'),contextIsolation:true,nodeIntegration:false}});
answer.loadFile(html('answer.html'));top(answer);}
function createRegion(){const d=screen.getPrimaryDisplay();region=new BrowserWindow({x:d.bounds.x,y:d.bounds.y,width:d.bounds.width,height:d.bounds.height,frame:false,transparent:true,backgroundColor:'#00000000',alwaysOnTop:true,skipTaskbar:true,resizable:false,webPreferences:{preload:html('preload.cjs'),contextIsolation:true,nodeIntegration:false}});
region.loadFile(html('region.html'));top(region);region.setAlwaysOnTop(true,'screen-saver');}
async function capture(){const d=screen.getPrimaryDisplay();const sources=await desktopCapturer.getSources({types:['screen'],thumbnailSize:{width:d.size.width,height:d.size.height}});const src=sources.find(s=>s.display_id===String(d.id))||sources[0];if(!src)throw Error('No screen source available');return src.thumbnail.toDataURL();}
ipcMain.handle('capture-screen',capture);

ipcMain.handle('capture-region',async(_,rect)=>{if(!rect||![rect.x,rect.y,rect.width,rect.height].every(Number.isFinite)||rect.width<2||rect.height<2)throw Error('Invalid screen selection');const d=screen.getPrimaryDisplay();const sources=await desktopCapturer.getSources({types:['screen'],thumbnailSize:{width:Math.round(d.size.width*d.scaleFactor),height:Math.round(d.size.height*d.scaleFactor)}});const src=sources.find(s=>s.display_id===String(d.id))||sources[0];if(!src)throw Error('No screen source available');const image=src.thumbnail;const f=d.scaleFactor;const x=Math.max(0,Math.round(rect.x*f)),y=Math.max(0,Math.round(rect.y*f));const width=Math.min(image.getSize().width-x,Math.round(rect.width*f)),height=Math.min(image.getSize().height-y,Math.round(rect.height*f));if(width<2||height<2)throw Error('Selected area is outside the captured display');return image.crop({x,y,width,height}).toDataURL();});
ipcMain.on('bubble-move',(_,dx,dy)=>{if(bubble&&!bubble.isDestroyed()){const b=bubble.getBounds();bubble.setPosition(b.x+Math.round(dx),b.y+Math.round(dy));}});\nipcMain.on('bubble-action',(_,action)=>{if(action==='open'){panel?.show();panel?.focus();}else if(action==='answer'){createAnswer();answer?.show();}else if(action==='region'){createRegion();}else if(action==='hide'){bubble?.hide();}else if(action==='read'||action==='listen'||action==='transcribe'||action==='readlisten'){panel?.show();panel?.webContents.send('run-mode',action);}}); 
ipcMain.on('region-selected',(_,payload)=>{region?.close();panel?.show();panel?.webContents.send('region-selected',payload);});
ipcMain.on('show-answer',(_,text)=>{createAnswer();answer?.show();answer?.webContents.send('answer-text',text);});
ipcMain.on('answer-close',()=>answer?.hide());
ipcMain.on('panel-hide',()=>panel?.hide());
ipcMain.on('set-always-on-top',(_,v)=>{if(panel&&!panel.isDestroyed())panel.setAlwaysOnTop(!!v);});
ipcMain.on('minimize-window',()=>panel?.hide());
ipcMain.on('close-window',()=>panel?.hide());
app.whenReady().then(()=>{session.defaultSession.setDisplayMediaRequestHandler(async(_request,callback)=>{const sources=await desktopCapturer.getSources({types:['screen']});callback(sources.length?{video:sources[0],audio:'loopback'}:{});});createPanel();createAnswer();globalShortcut.register('CommandOrControl+Shift+Space',()=>{if(panel?.isVisible())panel.hide();else panel?.show();});globalShortcut.register('CommandOrControl+Shift+B',()=>{if(bubble?.isVisible())bubble.hide();else bubble?.show();});globalShortcut.register('CommandOrControl+Shift+R',()=>createRegion());});
app.on('will-quit',()=>globalShortcut.unregisterAll());
app.on('window-all-closed',e=>{});
