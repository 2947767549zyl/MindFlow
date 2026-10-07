<script setup lang="ts">
import { h } from 'vue';
import { NButton, NTag, NSwitch } from 'naive-ui';

defineOptions({ name: 'McpConfig' });

interface McpServer {
  name: string;
  transportType: 'stdio' | 'http' | string;
  command?: string | null;
  args?: string[] | null;
  url?: string | null;
  headers?: Record<string, string> | null;
  env?: Record<string, string> | null;
  enabled: boolean;
  createdBy?: string | null;
  updatedAt?: string | null;
  runtimeStatus?: string | null;
}

interface KeyValueRow {
  key: string;
  value: string;
}

const loading = ref(false);
const servers = ref<McpServer[]>([]);

const formVisible = ref(false);
const formMode = ref<'create' | 'edit'>('create');
const submitting = ref(false);
const form = ref({
  name: '',
  transportType: 'stdio' as 'stdio' | 'http',
  command: 'npx',
  args: ['-y'] as string[],
  url: '',
  headers: [] as KeyValueRow[],
  env: [] as KeyValueRow[]
});

const logsVisible = ref(false);
const logsLoading = ref(false);
const logsContent = ref('');
const logsServerName = ref('');

const runtimeStatusMeta: Record<string, { label: string; type: 'default' | 'success' | 'warning' | 'error' | 'info' }> = {
  READY: { label: '就绪', type: 'success' },
  STARTING: { label: '启动中', type: 'warning' },
  ERROR: { label: '异常', type: 'error' },
  NOT_STARTED: { label: '未启动', type: 'default' }
};

async function loadServers() {
  loading.value = true;
  const { error, data } = await request<McpServer[]>({ url: 'mcp/servers' });
  if (!error && data) {
    servers.value = data;
  }
  loading.value = false;
}

function resetForm() {
  form.value = {
    name: '',
    transportType: 'stdio',
    command: 'npx',
    args: ['-y'],
    url: '',
    headers: [],
    env: []
  };
}

function openCreate() {
  formMode.value = 'create';
  resetForm();
  formVisible.value = true;
}

function openEdit(row: McpServer) {
  formMode.value = 'edit';
  form.value = {
    name: row.name,
    transportType: row.transportType === 'http' ? 'http' : 'stdio',
    command: row.command || 'npx',
    args: row.args && row.args.length > 0 ? [...row.args] : ['-y'],
    url: row.url || '',
    headers: mapToRows(row.headers),
    env: mapToRows(row.env)
  };
  formVisible.value = true;
}

function mapToRows(source?: Record<string, string> | null): KeyValueRow[] {
  if (!source) return [];
  return Object.entries(source).map(([key, value]) => ({ key, value: String(value) }));
}

function rowsToMap(rows: KeyValueRow[]): Record<string, string> {
  const result: Record<string, string> = {};
  rows.forEach(row => {
    if (row.key.trim()) result[row.key.trim()] = row.value;
  });
  return result;
}

function addArg() {
  form.value.args.push('');
}

function removeArg(index: number) {
  form.value.args.splice(index, 1);
}

function addRow(target: KeyValueRow[]) {
  target.push({ key: '', value: '' });
}

function removeRow(target: KeyValueRow[], index: number) {
  target.splice(index, 1);
}

async function submitForm() {
  if (!form.value.name.trim()) {
    window.$message?.warning('请填写 server 名称');
    return;
  }
  submitting.value = true;
  const payload =
    form.value.transportType === 'stdio'
      ? {
          name: form.value.name.trim(),
          transportType: 'stdio',
          command: form.value.command.trim(),
          args: form.value.args.filter(arg => arg.trim() !== ''),
          env: rowsToMap(form.value.env)
        }
      : {
          name: form.value.name.trim(),
          transportType: 'http',
          url: form.value.url.trim(),
          headers: rowsToMap(form.value.headers)
        };

  const { error, data } = await request({
    url: formMode.value === 'create' ? 'mcp/servers' : `mcp/servers/${form.value.name.trim()}`,
    method: formMode.value === 'create' ? 'post' : 'put',
    data: payload
  });
  submitting.value = false;
  if (error) return;

  window.$message?.success(data?.message || '已保存');
  formVisible.value = false;
  loadServers();
}

async function toggleEnabled(row: McpServer) {
  const target = row.enabled;
  const { error } = await request({
    url: `mcp/servers/${row.name}/${target ? 'disable' : 'enable'}`,
    method: 'post'
  });
  if (error) {
    row.enabled = !target;
    return;
  }
  window.$message?.success(target ? '已禁用（重启后端或再次启用后生效）' : '已启用');
  loadServers();
}

async function restartServer(row: McpServer) {
  const { error, data } = await request({
    url: `mcp/servers/${row.name}/restart`,
    method: 'post'
  });
  if (error) return;
  window.$message?.success(data?.data?.runtime || '已触发重启');
  loadServers();
}

function removeServer(row: McpServer) {
  window.$dialog?.warning({
    title: '删除 MCP Server',
    content: `确认删除「${row.name}」？该操作不可撤销，删除后需要重启后端才会卸载其工具。`,
    positiveText: '删除',
    negativeText: '取消',
    onPositiveClick: async () => {
      const { error } = await request({ url: `mcp/servers/${row.name}`, method: 'delete' });
      if (error) return;
      window.$message?.success('已删除');
      loadServers();
    }
  });
}

async function showLogs(row: McpServer) {
  logsServerName.value = row.name;
  logsVisible.value = true;
  logsLoading.value = true;
  logsContent.value = '';
  const { error, data } = await request<{ logs: string }>({ url: `mcp/servers/${row.name}/logs` });
  logsLoading.value = false;
  if (!error && data) {
    logsContent.value = data.logs || '（暂无输出）';
  } else {
    logsContent.value = '（读取失败）';
  }
}

const columns = computed(() => [
  { title: '名称', key: 'name', width: 180, ellipsis: { tooltip: true } },
  {
    title: '传输',
    key: 'transportType',
    width: 90,
    render: (row: McpServer) =>
      h(NTag, { size: 'small', type: row.transportType === 'http' ? 'info' : 'default' }, { default: () => row.transportType })
  },
  {
    title: '目标',
    key: 'target',
    minWidth: 280,
    ellipsis: { tooltip: true },
    render: (row: McpServer) =>
      row.transportType === 'http'
        ? row.url || '-'
        : `${row.command || ''} ${(row.args || []).join(' ')}`.trim() || '-'
  },
  {
    title: '运行状态',
    key: 'runtimeStatus',
    width: 110,
    render: (row: McpServer) => {
      const meta = runtimeStatusMeta[row.runtimeStatus || 'NOT_STARTED'] || runtimeStatusMeta.NOT_STARTED;
      return h(NTag, { size: 'small', type: meta.type, round: true }, { default: () => meta.label });
    }
  },
  {
    title: '启用',
    key: 'enabled',
    width: 90,
    render: (row: McpServer) =>
      h(NSwitch, {
        value: row.enabled,
        size: 'small',
        'onUpdate:value': () => toggleEnabled(row)
      })
  },
  { title: '更新时间', key: 'updatedAt', width: 170, ellipsis: { tooltip: true } },
  {
    title: '操作',
    key: 'actions',
    width: 250,
    render: (row: McpServer) =>
      h('div', { class: 'flex gap-6px' }, [
        h(NButton, { size: 'tiny', onClick: () => openEdit(row) }, { default: () => '编辑' }),
        h(NButton, { size: 'tiny', onClick: () => restartServer(row) }, { default: () => '重启' }),
        h(NButton, { size: 'tiny', onClick: () => showLogs(row) }, { default: () => '日志' }),
        h(NButton, { size: 'tiny', type: 'error', ghost: true, onClick: () => removeServer(row) }, { default: () => '删除' })
      ])
  }
]);

onMounted(() => {
  loadServers();
});
</script>

<template>
  <div class="h-full flex-col gap-12px p-16px">
    <NAlert type="warning" :bordered="false">
      MCP server 由服务端进程启动，仅管理员可配置；命令受白名单与 SSRF 校验约束，所有变更都会写入审计日志。
      配置修改后需「重启」该 server（或重启后端）才会生效。
    </NAlert>

    <div class="flex items-center justify-between">
      <div class="text-16px font-medium">MCP 工具配置</div>
      <div class="flex gap-8px">
        <NButton size="small" @click="loadServers">刷新</NButton>
        <NButton size="small" type="primary" @click="openCreate">新增 MCP Server</NButton>
      </div>
    </div>

    <NDataTable
      :columns="columns"
      :data="servers"
      :loading="loading"
      :bordered="false"
      :single-line="false"
      size="small"
      flex-height
      class="min-h-0 flex-1"
    />

    <NModal v-model:show="formVisible" preset="card" class="w-640px max-w-[92vw]" :title="formMode === 'create' ? '新增 MCP Server' : '编辑 MCP Server'">
      <div class="flex-col gap-14px">
        <div>
          <div class="mb-6px text-13px">名称</div>
          <NInput v-model:value="form.name" :disabled="formMode === 'edit'" placeholder="如 chrome-devtools（字母/数字/-/_，≤64）" />
        </div>

        <div>
          <div class="mb-6px text-13px">传输类型</div>
          <NRadioGroup v-model:value="form.transportType">
            <NSpace>
              <NRadio value="stdio">stdio（本地子进程）</NRadio>
              <NRadio value="http">http（远程 Streamable HTTP）</NRadio>
            </NSpace>
          </NRadioGroup>
        </div>

        <template v-if="form.transportType === 'stdio'">
          <div>
            <div class="mb-6px text-13px">命令（白名单：npx / uvx / pnpm / bunx / node / python / python3）</div>
            <NInput v-model:value="form.command" placeholder="npx" />
          </div>
          <div>
            <div class="mb-6px flex items-center justify-between">
              <span class="text-13px">参数</span>
              <NButton size="tiny" @click="addArg">添加参数</NButton>
            </div>
            <div class="flex-col gap-8px">
              <div v-for="(_, index) in form.args" :key="index" class="flex gap-8px">
                <NInput v-model:value="form.args[index]" size="small" placeholder="如 -y 或 包名" />
                <NButton size="small" quaternary type="error" @click="removeArg(index)">移除</NButton>
              </div>
            </div>
          </div>
          <div>
            <div class="mb-6px flex items-center justify-between">
              <span class="text-13px">环境变量</span>
              <NButton size="tiny" @click="addRow(form.env)">添加变量</NButton>
            </div>
            <div class="flex-col gap-8px">
              <div v-for="(row, index) in form.env" :key="index" class="flex gap-8px">
                <NInput v-model:value="row.key" size="small" placeholder="KEY" />
                <NInput v-model:value="row.value" size="small" placeholder="VALUE" />
                <NButton size="small" quaternary type="error" @click="removeRow(form.env, index)">移除</NButton>
              </div>
            </div>
          </div>
        </template>

        <template v-else>
          <div>
            <div class="mb-6px text-13px">URL</div>
            <NInput v-model:value="form.url" placeholder="https://mcp.example.com/sse（禁止本机/内网地址）" />
          </div>
          <div>
            <div class="mb-6px flex items-center justify-between">
              <span class="text-13px">Headers</span>
              <NButton size="tiny" @click="addRow(form.headers)">添加 Header</NButton>
            </div>
            <div class="flex-col gap-8px">
              <div v-for="(row, index) in form.headers" :key="index" class="flex gap-8px">
                <NInput v-model:value="row.key" size="small" placeholder="Header 名" />
                <NInput v-model:value="row.value" size="small" placeholder="Header 值" />
                <NButton size="small" quaternary type="error" @click="removeRow(form.headers, index)">移除</NButton>
              </div>
            </div>
          </div>
        </template>
      </div>

      <template #footer>
        <div class="flex justify-end gap-8px">
          <NButton @click="formVisible = false">取消</NButton>
          <NButton type="primary" :loading="submitting" @click="submitForm">保存</NButton>
        </div>
      </template>
    </NModal>

    <NDrawer v-model:show="logsVisible" :width="560" placement="right">
      <NDrawerContent :title="`日志 · ${logsServerName}`" closable>
        <NSpin :show="logsLoading">
          <pre class="m-0 whitespace-pre-wrap break-all text-12px leading-[1.6]">{{ logsContent }}</pre>
        </NSpin>
      </NDrawerContent>
    </NDrawer>
  </div>
</template>

<style scoped></style>
