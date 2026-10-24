import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import vm from 'node:vm';
import test from 'node:test';
import assert from 'node:assert/strict';

// 执行实际交付脚本，检查每次操作的专属页面及延迟响应。
const page = readFileSync(fileURLToPath(new URL('../../app/src/main/java/tv/dandan/app/remote/RemotePage.kt', import.meta.url)), 'utf8');
const property = name => page.match(new RegExp(`val ${name}(?:\\s*:\\s*String)?\\s*=\\s*"""([\\s\\S]*?)"""`))?.[1];
const rootHtml = property('html');
const formHtml = property('formHtml') ?? rootHtml;
const script = formHtml.match(/<script>([\s\S]*?)<\/script>/)[1];
const A = '11111111-1111-4111-8111-111111111111';
const B = '22222222-2222-4222-8222-222222222222';
const scoped = `/api/input/${A}`;
const tick = () => new Promise(resolve => setImmediate(resolve));
class Element {
  constructor(tagName = 'div') { this.tagName = tagName; this.children = []; this.dataset = {}; this.value = ''; this.checked = false; this.disabled = false; this.hidden = false; }
  append(...items) { this.children.push(...items); }
  replaceChildren(...items) { this.children = items; }
}
function harness() {
  const nodes = new Map();
  const calls = [];
  const all = node => [node, ...node.children.flatMap(all)];
  const document = {
    getElementById: id => { if (!nodes.has(id)) nodes.set(id, new Element()); return nodes.get(id); },
    createElement: tag => new Element(tag), createTextNode: text => Object.assign(new Element(), {textContent: text}),
    querySelectorAll: selector => selector === '[data-action]' ? [] : all(document.getElementById('input-fields')).filter(n => selector === '[data-key]' ? n.dataset.key : n.dataset.clearSecret && n.checked)
  };
  const context = vm.createContext({document, URLSearchParams, TextEncoder, AbortController,
    location: {search:'?token=test-token', pathname:`/input/${A}`}, history:{replaceState(){}},
    sessionStorage:{setItem(){},getItem(){return null;}},setInterval(){},setTimeout(){return 1;},clearTimeout(){},
    fetch: (path, options) => new Promise(resolve => calls.push({path,options,resolve}))});
  vm.runInContext(script, context);
  const respond = (call, data) => { assert.ok(call, '应该发出预期请求'); call.resolve({ok:true,json:async()=>({success:true,...data})}); };
  const next = (path, method='GET') => calls.find(c => !c.used && c.path===path && c.options.method===method && (c.used=true));
  return {document,context,calls,respond,next,run:code=>vm.runInContext(code,context), fields:()=>document.querySelectorAll('[data-key]')};
}
const form = (id=A, fields=[{key:'name',label:'名称',value:'',maxLength:100}]) => ({id,title:'本次操作',fields});
const inputCall = h => h.next(scoped) ?? h.next('/api/input');
async function ready(fields) {
  const h=harness();const status=h.next('/api/status');if(status)h.respond(status,{status:null});
  h.respond(inputCall(h),{form:form(A,fields)});await tick();return h;
}
test('遥控首页与本次输入页面分离，只请求路径指定的会话',async()=>{
  assert.ok(!rootHtml.includes('id="input-form"'));
  assert.ok(!rootHtml.includes('/api/input'));
  assert.ok(!formHtml.includes('data-action="toggle"'));
  const h=await ready();assert.ok(h.calls.some(c=>c.path===scoped));
  assert.ok(!h.calls.some(c=>c.path==='/api/input'||c.path==='/api/status'));
});
test('A提交完成后不跟随B，旧轮询也不能复活A',async()=>{
  const h=await ready();h.fields()[0].value='A 内容';
  const polling=h.run('refreshInput()');const old=inputCall(h);
  const submitting=h.document.getElementById('input-form').onsubmit({preventDefault(){}});
  h.respond(h.next('/api/input','POST'),{});await submitting;
  h.respond(old,{form:form(B)});await polling;
  const count=h.calls.length;h.run('refreshInput()');
  assert.equal(h.run('activeForm'),null);assert.equal(h.calls.length,count);
  assert.match(h.document.getElementById('input-status').textContent,/完成|已送达/);
});
test('取消或过期停留在结束页，不显示另一会话',async()=>{
  const h=await ready();const polling=h.run('refreshInput()');const old=inputCall(h);
  const cancelling=h.document.getElementById('cancel-input').onclick();
  h.respond(h.next('/api/input/cancel','POST'),{});await cancelling;
  h.respond(old,{form:form(B)});await polling;
  assert.equal(h.run('activeForm'),null);assert.match(h.document.getElementById('input-status').textContent,/取消|结束/);
  const expired=await ready();const refreshing=expired.run('refreshInput()');expired.respond(inputCall(expired),{form:null});await refreshing;
  const count=expired.calls.length;expired.run('refreshInput()');assert.equal(expired.calls.length,count);
});
test('慢GET跨过刷新周期仍显示且保持草稿，不并发第二个GET',async()=>{
  const h=await ready();h.fields()[0].value='保留草稿';
  const first=h.run('refreshInput()');const slow=inputCall(h);const count=h.calls.length;
  await h.run('refreshInput()');assert.equal(h.calls.length,count);
  h.respond(slow,{form:form()});await first;
  assert.equal(h.fields()[0].value,'保留草稿');
});
test('来源选择渲染select，条件字段隐藏且不提交，切回来保留草稿',async()=>{
  const fields=[
    {key:'kind',label:'类型',value:'HTTP',required:true,maxLength:10,options:[{value:'HTTP',label:'视频链接'},{value:'SMB',label:'家庭共享'}]},
    {key:'location',label:'地址',value:'https://example.com/a.mp4',required:true,maxLength:8192},
    {key:'username',label:'账号',value:'',required:true,maxLength:200,showWhen:{key:'kind',values:['SMB']}},
  ];
  const h=await ready(fields);const byKey=key=>h.fields().find(n=>n.dataset.key===key);
  const select=byKey('kind');assert.equal(select.tagName,'select');assert.equal(select.children[1].textContent,'家庭共享');
  assert.equal(byKey('username').disabled,true);assert.equal(byKey('username').required,false);
  select.value='SMB';select.onchange();assert.equal(byKey('username').disabled,false);assert.equal(byKey('username').required,true);
  byKey('username').value='草稿账号';select.value='HTTP';select.onchange();select.value='SMB';select.onchange();
  assert.equal(byKey('username').value,'草稿账号');select.value='HTTP';select.onchange();
  const submitting=h.document.getElementById('input-form').onsubmit({preventDefault(){}});const post=h.next('/api/input','POST');
  assert.ok(!Object.hasOwn(JSON.parse(post.options.body).values,'username'));h.respond(post,{});await submitting;
});
test('最大合法中文屏蔽规则可按UTF8预算完整提交',async()=>{
  const keywords=Array.from({length:200},(_,i)=>String(i).padStart(3,'0')+'字'.repeat(197)).join('\n');
  const patterns=Array.from({length:100},(_,i)=>String(i).padStart(3,'0')+'规'.repeat(253)).join('\n');
  const h=await ready([{key:'keywords',label:'关键字',value:keywords,multiline:true,maxLength:65536},
    {key:'patterns',label:'正则',value:patterns,multiline:true,maxLength:65536}]);
  const submitting=h.document.getElementById('input-form').onsubmit({preventDefault(){}});
  const post=h.next('/api/input','POST');assert.ok(post,'完整合法规则不能被旧64KiB预算拦截');
  assert.ok(new TextEncoder().encode(post.options.body).length>65536);
  assert.equal(JSON.parse(post.options.body).values.keywords,keywords);
  assert.equal(JSON.parse(post.options.body).values.patterns,patterns);
  h.respond(post,{});await submitting;
});
