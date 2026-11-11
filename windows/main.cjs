const { app, BrowserWindow, ipcMain, globalShortcut, desktopCapturer, screen } = require('electron');
const path = require('path');
let win;
let compact = false;
function createWindow() {
  win = new BrowserWindow({
    width: 430, height: 700, minWidth: 360, minHeight: 500,
    title: 'Dominion Ask AI',
    alwaysOnTop: true,
    frame: true,
    autoHideMenuBar: true,
    backgroundColor: '#10131c',
    webPreferences: { preload: path.join(__dirname, 'preload.cjs'), contextIsolation: true, nodeIntegration: false, sandbox: true }
  });
  win.loadFile('index.html');
  win.on('closed', () => { win = null; });
  globalShortcut.register('CommandOrControl+Shift+Space', () => {
    if (!win) return;
    if (win.isVisible()) win.hide(); else { win.show(); win.focus(); }
  });
  globalShortcut.register('CommandOrControl+Shift+M', () => win && win.webContents.send('toggle-mic'));
}
app.whenReady().then(createWindow);
app.on('will-quit', () => globalShortcut.unregisterAll());
app.on('window-all-closed', () => { if (process.platform !== 'darwin') app.quit(); });
ipcMain.handle('capture-screen', async () => {
  const displays = screen.getAllDisplays();
  const sources = await desktopCapturer.getSources({ types: ['screen'], thumbnailSize: { width: 1600, height: 1000 } });
  return sources.map((s, i) => ({ id: s.id, name: s.name, dataUrl: s.thumbnail.toDataURL(), display: i }));
});
ipcMain.on('set-always-on-top', (_e, value) => win && win.setAlwaysOnTop(Boolean(value), 'floating'));
ipcMain.on('minimize-window', () => win && win.minimize());
ipcMain.on('close-window', () => win && win.close());