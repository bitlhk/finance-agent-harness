import assert from 'node:assert/strict';
import {assetContents} from '../browser/asset-contents.js';

const files=[1,2].map(version=>({fileId:'file-'+version,path:'SKILL.md',role:'SKILL',origin:'REFERENCED_ASSET',
  reference:{id:'method',version:String(version),source:'repo://'+version},content:'body-version-'+version,previewStatus:'AVAILABLE'}));
const value={schemaVersion:'finance-asset-contents.v0.1',kind:'CATALOG_COMPONENT',version:'1',identity:{},
  files,bindings:[],dependencies:[],composition:{nodes:[],relations:[]}};
const esc=x=>String(x??'').replaceAll('&','&amp;').replaceAll('<','&lt;');
globalThis.document={querySelector:()=>null};
const panel=assetContents({api:async()=>value,esc,icon:()=>'',rerender:()=>{}});
await panel.open('/asset');
const select=key=>panel.act({target:{closest:()=>({dataset:{assetFile:key},hasAttribute:()=>false,closest:()=>null})}});
assert.equal(select('SKILL.md'),true);
assert.match(panel.html(),/此路径对应多个来源或版本/);
assert.doesNotMatch(panel.html(),/body-version-/);
assert.match(panel.html(),/data-asset-file="file-1"/);
assert.match(panel.html(),/data-asset-file="file-2"/);
select('file-2');
assert.match(panel.html(),/body-version-2/);
assert.doesNotMatch(panel.html(),/body-version-1|此路径对应多个来源或版本/);
select('missing');
assert.match(panel.html(),/没有可读取文件/);
assert.doesNotMatch(panel.html(),/body-version-/);
await panel.open('/asset');
assert.doesNotMatch(panel.html(),/没有可读取文件/);
console.log('10 legacy file navigation checks passed');
