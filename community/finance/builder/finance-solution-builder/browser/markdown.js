import { Marked } from "marked";
import DOMPurify from "dompurify";

const escape = (value) => String(value ?? "").replace(/[&<>"']/g, (char) => ({
  "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;",
})[char]);

const markdown = new Marked({
  gfm: true,
  breaks: true,
  renderer: {
    html: ({ text }) => escape(text),
    // Model-generated images must not make automatic requests carrying customer data.
    image: ({ text }) => escape(text || "[image]"),
  },
});

export function renderMarkdown(value) {
  const fragment = DOMPurify.sanitize(markdown.parse(String(value ?? "")), {
    RETURN_DOM_FRAGMENT: true,
    ALLOWED_TAGS: ["p", "br", "strong", "em", "del", "h1", "h2", "h3", "h4", "h5", "h6",
      "ul", "ol", "li", "blockquote", "hr", "pre", "code", "a", "table", "thead", "tbody",
      "tr", "th", "td", "input"],
    ALLOWED_ATTR: ["href", "title", "start", "align", "type", "checked", "disabled"],
    ALLOW_DATA_ATTR: false,
    ALLOW_ARIA_ATTR: false,
  });
  for (const link of fragment.querySelectorAll("a")) {
    const href = link.getAttribute("href");
    let safe = false;
    try {
      safe = !!href && ["https:", "http:", "mailto:"].includes(new URL(href, location.origin).protocol);
    } catch { /* Unsupported links remain readable without navigation. */ }
    if (!safe) {
      link.removeAttribute("href");
    } else {
      link.setAttribute("target", "_blank");
      link.setAttribute("rel", "noopener noreferrer");
      link.setAttribute("referrerpolicy", "no-referrer");
    }
  }
  for (const input of fragment.querySelectorAll("input")) {
    input.type = "checkbox";
    input.disabled = true;
    input.setAttribute("aria-label", input.checked ? "已完成" : "未完成");
  }
  for (const table of fragment.querySelectorAll("table")) {
    const viewport = document.createElement("div");
    viewport.className = "markdown-table-scroll";
    viewport.tabIndex = 0;
    viewport.setAttribute("role", "region");
    viewport.setAttribute("aria-label", "数据表格");
    table.replaceWith(viewport);
    viewport.append(table);
  }
  const container = document.createElement("div");
  container.append(fragment);
  return container.innerHTML;
}
