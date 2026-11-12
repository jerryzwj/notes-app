/* 轻量 Markdown 渲染 —— 主应用（app.js）与分享页（share.html）共用 */
(function () {
  const escapeHTML = (s) =>
    String(s)
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;');

  const cellSplit = (row) =>
    row
      .replace(/^\s*\|/, '')
      .replace(/\|\s*$/, '')
      .split('|')
      .map((c) => c.trim());

  const isRow = (s) => /^\s*\|.*\|\s*$/.test(s);
  const isSep = (s) => /^\s*\|[\s:\-|]+\|\s*$/.test(s) && /-/.test(s);

  function render(src) {
    const lines = String(src).replace(/\r\n/g, '\n').split('\n');
    let html = '';
    let inCode = false;
    let code = [];
    let listType = null;

    const closeList = () => {
      if (listType) {
        html += listType === 'ul' ? '</ul>' : '</ol>';
        listType = null;
      }
    };

    const inline = (s) => {
      let t = escapeHTML(s);
      t = t.replace(/`([^`]+)`/g, '<code>$1</code>');
      t = t.replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>');
      t = t.replace(/~~([^~]+)~~/g, '<del>$1</del>');
      t = t.replace(/(^|[^*])\*([^*\s][^*]*)\*/g, '$1<em>$2</em>');
      t = t.replace(/\[([^\]]+)\]\((https?:\/\/[^\s)]+)\)/g, '<a href="$2" target="_blank" rel="noopener">$1</a>');
      t = t.replace(/^\[ \]\s*/, '☐ ');
      t = t.replace(/^\[[xX]\]\s*/, '☑ ');
      return t;
    };

    for (let i = 0; i < lines.length; i += 1) {
      const line = lines[i];
      if (/^```/.test(line.trim())) {
        if (inCode) {
          html += `<pre><code>${escapeHTML(code.join('\n'))}</code></pre>`;
          inCode = false;
          code = [];
        } else {
          closeList();
          inCode = true;
        }
        continue;
      }
      if (inCode) {
        code.push(line);
        continue;
      }
      if (!line.trim()) {
        closeList();
        continue;
      }

      let m;
      if ((m = line.match(/^(#{1,6})\s+(.*)$/))) {
        closeList();
        const lvl = m[1].length;
        html += `<h${lvl}>${inline(m[2])}</h${lvl}>`;
        continue;
      }
      if (/^(-{3,}|\*{3,})\s*$/.test(line)) {
        closeList();
        html += '<hr>';
        continue;
      }
      if (isRow(line) && i + 1 < lines.length && isSep(lines[i + 1])) {
        closeList();
        const head = cellSplit(line);
        html += '<table><thead><tr>' + head.map((c) => `<th>${inline(c)}</th>`).join('') + '</tr></thead><tbody>';
        i += 1;
        while (i + 1 < lines.length && isRow(lines[i + 1])) {
          i += 1;
          const cells = cellSplit(lines[i]);
          html += '<tr>' + cells.map((c) => `<td>${inline(c)}</td>`).join('') + '</tr>';
        }
        html += '</tbody></table>';
        continue;
      }
      if ((m = line.match(/^>\s*(.*)$/))) {
        closeList();
        html += `<blockquote>${inline(m[1])}</blockquote>`;
        continue;
      }
      if ((m = line.match(/^\s*[-*+]\s+(.*)$/))) {
        if (listType !== 'ul') {
          closeList();
          html += '<ul>';
          listType = 'ul';
        }
        const task = m[1].match(/^\[([ xX])\]\s*(.*)$/);
        if (task) {
          const done = task[1].toLowerCase() === 'x';
          html += `<li class="task${done ? ' done' : ''}"><span class="task-box">${
            done ? '✓' : ''
          }</span>${inline(task[2])}</li>`;
        } else {
          html += `<li>${inline(m[1])}</li>`;
        }
        continue;
      }
      if ((m = line.match(/^\s*\d+[.)]\s+(.*)$/))) {
        if (listType !== 'ol') {
          closeList();
          html += '<ol>';
          listType = 'ol';
        }
        html += `<li>${inline(m[1])}</li>`;
        continue;
      }
      closeList();
      html += `<p>${inline(line)}</p>`;
    }

    if (inCode) html += `<pre><code>${escapeHTML(code.join('\n'))}</code></pre>`;
    closeList();
    return html || '<p class="hint">（空白笔记）</p>';
  }

  window.renderMarkdown = render;
})();
