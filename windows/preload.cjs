const {contextBridge,ipcRenderer}=require('electron');
contextBridge.exposeInMainWorld('dominion',{
 captureScreen:()=>ipcRenderer.invoke('capture-screen'),

 captureRegion:rect=>ipcRenderer.invoke('capture-region',rect),
 action:name=>ipcRenderer.send('bubble-action',name),
 setFloatingEnabled:v=>ipcRenderer.send('set-floating-enabled',v),
 moveBubble:(dx,dy)=>ipcRenderer.send('bubble-move',dx,dy),
 regionSelected:data=>ipcRenderer.send('region-selected',data),
 showAnswer:text=>ipcRenderer.send('show-answer',text),
 closeAnswer:()=>ipcRenderer.send('answer-close'),
 hidePanel:()=>ipcRenderer.send('panel-hide'),
 setAlwaysOnTop:v=>ipcRenderer.send('set-always-on-top',v),
 minimize:()=>ipcRenderer.send('minimize-window'),
 close:()=>ipcRenderer.send('close-window'),
 onRunMode:cb=>ipcRenderer.on('run-mode',(_,v)=>cb(v)),
 onRegionSelected:cb=>ipcRenderer.on('region-selected',(_,v)=>cb(v)),
 onAnswerText:cb=>ipcRenderer.on('answer-text',(_,v)=>cb(v)),
 onToggleMic:cb=>ipcRenderer.on('toggle-mic',cb)
});