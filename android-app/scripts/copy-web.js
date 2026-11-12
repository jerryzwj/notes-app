// 把 ../public 的内容同步到 android-app/www（Capacitor 只认 www 作为 webDir）
const fs = require('fs');
const path = require('path');

const src = path.resolve(__dirname, '..', '..', 'public');
const dst = path.resolve(__dirname, '..', 'www');

const skip = new Set(['.DS_Store']);

function clean(dir) {
  if (!fs.existsSync(dir)) return;
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    if (skip.has(entry.name)) continue;
    fs.rmSync(path.join(dir, entry.name), { recursive: true, force: true });
  }
}

function copy(dir, out) {
  fs.mkdirSync(out, { recursive: true });
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    if (skip.has(entry.name)) continue;
    const from = path.join(dir, entry.name);
    const to = path.join(out, entry.name);
    if (entry.isDirectory()) copy(from, to);
    else fs.copyFileSync(from, to);
  }
}

clean(dst);
copy(src, dst);
const count = fs.readdirSync(dst).length;
console.log(`synced ${count} entries -> ${dst}`);
