const {app,BrowserWindow,shell,session,Menu}=require('electron');
const path=require('node:path');
const URL_XAI='https://xtorin-ai-ec7c0363.base44.app';
const ORIGIN=new URL(URL_XAI).origin;
function openWindow(){
 const win=new BrowserWindow({width:1180,height:800,minWidth:420,minHeight:540,title:'Xtorin AI',backgroundColor:'#0d1020',autoHideMenuBar:true,webPreferences:{preload:path.join(__dirname,'preload.js'),contextIsolation:true,nodeIntegration:false,sandbox:true,webSecurity:true,partition:'persist:xtorin'}});
 win.webContents.setWindowOpenHandler(({url})=>{if(/^https:\/\//.test(url))shell.openExternal(url);return {action:'deny'};});
 win.webContents.on('will-navigate',(event,url)=>{if(!url.startsWith(ORIGIN+'/')&&url!==ORIGIN){event.preventDefault();if(/^https:\/\//.test(url))shell.openExternal(url);}});
 win.loadURL(URL_XAI);
}
app.whenReady().then(()=>{
 const ses=session.fromPartition('persist:xtorin');
 ses.setPermissionRequestHandler((wc,p,cb,d)=>{let o='';try{o=new URL(d.requestingUrl||wc.getURL()).origin}catch{}cb(o===ORIGIN&&['media','microphone','notifications','clipboard-sanitized-write'].includes(p));});
 ses.setPermissionCheckHandler((_wc,p,o)=>o===ORIGIN&&['media','microphone','notifications','clipboard-sanitized-write'].includes(p));
 Menu.setApplicationMenu(null);openWindow();
 app.on('activate',()=>{if(BrowserWindow.getAllWindows().length===0)openWindow()});
});
app.on('window-all-closed',()=>{if(process.platform!=='darwin')app.quit()});
