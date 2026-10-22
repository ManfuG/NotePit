import MarkdownIt from 'markdown-it';
import { katex as katexPlugin } from '@mdit/plugin-katex';
import katex from 'katex';
import renderMathInElement from 'katex/contrib/auto-render';
import DOMPurify from 'dompurify';
import 'katex/dist/katex.min.css';
import './preview.css';

export type PreviewKind = 'markdown' | 'latex';

const mathOptions = { trust: false, throwOnError: false, strict: 'ignore' as const, maxExpand: 1000, maxSize: 20 };
const markdown = new MarkdownIt({ html: false, linkify: true, breaks: false }).use(katexPlugin, {
  ...mathOptions, delimiters: 'all', mathFence: true,
});
// Images are deliberately text-only: viewing a local note never fetches a remote resource.
markdown.renderer.rules.image = (tokens, index) => markdown.utils.escapeHtml(tokens[index].content);

function sanitize(html: string): DocumentFragment {
  return DOMPurify.sanitize(html, {
    RETURN_DOM_FRAGMENT: true,
    FORBID_TAGS: ['img', 'picture', 'audio', 'video', 'source', 'iframe', 'object', 'embed', 'script', 'style', 'link', 'form', 'input', 'button'],
    FORBID_ATTR: ['src', 'srcset', 'poster', 'action', 'formaction'],
  });
}

export function renderPreview(target: HTMLElement, source: string, kind: PreviewKind): void {
  if (kind === 'markdown') {
    target.replaceChildren(sanitize(markdown.render(source)));
    return;
  }
  const rendered = document.createElement('div');
  rendered.textContent = source;
  renderMathInElement(rendered, {
    ...mathOptions,
    delimiters: [
      { left: '$$', right: '$$', display: true },
      { left: '$', right: '$', display: false },
      { left: '\\(', right: '\\)', display: false },
      { left: '\\[', right: '\\]', display: true },
      ...['equation', 'equation*', 'align', 'align*', 'alignat', 'alignat*', 'gather', 'gather*', 'multline', 'multline*', 'displaymath']
        .map(environment => ({ left: `\\begin{${environment}}`, right: `\\end{${environment}}`, display: true })),
    ],
  });
  const formulas = document.createElement('div');
  const heading = document.createElement('p');
  heading.className = 'formula-preview-note';
  heading.textContent = 'Formula preview only — not a full LaTeX document compilation.';
  formulas.append(heading);
  for (const formula of rendered.querySelectorAll<HTMLElement>('.katex, .katex-error')) {
    if (formula.parentElement?.closest('.katex, .katex-error')) continue;
    const card = document.createElement('div');
    card.className = 'formula-preview-card';
    card.append((formula.parentElement?.classList.contains('katex-display') ? formula.parentElement : formula).cloneNode(true));
    formulas.append(card);
  }
  if (formulas.childElementCount === 1 && source.trim() && !/\\(?:documentclass|begin\s*\{document\}|end\s*\{document\})/.test(source)) {
    const card = document.createElement('div');
    card.className = 'formula-preview-card';
    katex.render(source.trim(), card, { ...mathOptions, displayMode: true });
    formulas.append(card);
  }
  if (formulas.childElementCount === 1) {
    const empty = document.createElement('p');
    empty.textContent = 'No math snippets found. Use $…$, $$…$$, \\(…\\), \\[…\\], or a math environment.';
    formulas.append(empty);
  }
  target.replaceChildren(sanitize(formulas.innerHTML));
}
