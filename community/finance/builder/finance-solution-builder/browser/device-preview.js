import { preview, terminalName } from "./presentation.js";

const dimensions = {
  MOBILE_WEB: [414, 868, "390 × 844 · 手机比例预览"],
  DESKTOP_WEB: [1000, 656, "16:10 · 无品牌笔记本"],
  ATM_WEB: [460, 760, "浏览器模拟终端 · 外设仅为装饰"],
};

export function devicePreview(config, terminal, capabilities, page, enlarged = false) {
  const content=document.createElement('template');
  content.innerHTML=preview(config, terminal, capabilities, page);
  if(capabilities.length){
    const empty=content.content.querySelector('.preview-shortcuts>p,.preview-result>.fact-gap');
    if(empty?.textContent==='尚未选择业务能力'){
      empty.textContent='已选业务能力';
      const question=content.content.querySelector('.preview-question');
      if(question)question.textContent='查看已选业务能力';
    }
  }
  const screen = `<div class="device-screen">${content.innerHTML}</div>`;
  let frame;
  if (terminal === "DESKTOP_WEB") {
    frame = `<div class="device-frame device-laptop"><div class="laptop-lid"><span class="device-camera" aria-hidden="true"></span>${screen}</div><div class="laptop-base" aria-hidden="true"></div></div>`;
  } else if (terminal === "ATM_WEB") {
    const keys = '<span></span>'.repeat(4);
    frame = `<div class="device-frame device-atm"><div class="atm-cabinet"><div class="atm-canopy" aria-hidden="true"></div><div class="atm-screen-surround"><div class="atm-side-keys" aria-hidden="true">${keys}</div>${screen}<div class="atm-side-keys" aria-hidden="true">${keys}</div></div><div class="atm-hardware" aria-hidden="true"><div class="atm-keypad">${'<span></span>'.repeat(12)}</div><div class="atm-reader"><span></span></div></div><div class="atm-lower-panel" aria-hidden="true"><span></span></div></div><div class="atm-base" aria-hidden="true"></div></div>`;
  } else {
    frame = `<div class="device-frame device-phone"><span class="phone-receiver" aria-hidden="true"></span>${screen}<span class="phone-home-bar" aria-hidden="true"></span></div>`;
  }
  return `<div class="device-stage ${enlarged ? "is-enlarged" : ""}" data-device-preview="${terminal}" aria-label="${terminalName(terminal)}布局预览"><div class="device-viewport"><div class="device-scale-box">${frame}</div></div><div class="device-caption"><span>${dimensions[terminal][2]}</span><span>固定示例数据</span></div></div>`;
}

let observer;
export function resizeDevicePreviews() {
  observer?.disconnect();
  observer = new ResizeObserver((entries) => {
    for (const { target } of entries) {
      const stage = target.closest("[data-device-preview]");
      const [width, height] = dimensions[stage.dataset.devicePreview];
      const max = stage.classList.contains("is-enlarged") ? 1 : stage.dataset.devicePreview === "MOBILE_WEB" ? .72 : .82;
      const scale = Math.min(max, Math.max(0.1, (target.clientWidth - 24) / width));
      const box = target.querySelector(".device-scale-box");
      box.style.width = `${width * scale}px`;
      box.style.height = `${height * scale}px`;
      box.firstElementChild.style.transform = `scale(${scale})`;
    }
  });
  document.querySelectorAll(".device-viewport").forEach((node) => observer.observe(node));
}
