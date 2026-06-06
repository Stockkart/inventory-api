#!/usr/bin/env python3
"""Generate VERTICAL_PLUGIN_ARCHITECTURE.html from the markdown source."""

from __future__ import annotations

import html
import re
import sys
from pathlib import Path

DOCS = Path(__file__).resolve().parent
SRC = DOCS / "VERTICAL_PLUGIN_ARCHITECTURE.md"
OUT = DOCS / "VERTICAL_PLUGIN_ARCHITECTURE.html"

CSS = """
:root {
  --bg: #f8fafc;
  --surface: #ffffff;
  --text: #0f172a;
  --muted: #64748b;
  --border: #e2e8f0;
  --accent: #0d9488;
  --accent-soft: #ccfbf1;
  --code-bg: #f1f5f9;
  --sidebar-w: 280px;
}
* { box-sizing: border-box; }
html { scroll-behavior: smooth; }
body {
  margin: 0;
  font-family: "Segoe UI", system-ui, -apple-system, sans-serif;
  font-size: 15px;
  line-height: 1.65;
  color: var(--text);
  background: var(--bg);
}
.layout {
  display: grid;
  grid-template-columns: var(--sidebar-w) 1fr;
  min-height: 100vh;
}
nav.sidebar {
  position: sticky;
  top: 0;
  height: 100vh;
  overflow-y: auto;
  background: var(--surface);
  border-right: 1px solid var(--border);
  padding: 1.25rem 1rem 2rem;
  font-size: 0.8rem;
}
nav.sidebar h2 {
  font-size: 0.75rem;
  text-transform: uppercase;
  letter-spacing: 0.06em;
  color: var(--muted);
  margin: 0 0 0.75rem;
}
nav.sidebar ul { list-style: none; padding: 0; margin: 0; }
nav.sidebar li { margin: 0.2rem 0; }
nav.sidebar a {
  color: var(--text);
  text-decoration: none;
  display: block;
  padding: 0.2rem 0.4rem;
  border-radius: 4px;
}
nav.sidebar a:hover { background: var(--accent-soft); color: var(--accent); }
nav.sidebar .toc-h3 { padding-left: 0.75rem; font-size: 0.75rem; color: var(--muted); }
main {
  max-width: 52rem;
  padding: 2rem 2.5rem 4rem;
  background: var(--surface);
  margin: 1rem;
  border-radius: 12px;
  box-shadow: 0 1px 3px rgba(15,23,42,0.08);
}
header.doc-header {
  border-bottom: 2px solid var(--accent);
  padding-bottom: 1.25rem;
  margin-bottom: 2rem;
}
header.doc-header h1 { margin: 0 0 0.5rem; font-size: 1.85rem; line-height: 1.25; }
header.doc-header .meta { color: var(--muted); font-size: 0.9rem; }
header.doc-header .badge {
  display: inline-block;
  background: var(--accent-soft);
  color: var(--accent);
  font-weight: 600;
  font-size: 0.75rem;
  padding: 0.15rem 0.5rem;
  border-radius: 4px;
  margin-right: 0.5rem;
}
h2 { margin-top: 2.5rem; padding-top: 0.5rem; border-top: 1px solid var(--border); font-size: 1.35rem; }
h2:first-of-type { border-top: none; margin-top: 0; }
h3 { font-size: 1.1rem; margin-top: 1.75rem; }
h4 { font-size: 1rem; margin-top: 1.25rem; }
p { margin: 0.75rem 0; }
ul, ol { margin: 0.75rem 0; padding-left: 1.5rem; }
li { margin: 0.25rem 0; }
hr { border: none; border-top: 1px solid var(--border); margin: 2rem 0; }
table {
  width: 100%;
  border-collapse: collapse;
  margin: 1rem 0;
  font-size: 0.9rem;
}
th, td {
  border: 1px solid var(--border);
  padding: 0.5rem 0.65rem;
  text-align: left;
  vertical-align: top;
}
th { background: var(--code-bg); font-weight: 600; }
tr:nth-child(even) td { background: #fafbfc; }
code {
  font-family: ui-monospace, "Cascadia Code", "SF Mono", monospace;
  font-size: 0.88em;
  background: var(--code-bg);
  padding: 0.12em 0.35em;
  border-radius: 4px;
}
pre {
  background: #1e293b;
  color: #e2e8f0;
  padding: 1rem 1.15rem;
  border-radius: 8px;
  overflow-x: auto;
  font-size: 0.82rem;
  line-height: 1.5;
  margin: 1rem 0;
}
pre code {
  background: none;
  padding: 0;
  color: inherit;
  font-size: inherit;
}
a { color: var(--accent); }
strong { font-weight: 600; }
blockquote {
  margin: 1rem 0;
  padding: 0.5rem 1rem;
  border-left: 4px solid var(--accent);
  background: var(--accent-soft);
  color: var(--text);
}
figure.doc-figure {
  margin: 1.25rem 0 1.5rem;
  text-align: center;
}
figure.doc-figure img {
  max-width: 100%;
  height: auto;
  border: 1px solid var(--border);
  border-radius: 8px;
  box-shadow: 0 2px 8px rgba(15,23,42,0.08);
}
figure.doc-figure figcaption {
  margin-top: 0.5rem;
  font-size: 0.85rem;
  color: var(--muted);
}
details {
  margin: 1rem 0;
  padding: 0.75rem 1rem;
  border: 1px solid var(--border);
  border-radius: 8px;
  background: #fafbfc;
}
details summary {
  cursor: pointer;
  font-weight: 600;
  color: var(--muted);
}
.checklist { list-style: none; padding-left: 0; }
.checklist li::before { content: "☐ "; color: var(--muted); }
footer.doc-footer {
  margin-top: 3rem;
  padding-top: 1rem;
  border-top: 1px solid var(--border);
  color: var(--muted);
  font-size: 0.85rem;
}
@media print {
  .layout { display: block; }
  nav.sidebar { display: none; }
  main { margin: 0; box-shadow: none; max-width: none; }
  pre { white-space: pre-wrap; word-break: break-word; }
}
@media (max-width: 900px) {
  .layout { grid-template-columns: 1fr; }
  nav.sidebar {
    position: relative;
    height: auto;
    max-height: 40vh;
    border-right: none;
    border-bottom: 1px solid var(--border);
  }
}
"""


def slugify(text: str) -> str:
    t = text.strip().lower()
    t = re.sub(r"[^\w\s-]", "", t, flags=re.UNICODE)
    t = re.sub(r"[\s_]+", "-", t)
    return t.strip("-")


def inline_format(text: str) -> str:
    text = html.escape(text)
    text = re.sub(r"`([^`]+)`", r"<code>\1</code>", text)
    text = re.sub(r"\*\*([^*]+)\*\*", r"<strong>\1</strong>", text)
    text = re.sub(r"\*([^*]+)\*", r"<em>\1</em>", text)
    text = re.sub(
        r"\[([^\]]+)\]\(([^)]+)\)",
        lambda m: f'<a href="{html.escape(m.group(2), quote=True)}">{m.group(1)}</a>',
        text,
    )
    return text


def parse_table(lines: list[str]) -> str:
    rows = []
    for line in lines:
        if not line.strip().startswith("|"):
            break
        cells = [c.strip() for c in line.strip().strip("|").split("|")]
        rows.append(cells)
    if len(rows) < 2:
        return ""
    header = rows[0]
    body = rows[2:] if len(rows) > 1 and re.match(r"^[-:|\s]+$", "|".join(rows[1])) else rows[1:]
    out = ["<table><thead><tr>"]
    for c in header:
        out.append(f"<th>{inline_format(c)}</th>")
    out.append("</tr></thead><tbody>")
    for row in body:
        out.append("<tr>")
        for c in row:
            out.append(f"<td>{inline_format(c)}</td>")
        out.append("</tr>")
    out.append("</tbody></table>")
    return "".join(out)


def convert_md(md: str) -> tuple[str, list[tuple[int, str, str]]]:
    """Return HTML body and TOC entries (level, id, title)."""
    lines = md.splitlines()
    toc: list[tuple[int, str, str]] = []
    out: list[str] = []
    i = 0
    in_code = False
    code_buf: list[str] = []
    code_lang = ""
    skip_toc_section = False

    while i < len(lines):
        line = lines[i]

        if in_code:
            if line.strip().startswith("```"):
                lang_class = f' class="language-{code_lang}"' if code_lang else ""
                body = html.escape("\n".join(code_buf))
                out.append(f"<pre><code{lang_class}>{body}</code></pre>")
                in_code = False
                code_buf = []
                code_lang = ""
            else:
                code_buf.append(line)
            i += 1
            continue

        if line.strip().startswith("```"):
            in_code = True
            code_lang = line.strip()[3:].strip() or "text"
            i += 1
            continue

        if line.strip() == "---":
            out.append("<hr>")
            i += 1
            continue

        hm = re.match(r"^(#{1,4})\s+(.+)$", line)
        if hm:
            level = len(hm.group(1))
            title = hm.group(2).strip()
            sid = slugify(re.sub(r"\[([^\]]+)\]\([^)]+\)", r"\1", title))
            if title.lower() == "table of contents":
                skip_toc_section = True
                i += 1
                while i < len(lines) and not lines[i].startswith("## "):
                    i += 1
                skip_toc_section = False
                continue
            tag = f"h{min(level, 4)}"
            out.append(f'<{tag} id="{sid}">{inline_format(title)}</{tag}>')
            if level <= 3 and title.lower() != "document maintenance":
                toc.append((level, sid, title))
            i += 1
            continue

        if skip_toc_section:
            i += 1
            continue

        if line.strip().startswith("|"):
            table_lines = []
            while i < len(lines) and lines[i].strip().startswith("|"):
                table_lines.append(lines[i])
                i += 1
            out.append(parse_table(table_lines))
            continue

        if re.match(r"^[-*]\s+", line) or re.match(r"^\d+\.\s+", line):
            items = []
            ordered = bool(re.match(r"^\d+\.", line))
            while i < len(lines):
                m_ul = re.match(r"^[-*]\s+(.+)$", lines[i])
                m_ol = re.match(r"^\d+\.\s+(.+)$", lines[i])
                if m_ul and not ordered:
                    items.append(m_ul.group(1))
                    i += 1
                elif m_ol and ordered:
                    items.append(m_ol.group(1))
                    i += 1
                elif lines[i].strip() == "":
                    break
                else:
                    break
            tag = "ol" if ordered else "ul"
            cls = ' class="checklist"' if items and items[0].strip().startswith("[ ]") or items[0].strip().startswith("- [ ]") else ""
            if items and re.match(r"^-?\s*\[[ xX]?\]", items[0]):
                cls = ' class="checklist"'
            out.append(f"<{tag}{cls}>")
            for it in items:
                it_clean = re.sub(r"^-?\s*\[[ xX]?\]\s*", "", it)
                out.append(f"<li>{inline_format(it_clean)}</li>")
            out.append(f"</{tag}>")
            continue

        if line.strip() == "":
            i += 1
            continue

        img_m = re.match(r"^!\[([^\]]*)\]\(([^)]+)\)\s*$", line.strip())
        if img_m:
            alt = html.escape(img_m.group(1))
            src = html.escape(img_m.group(2), quote=True)
            out.append(f'<figure class="doc-figure"><img src="{src}" alt="{alt}"><figcaption>{alt}</figcaption></figure>')
            i += 1
            continue

        para = [line]
        i += 1
        while i < len(lines) and lines[i].strip() and not lines[i].startswith("#") and not lines[i].strip().startswith("|") and not lines[i].strip().startswith("```") and not re.match(r"^[-*]\s+", lines[i]) and not re.match(r"^\d+\.\s+", lines[i]):
            para.append(lines[i])
            i += 1
        text = " ".join(para)
        if text.startswith("> "):
            out.append(f"<blockquote><p>{inline_format(text[2:])}</p></blockquote>")
        else:
            out.append(f"<p>{inline_format(text)}</p>")

    return "\n".join(out), toc


def build_sidebar(toc: list[tuple[int, str, str]]) -> str:
    parts = ['<nav class="sidebar"><h2>Contents</h2><ul>']
    for level, sid, title in toc:
        if level == 2:
            parts.append(f'<li><a href="#{sid}">{html.escape(title)}</a></li>')
        elif level == 3:
            parts.append(f'<li class="toc-h3"><a href="#{sid}">{html.escape(title)}</a></li>')
    parts.append("</ul></nav>")
    return "".join(parts)


def main() -> int:
    if not SRC.exists():
        print(f"Missing source: {SRC}", file=sys.stderr)
        return 1
    md = SRC.read_text(encoding="utf-8")
    version = "3.3"
    m = re.search(r"\*\*Version:\*\*\s*([\d.]+)", md)
    if m:
        version = m.group(1)
    updated = "2026-06-02"
    m2 = re.search(r"\*Last updated:\s*([^*]+)\*", md)
    if m2:
        updated = m2.group(1).strip()

    body_html, toc = convert_md(md)
    sidebar = build_sidebar(toc)

    page = f"""<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>StockKart Vertical Plugin Architecture v{html.escape(version)}</title>
  <style>{CSS}</style>
</head>
<body>
  <div class="layout">
    {sidebar}
    <main>
      <header class="doc-header">
        <span class="badge">v{html.escape(version)}</span>
        <span class="badge">StockKart</span>
        <h1>Vertical Plugin Architecture</h1>
        <p class="meta">Implementation &amp; operations guide · Last updated {html.escape(updated)}</p>
        <p class="meta">Source: <code>docs/VERTICAL_PLUGIN_ARCHITECTURE.md</code> — open this file in a browser or print to PDF.</p>
      </header>
      <article class="content">
{body_html}
      </article>
      <footer class="doc-footer">
        <p>Generated from VERTICAL_PLUGIN_ARCHITECTURE.md. Regenerate with <code>python3 docs/generate_architecture_html.py</code>.</p>
      </footer>
    </main>
  </div>
</body>
</html>
"""
    OUT.write_text(page, encoding="utf-8")
    print(f"Wrote {OUT} ({len(page):,} bytes)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
