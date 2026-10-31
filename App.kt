package com.example.ambilight

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlarmManager
import android.app.AlertDialog
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.provider.Settings
import java.util.Calendar
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebView
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/* JS-прослойка: делает navigator.bluetooth рабочим внутри WebView через нативный Bluetooth */
private const val SHIM = """
(function(){
window.onerror=function(m){window.__errShown=true;setTimeout(function(){var e=document.getElementById("msg");if(e)e.textContent="Ошибка страницы: "+m},0)};
var pend={},nid=1,devs={};
function hex(v){return Array.prototype.map.call(v,function(b){return ("0"+b.toString(16)).slice(-2)}).join("")}
window.__bleResult=function(id,ok,data){var p=pend[id];if(!p)return;delete pend[id];
 if(ok)p.res(data);else{var e=new Error(String(data));if(String(data).indexOf("NotFoundError")===0)e.name="NotFoundError";p.rej(e)}};
function call(m,a){return new Promise(function(res,rej){var id=nid++;pend[id]={res:res,rej:rej};
 try{AndroidBle.invoke(id,m,JSON.stringify(a||{}))}catch(e){delete pend[id];rej(e)}})}
function Char(addr,c){this._a=addr;this.uuid=c.uuid;this.properties={write:!!c.write,writeWithoutResponse:!!c.wnr}}
Char.prototype.writeValueWithoutResponse=function(v){return call("write",{a:this._a,c:this.uuid,h:hex(v),nr:true})};
Char.prototype.writeValue=function(v){return call("write",{a:this._a,c:this.uuid,h:hex(v),nr:false})};
function mk(info){
 var d={id:info.id,name:info.name||null,_l:[]};
 d.gatt={device:d,connected:false,_svc:[],
  connect:function(){return call("connect",{a:d.id}).then(function(svc){d.gatt.connected=true;d.gatt._svc=svc;return d.gatt})},
  disconnect:function(){d.gatt.connected=false;call("disconnect",{a:d.id}).catch(function(){})},
  getPrimaryServices:function(){return Promise.resolve(d.gatt._svc.map(function(s){
   return {uuid:s.uuid,getCharacteristics:function(){return Promise.resolve(s.chars.map(function(c){return new Char(d.id,c)}))}}}))}};
 d.addEventListener=function(t,f){if(t==="gattserverdisconnected")d._l.push(f)};
 devs[d.id]=d;return d}
window.__bleDevice=function(id,name){return mk({id:id,name:name})};
window.__bleEvent=function(a){var d=devs[a];if(!d)return;d.gatt.connected=false;d._l.forEach(function(f){try{f({target:d})}catch(e){}})};
Object.defineProperty(navigator,"bluetooth",{value:{requestDevice:function(){return call("scan").then(mk)}},configurable:true});
})();
"""

private val PAGE_PARTS = listOf(
"""<!DOCTYPE html>
<html lang="ru">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
<title>Lumo</title>
<style>
:root{--bg:#f4f3f0;--card:#fff;--tx:#1d1d1f;--mut:#6b6b70;--bd:#e3e1dc;--ac:#e8890c;box-sizing:border-box;padding-top:env(safe-area-inset-top,0px);padding-bottom:env(safe-area-inset-bottom,0px)}
@media (prefers-color-scheme:dark){:root:not([data-theme="light"]){--bg:#121214;--card:#1c1c20;--tx:#f2f2f4;--mut:#9a9aa2;--bd:#2e2e34;--ac:#ffa11f}}
:root[data-theme="dark"]{--bg:#121214;--card:#1c1c20;--tx:#f2f2f4;--mut:#9a9aa2;--bd:#2e2e34;--ac:#ffa11f}
*{box-sizing:border-box}
html{scroll-padding-top:env(safe-area-inset-top,0px)}
body{margin:0;background:var(--bg);color:var(--tx);font:16px/1.4 system-ui,-apple-system,"Segoe UI",Roboto,sans-serif}
main{max-width:520px;margin:0 auto;padding:16px}
h1{font-size:22px;margin:4px 0 14px}
.card{background:var(--card);border:1px solid var(--bd);border-radius:16px;padding:14px;margin-bottom:12px}
.card h2{font-size:13px;text-transform:uppercase;letter-spacing:.06em;color:var(--mut);margin:0 0 10px;font-weight:600}
.row{display:flex;gap:8px;flex-wrap:wrap}
button,select{font:inherit;color:var(--tx);background:var(--bg);border:1px solid var(--bd);border-radius:12px;padding:10px 14px;cursor:pointer}
select{padding:6px 8px;font-size:13px;max-width:130px}
button:active{transform:scale(.97)}
button.pri{background:var(--ac);border-color:var(--ac);color:#111;font-weight:600}
.big{flex:1;padding:16px;font-size:17px}
.dev{display:flex;align-items:center;gap:8px;padding:8px 0;border-top:1px solid var(--bd)}
.dev:first-child{border-top:0}
.dot{width:22px;height:22px;border-radius:50%;border:1px solid var(--bd);flex:none}
.dev label{flex:1;display:flex;align-items:center;gap:8px;min-width:0}
.dev small{color:var(--mut)}
input[type=checkbox]{width:20px;height:20px;accent-color:var(--ac);flex:none}
input[type=range]{width:100%;accent-color:var(--ac);height:32px}
#hue{-webkit-appearance:none;appearance:none;height:18px;border-radius:9px;background:linear-gradient(90deg,#f00,#ff0,#0f0,#0ff,#00f,#f0f,#f00)}
#hue::-webkit-slider-thumb{-webkit-appearance:none;width:28px;height:28px;border-radius:50%;background:#fff;border:3px solid #333}
#hue::-moz-range-thumb{width:24px;height:24px;border-radius:50%;background:#fff;border:3px solid #333}
.lbl{display:flex;justify-content:space-between;color:var(--mut);font-size:14px;margin-top:8px}
.hint{color:var(--mut);font-size:13px;margin:8px 0 0}
#msg{min-height:20px;font-size:13px;color:var(--mut);margin:0 2px 8px}
#preview{height:70px;border-radius:12px;border:1px solid var(--bd);margin-bottom:12px;transition:background .15s}
pre{margin:0;font:12px/1.5 ui-monospace,Menlo,monospace;color:var(--mut);white-space:pre-wrap;word-break:break-all}
:root{--bg:#f7efe6;--g1:#ffe4cc;--g2:#e8dcf8;--card:rgba(255,255,255,.7);--card-solid:#fffaf4;--tx:#33261f;--mut:#8a7a6f;--bd:rgba(120,90,60,.14);--ac:#f19a6b;--field:rgba(255,255,255,.75);--track:rgba(120,90,60,.15);--sh:0 10px 30px -12px rgba(120,80,40,.28)}
@media (prefers-color-scheme:dark){:root:not([data-theme="light"]){--bg:#17141d;--g1:#3a2433;--g2:#1f2a44;--card:rgba(255,255,255,.06);--card-solid:#221d2b;--tx:#f4eef8;--mut:#a79eb3;--bd:rgba(255,255,255,.09);--ac:#ffb37a;--field:rgba(255,255,255,.08);--track:rgba(255,255,255,.14);--sh:0 10px 30px -12px rgba(0,0,0,.6)}}
:root[data-theme="dark"]{--bg:#17141d;--g1:#3a2433;--g2:#1f2a44;--card:rgba(255,255,255,.06);--card-solid:#221d2b;--tx:#f4eef8;--mut:#a79eb3;--bd:rgba(255,255,255,.09);--ac:#ffb37a;--field:rgba(255,255,255,.08);--track:rgba(255,255,255,.14);--sh:0 10px 30px -12px rgba(0,0,0,.6)}
body{background:radial-gradient(120% 55% at 0% 0%,var(--g1),transparent 65%),radial-gradient(100% 50% at 100% 25%,var(--g2),transparent 65%),var(--bg);background-attachment:fixed}
h1{font-size:26px;font-weight:700;letter-spacing:-.01em}
.card{background:var(--card);border:1px solid var(--bd);border-radius:28px;padding:18px;box-shadow:var(--sh);-webkit-backdrop-filter:blur(14px);backdrop-filter:blur(14px)}
.card h2{text-transform:none;letter-spacing:.02em;font-size:14px}
button,select{background:var(--field);border:1px solid var(--bd);border-radius:999px;padding:11px 18px;transition:transform .12s,background .2s}
select{padding:7px 12px;font-size:13px;max-width:110px}
button.pri{background:linear-gradient(135deg,#ffc58a,#f48f7c);border:0;color:#3a1c10;box-shadow:0 8px 20px -8px rgba(240,120,90,.6)}
.big{padding:18px;font-size:17px}
.row{gap:10px}
.dev{padding:10px 0}
.dot{width:28px;height:28px;box-shadow:0 0 0 3px var(--card-solid),0 3px 10px rgba(0,0,0,.2)}
.ic{padding:8px 11px;font-size:15px}
.nm{flex:1;min-width:0;font:inherit;color:var(--tx);background:var(--field);border:1px solid var(--ac);border-radius:999px;padding:6px 12px;outline:0}
#preview{height:96px;border-radius:30px;border:0;box-shadow:inset 0 0 0 1px rgba(255,255,255,.35),var(--sh)}
input[type=range]:not(#hue){-webkit-appearance:none;appearance:none;height:14px;border-radius:999px;background:var(--track);margin:6px 0}
input[type=range]::-webkit-slider-thumb{-webkit-appearance:none;width:28px;height:28px;border-radius:50%;background:#fff;border:0;box-shadow:0 2px 10px rgba(0,0,0,.28)}
input[type=range]::-moz-range-thumb{width:28px;height:28px;border-radius:50%;background:#fff;border:0;box-shadow:0 2px 10px rgba(0,0,0,.28)}
#hue{height:18px;border-radius:999px;margin:6px 0}
pre{background:var(--field);border-radius:18px;padding:12px}
.sheet{position:fixed;inset:0;z-index:50;display:flex;align-items:flex-end;justify-content:center;background:rgba(30,20,14,.4);-webkit-backdrop-filter:blur(6px);backdrop-filter:blur(6px)}
.sheet[hidden]{display:none}
.panel{width:100%;max-width:520px;max-height:82vh;overflow:auto;background:var(--card-solid);color:var(--tx);border-radius:32px 32px 0 0;padding:20px 16px calc(20px + env(safe-area-inset-bottom,0px));animation:up .25s ease}
@keyframes up{from{transform:translateY(4""",
"""0px);opacity:0}to{transform:none;opacity:1}}
.ph{display:flex;justify-content:space-between;align-items:center;margin-bottom:14px;font-size:20px}
#scenes{display:grid;grid-template-columns:1fr 1fr;gap:12px}
#scenes button{height:92px;border:0;border-radius:26px;font-size:16px;font-weight:600;color:#fff;text-shadow:0 1px 6px rgba(0,0,0,.25);box-shadow:0 8px 18px -10px rgba(0,0,0,.5)}
#scenes button:nth-child(1){background:linear-gradient(135deg,#ffe2b0,#ffb36b);color:#4a2a10;text-shadow:none}
#scenes button:nth-child(2){background:linear-gradient(135deg,#ffffff,#e6e9f2);color:#333;text-shadow:none}
#scenes button:nth-child(3){background:linear-gradient(135deg,#ffcf8a,#e69a4d)}
#scenes button:nth-child(4){background:linear-gradient(135deg,#6a4fb8,#2a1a5e)}
#scenes button:nth-child(5){background:linear-gradient(135deg,#ff9a5a,#e0407a)}
#scenes button:nth-child(6){background:linear-gradient(135deg,#5b3a52,#241526)}
#scenes button:nth-child(7){background:linear-gradient(135deg,#46b8ff,#1f5fd6)}
#scenes button:nth-child(8){background:linear-gradient(135deg,#6fd68a,#228a58)}

.top{display:flex;justify-content:space-between;align-items:center}
.top h1{margin:4px 0 14px}
.seg{display:flex;gap:8px;margin-bottom:18px}
.seg button{flex:1;padding:11px 8px}
.sh{font-size:14px;color:var(--mut);margin:6px 0 10px;font-weight:600}
details.devmenu{margin-top:6px}
details.devmenu summary{cursor:pointer;color:var(--mut);padding:10px 0;font-size:14px}

.tm{font:inherit;font-size:36px;text-align:center;width:100%;padding:12px;border-radius:24px;border:1px solid var(--bd);background:var(--field);color:var(--tx);margin-bottom:16px}
.days{display:flex;gap:6px}
.days button{flex:1;min-width:0;padding:10px 0}
.devrow{display:flex;justify-content:space-between;align-items:center;gap:12px}
.cnt{font-size:20px;font-weight:600}
</style>
</head>
<body>
<main>
<div class="top"><h1>💡 Lumo</h1><button class="ic" id="openSet" aria-label="Настройки">⚙</button></div>
<div id="preview"></div>
<p id="msg"></p>

<div class="card">
<div class="devrow"><div><h2 style="margin:0 0 4px">Устройства</h2><div id="devCount" class="cnt">Нет устройств</div></div><button class="pri" id="add">+ Добавить</button></div>
</div>

<div class="card">
<h2>Питание</h2>
<div class="row"><button class="big pri" id="on">Включить</button><button class="big" id="off">Выключить</button></div>
<div class="row" style="margin-top:10px"><button id="sleep" style="flex:1">🌙 Таймер сна: выкл</button></div>
</div>

<div class="card">
<h2>Быстрые</h2>
<div class="row" id="quick" style="gap:8px"></div>
</div>
<div class="card">
<h2>Цвет</h2>
<input type="range" id="hue" min="0" max="360" value="30">
<div class="lbl"><span>Насыщенность</span><span id="sv">100%</span></div>
<input type="range" id="sat" min="0" max="100" value="100">
<div class="lbl"><span>Яркость</span><span id="bv">100%</span></div>
<input type="range" id="bri" min="1" max="100" value="100">
</div>

<div class="card">
<h2>Сцены</h2>
<div class="row"><button class="big" id="openScenes">🎨 Выбрать сцену</button></div>
</div>

<div class="card" id="schedCard" hidden>
<h2>⏰ Расписание</h2>
<div id="schedList"></div>
<div class="row" style="margin-top:10px"><button class="pri" id="addSched">+ Правило</button><button id="battBtn">Работа в фоне</button></div>
<p class="hint">Лента включается и выключается сама, даже когда приложение закрыто. Телефон должен быть рядом с лентой. На Xiaomi и Redmi включите для Lumo «Автозапуск» и «Без ограничений» в разделе батареи.</p>
</div>




</main>

<div id="sheet" class="sheet" hidden>
<div class="panel">
<div class="ph"><b>Сцены</b><button class="ic" id="closeScenes">✕</button></div>
<div id="scenes"></div>
<div class="row" style="margin-top:14px"><button id="pinMode" class="big" style="flex:1">📌 Закрепить</button></div>
<h2 class="sh" style="margin:18px 0 8px">Своя сцена</h2>
<div class="row"><input type="text" id="scnName" maxlength="20" placeholder="Название" style="flex:1;min-width:0;font:inherit;color:var(--tx);background:var(--field);border:1px solid var(--bd);border-radius:999px;padding:11px 16px"><button class="pri" id="saveScn">💾 Сохранить</button></div>
<p class="hint">Сохраняется текущий цвет ленты. Закрепить можно до трёх сцен.</p>
</div>
</div>

<div id="setsheet" class="sheet" hidden>
<div class="panel">
<div class="ph"><b>Настройки</b><button class="ic" id="closeSet">✕</button></div>
<h2 class="sh">Устройства</h2>
<div id="devs"></div>
<label class="hint" style="display:flex;gap:8px;align-items:center;margin:10px 0 20px"><input type="checkbox" id="rgbw"> Есть отдельный белый канал (RGBW)</label>
<h2 class="sh">Тема оформления</h2>
<div class="seg" id="themeSeg"><button data-t="auto">Авто</button><button data-t="light">Светлая</button><button data-t="dark">Тёмная</button></div>
<details class="devmenu"><summary>Меню разработчика</summary>
<p class="hint">Что отправлено на ленту:</p>
<pre id="log">пусто</pre>
</details>
</div>
</div>

<div id="schedsheet" class="sheet" hidden>
<div class="panel">
<div class="ph"><b>Правило</b><button class="ic" id="closeSched">✕</button></div>
<input type="time" id="stime" class="tm" value="22:00">
<h2 class="sh">Действие</h2>
<div class="seg" id="actSeg"><button data-a="on">Включить</button><button data-a="off">Выключить</button></div>
<h2 class="sh">Дни</h2>
<div class="days" id="daysBox"></div>
<p class="hint" id="schedErr" style="color:#d9534f"></p>
<div class="row" style="margin-top:14px"><button class="big pri" id="saveSched">Сохранить</button><button class="big" id="delSched">Удалить</button></div>
</div>
</div>

<script>
var devices=[], nid=1, state={on:true,r:255,g:140,b:0,mode:null}, logs=[];
var KNOWN=[0xFFE5,0xFFD5,0xFFFF,0xFFE0,0xFFD0,0xFFF0,0xFF00,0xFFB0,0xFEE0,"6e400001-b5a3-f393-e0a9-e50e24dcca9e"];
var ${'$'}=function(i){return document.getElementById(i)};
function msg(t){${'$'}("msg").textContent=t}
function hex(a){return a.map(function(x){return ("0"+x.toString(16)).slice(-2)}).join(" ")}
function log(t){logs.uns""",
"""hift(t);logs=logs.slice(0,10);${'$'}("log").textContent=logs.join("\n")}
function sleep(ms){return new Promise(function(r){setTimeout(r,ms)})}

var P={
 magic:{on:function(){return [0xCC,0x23,0x33]},off:function(){return [0xCC,0x24,0x33]},col:function(r,g,b){return [0x56,r,g,b,0x00,0xF0,0xAA]},ww:function(v){return [0x56,0,0,0,v,0x0F,0xAA]}},
 elk:{on:function(){return [0x7E,0,4,0xF0,0,1,0xFF,0,0xEF]},off:function(){return [0x7E,0,4,0,0,0,0xFF,0,0xEF]},col:function(r,g,b){return [0x7E,0,5,3,r,g,b,0,0xEF]}}
};
function rgb2hsv(r,g,b){r/=255;g/=255;b/=255;var mx=Math.max(r,g,b),mn=Math.min(r,g,b),dl=mx-mn,h=0;
 if(dl){if(mx===r)h=((g-b)/dl+6)%6;else if(mx===g)h=(b-r)/dl+2;else h=(r-g)/dl+4}
 return [Math.round(h*60),mx?Math.round(dl/mx*100):0,Math.round(mx*100)]}
P.ledwf={on:function(){return [0x3B,0x23,0,0,0,0,0,0,0,0x32,0,0]},off:function(){return [0x3B,0x24,0,0,0,0,0,0,0,0x32,0,0]},col:function(r,g,b){var m=rgb2hsv(r,g,b);return [0x3B,0xA1,Math.round(m[0]/2),m[1],m[2],0,0,0,0,0x32,0,0]}};
P.ledwf2={on:P.ledwf.on,off:P.ledwf.off,col:function(r,g,b){return [0x31,r,g,b,0,0,0xF0,0x0F]}};
P.wrap=function(d,p){if(d.proto.indexOf("ledwf")!==0)return p;var s=0;p.forEach(function(x){s=(s+x)&255});var n=p.length+1;d.seq=((d.seq||0)+1)&255;return [0,d.seq,0x80,0,0,n,n+1,0x0B].concat(p,[s])};

function hsv(h,s,v){s/=100;v/=100;var f=function(n){var k=(n+h/60)%6;return v-v*s*Math.max(0,Math.min(k,4-k,1))};
 return [Math.round(f(5)*255),Math.round(f(3)*255),Math.round(f(1)*255)]}

function saveName(d){if(d.demo||!d.dev)return;try{var m=JSON.parse(localStorage.getItem("lights-names")||"{}");m[d.dev.id]=d.name;localStorage.setItem("lights-names",JSON.stringify(m))}catch(e){}}
function savedName(dev){try{return JSON.parse(localStorage.getItem("lights-names")||"{}")[dev.id]}catch(e){}}
var _ka=null;
function saveDevs(){
 try{localStorage.setItem("lumo-devs",JSON.stringify(devices.filter(function(d){return !d.demo&&d.dev}).map(function(d){return {id:d.dev.id,name:d.dev.name||null,proto:d.proto,sel:d.sel}})))}catch(e){}
}
function watch(d){d.dev.addEventListener("gattserverdisconnected",function(){d.ok=false;render();reconnect(d,0)})}
function reconnect(d,n){
 if(n>12||devices.indexOf(d)<0||d.rc)return;
 d.rc=true;
 setTimeout(async function(){
  d.rc=false;
  if(devices.indexOf(d)<0||d.ok)return;
  try{var r=await connectGatt(d.dev);d.char=r.ch;d.ok=true;render();log(d.name+": связь восстановлена")}
  catch(e){reconnect(d,n+1)}
 },2500*(n+1));
}
function render(){
 var nOk=devices.filter(function(d){return d.ok}).length;
 var want=devices.length>0;if(want!==_ka){_ka=want;try{AndroidBle.invoke(0,"keepalive",JSON.stringify({on:want}))}catch(e){}}
 ${'$'}("devCount").textContent=devices.length?("Подключено: "+nOk+(nOk<devices.length?" из "+devices.length:"")):"Нет устройств";
 var el=${'$'}("devs");el.innerHTML="";
 if(!devices.length){el.innerHTML='<p class="hint" style="margin:0">Устройств нет. Нажмите «+ Добавить» на главном экране.</p>'}
 devices.forEach(function(d){
  var row=document.createElement("div");row.className="dev";
  var dot=document.createElement("span");dot.className="dot";dot.style.background=d.on?d.css:"#000";
  var lb=document.createElement("label");
  var cb=document.createElement("input");cb.type="checkbox";cb.checked=d.sel;cb.onchange=function(){d.sel=cb.checked;saveDevs()};
  var nm;
  if(d.editing){
   nm=document.createElement("input");nm.type="text";nm.className="nm";nm.maxLength=24;nm.value=(d.draft!=null?d.draft:d.name);
   nm.oninput=function(){d.draft=nm.value};
   nm.onblur=function(){d.name=nm.value.trim()||d.name;d.draft=null;d.editing=false;saveName(d);render()};
   nm.onkeydown=function(e){if(e.key==="Enter")nm.blur()};
   setTimeout(function(){nm.focus();nm.select()},0);
  }else{nm=document.createElement("span");nm.textContent=d.name;nm.style.overflow="hidden";nm.style.textOverflow="ellipsis";}
  var st=document.createElement("small");st.textContent=d.demo?"демо":(d.ok?"ок":"нет связи");
  lb.append(cb,nm,st);
  row.append(dot,lb);
  var ed=document.createElement("button");ed.className="ic";ed.textContent="✎";ed.title="Переименовать";ed.onclick=function(){d.editing=true;render()};row.append(ed);
  if(!d.demo){
   var sel=document.createElement("select");
   [["ledwf","LEDnetWF"],["ledwf2","LEDnetWF (RGB)"],["magic","Magic Home"],["elk","ELK-BLEDOM"]].forEach(function(o){var op=document.createElement("option");op.value=o[0];op.textContent=o[1];if(d.proto===o[0])op.selected=true;sel.append(op)});
   sel.onchange=function(){d.proto=sel.value;log(d.name+": протокол "+d.proto);saveDevs()};
   row.append(sel);
  }
  var rm=document.createElement("button");rm.textContent="✕";rm.onclick=function(){
   if(d.dev&&d.dev.gatt&&d.dev.gatt.connected)d.dev.gatt.disconnect();
   devices=devices.filter(function(x){return x!==d});render();syncSched();saveDevs()};
  row.append(rm);el.append(row)});
 ${'$'}("preview").style.background=state.on?"rgb("+state.r+","+state.g+","+state.b+")":"#000";
}

/* ---- отправка: очередь на каждое устройство, побеждает последняя команда ---- */
function send(build,on,rgb){
 if(on!==undefined)state.on=on;
 if(rgb){state.r=rgb[0];state.g=rgb[1];state.b=rgb[2]}
 devices.forEach(function(d){
  if(!d.sel)return;
  var pk=build(d);
  if(on!==undefined)d.on=on;
  if(rgb)d.css="rgb("+rgb[0]+","+rgb[1]+","+rgb[2]+")";
  if(!d.demo){d.q=pk;pump(d)}
 });
 render();
}
async function pump(d){
 if(d.busy)return;d.busy=true;
 try{
  while(d.q){
   var pk=d.q;d.q=null;
   if(!d.dev.gatt.connected){var r=await connectGatt(d.dev);d.char=r.ch;log(d.name+": переподключено")}
   for(var i=0;i<pk.length;i++){
    var u=new Uint8Array(P.wrap(d,pk[i]));
    try{
     if(d.char.properties.writeWithoutResponse)await d.char.writeValueWithoutResponse(u);else await d.char.writeValue(u);
    }catch(e){await d.char.writeValue(u)}
    log(d.name+" ["+d.proto+"] "+hex(Array.from(u)));
    await sleep(40);
   }
   d.ok=true;
  }
 }catch(e){d.ok=false;log("ОШИБКА "+d.name+": "+e.message);msg("Ошибка связи с "+""",
"""d.name+": "+e.message)}
 d.busy=false;render();
}

async function connectGatt(dev){
 var s=await dev.gatt.connect();
 var svs=await s.getPrimaryServices();
 var best=null,names=[];
 for(var i=0;i<svs.length;i++){
  var sid=svs[i].uuid.slice(4,8);names.push(sid);
  if(["1800","1801","180a"].indexOf(sid)>=0)continue;
  var cs=await svs[i].getCharacteristics();
  for(var j=0;j<cs.length;j++){
   var p=cs[j].properties;
   if(!(p.write||p.writeWithoutResponse))continue;
   var u=cs[j].uuid.slice(4,8);
   if(["ffe9","ffd9","fff3","ff01"].indexOf(u)>=0)return {ch:cs[j],proto:u==="fff3"?"elk":(u==="ff01"?"ledwf":"magic"),info:"службы "+names.join(",")+", канал "+u};
   if(!best)best={ch:cs[j],proto:"magic",info:"службы "+names.join(",")+", канал "+u+" (неизвестный!)"};
  }
 }
 if(!best)throw new Error("не найден канал записи, службы: "+names.join(","));
 return best;
}

async function addBle(){
 if(!navigator.bluetooth){msg("Web Bluetooth недоступен. Откройте страницу в Chrome на Android или компьютере.");return}
 try{
  var dev=await navigator.bluetooth.requestDevice({acceptAllDevices:true,optionalServices:KNOWN});
  msg("Подключаюсь к "+(dev.name||"устройству")+"…");
  var r=await connectGatt(dev);
  var d={id:nid++,name:savedName(dev)||dev.name||"Лента",dev:dev,char:r.ch,proto:/^LEDnetWF/i.test(dev.name||"")?"ledwf":r.proto,sel:true,ok:true,on:true,css:"rgb(255,140,0)"};
  watch(d);
  devices.push(d);syncSched();saveDevs();
  log(d.name+": "+r.info+", протокол "+d.proto);
  msg("Подключено: "+d.name);render();
 }catch(e){
  if(e.name==="NotFoundError")msg("Устройство не выбрано. Включите ленту, закройте приложение Zengge и попробуйте снова.");
  else if(e.name==="SecurityError"||/policy/i.test(e.message))msg("Браузер блокирует Bluetooth во встроенной странице. Откройте ссылку прямо в Chrome.");
  else msg("Не удалось подключиться: "+e.message);
 }
}

/* ---- команды ---- */
function pre(d,a){if(!d.on)a.unshift(P[d.proto].on());return a}
function setColor(r,g,b){send(function(d){return pre(d,[P[d.proto].col(r,g,b)])},true,[r,g,b])}
function white(br){
 var v=Math.round(255*br/100),w=!${'$'}("rgbw").checked;
 send(function(d){
  if(!w&&d.proto==="magic")return pre(d,[P.magic.ww(v)]);
  return pre(d,[P[d.proto].col(v,Math.round(v*.72),Math.round(v*.38))]);
 },true,[v,Math.round(v*.72),Math.round(v*.38)]);
 state.mode=null;
}
function fromSliders(){
 var c=hsv(+${'$'}("hue").value,+${'$'}("sat").value,+${'$'}("bri").value);
 ${'$'}("sv").textContent=${'$'}("sat").value+"%";${'$'}("bv").textContent=${'$'}("bri").value+"%";
 state.mode=null;setColor(c[0],c[1],c[2]);
}

${'$'}("add").onclick=addBle;
${'$'}("openScenes").onclick=function(){${'$'}("sheet").hidden=false};
${'$'}("closeScenes").onclick=function(){${'$'}("sheet").hidden=true};
${'$'}("sheet").onclick=function(e){if(e.target===${'$'}("sheet"))${'$'}("sheet").hidden=true};
${'$'}("scenes").addEventListener("click",function(e){if(!pinMode&&e.target.closest("button"))${'$'}("sheet").hidden=true});

${'$'}("on").onclick=function(){send(function(d){return [P[d.proto].on()]},true)};
${'$'}("off").onclick=function(){send(function(d){return [P[d.proto].off()]},false)};
["hue","sat","bri"].forEach(function(i){${'$'}(i).oninput=fromSliders});

var SCN=[
 {n:"🕯 Тёплый",w:"w",c:[255,185,100]},{n:"⚪ Белый",w:"w100",c:[255,255,255]},{n:"☀️ Дневной",c:[205,228,255]},{n:"❄️ Холодный",c:[160,200,255]},
 {n:"📖 Чтение",c:[255,170,80]},{n:"🎬 Кино",c:[40,20,90]},{n:"🌅 Закат",c:[255,70,10]},{n:"🌙 Ночник",c:[40,8,0]},
 {n:"🔥 Камин",c:[255,90,10]},{n:"🌇 Янтарь",c:[255,150,0]},{n:"🍑 Персик",c:[255,140,110]},{n:"🪸 Коралл",c:[255,80,90]},
 {n:"🌸 Сакура",c:[255,120,170]},{n:"✨ Романтика",c:[200,20,80]},{n:"💜 Лаванда",c:[160,110,255]},{n:"🍇 Виноград",c:[110,30,170]},
 {n:"🌌 Космос",c:[30,20,140]},{n:"🌊 Океан",c:[0,120,255]},{n:"🧊 Лёд",c:[140,220,255]},{n:"🍃 Мята",c:[90,255,170]},
 {n:"🌿 Лес",c:[20,200,60]},{n:"🌴 Тропики",c:[0,220,140]},{n:"🍋 Лимон",c:[255,220,20]},{n:"🟣 Неон",c:[255,0,200]}
];
var DEF_PINS=["🕯 Тёплый","🎬 Кино","🌅 Закат"];
var pins=DEF_PINS,custom=[];
try{var pp=JSON.parse(localStorage.getItem("lumo-pins")||"null");if(Array.isArray(pp))pins=pp}catch(e){}
try{custom=JSON.parse(localStorage.getItem("lumo-scenes")||"[]")||[]}catch(e){custom=[]}
var pinMode=false;
function allScn(){return SCN.concat(custom)}
function findScn(n){return allScn().filter(function(x){return x.n===n})[0]}
function applyScn(s){state.mode=null;if(s.w==="w")white(+${'$'}("bri").value);else if(s.w==="w100")white(100);else setColor(s.c[0],s.c[1],s.c[2])}
function tileBg(b,c){
 if(!c)c=[255,255,255];
 var mx=Math.max(c[0],c[1],c[2],1),n=c.map(function(x){return Math.round(x*255/mx)});
 var lum=(n[0]*299+n[1]*587+n[2]*114)/1000,dk=function(x){return Math.round(x*.6)};
 b.style.background="linear-gradient(135deg,rgb("+n.join(",")+"),rgb("+n.map(dk).join(",")+"))";
 b.style.color=lum>170?"#33261f":"#fff";b.style.textShadow=lum>170?"none":"0 1px 6px rgba(0,0,0,.25)";
}
function saveQ(k,v){try{localStorage.setItem(k,JSON.stringify(v))}catch(e){}}
function togglePin(n){
 var i=pins.indexOf(n);
 if(i>=0)pins.splice(i,1);else{if(pins.length>=3)pins.shift();pins.push(n)}
 saveQ("lumo-pins",pins);renderScn();
}
function renderQuick(){
 var q=${'$'}("quick");if(!q)return;q.innerHTML="";
 var list=pins.map(findScn).filter(Boolean);
 if(!list.length){q.innerHTML='<p class="hint" style="margin:0">Закрепите сцены в окне «Сцены» (📌).</p>';return}
 list.forEach(function(s){var b=document.createElement("button");b.textContent=s.n;tileBg(b,s.c);b.style.flex="1";b.style.padding="16px 6px";b.onclick=function(){applyScn(s)};q.append(b)});
}
function renderScn(){
 var box=${'$'}("scenes");box.innerHTML="";
 allScn().forEach(function(s){
  var b=document.createElement("button");
  b.textContent=s.n+(pinMode&&pins.indexOf(s.n)>=0?" 📌":"");
  tileBg(b,s.c);
  b.onclick=function(){if(pinMode){togglePin(s.n);return}applyScn(s)};
  box.append(b)});
 renderQuick();
}
${'$'}("pinMode").onclick=function(){pinMode=!pinMode;this.textContent=pinMode?"Готово":"📌 Закрепить";this.className=pinMode?"pri big":"big";renderScn()};
${'$'}("saveScn").on""",
"""click=function(){
 var n=(${'$'}("scnName").value||"").trim();
 if(!n){msg("Введите название сцены");return}
 custom=custom.filter(function(x){return x.n!==n});
 custom.push({n:n,c:[state.r,state.g,state.b]});
 saveQ("lumo-scenes",custom);
 ${'$'}("scnName").value="";renderScn();msg("Сцена «"+n+"» сохранена");
};
try{renderScn()}catch(e){msg("Ошибка сцен: "+e.message)}

var fx=[["🌈 Радуга",0x25],["💓 Пульс",0x26],["🔴 Пульс красный",0x27],["🟢 Пульс зелёный",0x28],["🔵 Пульс синий",0x29],["⚡ Стробоскоп",0x30],["🔀 Смена цвета",0x37],["✨ Плавно",0x38]];
fx=[["🌈 Радуга",0x25],["🔴 Красный",0x26],["🟢 Зелёный",0x27],["🔵 Синий",0x28],["🟣 Фиолетовый",0x2B],["🟠 Красно-синий",0x2E],["⚡ Стробоскоп",0x30],["🎆 Скачки цвета",0x38]];
function sendFx(i){
 var pct=+${'$'}("spd").value,sp=Math.max(1,Math.round(32-pct*31/100));
 state.mode=i;
 send(function(d){
  if(d.proto.indexOf("ledwf")===0)return pre(d,[[0x38,fx[i][1]-0x24,pct,100]]);
  if(d.proto==="elk")return pre(d,[[0x7E,0,3,0x80+i,3,0,0,0,0xEF],[0x7E,0,2,pct,0,0,0,0,0xEF]]);
  return pre(d,[[0xBB,fx[i][1],sp,0x44]]);
 },true);
}



render();
msg("Нажмите «+ Добавить», чтобы подключить ленту. Приложение Zengge должно быть закрыто.");

var themePref="auto";
try{themePref=localStorage.getItem("lumo-theme")||"auto"}catch(e){}
function applyTheme(){
 var sys=(typeof window.__sysDark==="boolean")?window.__sysDark:matchMedia("(prefers-color-scheme: dark)").matches;
 var dark=themePref==="dark"||(themePref==="auto"&&sys);
 document.documentElement.setAttribute("data-theme",dark?"dark":"light");
 try{AndroidBle.invoke(0,"theme",JSON.stringify({dark:dark,bg:dark?"#17141d":"#f7efe6"}))}catch(e){}
 Array.prototype.forEach.call(document.querySelectorAll("#themeSeg button"),function(b){b.className=b.getAttribute("data-t")===themePref?"pri":""});
}
Array.prototype.forEach.call(document.querySelectorAll("#themeSeg button"),function(b){
 b.onclick=function(){themePref=b.getAttribute("data-t");try{localStorage.setItem("lumo-theme",themePref)}catch(e){}applyTheme()}});
${'$'}("openSet").onclick=function(){${'$'}("setsheet").hidden=false};
${'$'}("closeSet").onclick=function(){${'$'}("setsheet").hidden=true};
${'$'}("setsheet").onclick=function(e){if(e.target===${'$'}("setsheet"))${'$'}("setsheet").hidden=true};
applyTheme();

/* ---- расписание ---- */
var sched=[];
try{sched=JSON.parse(localStorage.getItem("lumo-sched")||"[]")}catch(e){}
var DORD=[1,2,3,4,5,6,0],DNAME={0:"Вс",1:"Пн",2:"Вт",3:"Ср",4:"Чт",5:"Пт",6:"Сб"},cur=null;
function hasNative(){return typeof AndroidBle!=="undefined"}
function syncSched(){
 if(!hasNative())return;
 var dv=devices.filter(function(d){return !d.demo&&d.dev}).map(function(d){var pr=P[d.proto];
  return {a:d.dev.id,p:d.proto,c:d.char?d.char.uuid:"",on:pr.on(),off:pr.off()}});
 var data={items:sched.map(function(x){return {id:String(x.id),t:x.t,d:x.d,a:x.a,e:x.e}}),devs:dv};
 try{AndroidBle.invoke(0,"schedule",JSON.stringify(data))}catch(e){}
}
function schedSave(){
 sched.sort(function(a,b){return a.t<b.t?-1:a.t>b.t?1:0});
 try{localStorage.setItem("lumo-sched",JSON.stringify(sched))}catch(e){}
 syncSched();renderSched();
}
function daysText(d){
 var k=d.slice().sort().join();
 if(d.length===7)return "Каждый день";
 if(k==="1,2,3,4,5")return "Будни";
 if(k==="0,6")return "Выходные";
 return DORD.filter(function(n){return d.indexOf(n)>=0}).map(function(n){return DNAME[n]}).join(" ");
}
function renderSched(){
 ${'$'}("schedCard").hidden=!hasNative();
 var el=${'$'}("schedList");el.innerHTML="";
 if(!sched.length){el.innerHTML='<p class="hint" style="margin:0">Правил пока нет.</p>';return}
 sched.forEach(function(x){
  var row=document.createElement("div");row.className="dev";
  var cb=document.createElement("input");cb.type="checkbox";cb.checked=x.e;cb.onchange=function(){x.e=cb.checked;schedSave()};
  var tx=document.createElement("div");tx.style.flex="1";tx.style.cursor="pointer";
  var t=document.createElement("b");t.style.fontSize="22px";t.textContent=x.t;
  var ac=document.createElement("span");ac.textContent="  "+(x.a==="on"?"Включить":"Выключить");
  var sm=document.createElement("small");sm.style.display="block";sm.textContent=daysText(x.d);
  tx.append(t,ac,sm);tx.onclick=function(){openSched(x)};
  row.append(cb,tx);el.append(row)});
}
function paintSched(){
 Array.prototype.forEach.call(${'$'}("daysBox").children,function(b){b.className=cur.d.indexOf(+b.getAttribute("data-d"))>=0?"pri":""});
 Array.prototype.forEach.call(${'$'}("actSeg").children,function(b){b.className=b.getAttribute("data-a")===cur.a?"pri":""});
}
function openSched(x){
 cur=x?{id:x.id,t:x.t,d:x.d.slice(),a:x.a,e:x.e}:{id:Date.now(),t:"22:00",d:[0,1,2,3,4,5,6],a:"off",e:true,isNew:true};
 ${'$'}("stime").value=cur.t;${'$'}("schedErr").textContent="";
 ${'$'}("delSched").style.display=x?"":"none";
 paintSched();${'$'}("schedsheet").hidden=false;
}
DORD.forEach(function(n){var b=document.createElement("button");b.textContent=DNAME[n];b.setAttribute("data-d",n);
 b.onclick=function(){var i=cur.d.indexOf(n);if(i>=0)cur.d.splice(i,1);else cur.d.push(n);paintSched()};${'$'}("daysBox").append(b)});
Array.prototype.forEach.call(${'$'}("actSeg").children,function(b){b.onclick=function(){cur.a=b.getAttribute("data-a");paintSched()}});
${'$'}("addSched").onclick=function(){openSched(null)};
${'$'}("closeSched").onclick=function(){${'$'}("schedsheet").hidden=true};
${'$'}("schedsheet").onclick=function(e){if(e.target===${'$'}("schedsheet"))${'$'}("schedsheet").hidden=true};
${'$'}("saveSched").onclick=function(){
 cur.t=${'$'}("stime").value||"22:00";
 if(!cur.d.length){${'$'}("schedErr").textContent="Выберите хотя бы один день";return}
 var i=-1;sched.forEach(function(x,k){if(x.id===cur.id)i=k});
 var item={id:cur.id,t:cur.t,d:cur.d,a:cur.a,e:true};
 if(i>=0){item.e=sched[i].e;sched[i]=item}else sched.push(item);
 ${'$'}("schedsheet").hidden=true;schedSave();
};
${'$'}("delSched").onclick=function(){sched=sched.filter(function(x){return x.id!==cur.id});${'$'}("schedsheet").hidden=true;schedSave()};
${'$'}("battBtn").onclick=function(){try{AndroidBle.invoke(0,"battery","{}")}catch(e){}};
renderSched();

/* ---- память устройств ---""",
"""- */
async function restoreDevs(){
 if(typeof AndroidBle==="undefined"||!window.__bleDevice)return;
 var saved=[];try{saved=JSON.parse(localStorage.getItem("lumo-devs")||"[]")}catch(e){}
 if(!saved.length)return;
 saved.forEach(function(sv){
  var dev=window.__bleDevice(sv.id,sv.name||null);
  var d={id:nid++,name:savedName(dev)||sv.name||"Лента",dev:dev,char:null,proto:sv.proto||"ledwf",sel:sv.sel!==false,ok:false,on:true,css:"rgb(255,140,0)"};
  watch(d);devices.push(d)});
 render();syncSched();msg("Подключаюсь к сохранённым устройствам…");
 for(var i=0;i<devices.length;i++){
  var dd=devices[i];
  try{var r=await connectGatt(dd.dev);dd.char=r.ch;dd.ok=true;log(dd.name+": подключено автоматически")}
  catch(e){log(dd.name+": не удалось подключиться ("+e.message+")");reconnect(dd,0)}
  render();
 }
 msg(devices.some(function(x){return x.ok})?"Устройства подключены":"Не удалось подключиться. Проверьте, что лента включена, а Zengge закрыт.");
}
document.addEventListener("visibilitychange",function(){
 if(document.hidden)return;
 devices.forEach(function(d){if(!d.demo&&d.dev&&!d.dev.gatt.connected){d.ok=false;reconnect(d,0)}});
 render();
});
restoreDevs();

/* ---- таймер сна: плавное затухание и выключение ---- */
var SLP=[0,15,30,60],sleepMin=0,sleepEnd=0,sleepT=null,sleepBase=null;
function sleepLabel(){
 var b=${'$'}("sleep");
 if(!sleepEnd){b.textContent="🌙 Таймер сна: выкл";return}
 b.textContent="🌙 Сон через "+Math.max(1,Math.ceil((sleepEnd-Date.now())/60000))+" мин";
}
function sleepStop(){
 sleepEnd=0;sleepMin=0;sleepBase=null;
 if(sleepT)clearInterval(sleepT);
 sleepT=null;sleepLabel();
}
function sleepTick(){
 var left=sleepEnd-Date.now();
 if(left<=0){
  sleepStop();
  send(function(d){return [P[d.proto].off()]},false);
  msg("Спокойной ночи");
  return;
 }
 if(left<60000&&state.on){
  if(!sleepBase)sleepBase=[state.r,state.g,state.b];
  if(Math.floor(left/1000)%5===0){
   var k=left/60000;
   setColor(Math.round(sleepBase[0]*k),Math.round(sleepBase[1]*k),Math.round(sleepBase[2]*k));
  }
 }
 sleepLabel();
}
${'$'}("sleep").onclick=function(){
 sleepMin=SLP[(SLP.indexOf(sleepMin)+1)%SLP.length];
 sleepBase=null;
 if(!sleepMin){sleepStop();msg("Таймер сна выключен");return}
 sleepEnd=Date.now()+sleepMin*60000;
 if(!sleepT)sleepT=setInterval(sleepTick,1000);
 sleepLabel();
 msg("Лента погаснет через "+sleepMin+" мин");
};
sleepLabel();
</script>
</body>
</html>
"""
)

@SuppressLint("MissingPermission", "SetJavaScriptEnabled")
@Suppress("DEPRECATION")
class MainActivity : Activity() {
    private lateinit var web: WebView
    private val ui = Handler(Looper.getMainLooper())
    private val gatts = AppState.gatts
    private val chars = AppState.chars
    private val connectIds = ConcurrentHashMap<String, Int>()
    private var pendingScan: (() -> Unit)? = null
    private var pendingFail: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2)
        }
        Scheduler.arm(applicationContext)
        window.statusBarColor = Color.parseColor("#F7EFE6")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        web = WebView(this)
        web.setBackgroundColor(Color.parseColor("#F7EFE6"))
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.addJavascriptInterface(Bridge(), "AndroidBle")
        setContentView(web)
        val dark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val html = PAGE_PARTS.joinToString("").replaceFirst("<head>", "<head><script>window.__sysDark=" + dark + ";" + SHIM + "</script>")
        web.loadDataWithBaseURL("https://lights.local/", html, "text/html", "utf-8", null)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        web.evaluateJavascript(
            "(function(){var s=document.querySelector('.sheet:not([hidden])');if(s){s.hidden=true;return 1}return 0})()"
        ) { r -> if (r != "1") moveTaskToBack(true) }
    }

    override fun onDestroy() {
        try { stopService(Intent(this, KeepService::class.java)) } catch (e: Exception) { }
        for (g in gatts.values) { try { g.disconnect(); g.close() } catch (e: Exception) { } }
        gatts.clear()
        super.onDestroy()
    }

    /* ---------- мост JS -> Android ---------- */

    inner class Bridge {
        @JavascriptInterface
        fun invoke(id: Int, method: String, args: String) {
            try {
                val a = JSONObject(args)
                when (method) {
                    "scan" -> runOnUiThread { startScan(id) }
                    "theme" -> {
                        val dark = a.optBoolean("dark")
                        val bg = Color.parseColor(a.optString("bg", "#F7EFE6"))
                        runOnUiThread {
                            window.statusBarColor = bg
                            window.navigationBarColor = bg
                            web.setBackgroundColor(bg)
                            window.decorView.systemUiVisibility =
                                if (dark) 0 else (View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR)
                        }
                        resolve(id, "\"ok\"")
                    }
                    "connect" -> connect(id, a.getString("a"))
                    "keepalive" -> {
                        val keep = a.optBoolean("on")
                        runOnUiThread {
                            try {
                                if (keep) startForegroundService(Intent(this@MainActivity, KeepService::class.java))
                                else stopService(Intent(this@MainActivity, KeepService::class.java))
                            } catch (e: Exception) { }
                        }
                        resolve(id, "\"ok\"")
                    }
                    "schedule" -> {
                        Scheduler.save(applicationContext, args)
                        resolve(id, "\"ok\"")
                    }
                    "battery" -> {
                        runOnUiThread {
                            try { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } catch (e: Exception) { }
                        }
                        resolve(id, "\"ok\"")
                    }
                    "disconnect" -> {
                        gatts.remove(a.getString("a"))?.let { try { it.disconnect(); it.close() } catch (e: Exception) { } }
                        resolve(id, "\"ok\"")
                    }
                    "write" -> write(id, a.getString("a"), a.getString("c"), a.getString("h"), a.optBoolean("nr"))
                    else -> reject(id, "unknown method")
                }
            } catch (e: Exception) {
                reject(id, e.message ?: "error")
            }
        }
    }

    private fun js(code: String) { runOnUiThread { web.evaluateJavascript(code, null) } }
    private fun resolve(id: Int, json: String) = js("window.__bleResult($id,true,$json)")
    private fun reject(id: Int, msg: String) = js("window.__bleResult($id,false,${JSONObject.quote(msg)})")

    /* ---------- разрешения ---------- */

    private fun missing(): List<String> {
        val l = ArrayList<String>()
        if (Build.VERSION.SDK_INT >= 31) {
            l.add(Manifest.permission.BLUETOOTH_SCAN)
            l.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        l.add(Manifest.permission.ACCESS_FINE_LOCATION)
        return l.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
    }

    private fun mustMissing(): List<String> =
        missing().filter { Build.VERSION.SDK_INT < 31 || it != Manifest.permission.ACCESS_FINE_LOCATION }

    private fun startScan(id: Int) {
        if (mustMissing().isEmpty()) { doScan(id); return }
        pendingScan = { doScan(id) }
        pendingFail = { reject(id, "Нужно разрешение Bluetooth: Настройки, Приложения, Освещение, Разрешения") }
        requestPermissions(missing().toTypedArray(), 1)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != 1) return
        val run = pendingScan
        val fail = pendingFail
        pendingScan = null
        pendingFail = null
        if (mustMissing().isEmpty()) run?.invoke() else fail?.invoke()
    }

    /* ---------- поиск устройств ---------- */

    private fun rank(n: String): Int = if (n.startsWith("LEDnet", true)) 0 else if (n.isNotEmpty()) 1 else 2

    private fun doScan(id: Int) {
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null || !adapter.isEnabled) { reject(id, "Включите Bluetooth"); return }
        if (Build.VERSION.SDK_INT < 31 && getSystemService(LocationManager::class.java)?.isLocationEnabled == false) {
            reject(id, "Включите геолокацию в шторке: без неё Android не ищет Bluetooth-устройства")
            return
        }
        val scanner = adapter.bluetoothLeScanner
        if (scanner == null) { reject(id, "Bluetooth недоступен"); return }

        val found = LinkedHashMap<String, Pair<String, Int>>()
        var cancelled = false
        val cbk = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val n = result.scanRecord?.deviceName ?: result.device.name ?: ""
                val old = found[result.device.address]
                found[result.device.address] = Pair(if (n.isNotEmpty()) n else (old?.first ?: ""), result.rssi)
            }
        }
        scanner.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), cbk)

        val wait = AlertDialog.Builder(this)
            .setTitle("Поиск Bluetooth…")
            .setMessage("Подождите несколько секунд. Лента должна быть включена, а приложение Zengge закрыто.")
            .setCancelable(false)
            .setNegativeButton("Отмена") { _, _ ->
                cancelled = true
                try { scanner.stopScan(cbk) } catch (e: Exception) { }
                reject(id, "NotFoundError: cancelled")
            }
            .show()

        ui.postDelayed({
            if (cancelled) return@postDelayed
            try { scanner.stopScan(cbk) } catch (e: Exception) { }
            wait.dismiss()
            val items = found.entries
                .sortedWith(compareBy<Map.Entry<String, Pair<String, Int>>>({ rank(it.value.first) }, { -it.value.second }))
                .take(30)
            if (items.isEmpty()) {
                reject(id, "Устройства не найдены. Проверьте, что лента включена и Zengge закрыт.")
                return@postDelayed
            }
            val labels = items.map {
                (if (it.value.first.isEmpty()) "Без имени" else it.value.first) +
                    "  (" + it.key.takeLast(5) + ", " + it.value.second + " dBm)"
            }.toTypedArray()
            AlertDialog.Builder(this)
                .setTitle("Выберите ленту")
                .setItems(labels) { _, i ->
                    val e = items[i]
                    resolve(id, JSONObject().put("id", e.key).put("name", e.value.first).toString())
                }
                .setNegativeButton("Отмена") { _, _ -> reject(id, "NotFoundError: cancelled") }
                .setOnCancelListener { reject(id, "NotFoundError: cancelled") }
                .show()
        }, 6000)
    }

    /* ---------- соединение ---------- */

    private val cb = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            val addr = g.device.address
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                if (!g.requestMtu(185)) g.discoverServices()
            } else {
                chars.keys.removeAll { it.startsWith("$addr|") }
                gatts.remove(addr)
                try { g.close() } catch (e: Exception) { }
                val id = connectIds.remove(addr)
                if (id != null) reject(id, "Не удалось подключиться (код $status)")
                else js("window.__bleEvent(${JSONObject.quote(addr)})")
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            g.discoverServices()
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val addr = g.device.address
            val arr = JSONArray()
            for (s in g.services) {
                val ca = JSONArray()
                for (c in s.characteristics) {
                    chars["$addr|${c.uuid}"] = c
                    ca.put(
                        JSONObject()
                            .put("uuid", c.uuid.toString())
                            .put("write", (c.properties and BluetoothGattCharacteristic.PROPERTY_WRITE) != 0)
                            .put("wnr", (c.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0)
                    )
                }
                arr.put(JSONObject().put("uuid", s.uuid.toString()).put("chars", ca))
            }
            val id = connectIds.remove(addr)
            if (id != null) resolve(id, arr.toString())
        }
    }

    private fun connect(id: Int, addr: String) {
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null) { reject(id, "Bluetooth недоступен"); return }
        gatts.remove(addr)?.let { try { it.disconnect(); it.close() } catch (e: Exception) { } }
        connectIds[addr] = id
        runOnUiThread {
            gatts[addr] = adapter.getRemoteDevice(addr).connectGatt(this, false, cb, BluetoothDevice.TRANSPORT_LE)
            ui.postDelayed({
                if (connectIds[addr] == id) {
                    connectIds.remove(addr)
                    gatts.remove(addr)?.let { try { it.disconnect(); it.close() } catch (e: Exception) { } }
                    reject(id, "Лента не отвечает. Закройте Zengge и попробуйте снова.")
                }
            }, 15000)
        }
    }

    private fun write(id: Int, addr: String, uuid: String, hex: String, nr: Boolean) {
        val g = gatts[addr]
        val c = chars["$addr|$uuid"]
        if (g == null || c == null) { reject(id, "Нет соединения с лентой"); return }
        val bytes = ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        val canNoResp = (c.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
        c.writeType = if (nr && canNoResp) BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                      else BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        c.value = bytes
        var ok = false
        var tries = 0
        while (!ok && tries < 6) {
            ok = g.writeCharacteristic(c)
            if (!ok) { Thread.sleep(25); tries++ }
        }
        if (ok) resolve(id, "\"ok\"") else reject(id, "Запись не удалась")
    }
}


/* ---------------- Расписание (работает при закрытом приложении) ---------------- */

object AppState {
    val gatts = ConcurrentHashMap<String, BluetoothGatt>()
    val chars = ConcurrentHashMap<String, BluetoothGattCharacteristic>()
}

@SuppressLint("MissingPermission")
@Suppress("DEPRECATION")
fun writePkt(g: BluetoothGatt, c: BluetoothGattCharacteristic, pkt: ByteArray) {
    val nr = (c.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
    c.writeType = if (nr) BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE else BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
    c.value = pkt
    g.writeCharacteristic(c)
}

@SuppressLint("MissingPermission")
@Suppress("DEPRECATION")
class OneShot(
    private val ctx: Context,
    private val addr: String,
    private val uuid: String,
    private val pkt: ByteArray,
    private val onDone: () -> Unit
) : BluetoothGattCallback() {
    private var finished = false
    private val h = Handler(Looper.getMainLooper())
    private var gatt: BluetoothGatt? = null

    private fun finish() {
        if (finished) return
        finished = true
        try { gatt?.close() } catch (e: Exception) { }
        onDone()
    }

    fun start() {
        val adapter = ctx.getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null || !adapter.isEnabled) { finish(); return }
        try {
            gatt = adapter.getRemoteDevice(addr).connectGatt(ctx, false, this, BluetoothDevice.TRANSPORT_LE)
        } catch (e: Exception) { finish(); return }
        h.postDelayed({ finish() }, 15000)
    }

    override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
        if (newState == BluetoothProfile.STATE_CONNECTED) {
            if (!g.requestMtu(185)) g.discoverServices()
        } else {
            finish()
        }
    }

    override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) { g.discoverServices() }

    override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
        val target = if (uuid.isEmpty()) "0000ff01-0000-1000-8000-00805f9b34fb" else uuid
        var found: BluetoothGattCharacteristic? = null
        for (s in g.services) for (ch in s.characteristics) if (ch.uuid.toString() == target) found = ch
        val c = found
        if (c == null) { g.disconnect(); return }
        writePkt(g, c, pkt)
        h.postDelayed({ g.disconnect() }, 500)
    }
}

@SuppressLint("MissingPermission")
object Scheduler {
    private const val ACTION = "com.example.ambilight.ALARM"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("lumo", Context.MODE_PRIVATE)

    private fun root(ctx: Context): JSONObject =
        try { JSONObject(prefs(ctx).getString("sched", "{}") ?: "{}") } catch (e: Exception) { JSONObject() }

    fun save(ctx: Context, json: String) {
        val inc = JSONObject(json)
        val old = root(ctx)
        if ((inc.optJSONArray("devs")?.length() ?: 0) == 0 && old.has("devs")) inc.put("devs", old.getJSONArray("devs"))
        prefs(ctx).edit().putString("sched", inc.toString()).apply()
        arm(ctx)
    }

    private fun hasDay(x: JSONObject, dow0: Int): Boolean {
        val d = x.optJSONArray("d") ?: return false
        for (i in 0 until d.length()) if (d.getInt(i) == dow0) return true
        return false
    }

    private fun pending(ctx: Context): PendingIntent =
        PendingIntent.getBroadcast(
            ctx, 7, Intent(ctx, AlarmReceiver::class.java).setAction(ACTION),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    fun arm(ctx: Context) {
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        val pi = pending(ctx)
        am.cancel(pi)
        val arr = root(ctx).optJSONArray("items") ?: return
        val now = System.currentTimeMillis()
        var best = Long.MAX_VALUE
        for (i in 0 until arr.length()) {
            val x = arr.getJSONObject(i)
            if (!x.optBoolean("e", true)) continue
            val parts = x.optString("t", "00:00").split(":")
            for (off in 0..7) {
                val c = Calendar.getInstance()
                c.add(Calendar.DAY_OF_YEAR, off)
                c.set(Calendar.HOUR_OF_DAY, parts[0].toInt())
                c.set(Calendar.MINUTE, parts[1].toInt())
                c.set(Calendar.SECOND, 0)
                c.set(Calendar.MILLISECOND, 0)
                if (c.timeInMillis > now + 1000 && hasDay(x, c.get(Calendar.DAY_OF_WEEK) - 1)) {
                    if (c.timeInMillis < best) best = c.timeInMillis
                    break
                }
            }
        }
        if (best == Long.MAX_VALUE) return
        try {
            if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, best, pi)
            } else {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, best, pi)
            }
        } catch (e: Exception) { }
    }

    private fun wrapLed(p: IntArray): ByteArray {
        var sum = 0
        for (b in p) sum = (sum + b) and 255
        val n = p.size + 1
        val seq = ((System.currentTimeMillis() / 1000) % 250).toInt() + 1
        val full = IntArray(8 + p.size + 1)
        val head = intArrayOf(0, seq, 0x80, 0, 0, n, n + 1, 0x0B)
        System.arraycopy(head, 0, full, 0, 8)
        System.arraycopy(p, 0, full, 8, p.size)
        full[full.size - 1] = sum
        return ByteArray(full.size) { full[it].toByte() }
    }

    fun runDue(ctx: Context, done: () -> Unit) {
        val p = prefs(ctx)
        val r = root(ctx)
        val arr = r.optJSONArray("items") ?: JSONArray()
        val devs = r.optJSONArray("devs") ?: JSONArray()
        val now = System.currentTimeMillis()
        var action: String? = null
        for (i in 0 until arr.length()) {
            val x = arr.getJSONObject(i)
            if (!x.optBoolean("e", true)) continue
            val parts = x.optString("t", "00:00").split(":")
            val c = Calendar.getInstance()
            c.set(Calendar.HOUR_OF_DAY, parts[0].toInt())
            c.set(Calendar.MINUTE, parts[1].toInt())
            c.set(Calendar.SECOND, 0)
            c.set(Calendar.MILLISECOND, 0)
            val diff = now - c.timeInMillis
            if (diff < -5000 || diff > 120000) continue
            if (!hasDay(x, c.get(Calendar.DAY_OF_WEEK) - 1)) continue
            val key = "last_" + x.optString("id")
            if (p.getLong(key, 0L) == c.timeInMillis) continue
            p.edit().putLong(key, c.timeInMillis).apply()
            action = x.optString("a")
        }
        arm(ctx)
        val act = action
        if (act == null || devs.length() == 0) { done(); return }

        var left = devs.length()
        val lock = Any()
        val finish: () -> Unit = {
            var last = false
            synchronized(lock) { left--; last = left == 0 }
            if (last) done()
        }
        for (i in 0 until devs.length()) {
            val d = devs.getJSONObject(i)
            val addr = d.getString("a")
            val uuid = d.optString("c")
            val ja = d.getJSONArray(if (act == "on") "on" else "off")
            val payload = IntArray(ja.length()) { ja.getInt(it) }
            val pkt = if (d.optString("p").startsWith("ledwf")) wrapLed(payload)
                      else ByteArray(payload.size) { payload[it].toByte() }
            val g = AppState.gatts[addr]
            val c = AppState.chars["$addr|$uuid"]
            if (g != null && c != null) {
                try { writePkt(g, c, pkt) } catch (e: Exception) { }
                finish()
            } else {
                OneShot(ctx, addr, uuid, pkt, finish).start()
            }
        }
    }
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val pr = goAsync()
        val wl = ctx.getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "lumo:alarm")
        wl.acquire(30000)
        var fin = false
        val end: () -> Unit = {
            if (!fin) {
                fin = true
                try { if (wl.isHeld) wl.release() } catch (e: Exception) { }
                pr.finish()
            }
        }
        try {
            Scheduler.runDue(ctx.applicationContext) { end() }
        } catch (e: Exception) { end() }
        Handler(Looper.getMainLooper()).postDelayed({ end() }, 25000)
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        Scheduler.arm(ctx.applicationContext)
    }
}

/* Держит приложение живым и связь с лентой активной, пока устройства подключены */
class KeepService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("keep", "Связь с лентой", NotificationManager.IMPORTANCE_LOW))
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
            PendingIntent.FLAG_IMMUTABLE
        )
        val n = Notification.Builder(this, "keep")
            .setContentTitle("Lumo")
            .setContentText("Связь с лентой активна")
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
        try {
            startForeground(2, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } catch (e: Exception) {
            stopSelf()
        }
        return START_NOT_STICKY
    }
}
