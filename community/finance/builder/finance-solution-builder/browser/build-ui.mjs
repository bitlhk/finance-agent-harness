import { readFileSync, writeFileSync } from "node:fs";
import { icons } from "lucide";
import { build } from "esbuild";

const licenses = ["marked/LICENSE", "dompurify/LICENSE", "lucide/LICENSE"].map((name) =>
  `${name}\n${readFileSync(new URL(`../node_modules/${name}`, import.meta.url), "utf8")}`,
).join("\n\n");
const elkSourceNotice = [
  "Source Code for elkjs 0.10.0 is available under EPL-2.0 at",
  "https://github.com/kieler/elkjs/releases/tag/0.10.0",
  "The underlying ELK 0.10.0 Source Code is available under EPL-2.0 at",
  "https://github.com/eclipse-elk/elk/releases/tag/v0.10.0",
].join("\n");

await build({
  entryPoints: ["browser/workbench.js"], bundle: true, minify: true, format: "iife",
  supported: {"template-literal": false},
  outfile: "examples/local-workbench/app.js",
  banner: { js: `/* Third-party licenses\n${(licenses+'\n\n'+elkSourceNotice+'\n\nelkjs/LICENSE.md\n'+readFileSync('node_modules/elkjs/LICENSE.md','utf8')).replace(/\*\//g, "* /")}\n*/` },
});
await build({entryPoints:["browser/experience.js"],bundle:true,minify:true,format:"iife",outfile:"examples/agent-experience/app.js",
  banner:{js:`/* Third-party licenses\n${licenses.replace(/\*\//g,"* /")}\n*/`}});
const iconNames = ["House", "Minimize2", "BookOpen", "Plus", "Upload", "Smartphone", "Monitor", "Mic", "PanelRightClose", "ArrowLeft", "CircleHelp", "LogOut", "X", "Maximize2", "SlidersHorizontal", "Wallet", "ChartPie", "ArrowLeftRight", "CreditCard", "BriefcaseBusiness", "ChartColumnIncreasing", "Clock3", "Info", "Check"];
let sprite=readFileSync("examples/local-workbench/icons.svg","utf8");
iconNames.push("RefreshCw", "Menu", "PanelLeftOpen", "PanelLeftClose");
const encode = ([tag, attrs, children]) => `<${tag} ${Object.entries(attrs || {}).map(([k,v])=>`${k}="${v}"`).join(" ")}>${(children||[]).map(encode).join("")}</${tag}>`;
for(const name of [...iconNames, "ChartColumn"]){const id=name.replace(/([a-z])([A-Z0-9])/g,"$1-$2").toLowerCase();if(!sprite.includes(`id="${id}"`))sprite=sprite.replace('</svg>',`<symbol id="${id}" viewBox="0 0 24 24">${icons[name][2].map(encode).join("")}</symbol></svg>`);}
writeFileSync("examples/local-workbench/icons.svg",sprite);
writeFileSync("examples/agent-experience/icons.svg",sprite);
writeFileSync("examples/local-workbench/terminal.css",readFileSync("examples/agent-experience/style.css","utf8")+'\n'+readFileSync("browser/terminal.css","utf8")+'\n'+readFileSync("browser/method-assets.css","utf8")+'\n'+readFileSync("browser/industry-standards.css","utf8")+'\n'+readFileSync("browser/execution-history.css","utf8")+'\n'+readFileSync("browser/studio-navigation.css","utf8"));
writeFileSync("examples/local-workbench/terminal.css",readFileSync("examples/local-workbench/terminal.css","utf8")+'\n'+readFileSync("browser/asset-contents.css","utf8"));
