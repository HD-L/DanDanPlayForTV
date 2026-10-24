package tv.dandan.app.remote

internal object RemotePage {
    val html: String = """
<!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<meta name="referrer" content="no-referrer"><title>弹弹 TV · 遥控器</title>
<style>body{background:#101923;color:#eef7f4;font:18px system-ui;max-width:600px;margin:24px auto;padding:20px}h1{color:#72e0bc}button,input{box-sizing:border-box;border:2px solid #33475b;border-radius:12px;padding:16px;background:#1d2b3b;color:white;font:inherit;margin:6px 0}button{cursor:pointer;width:48%}button:focus,input:focus{outline:3px solid #72e0bc}input{width:100%}#status{line-height:1.6;color:#b5c9d5}small{color:#b5c9d5}</style>
<h1>弹弹 TV 遥控器</h1><p id="status">正在连接电视…</p>
<button data-action="toggle">播放 / 暂停</button> <button data-action="stop">结束播放</button>
<button onclick="control('seekBy',{offset:-10000})">快退 10 秒</button> <button onclick="control('seekBy',{offset:10000})">快进 10 秒</button>
<p><label>播放倍速 <input id="speed" type="number" min="0.25" max="3" step="0.25" value="1"></label><button onclick="control('speed',{speed:Number(document.getElementById('speed').value)})">设置倍速</button></p>
<p><label>播放器音量（0–100%）<input id="volume" type="range" min="0" max="100" step="5" value="100"></label><button onclick="control('volume',{percent:Number(document.getElementById('volume').value)})">设置音量</button></p>
<p><label>媒体地址 <input id="url" placeholder="http:// 或 https:// 视频地址" type="url"></label><button onclick="openMedia()">投送到电视</button></p>
<small>请保持手机与电视连接同一局域网。填写来源、凭证等信息时，请扫描电视为本次操作显示的专属二维码。停止或重新配对会撤销旧令牌。</small>
<script>
const suppliedToken=new URLSearchParams(location.search).get('token');
if(suppliedToken)sessionStorage.setItem('dandanTvToken',suppliedToken);
const token=suppliedToken||sessionStorage.getItem('dandanTvToken')||'';
history.replaceState(null,'',location.pathname);
async function request(path,body){const abort=new AbortController();const timer=setTimeout(()=>abort.abort(),10000);try{const r=await fetch(path,{method:body?'POST':'GET',headers:{'Authorization':'Bearer '+token,'Content-Type':'application/json'},body:body?JSON.stringify(body):undefined,signal:abort.signal});const data=await r.json();if(!r.ok||!data.success)throw Error(data.errorMessage||'请求失败');return data;}catch(e){if(e.name==='AbortError')throw Error('连接超时，请检查电视与网络后重试');throw e;}finally{clearTimeout(timer);}}
function show(s){document.getElementById('status').textContent=s? s.title+' · '+(s.playing?'播放中':'已暂停')+' · '+Math.floor(s.position/1000)+' / '+Math.floor(s.duration/1000)+' 秒':'已连接，电视当前没有播放媒体';}
async function refresh(){try{const r=await request('/api/status');show(r.status);}catch(e){document.getElementById('status').textContent=e.message;}}
async function control(action,params={}){try{const r=await request('/api/control',{action,...params});show(r.status);}catch(e){document.getElementById('status').textContent=e.message;}}
async function openMedia(){try{await request('/api/open',{url:document.getElementById('url').value});document.getElementById('status').textContent='已发送，请查看电视';}catch(e){document.getElementById('status').textContent=e.message;}}
document.querySelectorAll('[data-action]').forEach(b=>b.onclick=()=>control(b.dataset.action));refresh();setInterval(refresh,2500);
</script></html>
""".trimIndent()

    val formHtml: String = """
<!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<meta name="referrer" content="no-referrer"><title>弹弹 TV · 本次输入</title>
<style>body{background:#101923;color:#eef7f4;font:18px system-ui;max-width:600px;margin:24px auto;padding:20px}h1{color:#72e0bc}button,input,textarea,select{box-sizing:border-box;border:2px solid #33475b;border-radius:12px;padding:16px;background:#1d2b3b;color:white;font:inherit;margin:6px 0}button{cursor:pointer;width:48%}button:focus,input:focus,textarea:focus,select:focus{outline:3px solid #72e0bc}input,textarea,select{width:100%}input[type=checkbox]{width:auto}#input-status{line-height:1.6;color:#b5c9d5}label{display:block;margin:16px 0}textarea{min-height:150px}#send-input{background:#245846;width:100%}[hidden]{display:none!important}small{color:#b5c9d5}</style>
<h1 id="input-title">本次手机输入</h1><p id="input-status">正在连接本次操作…</p>
<form id="input-form" autocomplete="off" hidden><div id="input-fields"></div><button id="send-input" type="submit">提交到电视</button><button id="cancel-input" type="button">取消本次输入</button></form>
<small>此页面只用于本次操作。提交、取消或过期后，请回到电视；新的操作需要扫描新的二维码。</small>
<script>
const suppliedToken=new URLSearchParams(location.search).get('token');
if(suppliedToken)sessionStorage.setItem('dandanTvToken',suppliedToken);
const token=suppliedToken||sessionStorage.getItem('dandanTvToken')||'';
const formId=location.pathname.substring('/input/'.length);
history.replaceState(null,'',location.pathname);
async function request(path,body){const abort=new AbortController();const timer=setTimeout(()=>abort.abort(),10000);try{const r=await fetch(path,{method:body?'POST':'GET',headers:{'Authorization':'Bearer '+token,'Content-Type':'application/json'},body:body?JSON.stringify(body):undefined,signal:abort.signal});const data=await r.json();if(!r.ok||!data.success){const error=Error(data.errorMessage||'请求失败');error.status=r.status;throw error;}return data;}catch(e){if(e.name==='AbortError')throw Error('连接超时，请检查电视与网络后重试');throw e;}finally{clearTimeout(timer);}}
let activeForm=null,inputRevision=0,inputPolling=false,inputEnded=false;
const pendingForms=new Set(),fieldRows=new Map();
function inputButtons(){const disabled=inputEnded||pendingForms.has(formId);document.getElementById('send-input').disabled=disabled;document.getElementById('cancel-input').disabled=disabled;}
function finishInput(message){inputEnded=true;++inputRevision;activeForm=null;fieldRows.clear();document.getElementById('input-fields').replaceChildren();document.getElementById('input-form').hidden=true;document.getElementById('input-status').textContent=message;inputButtons();}
function updateFieldVisibility(){fieldRows.forEach(({field,row,input,clear})=>{const condition=field.showWhen;const visible=!condition||condition.values.includes(fieldRows.get(condition.key)?.input.value);row.hidden=!visible;input.disabled=!visible;if(clear)clear.disabled=!visible;input.required=visible&&field.required&&!(field.secret&&field.hasStoredValue&&!(clear&&clear.checked));});}
function renderInput(form){
if(inputEnded)return;
if(!form||form.id!==formId){finishInput('本次输入已结束、取消或过期。请回到电视重新操作并扫描新的二维码。');return;}
if(activeForm&&activeForm.id===form.id)return;
activeForm=form;document.getElementById('input-title').textContent=form.title;document.getElementById('input-status').textContent=form.hint||'填写后提交，电视会继续本次操作。表单十分钟内有效。';
const fields=document.getElementById('input-fields');fields.replaceChildren();fieldRows.clear();
form.fields.forEach(field=>{const row=document.createElement('div');const label=document.createElement('label');label.textContent=field.label;const options=field.options||[];const input=document.createElement(options.length?'select':field.multiline?'textarea':'input');input.dataset.key=field.key;input.maxLength=field.maxLength;if(options.length){options.forEach(option=>{const item=document.createElement('option');item.value=option.value;item.textContent=option.label;input.append(item);});input.value=options.some(option=>option.value===field.value)?field.value:options[0].value;}else{input.value=field.value||'';if(!field.multiline){input.type=field.secret?'password':'text';input.autocomplete=field.secret?'new-password':'off';}}if(field.secret&&field.hasStoredValue)input.placeholder='已保存；留空保留原值';label.append(input);row.append(label);let clear=null;if(field.secret&&field.hasStoredValue){const clearLabel=document.createElement('label');clear=document.createElement('input');clear.type='checkbox';clear.dataset.clearSecret=field.key;clear.onchange=updateFieldVisibility;clearLabel.append(clear,document.createTextNode(' 清除已存的'+field.label));row.append(clearLabel);}fieldRows.set(field.key,{field,row,input,clear});input.onchange=updateFieldVisibility;input.oninput=updateFieldVisibility;fields.append(row);});
updateFieldVisibility();document.getElementById('input-form').hidden=false;inputButtons();
}
async function refreshInput(){if(inputPolling||inputEnded||pendingForms.has(formId))return;inputPolling=true;const revision=++inputRevision;try{const data=await request('/api/input/'+encodeURIComponent(formId));if(revision===inputRevision)renderInput(data.form);}catch(e){if(revision===inputRevision){if(e.status===401)finishInput('配对已失效，本次输入已结束。请在电视重新操作。');else document.getElementById('input-status').textContent=e.message;}}finally{inputPolling=false;}}
document.getElementById('input-form').onsubmit=async event=>{event.preventDefault();if(inputEnded||!activeForm||activeForm.id!==formId||pendingForms.has(formId))return;const id=activeForm.id;const values={};document.querySelectorAll('[data-key]').forEach(input=>{if(!input.disabled)values[input.dataset.key]=input.value;});const clearSecrets=Array.from(document.querySelectorAll('[data-clear-secret]:checked')).filter(input=>!input.disabled).map(input=>input.dataset.clearSecret);const body={id,values,clearSecrets};if(new TextEncoder().encode(JSON.stringify(body)).length>260000){document.getElementById('input-status').textContent='输入内容过长，请减少文字或规则数量';return;}pendingForms.add(id);++inputRevision;inputButtons();try{await request('/api/input',body);if(id===formId)finishInput('本次输入已完成并送达电视，请返回电视查看结果。');}catch(e){if(activeForm&&activeForm.id===id&&!inputEnded){if(e.status===401)finishInput('配对已失效，本次输入已结束。请在电视重新操作。');else document.getElementById('input-status').textContent=e.message;}}finally{pendingForms.delete(id);inputButtons();}};
document.getElementById('cancel-input').onclick=async()=>{if(inputEnded||!activeForm||activeForm.id!==formId||pendingForms.has(formId))return;const id=activeForm.id;pendingForms.add(id);++inputRevision;inputButtons();try{await request('/api/input/cancel',{id});if(id===formId)finishInput('本次输入已取消，请返回电视。');}catch(e){if(activeForm&&activeForm.id===id&&!inputEnded)document.getElementById('input-status').textContent=e.message;}finally{pendingForms.delete(id);inputButtons();}};
refreshInput();setInterval(refreshInput,1500);
</script></html>
""".trimIndent()
}
