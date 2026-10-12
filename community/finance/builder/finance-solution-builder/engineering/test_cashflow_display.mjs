import assert from 'node:assert/strict';
import {build} from 'esbuild';
globalThis.location = {pathname:'/experience/test/'};
const bundle = await build({entryPoints:['browser/presentation.js'],bundle:true,write:false,format:'esm'});
const {cards, label, preview, defaultUI} = await import('data:text/javascript;base64,'+Buffer.from(bundle.outputFiles[0].text).toString('base64'));
const gapCodes = ['TOTALS_NOT_RETURNED','CATEGORIES_NOT_RETURNED','TREND_NOT_RETURNED','SOURCE_TIME_NOT_RETURNED',
  'TREND_TOTALS_CONFLICT','CLASSIFICATION_NOT_RETURNED','ACCOUNT_LIST_LIMIT_100','SOURCE_BOOKED_STATUS_NOT_VERIFIED',
  'ANALYSIS_PERIOD_TIMEZONE_NOT_VERIFIED','PERIOD_COVERAGE_NOT_VERIFIED','TREND_COMPARABILITY_NOT_VERIFIED',
  'OTHER_BANKS_AND_CURRENCIES_NOT_COVERED','CREDIT_CARD_BILLS_NOT_ADDED_TO_CASHFLOW'];
for (const code of gapCodes) assert.notEqual(label(code), code);
const card = {type:'cashflow',section:'ANALYSIS',period:'2026-08',currency:'CNY',coverage:'OWN_BANK_BOOKED_WITH_CATEGORIES',
  totals:{income:'90071992547409.91',expense:'0.00',netCashflow:'90071992547409.91'},categories:[],trend:[],gaps:[],
  source:{system:'controlled-bank',recordVersion:'spending-analysis-v1'}};
const render = value => cards({schemaVersion:'finance-display.v0.1',cards:[value]});
assert(render(card).includes('本次统计口径返回0条分类记录'));
assert(!render(card).includes('分类信息未返回'));
assert(render(card).includes('90,071,992,547,409.91'));
assert(render({...card,gaps:['CATEGORIES_NOT_RETURNED']}).includes('分类信息未返回'));
assert(!render({...card,gaps:['CATEGORIES_NOT_RETURNED']}).includes('返回0条分类记录'));
const populated = {...card,categories:[{name:'<img onerror=alert(1)>',classificationVersion:'classifier-v1',transactionCount:1,totals:card.totals}],gaps:gapCodes};
const html = render(populated);
assert(!html.includes('<img onerror='));
assert(html.includes('&lt;img'));
assert(!html.match(/<td>[^]*?classifier-v1[^]*?<\/td>/));
for (const code of gapCodes) assert(html.includes(`data-gap="${code}"`));
const known = {...card,categories:[{name:'DAILY_LIVING',classificationVersion:'spending-category-v1',transactionCount:1,totals:card.totals}]};
assert(render(known).includes('<td>日常消费</td>'));
assert(render(known).includes('<code>DAILY_LIVING</code>'));
assert(render({...known,categories:[{...known.categories[0],classificationVersion:'other-source'}]}).includes('<td>DAILY_LIVING</td>'));
assert(render({...known,categories:[{...known.categories[0],name:'NEW_CATEGORY'}]}).includes('<td>NEW_CATEGORY</td>'));
for (const terminal of ['MOBILE_WEB','DESKTOP_WEB','ATM_WEB']) {
  const view = preview(defaultUI(),terminal,['finance.transaction-summary.query','finance.spending-analysis.query']);
  assert(view.includes('cashflow-summary-v1'));
  assert(view.includes('固定布局样例'));
}
console.log('cashflow display: exact decimal, missing/empty distinction, 13 gap labels, escaping and 3 terminal previews passed');
