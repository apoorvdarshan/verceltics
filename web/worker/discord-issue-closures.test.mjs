import {test, afterEach} from "node:test";
import assert from "node:assert/strict";
import {GITHUB_ISSUES_WEBHOOK_PATH, handleGitHubIssueClosure, rememberDiscordIssue, retryDiscordIssueClosures, closureMessage} from "./discord-issue-closures.mjs";
import {handleDiscordInteractionsRequest} from "./discord-interactions.mjs";
const USER = '123456789012345678';
const CHANNEL = '223456789012345678';
const MESSAGE = '323456789012345678';
const SECRET = 'unit-test-only-secret';
const REPO = 'apoorvdarshan/verceltics';
function setup() {
  const data = new Map();
  const state = {
    async get(key) { return data.get(key) ?? null; },
    async put(key, value) { data.set(key, value); },
    async delete(key) { data.delete(key); },
    async list({prefix}) { return { keys: [...data.keys()].filter(k => k.startsWith(prefix)).map(name => ({name})), list_complete: true }; },
  };
  return {data, env: { DISCORD_STATE: state, DISCORD_BOT_TOKEN:'unit-bot-token', DISCORD_GITHUB_WEBHOOK_SECRET: SECRET }};
}
const link = {userId:USER,channelId:CHANNEL,messageId:MESSAGE};
const payload = (extra = {}) => ({ repository:{full_name:REPO},action:'closed',issue:{number:42,state:'closed',closed_at:'2026-10-12T15:00:00Z',state_reason:'completed'},...extra });
async function request(body, event='issues', secret=SECRET) {
  const raw = JSON.stringify(body);
  const key = await crypto.subtle.importKey('raw',new TextEncoder().encode(secret),{name:'HMAC',hash:'SHA-256'},false,['sign']);
  const signed = await crypto.subtle.sign('HMAC',key,new TextEncoder().encode(raw));
  const hex = [...new Uint8Array(signed)].map(b=>b.toString(16).padStart(2,'0')).join('');
  return new Request('https://example.com'+GITHUB_ISSUES_WEBHOOK_PATH,{method:'POST',headers:{'x-hub-signature-256':'sha256='+hex,'x-github-event':event},body:raw});
}
async function deliver(env, body=payload(), event='issues', secret=SECRET) {
  const pending=[];
  const response=await handleGitHubIssueClosure(await request(body,event,secret),env,{waitUntil:p=>pending.push(p)});
  await Promise.all(pending);
  return response;
}
const nativeFetch=globalThis.fetch;
afterEach(()=>{ globalThis.fetch=nativeFetch; });

test('signed closure replies to original report and mentions only its submitter',async()=>{
  const {env,data}=setup();
  assert.equal(await rememberDiscordIssue(env,42,link),true);
  const calls=[];
  globalThis.fetch=async(url,init)=>{calls.push([url,JSON.parse(init.body)]);return Response.json({id:'423456789012345678'});};
  assert.equal((await deliver(env)).status,202);
  assert.equal(calls.length,1);
  assert.equal(calls[0][0],`https://discord.com/api/v10/channels/${CHANNEL}/messages`);
  assert.deepEqual(calls[0][1].allowed_mentions,{parse:[],users:[USER],replied_user:false});
  assert.equal(calls[0][1].message_reference.message_id,MESSAGE);
  assert.equal(calls[0][1].message_reference.fail_if_not_exists,false);
  assert.equal(calls[0][1].enforce_nonce,true);
  assert.match(calls[0][1].content,/closed as completed/);
  assert.match(calls[0][1].content,/not confirmation of an app release/);
  assert.equal([...data.keys()].filter(k=>k.startsWith('issue-close:pending:')).length,0);
  assert.equal((await deliver(env)).status,204);
  assert.equal(calls.length,1);
});

test('unsigned and wrong-secret requests cannot send notifications',async()=>{
  const {env}=setup(); await rememberDiscordIssue(env,42,link);
  globalThis.fetch=async()=>{throw Error('must not call Discord');};
  assert.equal((await deliver(env,payload(),'issues','wrong')).status,401);
  assert.equal((await handleGitHubIssueClosure(new Request('https://example.com',{method:'POST',body:'{}'}),env,{})).status,401);
});

test('ignores other repos, pull requests, reopened issues and reports without a private mapping',async()=>{
  const {env}=setup();
  assert.equal((await deliver(env)).status,204);
  await rememberDiscordIssue(env,42,link);
  assert.equal((await deliver(env,payload({repository:{full_name:'someone/other'}}))).status,403);
  assert.equal((await deliver(env,payload({action:'reopened'}))).status,204);
  assert.equal((await deliver(env,payload({issue:{...payload().issue,pull_request:{}}}))).status,204);
  assert.equal((await deliver(env,payload(),'issue_comment')).status,204);
});

test('failed send stays queued and retries successfully without changing its nonce',async()=>{
  const {env,data}=setup();await rememberDiscordIssue(env,42,link);
  const nonces=[];let succeeds=false;
  globalThis.fetch=async(url,init)=>{nonces.push(JSON.parse(init.body).nonce);return new Response(null,{status:succeeds?200:429});};
  await deliver(env);
  assert.equal([...data.keys()].filter(k=>k.startsWith('issue-close:pending:')).length,1);
  succeeds=true;
  await retryDiscordIssueClosures(env);
  await retryDiscordIssueClosures(env);
  assert.equal(nonces.length,2);assert.equal(nonces[0],nonces[1]);
});

test('failed queue persistence returns 503 without sending',async()=>{
  const {env}=setup();await rememberDiscordIssue(env,42,link);
  env.DISCORD_STATE.put=async()=>{throw Error('offline');};
  assert.equal((await deliver(env)).status,503);
});

test('mapping validates IDs and stores no tokens or report text',async()=>{
  const {env,data}=setup();
  assert.equal(await rememberDiscordIssue(env,42,{...link,userId:'@everyone'}),false);
  assert.equal(await rememberDiscordIssue(env,-1,link),false);
  assert.equal(await rememberDiscordIssue(env,42,link),true);
  assert.deepEqual(JSON.parse(data.get('issue-close:link:42')),link);
});

test('completed, not planned, duplicate and unspecified outcomes remain distinct',()=>{
  assert.match(closureMessage(42,USER,'completed'),/closed as completed/);
  assert.match(closureMessage(42,USER,'not_planned'),/closed without planned action/);
  assert.match(closureMessage(42,USER,'duplicate'),/closed as a duplicate/);
  assert.match(closureMessage(42,USER,null),/was closed on GitHub/);
  assert.doesNotMatch(closureMessage(42,USER,'@everyone'),/@everyone/);
});

test('a second closure after reopening can notify again, but replay cannot',async()=>{
  const {env}=setup();await rememberDiscordIssue(env,42,link);
  let count=0;globalThis.fetch=async()=>{count++;return new Response(null,{status:200});};
  await deliver(env);await deliver(env);
  await deliver(env,payload({issue:{...payload().issue,closed_at:'2026-10-13T15:00:00Z'}}));
  assert.equal(count,2);
});

test('concurrent delivery is deduplicated within a Worker instance',async()=>{
  const {env}=setup();await rememberDiscordIssue(env,42,link);
  let count=0;globalThis.fetch=async()=>{count++;await new Promise(r=>setTimeout(r,10));return new Response(null,{status:200});};
  await Promise.all([deliver(env),deliver(env)]);
  assert.equal(count,1);
});

test('signed setup ping works and incomplete configuration fails closed',async()=>{
  const {env}=setup();
  assert.equal((await deliver(env,payload(),'ping')).status,200);
  assert.equal((await deliver({...env,DISCORD_BOT_TOKEN:''})).status,503);
});

test('rejects malformed closures and oversized payloads',async()=>{
  const {env}=setup();
  assert.equal((await deliver(env,payload({issue:{...payload().issue,closed_at:'bad'}}))).status,400);
  const r=new Request('https://example.com',{method:'POST',body:'x'.repeat(262145)});
  assert.equal((await handleGitHubIssueClosure(r,env,{})).status,413);
});

for (const command of ['bug', 'feature']) {
test(`${command} creation captures private routing without exposing user IDs on GitHub`,async()=>{
  const {env,data}=setup();
  env.DISCORD_PUBLIC_KEY=''; env.DISCORD_APPLICATION_ID='523456789012345678';env.DISCORD_GUILD_ID=command === 'bug' ? '623456789012345678' : '623456789012345679';env.GITHUB_TOKEN='unit-github-token';
  env.DISCORD_STATE.getWithMetadata=async(key)=>({value:await env.DISCORD_STATE.get(key),metadata:null});
  const pair=await crypto.subtle.generateKey({name:'Ed25519'},true,['sign','verify']);
  env.DISCORD_PUBLIC_KEY=[...new Uint8Array(await crypto.subtle.exportKey('raw',pair.publicKey))].map(b=>b.toString(16).padStart(2,'0')).join('');
  let issueBody;
  globalThis.fetch=async(url,init)=>{
    if(url.includes('api.github.com')){issueBody=JSON.parse(init.body);return Response.json({number:42,html_url:`https://github.com/${REPO}/issues/42`});}
    if(url.includes('/webhooks/'))return Response.json({id:MESSAGE,channel_id:CHANNEL});
    throw Error('unexpected URL');
  };
  const raw=JSON.stringify({type:2,id:command === 'bug' ? '723456789012345678' : '723456789012345679',token:'test-interaction-token',application_id:env.DISCORD_APPLICATION_ID,guild_id:env.DISCORD_GUILD_ID,channel_id:CHANNEL,member:{user:{id:USER,username:'test-reporter'}},data:{name:command,options:[{name:'report',type:3,value:'Test report: app crashes on save.'}]}});
  const timestamp=String(Math.floor(Date.now()/1000));
  const sig=await crypto.subtle.sign('Ed25519',pair.privateKey,new TextEncoder().encode(timestamp+raw));
  const req=new Request('https://example.com/api/discord/interactions',{method:'POST',headers:{'X-Signature-Ed25519':[...new Uint8Array(sig)].map(b=>b.toString(16).padStart(2,'0')).join(''),'X-Signature-Timestamp':timestamp},body:raw});
  const pending=[];
  const response=await handleDiscordInteractionsRequest(req,env,{waitUntil:p=>pending.push(p)});
  assert.equal(response.status,200);
  for(let i=0;i<pending.length;i++)await pending[i];
  assert.deepEqual(JSON.parse(data.get('issue-close:link:42')),link);
  assert.doesNotMatch(issueBody.body,new RegExp(USER+'|test-reporter'));
});
}
