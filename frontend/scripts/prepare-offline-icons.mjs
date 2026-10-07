/**
 * Generate trimmed iconify collections for offline runtime registration.
 *
 * Scans `src/` for icon usage (`<icon-collection:name />`, `icon="collection:name"`,
 * `icon: 'collection:name'` in route meta) and writes minimal IconifyJSON files to
 * `src/assets/iconify/`, consumed by `src/plugins/iconify.ts`.
 *
 * Run after adding or changing icons:
 *
 * ```bash
 * node scripts/prepare-offline-icons.mjs
 * ```
 */
import { mkdirSync, readdirSync, readFileSync, writeFileSync } from 'node:fs';
import { dirname, extname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { getIcons } from '@iconify/utils';

const scriptsDir = dirname(fileURLToPath(import.meta.url));
const srcDir = join(scriptsDir, '../src');
const outDir = join(srcDir, 'assets/iconify');
const iconifyDir = join(scriptsDir, '../node_modules/@iconify/json/json');

const SCAN_EXTENSIONS = new Set(['.vue', '.ts', '.tsx', '.js', '.jsx', '.mjs', '.scss', '.css', '.html']);
const ICON_PATTERNS = [
  /(?<![a-z-])icon-([a-z0-9-]+):([a-z0-9-]+)/g,
  /icon\s*[:=]\s*['"]([a-z0-9-]+):([a-z0-9-]+)['"]/g
];

function walk(dir) {
  const files = [];
  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    const full = join(dir, entry.name);
    if (entry.isDirectory()) {
      if (entry.name === 'assets') continue;
      files.push(...walk(full));
    } else if (SCAN_EXTENSIONS.has(extname(entry.name))) {
      files.push(full);
    }
  }
  return files;
}

const used = new Map();
for (const file of walk(srcDir)) {
  const content = readFileSync(file, 'utf8');
  for (const pattern of ICON_PATTERNS) {
    for (const match of content.matchAll(pattern)) {
      const collection = match[1];
      const name = match[2];
      if (!used.has(collection)) used.set(collection, new Set());
      used.get(collection).add(name);
    }
  }
}

mkdirSync(outDir, { recursive: true });

let collectionsWritten = 0;
let iconsWritten = 0;
for (const [collection, names] of [...used.entries()].sort()) {
  let full;
  try {
    full = JSON.parse(readFileSync(join(iconifyDir, `${collection}.json`), 'utf8'));
  } catch {
    console.warn(`skip ${collection}: collection json not found in @iconify/json`);
    continue;
  }

  const data = getIcons(full, [...names].sort());
  if (data.not_found?.length) {
    throw new Error(`Missing icons in ${collection}: ${data.not_found.join(', ')}`);
  }

  const iconCount = Object.keys(data.icons).length;
  collectionsWritten += 1;
  iconsWritten += iconCount;
  writeFileSync(join(outDir, `${collection}.json`), JSON.stringify(data));
  console.log(`generated ${collection}.json (${iconCount} icons)`);
}

console.log(`done: ${collectionsWritten} collections, ${iconsWritten} icons`);
