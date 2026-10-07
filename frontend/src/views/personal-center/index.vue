<script setup lang="ts">
import { computed, onMounted, ref, h } from 'vue';
import { NTag } from 'naive-ui';
import { useStorage } from '@vueuse/core';
const { userInfo } = storeToRefs(useAuthStore());

const themeStore = useThemeStore();
const router = useRouter();

type ChatMode = 'react' | 'plan' | 'team';

const CHAT_MODE_OPTIONS: { value: ChatMode; label: string }[] = [
  { value: 'react', label: 'ReAct' },
  { value: 'plan', label: 'Plan' },
  { value: 'team', label: 'Team' }
];

const THEME_SCHEME_OPTIONS: { value: 'light' | 'dark' | 'auto'; label: string }[] = [
  { value: 'light', label: '浅色' },
  { value: 'dark', label: '深色' },
  { value: 'auto', label: '跟随系统' }
];

const defaultChatMode = useStorage<ChatMode>('mindflow-chat-mode', 'react');

const currentThemeScheme = computed(() => themeStore.themeScheme);

const contentStats = ref([
  { key: 'documents', label: '我的文档', value: 0, path: '/knowledge-base' },
  { key: 'conversations', label: '我的会话', value: 0, path: '/chat' },
  { key: 'skills', label: '技能数', value: 0, path: '/skill' }
]);

async function loadContentStats() {
  const [docResult, convResult, skillResult] = await Promise.all([
    request<Api.KnowledgeBase.List>({ url: '/documents/accessible' }),
    request<Api.Chat.Message[]>({ url: 'users/conversation' }),
    request<{ name: string }[]>({ url: 'skill' })
  ]);
  const counts: Record<string, number> = {
    documents: Array.isArray(docResult.data) ? docResult.data.length : 0,
    conversations: Array.isArray(convResult.data) ? convResult.data.length : 0,
    skills: Array.isArray(skillResult.data) ? skillResult.data.length : 0
  };
  contentStats.value = contentStats.value.map(item => ({ ...item, value: counts[item.key] ?? 0 }));
}

function selectDefaultMode(mode: ChatMode) {
  defaultChatMode.value = mode;
}

function selectThemeScheme(scheme: 'light' | 'dark' | 'auto') {
  themeStore.setThemeScheme(scheme);
}

function gotoPage(path: string) {
  router.push(path);
}

const tags = ref<Api.OrgTag.Mine>({
  orgTags: [],
  primaryOrg: '',
  orgTagDetails: []
});

const usage = ref<Api.User.UsageSnapshot>({
  day: '',
  chatRequestCount: 0,
  llm: {
    enabled: false,
    usedTokens: 0,
    limitTokens: 0,
    remainingTokens: 0,
    requestCount: 0
  },
  embedding: {
    enabled: false,
    usedTokens: 0,
    limitTokens: 0,
    remainingTokens: 0,
    requestCount: 0
  }
});

const loading = ref(false);

// Token 记录相关变量
const tokenRecords = ref<Api.User.TokenRecord[]>([]);
const tokenRecordLoading = ref(false);
const pagination = ref({
  page: 1,
  pageSize: 10,
  total: 0,
  pageCount: 0
});
const getPersonalData = async () => {
  loading.value = true;
  const [{ error: orgError, data: orgData }, { error: usageError, data: usageData }] = await Promise.all([
    request<Api.OrgTag.Mine>({
      url: '/users/org-tags'
    }),
    request<Api.User.UsageSnapshot>({
      url: '/users/usage'
    })
  ]);

  if (!orgError) {
    tags.value = orgData;
  }

  if (!usageError) {
    usage.value = usageData;
  }

  // 获取 Token 记录
  getTokenRecords();
  loadContentStats();

  loading.value = false;
};

const getOrgTags = async () => {
  const { error, data } = await request<Api.OrgTag.Mine>({
    url: '/users/org-tags'
  });
  if (!error) {
    tags.value = data;
  }
};

onMounted(() => {
  getPersonalData();
});

const visible = ref(false);
const currentTagId = ref('');
const showModal = (tagId: string) => {
  if (tagId === tags.value.primaryOrg) return;
  visible.value = true;
  currentTagId.value = tagId;
};
const submitLoading = ref(false);
const setPrimaryOrg = async () => {
  submitLoading.value = true;
  const { error } = await request({
    url: '/users/primary-org',
    method: 'PUT',
    data: { primaryOrg: currentTagId.value, userId: userInfo.value.id }
  });
  if (!error) {
    visible.value = false;
    getOrgTags();
  }
  submitLoading.value = false;
};

// Token 记录相关方法
const getTokenRecords = async () => {
  tokenRecordLoading.value = true;
  try {
    const { error, data } = await request({
      url: '/users/token-records',
      method: 'GET',
      params: {
        page: pagination.value.page - 1,
        size: pagination.value.pageSize
      }
    });

    if (!error && data) {
      tokenRecords.value = data.content || [];
      pagination.value.total = data.totalElements || 0;
      pagination.value.pageCount = data.totalPages || 0;
    }
  } finally {
    tokenRecordLoading.value = false;
  }
};

const handlePageChange = (page: number) => {
  pagination.value.page = page;
  getTokenRecords();
};

const quotaCards = computed(() => [
  { key: 'llm', title: 'LLM Token', data: usage.value.llm },
  { key: 'embedding', title: 'Embedding Token', data: usage.value.embedding }
]);

function usagePercent(item: { usedTokens: number; limitTokens: number }) {
  if (!item?.limitTokens) return 0;
  return Math.min(100, Math.max(0, Math.round((item.usedTokens / item.limitTokens) * 100)));
}

// Token 记录表格列定义
const tokenRecordColumns = computed(() => [
  {
    title: '日期',
    key: 'recordDate',
    width: 100,
    render: (row: Api.User.TokenRecord) => row.recordDate
  },
  {
    title: 'Token 类型',
    key: 'tokenType',
    width: 100,
    render: (row: Api.User.TokenRecord) => {
      const typeMap: Record<string, { text: string; type: any }> = {
        LLM: { text: 'LLM', type: 'info' },
        EMBEDDING: { text: 'Embedding', type: 'success' }
      };
      const type = typeMap[row.tokenType] || { text: row.tokenType, type: 'default' };
      return h(NTag, { type: type.type }, () => type.text);
    }
  },
  {
    title: '变动类型',
    key: 'changeType',
    width: 100,
    render: (row: Api.User.TokenRecord) => {
      const typeMap: Record<string, { text: string; type: any }> = {
        INCREASE: { text: '充值', type: 'success' },
        CONSUME: { text: '消耗', type: 'error' }
      };
      const type = typeMap[row.changeType] || { text: row.changeType, type: 'default' };
      return h(NTag, { type: type.type, size: 'small', bordered: false }, () => type.text);
    }
  },
  {
    title: '变动数量',
    key: 'amount',
    width: 120,
    render: (row: Api.User.TokenRecord) => {
      const increase = row.changeType === 'INCREASE';
      return h(
        'span',
        {
          style: {
            color: increase ? '#18a058' : '#d03050',
            fontWeight: '600'
          }
        },
        `${increase ? '+' : '-'}${row.amount.toLocaleString()}`
      );
    }
  },
  {
    title: '变动前余额',
    key: 'balanceBefore',
    width: 120,
    render: (row: Api.User.TokenRecord) => row.balanceBefore?.toLocaleString() || '-'
  },
  {
    title: '变动后余额',
    key: 'balanceAfter',
    width: 120,
    render: (row: Api.User.TokenRecord) => row.balanceAfter?.toLocaleString() || '-'
  },
  {
    title: '原因',
    key: 'reason',
    minWidth: 100,
    ellipsis: { tooltip: true },
    render: (row: Api.User.TokenRecord) => row.reason || '-'
  },
  {
    title: '请求次数',
    key: 'requestCount',
    width: 80,
    render: (row: Api.User.TokenRecord) => row.requestCount?.toLocaleString() || '0'
  },
  {
    title: '创建时间',
    key: 'createdAt',
    width: 180,
    render: (row: Api.User.TokenRecord) => new Date(row.createdAt).toLocaleString('zh-CN')
  }
]);
</script>

<template>
  <NSpin :show="loading">
    <div class="flex-col gap-14px pb-4">
      <div class="flex items-center justify-between">
        <h2 class="m-0 text-title font-600" style="color: rgb(var(--text-1))">个人中心</h2>
        <div class="flex items-center gap-9px">
          <span class="text-body-lg font-500" style="color: rgb(var(--text-1))">{{ userInfo.username }}</span>
          <span class="user-badge"><icon-solar:user-circle-linear /></span>
        </div>
      </div>

      <div class="grid gap-14px md:grid-cols-2">
        <section v-for="quota in quotaCards" :key="quota.key" class="pc-card">
          <div class="pc-card__title">{{ quota.title }}</div>
          <template v-if="quota.data.enabled">
            <div class="pc-card__sub">今日已用</div>
            <div class="pc-card__value">{{ quota.data.usedTokens.toLocaleString() }}</div>
            <div class="quota-bar">
              <div class="quota-bar__fill" :style="{ width: `${usagePercent(quota.data)}%` }" />
            </div>
            <div class="pc-card__foot">
              <span>剩余 {{ quota.data.remainingTokens.toLocaleString() }} / 总量 {{ quota.data.limitTokens.toLocaleString() }}</span>
              <span>请求 {{ quota.data.requestCount.toLocaleString() }} 次</span>
            </div>
          </template>
          <div v-else class="pc-card__sub">当前未启用配额</div>
        </section>
      </div>

      <div class="grid gap-14px sm:grid-cols-3">
        <section
          v-for="stat in contentStats"
          :key="stat.key"
          class="pc-card pc-card--stat"
          @click="gotoPage(stat.path)"
        >
          <div class="pc-card__value">{{ stat.value.toLocaleString() }}</div>
          <div class="pc-card__sub">{{ stat.label }}</div>
        </section>
      </div>

      <div class="grid gap-14px md:grid-cols-2">
        <section
          v-for="tag in tags.orgTagDetails"
          :key="tag.tagId"
          class="pc-card pc-card--org"
          @click="showModal(tag.tagId)"
        >
          <span class="org-icon"><icon-solar:users-group-rounded-bold-duotone /></span>
          <div class="min-w-0 flex-1">
            <div class="org-title">{{ tag.name }}</div>
            <div class="org-desc">{{ tag.description || '该组织暂无说明' }}</div>
          </div>
          <span class="org-tag">{{ tag.tagId === tags.primaryOrg ? '主组织' : '组织' }}</span>
        </section>
      </div>

      <section class="pc-card">
        <div class="pc-card__title">偏好设置</div>

        <div class="pref-row">
          <span class="pref-row__label">默认会话模式</span>
          <div class="flex items-center gap-8px">
            <button
              v-for="mode in CHAT_MODE_OPTIONS"
              :key="mode.value"
              type="button"
              class="pref-pill"
              :class="defaultChatMode === mode.value ? 'gradient-action text-white font-600' : 'pref-pill--idle'"
              @click="selectDefaultMode(mode.value)"
            >
              {{ mode.label }}
            </button>
          </div>
        </div>

        <div class="pref-row">
          <span class="pref-row__label">默认组织标签</span>
          <div class="flex items-center gap-8px">
            <button
              v-for="tag in tags.orgTagDetails"
              :key="tag.tagId"
              type="button"
              class="pref-pill"
              :class="tag.tagId === tags.primaryOrg ? 'gradient-action text-white font-600' : 'pref-pill--idle'"
              @click="showModal(tag.tagId)"
            >
              {{ tag.name }}
            </button>
          </div>
        </div>

        <div class="pref-row">
          <span class="pref-row__label">主题外观</span>
          <div class="flex items-center gap-8px">
            <button
              v-for="scheme in THEME_SCHEME_OPTIONS"
              :key="scheme.value"
              type="button"
              class="pref-pill"
              :class="currentThemeScheme === scheme.value ? 'gradient-action text-white font-600' : 'pref-pill--idle'"
              @click="selectThemeScheme(scheme.value)"
            >
              {{ scheme.label }}
            </button>
          </div>
        </div>
      </section>

      <section class="pc-table-card">
        <div class="pc-table-card__title">Token 变动记录</div>
        <NSpin :show="tokenRecordLoading">
          <NDataTable
            v-if="tokenRecords.length > 0"
            :columns="tokenRecordColumns"
            :data="tokenRecords"
            :loading="tokenRecordLoading"
            :pagination="{
              page: pagination.page,
              pageSize: pagination.pageSize,
              itemCount: pagination.total,
              onChange: handlePageChange
            }"
            :scroll-x="1200"
            size="small"
          />
          <NEmpty v-else description="暂无 Token 变动记录" class="py-40px" />
        </NSpin>
      </section>
    </div>

      <NModal
        v-model:show="visible"
        :loading="submitLoading"
        preset="dialog"
        title="设置主标签"
        content="确定将当前标签设置为主标签吗？"
        positive-text="确认"
        negative-text="取消"
        @positive-click="setPrimaryOrg"
        @negative-click="visible = false"
      />
  </NSpin>
</template>

<style scoped lang="scss">
.pc-card {
  border: 1px solid rgb(15 23 42 / 0.06);
  border-radius: 16px;
  background: #fff;
  box-shadow:
    0 1px 3px rgb(16 24 40 / 0.06),
    0 10px 26px -18px rgb(16 24 40 / 0.22);
  padding: 18px 20px;
}

.pc-card__title {
  font-size: 15px;
  font-weight: 600;
  color: rgb(var(--text-1));
}

.pc-card__sub {
  margin-top: 14px;
  font-size: 12px;
  color: rgb(var(--text-3));
}

.pc-card__value {
  font-size: 34px;
  font-weight: 700;
  line-height: 1.15;
  color: rgb(var(--text-1));
}

.quota-bar {
  margin-top: 12px;
  height: 8px;
  border-radius: 999px;
  background: rgb(15 23 42 / 0.06);
  overflow: hidden;
}

.quota-bar__fill {
  height: 100%;
  border-radius: 999px;
  background: var(--brand-grad);
  transition: width 0.4s ease;
}

.pc-card__foot {
  margin-top: 11px;
  display: flex;
  justify-content: space-between;
  gap: 12px;
  font-size: 12px;
  color: rgb(var(--text-3));
}

.pc-card--org {
  display: flex;
  align-items: center;
  gap: 12px;
  cursor: pointer;
  transition:
    transform 0.18s ease,
    box-shadow 0.18s ease;
}

.pc-card--org:hover {
  transform: translateY(-1px);
  box-shadow:
    0 1px 3px rgb(16 24 40 / 0.06),
    0 14px 28px -16px rgb(16 24 40 / 0.3);
}

.org-icon {
  display: inline-flex;
  height: 36px;
  width: 36px;
  flex-shrink: 0;
  align-items: center;
  justify-content: center;
  border: 1px solid rgb(var(--primary-color) / 0.45);
  border-radius: 11px;
  color: rgb(var(--primary-color));
  font-size: 18px;
}

.org-title {
  font-size: 14.5px;
  font-weight: 600;
  color: rgb(var(--text-1));
}

.org-desc {
  margin-top: 3px;
  overflow: hidden;
  font-size: 12.5px;
  color: rgb(var(--text-3));
  text-overflow: ellipsis;
  white-space: nowrap;
}

.org-tag {
  flex-shrink: 0;
  border-radius: 999px;
  background: rgb(15 23 42 / 0.05);
  padding: 3px 10px;
  font-size: 11px;
  color: rgb(var(--text-3));
}

.pc-table-card {
  border: 1px solid rgb(15 23 42 / 0.06);
  border-radius: 16px;
  background: #fff;
  box-shadow:
    0 1px 3px rgb(16 24 40 / 0.06),
    0 10px 26px -18px rgb(16 24 40 / 0.22);
  padding: 16px 18px 8px;
}

.pc-table-card__title {
  margin-bottom: 12px;
  font-size: 15px;
  font-weight: 600;
  color: rgb(var(--text-1));
}

.pc-table-card :deep(.n-data-table-th) {
  font-size: 12px;
  font-weight: 500;
  color: rgb(var(--text-3));
}

.pc-table-card :deep(.n-data-table-tr:hover .n-data-table-td) {
  background: #f7f8fa;
}

.user-badge {
  display: inline-flex;
  height: 34px;
  width: 34px;
  align-items: center;
  justify-content: center;
  border-radius: 999px;
  background: var(--brand-grad);
  color: #fff;
  font-size: 18px;
}

.pc-card--stat {
  cursor: pointer;
  transition:
    transform 0.18s ease,
    box-shadow 0.18s ease;
}

.pc-card--stat:hover {
  transform: translateY(-1px);
  box-shadow:
    0 1px 3px rgb(16 24 40 / 0.06),
    0 14px 28px -16px rgb(16 24 40 / 0.3);
}

.pref-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  border-top: 1px solid rgb(15 23 42 / 0.06);
  padding: 12px 0;
}

.pref-row:first-of-type {
  margin-top: 14px;
}

.pref-row:last-of-type {
  padding-bottom: 2px;
}

.pref-row__label {
  flex-shrink: 0;
  font-size: 13px;
  color: rgb(var(--text-2));
}

.pref-pill {
  border-radius: 999px;
  padding: 6px 14px;
  font-size: 13px;
  transition: all 0.16s ease;
}

.pref-pill--idle {
  border: 1px solid rgb(var(--primary-color) / 0.4);
  color: rgb(var(--primary-color));
}

.pref-pill--idle:hover {
  background: rgb(var(--primary-color) / 0.06);
}
</style>
