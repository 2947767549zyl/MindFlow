<script setup lang="ts">
defineOptions({ name: 'AgentTool' });

interface ToolParam {
  name: string;
  type: string;
  required: boolean;
  description: string;
}

interface BuiltinTool {
  name: string;
  description: string;
  parameters?: Record<string, any> | null;
}

interface McpTool {
  serverName: string;
  name: string;
  namespacedName: string;
  description: string;
  inputSchema?: Record<string, any> | null;
}

interface McpServerInfo {
  name: string;
  transport: string;
  status: string;
}

const loading = ref(false);
const keyword = ref('');
const builtinTools = ref<BuiltinTool[]>([]);
const mcpTools = ref<McpTool[]>([]);
const mcpServers = ref<McpServerInfo[]>([]);

async function loadTools() {
  loading.value = true;
  const { error, data } = await request<{
    builtin: BuiltinTool[];
    mcp: McpTool[];
    mcpServers: McpServerInfo[];
  }>({ url: 'agent/tools' });
  loading.value = false;
  if (error || !data) return;
  builtinTools.value = data.builtin || [];
  mcpTools.value = data.mcp || [];
  mcpServers.value = data.mcpServers || [];
}

function extractParams(schema?: Record<string, any> | null): ToolParam[] {
  const properties = (schema?.properties || {}) as Record<string, any>;
  const required = Array.isArray(schema?.required) ? (schema?.required as string[]) : [];
  return Object.entries(properties).map(([name, raw]) => {
    const prop = (raw || {}) as Record<string, any>;
    const type = prop.type || (Array.isArray(prop.enum) ? 'enum' : 'any');
    let description = prop.description || '';
    if (Array.isArray(prop.enum)) {
      const values = prop.enum.join(' / ');
      description = description ? `${description}（可选值：${values}）` : `可选值：${values}`;
    }
    return { name, type, required: required.includes(name), description };
  });
}

function isSensitive(description: string) {
  return (description || '').includes('⚠️');
}

function matchKeyword(name: string, description: string) {
  const kw = keyword.value.trim().toLowerCase();
  if (!kw) return true;
  return name.toLowerCase().includes(kw) || (description || '').toLowerCase().includes(kw);
}

const groupRules: { key: string; label: string; accent: string; test: (name: string) => boolean }[] = [
  { key: 'file', label: '项目文件', accent: '--mod-file', test: name => /^(read_file|grep|glob)$/i.test(name) },
  {
    key: 'search',
    label: '检索与知识',
    accent: '--mod-search',
    test: name => /search|navigat|retriev|knowledge|wiki|stats/i.test(name)
  },
  { key: 'generate', label: '生成与总结', accent: '--mod-generate', test: name => /generat|summar|writ|draft/i.test(name) },
  { key: 'memory', label: '记忆与技能', accent: '--mod-memory', test: name => /memory|skill|remember/i.test(name) },
  { key: 'other', label: '其他能力', accent: '--mod-other', test: () => true }
];

const builtinGroups = computed(() => {
  const result = groupRules.map(rule => ({ ...rule, tools: [] as BuiltinTool[] }));
  for (const tool of builtinTools.value) {
    if (!matchKeyword(tool.name, tool.description)) continue;
    result.find(rule => rule.test(tool.name))?.tools.push(tool);
  }
  return result.filter(group => group.tools.length > 0);
});

const serverByName = computed(() => new Map(mcpServers.value.map(server => [server.name, server])));

const mcpGroups = computed(() => {
  const groups = new Map<string, { serverName: string; server?: McpServerInfo; tools: McpTool[] }>();
  for (const tool of mcpTools.value) {
    if (!matchKeyword(tool.namespacedName, tool.description)) continue;
    const group = groups.get(tool.serverName) || {
      serverName: tool.serverName,
      server: serverByName.value.get(tool.serverName),
      tools: []
    };
    group.tools.push(tool);
    groups.set(tool.serverName, group);
  }
  return [...groups.values()].filter(group => group.tools.length > 0);
});

const statusMeta: Record<string, { label: string; type: 'default' | 'success' | 'warning' | 'error' }> = {
  READY: { label: '就绪', type: 'success' },
  STARTING: { label: '启动中', type: 'warning' },
  ERROR: { label: '异常', type: 'error' },
  NOT_STARTED: { label: '未启动', type: 'default' }
};

function statusOf(name: string) {
  const status = serverByName.value.get(name)?.status || 'UNKNOWN';
  return statusMeta[status] || { label: status, type: 'default' as const };
}

function accentStyle(accent: string) {
  return {
    '--accent': `rgb(var(${accent}))`,
    '--accent-soft': `rgb(var(${accent}) / 0.1)`
  } as Record<string, string>;
}

const visibleCount = computed(
  () =>
    builtinGroups.value.reduce((sum, group) => sum + group.tools.length, 0) +
    mcpGroups.value.reduce((sum, group) => sum + group.tools.length, 0)
);

onMounted(() => {
  loadTools();
});
</script>

<template>
  <div class="h-full flex-col gap-14px p-16px">
    <div class="flex items-center justify-between gap-12px">
      <div class="flex items-baseline gap-10px">
        <h2 class="m-0 text-title font-600" style="color: rgb(var(--text-1))">Agent 工具</h2>
        <span class="text-body-sm" style="color: rgb(var(--text-3))">模型在一次对话中可以调用的全部工具</span>
      </div>
      <div class="flex items-center gap-8px">
        <div class="tool-search">
          <icon-material-symbols:manage-search class="shrink-0 text-16px" style="color: rgb(var(--text-3))" />
          <input v-model="keyword" placeholder="搜索工具" class="tool-search__input" />
          <span v-if="keyword" class="tool-search__count">命中 {{ visibleCount }}</span>
        </div>
        <NButton size="small" secondary @click="loadTools">刷新</NButton>
      </div>
    </div>

    <div class="grid grid-cols-3 gap-14px">
      <div class="kpi-card" :style="accentStyle('--mod-search')">
        <span class="kpi-card__chip"><icon-material-symbols:verified-rounded /></span>
        <div class="flex-col gap-2px">
          <span class="kpi-card__value">{{ builtinTools.length }}</span>
          <span class="kpi-card__label">内置受信工具</span>
        </div>
      </div>
      <div class="kpi-card" :style="accentStyle('--mod-mcp')">
        <span class="kpi-card__chip"><icon-material-symbols:extension-outline /></span>
        <div class="flex-col gap-2px">
          <span class="kpi-card__value">{{ mcpTools.length }}</span>
          <span class="kpi-card__label">MCP 外置工具</span>
        </div>
      </div>
      <div class="kpi-card" :style="accentStyle('--mod-generate')">
        <span class="kpi-card__chip"><icon-material-symbols:lan /></span>
        <div class="flex-col gap-2px">
          <span class="kpi-card__value">{{ mcpServers.length }}</span>
          <span class="kpi-card__label">MCP 服务</span>
        </div>
      </div>
    </div>

    <div class="min-h-0 flex-1 grid gap-14px lg:grid-cols-2">
      <section class="panel">
        <header class="panel__head">
          <div class="flex items-center gap-9px" :style="accentStyle('--mod-search')">
            <span class="panel__chip"><icon-material-symbols:verified-rounded /></span>
            <span class="panel__title">内置受信工具</span>
          </div>
          <NTag size="tiny" type="success" :bordered="false">只读 · 免审批</NTag>
        </header>
        <div class="panel__body">
          <NSpin :show="loading">
            <div v-if="builtinGroups.length === 0 && !loading" class="empty-state">
              <icon-material-symbols:widgets class="text-38px" style="color: rgb(var(--text-3))" />
              <span class="text-body" style="color: rgb(var(--text-3))">
                {{ keyword ? '没有匹配的内置工具' : '暂无内置工具' }}
              </span>
            </div>
            <div v-for="group in builtinGroups" :key="group.key" class="group" :style="accentStyle(group.accent)">
              <div class="group__head">
                <icon-material-symbols:folder-open v-if="group.key === 'file'" class="text-15px" />
                <icon-material-symbols:manage-search v-else-if="group.key === 'search'" class="text-15px" />
                <icon-material-symbols:auto-awesome v-else-if="group.key === 'generate'" class="text-15px" />
                <icon-material-symbols:psychology v-else-if="group.key === 'memory'" class="text-15px" />
                <icon-material-symbols:widgets v-else class="text-15px" />
                <span>{{ group.label }}</span>
                <span class="group__count">{{ group.tools.length }}</span>
              </div>
              <div class="tool-grid">
                <article v-for="tool in group.tools" :key="tool.name" class="tool-card">
                  <div class="tool-card__top">
                    <span class="tool-card__icon"><icon-material-symbols:function /></span>
                    <code class="tool-card__name">{{ tool.name }}</code>
                    <span class="tool-card__count">{{ extractParams(tool.parameters).length }} 参数</span>
                  </div>
                  <p class="tool-card__desc" :title="tool.description">{{ tool.description }}</p>
                  <div v-if="extractParams(tool.parameters).length" class="tool-card__params">
                    <span
                      v-for="param in extractParams(tool.parameters)"
                      :key="param.name"
                      class="chip"
                      :title="param.description || param.name"
                    >
                      <code>{{ param.name }}</code>
                      <span class="chip__type">{{ param.type }}</span>
                      <i v-if="param.required" class="chip__required" />
                    </span>
                  </div>
                </article>
              </div>
            </div>
          </NSpin>
        </div>
      </section>

      <section class="panel">
        <header class="panel__head">
          <div class="flex items-center gap-9px" :style="accentStyle('--mod-mcp')">
            <span class="panel__chip"><icon-material-symbols:extension-outline /></span>
            <span class="panel__title">MCP 外置工具</span>
          </div>
          <NTag size="tiny" type="warning" :bordered="false">不受控 · 强制审批</NTag>
        </header>
        <div class="panel__body">
          <NSpin :show="loading">
            <div v-if="mcpGroups.length === 0 && !loading" class="empty-state">
              <icon-material-symbols:extension-off class="text-38px" style="color: rgb(var(--text-3))" />
              <span class="text-body" style="color: rgb(var(--text-3))">
                {{ keyword ? '没有匹配的外置工具' : '尚未导入 MCP 工具' }}
              </span>
              <span v-if="!keyword" class="text-body-sm" style="color: rgb(var(--text-3))">
                可在「MCP 工具」页配置外部 server 后回到本页查看
              </span>
            </div>
            <div v-for="group in mcpGroups" :key="group.serverName" class="group" :style="accentStyle('--mod-mcp')">
              <div class="group__head">
                <icon-material-symbols:lan class="text-15px" />
                <code class="group__server">{{ group.serverName }}</code>
                <NTag size="tiny" :bordered="false">{{ group.server?.transport || 'unknown' }}</NTag>
                <NTag size="tiny" :type="statusOf(group.serverName).type" :bordered="false">
                  {{ statusOf(group.serverName).label }}
                </NTag>
                <span class="group__count">{{ group.tools.length }}</span>
              </div>
              <div class="tool-grid">
                <article v-for="tool in group.tools" :key="tool.namespacedName" class="tool-card">
                  <div class="tool-card__top">
                    <span class="tool-card__icon"><icon-material-symbols:extension-outline /></span>
                    <code class="tool-card__name">{{ tool.namespacedName.replace('mcp__', '') }}</code>
                    <span v-if="isSensitive(tool.description)" class="tool-card__flag">敏感</span>
                    <span class="tool-card__count">{{ extractParams(tool.inputSchema).length }} 参数</span>
                  </div>
                  <p class="tool-card__desc" :title="tool.description">{{ tool.description }}</p>
                  <div v-if="extractParams(tool.inputSchema).length" class="tool-card__params">
                    <span
                      v-for="param in extractParams(tool.inputSchema)"
                      :key="param.name"
                      class="chip"
                      :title="param.description || param.name"
                    >
                      <code>{{ param.name }}</code>
                      <span class="chip__type">{{ param.type }}</span>
                      <i v-if="param.required" class="chip__required" />
                    </span>
                  </div>
                </article>
              </div>
            </div>
          </NSpin>
        </div>
      </section>
    </div>
  </div>
</template>

<style scoped>
.kpi-card {
  display: flex;
  align-items: center;
  gap: 14px;
  border-radius: 16px;
  background: rgb(var(--container-bg-color));
  box-shadow: var(--shadow-card);
  padding: 16px 18px;
}

.kpi-card__chip {
  display: inline-flex;
  height: 40px;
  width: 40px;
  flex-shrink: 0;
  align-items: center;
  justify-content: center;
  border-radius: 13px;
  background: var(--accent-soft);
  color: var(--accent);
  font-size: 21px;
}

.kpi-card__value {
  font-size: 28px;
  font-weight: 700;
  line-height: 1.15;
  color: rgb(var(--text-1));
}

.kpi-card__label {
  font-size: 12px;
  color: rgb(var(--text-3));
}

.tool-search {
  display: flex;
  height: 34px;
  align-items: center;
  gap: 8px;
  border: 1px solid rgb(15 23 42 / 0.1);
  border-radius: 999px;
  background: rgb(15 23 42 / 0.025);
  padding: 0 14px;
}

.tool-search__input {
  width: 150px;
  border: none;
  background: transparent;
  font-size: 13px;
  outline: none;
  color: rgb(var(--text-1));
}

.tool-search__input::placeholder {
  color: #b3b8c2;
}

.tool-search__count {
  flex-shrink: 0;
  border-radius: 999px;
  background: rgb(var(--primary-color) / 0.12);
  padding: 1px 8px;
  font-size: 11px;
  color: rgb(var(--primary-color));
}

.panel {
  display: flex;
  min-height: 0;
  flex-direction: column;
  border-radius: 16px;
  background: rgb(var(--container-bg-color));
  box-shadow: var(--shadow-card);
  overflow: hidden;
}

.panel__head {
  display: flex;
  flex-shrink: 0;
  align-items: center;
  justify-content: space-between;
  padding: 14px 16px 12px;
}

.panel__chip {
  display: inline-flex;
  height: 30px;
  width: 30px;
  align-items: center;
  justify-content: center;
  border-radius: 10px;
  background: var(--accent-soft);
  color: var(--accent);
  font-size: 16px;
}

.panel__title {
  font-size: 14.5px;
  font-weight: 600;
  color: rgb(var(--text-1));
}

.panel__body {
  min-height: 0;
  flex: 1;
  padding: 0 16px 16px;
  overflow-y: auto;
}

.empty-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 8px;
  padding: 52px 16px;
  text-align: center;
}

.group + .group {
  margin-top: 20px;
}

.group__head {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 10px;
  font-size: 12.5px;
  font-weight: 600;
  color: var(--accent);
}

.group__server {
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 12.5px;
  color: rgb(var(--text-1));
}

.group__count {
  margin-left: auto;
  border-radius: 999px;
  background: var(--accent-soft);
  padding: 1px 8px;
  font-size: 11px;
  font-weight: 600;
  color: var(--accent);
}

.tool-grid {
  display: grid;
  grid-template-columns: repeat(1, minmax(0, 1fr));
  gap: 10px;
}

@media (min-width: 1280px) {
  .tool-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

.tool-card {
  position: relative;
  display: flex;
  min-height: 128px;
  flex-direction: column;
  border: 1px solid rgb(15 23 42 / 0.07);
  border-radius: 14px;
  background: rgb(var(--container-bg-color));
  padding: 12px 13px 12px 16px;
  overflow: hidden;
  transition:
    transform 0.16s ease,
    box-shadow 0.16s ease,
    border-color 0.16s ease;
}

.tool-card::before {
  content: '';
  position: absolute;
  left: 0;
  top: 10px;
  bottom: 10px;
  width: 3px;
  border-radius: 0 3px 3px 0;
  background: var(--accent);
}

.tool-card:hover {
  border-color: var(--accent);
  box-shadow: var(--shadow-pop);
  transform: translateY(-2px);
}

.tool-card__top {
  display: flex;
  align-items: center;
  gap: 8px;
}

.tool-card__icon {
  display: inline-flex;
  height: 26px;
  width: 26px;
  flex-shrink: 0;
  align-items: center;
  justify-content: center;
  border-radius: 9px;
  background: var(--accent-soft);
  color: var(--accent);
  font-size: 14px;
}

.tool-card__name {
  min-width: 0;
  overflow: hidden;
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 13px;
  font-weight: 600;
  line-height: 1.35;
  color: var(--accent);
  text-overflow: ellipsis;
  white-space: nowrap;
}

.tool-card__flag {
  flex-shrink: 0;
  border-radius: 999px;
  background: rgb(245 158 11 / 0.14);
  padding: 1px 7px;
  font-size: 10.5px;
  font-weight: 600;
  color: #b45309;
}

.tool-card__count {
  margin-left: auto;
  flex-shrink: 0;
  border-radius: 999px;
  background: rgb(15 23 42 / 0.05);
  padding: 1px 8px;
  font-size: 10.5px;
  color: rgb(var(--text-3));
}

.tool-card__desc {
  display: -webkit-box;
  margin: 7px 0 0;
  overflow: hidden;
  font-size: 12.5px;
  line-height: 1.6;
  color: rgb(var(--text-2));
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
}

.tool-card__params {
  display: flex;
  flex-wrap: wrap;
  gap: 5px;
  margin-top: auto;
  padding-top: 9px;
}

.chip {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  border: 1px solid rgb(15 23 42 / 0.08);
  border-radius: 7px;
  background: rgb(15 23 42 / 0.02);
  padding: 1px 7px;
  cursor: help;
}

.chip code {
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 11.5px;
  color: rgb(var(--text-1));
}

.chip__type {
  font-size: 10.5px;
  color: var(--accent);
}

.chip__required {
  height: 5px;
  width: 5px;
  border-radius: 999px;
  background: #d03050;
}
</style>
