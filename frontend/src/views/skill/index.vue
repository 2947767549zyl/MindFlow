<script setup lang="ts">
import { h } from 'vue';
import { NButton, NTag, NSwitch, NTooltip } from 'naive-ui';
import { VueMarkdownIt, VueMarkdownItProvider } from '@/vendor/vue-markdown-shiki';

defineOptions({ name: 'SkillManage' });

interface SkillItem {
  name: string;
  description: string;
  version?: string | null;
  tags?: string | null;
  enabled: boolean;
  hasPackage?: boolean;
  status?: string | null;
  missingDeps?: string[];
  fileCount?: number | null;
  totalBytes?: number | null;
  dirPath?: string | null;
  entryPath?: string | null;
}

interface PackageImportResult {
  name: string;
  status: string;
  fileCount: number;
  totalBytes: number;
  dirPath: string;
  missingDeps?: string[];
  warnings?: string[];
}

interface SkillFilesResult {
  name: string;
  status: string;
  dirPath: string;
  entryPath: string;
  missingDeps?: string[];
  files: Array<{
    relPath: string;
    mediaType: string;
    sizeBytes: number;
    textReadable: boolean;
    isEntry: boolean;
  }>;
}

interface SkillContent {
  content: string;
  entryPath?: string | null;
}

const loading = ref(false);
const skills = ref<SkillItem[]>([]);
const importing = ref(false);
const importResults = ref<string[]>([]);
const fileInputRef = ref<HTMLInputElement | null>(null);
const dirInputRef = ref<HTMLInputElement | null>(null);

async function loadSkills() {
  loading.value = true;
  const { error, data } = await request<SkillItem[]>({ url: 'skill' });
  loading.value = false;
  if (error || !data) return;
  skills.value = data;
}

function humanSize(bytes?: number | null) {
  if (bytes == null || bytes < 0) return '未知大小';
  if (bytes < 1024) return `${bytes}B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)}KB`;
  return `${(bytes / 1024 / 1024).toFixed(1)}MB`;
}

function triggerImport() {
  fileInputRef.value?.click();
}

function triggerDirImport() {
  const input = dirInputRef.value;
  if (!input) return;
  // 目录选择用 webkitdirectory（Firefox 认 directory），由脚本设属性，避免模板类型报错
  input.setAttribute('webkitdirectory', '');
  input.setAttribute('directory', '');
  input.click();
}

/**
 * 目录导入：按所选根目录分组，逐个 Skill 目录提交 multipart。
 * 每个文件带上相对根目录的路径，后端据此在 data/skills/<name>/ 下原样重建目录树。
 */
async function onDirPicked(event: Event) {
  const input = event.target as HTMLInputElement;
  const files = Array.from(input.files || []);
  if (files.length === 0) return;

  const groups = new Map<string, File[]>();
  for (const file of files) {
    const rel = (file as File & { webkitRelativePath?: string }).webkitRelativePath || file.name;
    const rootName = rel.split('/')[0] || 'unnamed';
    if (!groups.has(rootName)) groups.set(rootName, []);
    groups.get(rootName)!.push(file);
  }

  importing.value = true;
  const results: string[] = [];
  for (const [rootName, groupFiles] of groups) {
    if (!groupFiles.some(f => ((f as File & { webkitRelativePath?: string }).webkitRelativePath || f.name)
      .toLowerCase()
      .endsWith('/skill.md') || f.name.toLowerCase() === 'skill.md')) {
      results.push(`${rootName}：目录内未找到 SKILL.md，已跳过`);
      continue;
    }
    const form = new FormData();
    for (const file of groupFiles) {
      const rel = (file as File & { webkitRelativePath?: string }).webkitRelativePath || file.name;
      form.append('files', file, file.name);
      form.append('paths', rel.includes('/') ? rel.slice(rel.indexOf('/') + 1) : rel);
    }
    form.append('rootDirName', rootName);
    try {
      const { error, data, response } = await request<PackageImportResult>({
        url: 'skill/package',
        method: 'post',
        data: form,
        headers: { 'Content-Type': 'multipart/form-data' },
        timeout: 5 * 60 * 1000
      });
      if (error) {
        // 后端把校验失败回成 code!=200 + 具体原因，这里直接透出，不要只报“网络错误”
        const reason = (response?.data as { message?: string } | undefined)?.message;
        results.push(`${rootName}：导入失败${reason ? `（${reason}）` : '（无权限或网络错误）'}`);
        continue;
      }
      const deps = data?.missingDeps?.length ? `；依赖提示：${data.missingDeps.join('；')}` : '';
      results.push(
        `${rootName}：已导入 ${data?.name || ''}（${data?.fileCount ?? groupFiles.length} 个文件，` +
          `${humanSize(data?.totalBytes)}，${data?.status === 'DEGRADED' ? '降级' : '完整'}）${deps}`
      );
      for (const warning of data?.warnings || []) {
        results.push(`  ↳ ${warning}`);
      }
    } catch {
      results.push(`${rootName}：导入异常`);
    }
  }
  importing.value = false;
  importResults.value = results;
  input.value = '';
  const failed = results.some(item => item.includes('失败') || item.includes('跳过') || item.includes('异常'));
  window.$message?.[failed ? 'warning' : 'success'](`导入完成：${groups.size} 个目录`);
  loadSkills();
}

/** 单个 SKILL.md 导入（旧入口）：后端现在同样落盘 + 入库，只是没有附属文件 */
async function onFilesPicked(event: Event) {
  const input = event.target as HTMLInputElement;
  const files = Array.from(input.files || []);
  if (files.length === 0) return;

  importing.value = true;
  const results: string[] = [];
  for (const file of files) {
    try {
      const content = await file.text();
      const { error, data, response } = await request<{ name?: string; status?: string; warnings?: string[] }>({
        url: 'skill',
        method: 'post',
        data: { content }
      });
      if (error) {
        const reason = (response?.data as { message?: string } | undefined)?.message;
        results.push(`${file.name}：导入失败${reason ? `（${reason}）` : '（无权限、frontmatter 非法或超限）'}`);
        continue;
      }
      results.push(`${file.name}：已导入 → ${data?.name || ''}（${data?.status || 'READY'}）`);
      for (const warning of data?.warnings || []) {
        results.push(`  ↳ ${warning}`);
      }
    } catch {
      results.push(`${file.name}：读取文件失败`);
    }
  }
  importing.value = false;
  importResults.value = results;
  input.value = '';
  const failed = results.some(item => item.includes('失败'));
  window.$message?.[failed ? 'warning' : 'success'](`导入完成：${files.length} 个文件`);
  loadSkills();
}

async function previewSkill(row: SkillItem) {
  const { error, data } = await request<SkillContent>({ url: `skill/${row.name}/content` });
  if (error || !data?.content) {
    window.$message?.warning('读取 Skill 内容失败');
    return;
  }
  window.$dialog?.info({
    title: `${row.name} · SKILL.md`,
    content: () => h(VueMarkdownIt, { content: data.content }),
    positiveText: '关闭',
    style: { width: '760px' }
  });
}

/** 包内文件清单：完整性与"到底有哪些附属文件可读"都在这里看 */
async function showFiles(row: SkillItem) {
  const { error, data } = await request<SkillFilesResult>({ url: `skill/${row.name}/files` });
  if (error || !data?.files) {
    if (!row.hasPackage) window.$message?.warning('该 Skill 仍是旧的单文件数据，尚无目录清单');
    return;
  }
  window.$dialog?.info({
    title: `${row.name} · ${data.files.length} 个文件`,
    content: () =>
      h('div', { class: 'flex-col gap-6px max-h-420px overflow-auto text-12px' }, [
        h('div', { class: 'color-#999' }, `目录：${data.dirPath}　入口：${data.entryPath}`),
        ...(data.missingDeps?.length
          ? [h('div', { class: 'color-#d68b00' }, `⚠ ${data.missingDeps.join('；')}`)]
          : []),
        ...data.files.map(item =>
          h(
            NButton,
            {
              size: 'tiny',
              text: true,
              disabled: !item.textReadable,
              onClick: () => (item.textReadable ? previewFile(row.name, item.relPath) : null)
            },
            {
              default: () =>
                `${item.isEntry ? '▸ ' : '  · '}${item.relPath}（${humanSize(item.sizeBytes)}）` +
                (item.isEntry ? ' ←入口' : '') +
                (item.textReadable ? '' : ' ←二进制，仅登记路径')
            }
          )
        )
      ]),
    positiveText: '关闭',
    style: { width: '640px' }
  });
}

async function previewFile(name: string, relPath: string) {
  const { error, data } = await request<{ content: string }>({
    url: `skill/${name}/file`,
    params: { path: relPath, offset: 1, limit: 2000 }
  });
  if (error || !data) return;
  window.$dialog?.info({
    title: `${name} / ${relPath}`,
    content: () =>
      h('pre', { class: 'max-h-460px overflow-auto text-12px whitespace-pre-wrap' }, data.content || '（空文件）'),
    positiveText: '关闭',
    style: { width: '760px' }
  });
}

async function downloadSkill(row: SkillItem) {
  const { error, data } = await request<SkillContent>({ url: `skill/${row.name}/content` });
  if (error || !data?.content) {
    window.$message?.warning('读取 Skill 内容失败');
    return;
  }
  const blob = new Blob([data.content], { type: 'text/markdown;charset=utf-8' });
  const url = URL.createObjectURL(blob);
  const link = document.createElement('a');
  link.href = url;
  link.download = `${row.name}.md`;
  link.click();
  URL.revokeObjectURL(url);
}

async function toggleEnabled(row: SkillItem) {
  const target = row.enabled;
  const { error } = await request({
    url: `skill/${row.name}/${target ? 'disable' : 'enable'}`,
    method: 'post'
  });
  if (error) {
    row.enabled = !target;
    window.$message?.error('操作失败（仅管理员可启停）');
    return;
  }
  window.$message?.success(target ? '已禁用' : '已启用');
  loadSkills();
}

function removeSkill(row: SkillItem) {
  window.$dialog?.warning({
    title: '删除 Skill',
    content: `确认删除「${row.name}」？将同时删除磁盘上的 data/skills/${row.name}/ 目录，删除后 Agent 不再看到该技能索引。`,
    positiveText: '删除',
    negativeText: '取消',
    onPositiveClick: async () => {
      const { error } = await request({ url: `skill/${row.name}`, method: 'delete' });
      if (error) {
        window.$message?.error('删除失败（仅管理员可删除）');
        return;
      }
      window.$message?.success('已删除');
      loadSkills();
    }
  });
}

const columns = computed(() => [
  { title: '名称', key: 'name', width: 180, ellipsis: { tooltip: true } },
  { title: '描述', key: 'description', minWidth: 240, ellipsis: { tooltip: true } },
  { title: '版本', key: 'version', width: 90 },
  {
    title: '完整性',
    key: 'status',
    width: 170,
    render: (row: SkillItem) => {
      if (!row.hasPackage) {
        return h('span', { class: 'text-11px color-#999' }, '旧单文件数据');
      }
      const ready = row.status === 'READY';
      const tag = h(
        NTag,
        { size: 'small', type: ready ? 'success' : 'warning', bordered: false },
        { default: () => `${ready ? '完整' : '降级'} · ${row.fileCount ?? 0} 文件/${humanSize(row.totalBytes)}` }
      );
      if (!row.missingDeps?.length) return tag;
      return h(NTooltip, { trigger: 'hover' }, {
        trigger: () => tag,
        default: () => h('div', { class: 'max-w-320px text-12px' }, row.missingDeps!.join('\n'))
      });
    }
  },
  {
    title: '启用',
    key: 'enabled',
    width: 80,
    render: (row: SkillItem) =>
      h(NSwitch, {
        value: row.enabled,
        size: 'small',
        'onUpdate:value': () => toggleEnabled(row)
      })
  },
  {
    title: '操作',
    key: 'actions',
    width: 280,
    render: (row: SkillItem) =>
      h('div', { class: 'flex gap-6px' }, [
        h(NButton, { size: 'tiny', onClick: () => previewSkill(row) }, { default: () => '预览' }),
        h(
          NButton,
          { size: 'tiny', disabled: !row.hasPackage, onClick: () => showFiles(row) },
          { default: () => '文件' }
        ),
        h(NButton, { size: 'tiny', onClick: () => downloadSkill(row) }, { default: () => '下载' }),
        h(NButton, { size: 'tiny', type: 'error', ghost: true, onClick: () => removeSkill(row) }, { default: () => '删除' })
      ])
  }
]);

onMounted(() => {
  loadSkills();
});
</script>

<template>
  <div class="h-full flex-col gap-12px p-16px">
    <NAlert type="warning" :bordered="false">
      Skill 是注入 Agent 提示词的“决策手册”，会直接影响 Agent 行为，请仅导入可信来源。
      查看/下载对所有用户开放；<b>导入/删除/启停仅管理员</b>，且所有变更都会写入审计日志。
    </NAlert>

    <div class="flex items-center justify-between">
      <div class="flex items-baseline gap-10px">
        <span class="text-16px font-medium">Skill 技能</span>
        <span class="text-11px color-#999">
          一个 Skill = 一个目录：索引常驻提示词，命中时 load_skill 读入口 SKILL.md，附属文件由 Agent 按需 read_file 读取
        </span>
      </div>
      <div class="flex gap-8px">
        <input ref="fileInputRef" type="file" accept=".md,text/markdown" multiple class="hidden" @change="onFilesPicked" />
        <input ref="dirInputRef" type="file" multiple class="hidden" @change="onDirPicked" />
        <NButton size="small" type="primary" :loading="importing" @click="triggerDirImport">导入 Skill 目录</NButton>
        <NButton size="small" :loading="importing" @click="triggerImport">仅导入 SKILL.md</NButton>
        <NButton size="small" @click="loadSkills">刷新</NButton>
      </div>
    </div>

    <NAlert v-if="importResults.length > 0" type="default" closable :bordered="false" @close="importResults = []">
      <div class="flex-col gap-2px">
        <div v-for="(item, index) in importResults" :key="index" class="text-12px">{{ item }}</div>
      </div>
    </NAlert>

    <NDataTable
      :columns="columns"
      :data="skills"
      :loading="loading"
      :bordered="false"
      :single-line="false"
      size="small"
      flex-height
      class="min-h-0 flex-1"
    />

    <div class="text-11px color-#999">
      目录结构建议：<code>&lt;skill-name&gt;/SKILL.md</code> + <code>references/</code>、<code>scripts/</code> 等附属文件；
      SKILL.md 需要合法 frontmatter：<code>name</code>（kebab-case，与目录名一致）、<code>description</code>（必填，≤1024 字，
      索引段只展示前 240 字，完整描述由 load_skill 读出），
      可选 <code>version</code> / <code>author</code> / <code>tags</code>。附属脚本当前仅可读、无执行通道。
    </div>
  </div>
</template>

<style scoped></style>
