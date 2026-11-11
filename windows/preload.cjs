const { contextBridge, ipcRenderer } = require('electron');
contextBridge.exposeInMainWorld('dominion', {
  captureScreen: () => ipcRenderer.invoke('capture-screen'),
  setAlwaysOnTop: value => ipcRenderer.send('set-always-on-top', value),
  minimize: () => ipcRenderer.send('minimize-window'),
  close: () => ipcRenderer.send('close-window'),
  onToggleMic: callback => ipcRenderer.on('toggle-mic', callback)
});