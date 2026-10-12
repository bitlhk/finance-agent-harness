import references from './industry-standards.v1.json';

export function industryStandards(esc, icon) {
  return `<section class="industry-standards" aria-labelledby="standards-title">
    <header class="standards-heading"><h1 id="standards-title">${esc(references.title)}</h1><p>${esc(references.boundary)}</p></header>
    <table class="standards-table" role="table">
      <caption class="visually-hidden">参考版本、用途与已核实范围</caption>
      <thead role="rowgroup"><tr role="row">${['名称与版本','类别','参考用途','适用边界','核实记录','官方来源'].map(label=>`<th scope="col" role="columnheader">${label}</th>`).join('')}</tr></thead>
      <tbody role="rowgroup">${references.references.map(item=>`<tr role="row" data-standard="${esc(item.id)}">
        <th scope="row" role="rowheader"><strong>${esc(item.title)}</strong><span class="standard-version">${esc(item.version)}</span></th>
        <td role="cell" data-label="类别">${esc(item.category)}</td>
        <td role="cell" data-label="参考用途">${esc(item.purpose)}</td>
        <td role="cell" data-label="适用边界">${esc(item.boundary)}</td>
        <td role="cell" data-label="核实记录"><span>${esc(item.verification)}</span><time datetime="${esc(item.verifiedAt)}">${esc(item.verifiedAt)}</time></td>
        <td role="cell" data-label="官方来源"><a href="${esc(item.url)}" target="_blank" rel="noopener noreferrer" aria-label="${esc(item.title)} 官方来源（新窗口）">查看原文 ${icon('arrow-up-right')}</a></td>
      </tr>`).join('')}</tbody>
    </table>
  </section>`;
}
