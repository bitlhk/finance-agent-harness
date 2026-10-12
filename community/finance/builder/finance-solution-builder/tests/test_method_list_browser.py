import subprocess
from pathlib import Path


def test_method_filters_and_business_links(tmp_path):
    project = Path(__file__).resolve().parents[1]
    # Use ES module copies because the browser sources are bundled as ESM.
    for name in ("method-list", "business-assets"):
        (tmp_path / (name + ".mjs")).write_text((project / "browser" / (name + ".js")).read_text())
    script = r'''
import assert from 'node:assert/strict';
import {visibleMethods,methodListFilters} from './method-list.mjs';
import {businessAssetsView} from './business-assets.mjs';
const items = [
 {assetId:'draft',status:'DRAFT',versions:[]},
 {assetId:'published',status:'PUBLISHED',versions:[{version:'1.0.0'}]},
 {assetId:'edited',status:'UNPUBLISHED_CHANGES',hasUnpublishedChanges:true,versions:[{version:'1.0.0'}]},
 {assetId:'archived',status:'ARCHIVED',versions:[]}
];
const ids=(filter,archived=false)=>visibleMethods(items,filter,archived).map(x=>x.assetId);
assert.deepEqual(ids('ALL'),['draft','published','edited']);
assert.deepEqual(ids('DRAFT'),['draft','edited']);
assert.deepEqual(ids('PUBLISHED'),['published','edited']);
assert.deepEqual(ids('PUBLISHED',true),['published','edited','archived']);
assert.match(methodListFilters('DRAFT',false),/data-method-filter="DRAFT" aria-pressed="true"/);
const part={id:'balance',status:'AVAILABLE',label:'Balance',description:'',scope:'',capabilityIds:[],
 capabilityPackRefs:[],domainPackRefs:[],providerRefs:[],profileRef:{id:'self'}};
const html=businessAssetsView({directory:{components:[part]},capabilities:[],selected:'balance',query:'',domain:'',
 methods:[{...items[1],name:'My method',componentId:'balance'},
 {...items[0],name:'Other business',componentId:'wealth'},
 {...items[3],name:'Archived method',componentId:'balance'}],esc:s=>String(s??''),icon:()=>''});
assert.match(html,/data-asset="published"/);
assert.match(html,/v1.0.0/);
assert.doesNotMatch(html,/Other business|Archived method/);
'''
    subprocess.run(["node", "--input-type=module", "-e", script], cwd=tmp_path, check=True)
