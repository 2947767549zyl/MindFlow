<script setup lang="ts">
defineOptions({ name: 'Experience' });

interface ExperienceItem {
  toolName: string;
  failureCategory: string;
  failureLabel: string;
  occurrenceCount: number;
  recoveryHint: string;
  lastErrorText: string;
  firstSeenAt: string | null;
  lastSeenAt: string | null;
}

const loading = ref(false);
const items = ref<ExperienceItem[]>([]);
const summary = ref({ experienceCount: 0, toolCount: 0, totalOccurrences: 0 });
const viewMode = ref<'card' | 'timeline'>('card');

const CATEGORY_ACCENT: Record<string, string> = {
  MISSING_ARG: '--mod-generate',
  NOT_FOUND: '--mod-search',
  RATE_LIMIT: '--mod-mcp',
  TIMEOUT: '--mod-file',
  EMPTY_RESULT: '--mod-other',
  PERMISSION_DENIED: '--mod-danger',
  UNKNOWN: '--mod-memory'
};

async function loadExperiences() {
  loading.value = true;
  const { error, data } = await request<{
    items: ExperienceItem[];
    experienceCount: number;
    toolCount: number;
    totalOccurrences: number;
  }>({ url: 'agent/experience' });
  loading.value = false;
  if (error || !data) return;
  items.value = data.items || [];
  summary.value = {
    experienceCount: data.experienceCount || 0,
    toolCount: data.toolCount || 0,
    totalOccurrences: data.totalOccurrences || 0
  };
}

function accentVar(category: string) {
  return CATEGORY_ACCENT[category] || '--mod-other';
}

function accentColor(category: string) {
  return `rgb(var(${accentVar(category)}))`;
}

function accentStyle(category: string) {
  return {
    '--accent': accentColor(category),
    '--accent-soft': `rgb(var(${accentVar(category)}) / 0.1)`
  } as Record<string, string>;
}

const categoryStats = computed(() => {
  const total = items.value.reduce((sum, item) => sum + item.occurrenceCount, 0);
  const map = new Map<string, { category: string; label: string; total: number }>();
  for (const item of items.value) {
    const entry = map.get(item.failureCategory) || {
      category: item.failureCategory,
      label: item.failureLabel,
      total: 0
    };
    entry.total += item.occurrenceCount;
    map.set(item.failureCategory, entry);
  }
  return [...map.values()]
    .map(entry => ({ ...entry, percent: total > 0 ? Math.round((entry.total / total) * 100) : 0 }))
    .sort((a, b) => b.total - a.total);
});

const topTools = computed(() => {
  const map = new Map<string, number>();
  for (const item of items.value) {
    map.set(item.toolName, (map.get(item.toolName) || 0) + item.occurrenceCount);
  }
  const ranked = [...map.entries()]
    .map(([toolName, total]) => ({ toolName, total }))
    .sort((a, b) => b.total - a.total)
    .slice(0, 3);
  const max = ranked[0]?.total || 1;
  return ranked.map(entry => ({ ...entry, percent: Math.round((entry.total / max) * 100) }));
});

const timelineItems = computed(() =>
  [...items.value].sort((a, b) => String(b.lastSeenAt || '').localeCompare(String(a.lastSeenAt || '')))
);

function formatTime(value: string | null) {
  if (!value) return '-';
  return value.replace('T', ' ').slice(0, 16);
}

onMounted(() => {
  loadExperiences();
});
</script>

<template>
  <div class="h-full flex-col gap-14px p-16px">
    <div class="flex items-end justify-between">
      <div class="flex-col gap-2px">
        <h2 class="m-0 text-title font-600" style="color: rgb(var(--text-1))">经验记忆库</h2>
        <span class="text-body-sm" style="color: rgb(var(--text-3))">
          Agent 自己踩过的坑：工具失败一次即归因归档，同类错误下次直接避开
        </span>
      </div>
      <NButton size="small" secondary @click="loadExperiences">刷新</NButton>
    </div>

    <div class="grid grid-cols-3 gap-14px">
      <div class="kpi-card" :style="accentStyle('TIMEOUT')">
        <span class="kpi-card__chip"><icon-material-symbols:psychology /></span>
        <div class="flex-col gap-2px">
          <span class="kpi-card__value">{{ summary.experienceCount }}</span>
          <span class="kpi-card__label">失败经验条数</span>
        </div>
      </div>
      <div class="kpi-card" :style="accentStyle('NOT_FOUND')">
        <span class="kpi-card__chip"><icon-material-symbols:widgets /></span>
        <div class="flex-col gap-2px">
          <span class="kpi-card__value">{{ summary.toolCount }}</span>
          <span class="kpi-card__label">涉及工具数</span>
        </div>
      </div>
      <div class="kpi-card" :style="accentStyle('RATE_LIMIT')">
        <span class="kpi-card__chip"><icon-material-symbols:manage-search /></span>
        <div class="flex-col gap-2px">
          <span class="kpi-card__value">{{ summary.totalOccurrences }}</span>
          <span class="kpi-card__label">累计失败次数</span>
        </div>
      </div>
    </div>

    <NSpin :show="loading" class="min-h-0 flex-1">
      <div v-if="items.length === 0 && !loading" class="empty-state">
        <div class="empty-illustration"><icon-material-symbols:psychology class="text-34px text-white" /></div>
        <span class="text-title-sm font-600" style="color: rgb(var(--text-1))">还没有积累经验</span>
        <span class="text-body" style="color: rgb(var(--text-3))">
          当工具调用失败（参数错 / 目标不存在 / 限流 / 超时 / 空结果 / 被权限拦截）时，系统会自动归因、归档恢复策略，并在后续对话中注入提醒
        </span>
      </div>

      <div v-else class="flex-col gap-14px">
        <section class="panel">
          <div class="panel__title">失败类别分布</div>
          <div class="dist-bar">
            <div
              v-for="stat in categoryStats"
              :key="stat.category"
              class="dist-bar__seg"
              :style="{ width: `${Math.max(stat.percent, 2)}%`, background: accentColor(stat.category) }"
              :title="`${stat.label}：${stat.total} 次（${stat.percent}%）`"
            />
          </div>
          <div class="dist-legend">
            <span v-for="stat in categoryStats" :key="stat.category" class="dist-legend__item">
              <i class="dist-legend__dot" :style="{ background: accentColor(stat.category) }" />
              <span class="dist-legend__label">{{ stat.label }}</span>
              <span class="dist-legend__value">{{ stat.total }} 次 · {{ stat.percent }}%</span>
            </span>
          </div>
        </section>

        <section class="panel">
          <div class="panel__title">最容易失败的工具 Top 3</div>
          <div class="grid gap-12px md:grid-cols-3">
            <div v-for="(tool, index) in topTools" :key="tool.toolName" class="top-tool" :style="accentStyle('PERMISSION_DENIED')">
              <span class="top-tool__rank">{{ index + 1 }}</span>
              <div class="min-w-0 flex-1">
                <code class="top-tool__name">{{ tool.toolName }}</code>
                <div class="top-tool__track"><div class="top-tool__fill" :style="{ width: `${tool.percent}%` }" /></div>
              </div>
              <span class="top-tool__total">{{ tool.total }}</span>
            </div>
          </div>
        </section>

        <div class="flex items-center gap-8px">
          <button
            type="button"
            class="view-pill"
            :class="viewMode === 'card' ? 'gradient-action font-600 text-white' : 'view-pill--idle'"
            @click="viewMode = 'card'"
          >
            卡片视图
          </button>
          <button
            type="button"
            class="view-pill"
            :class="viewMode === 'timeline' ? 'gradient-action font-600 text-white' : 'view-pill--idle'"
            @click="viewMode = 'timeline'"
          >
            时间线视图
          </button>
          <span class="text-body-sm" style="color: rgb(var(--text-3))">
            共 {{ items.length }} 条经验 · 累计 {{ summary.totalOccurrences }} 次失败
          </span>
        </div>

        <div v-if="viewMode === 'card'" class="grid gap-14px xl:grid-cols-2">
          <article
            v-for="item in items"
            :key="item.toolName + item.failureCategory"
            class="exp-card"
            :style="accentStyle(item.failureCategory)"
          >
            <div class="exp-card__head">
              <span class="exp-card__icon"><icon-material-symbols:error-rounded /></span>
              <code class="exp-card__tool">{{ item.toolName }}</code>
              <span class="exp-card__badge">{{ item.failureLabel }}</span>
              <span class="exp-card__count">已出现 {{ item.occurrenceCount }} 次</span>
            </div>
            <div class="exp-card__hint">
              <span class="exp-card__hint-label">恢复策略</span>
              <span class="exp-card__hint-text">{{ item.recoveryHint || '暂无策略' }}</span>
            </div>
            <div v-if="item.lastErrorText" class="exp-card__error" :title="item.lastErrorText">
              {{ item.lastErrorText }}
            </div>
            <div class="exp-card__foot">
              <span>最近一次：{{ formatTime(item.lastSeenAt) }}</span>
              <span>{{ item.failureCategory }}</span>
            </div>
          </article>
        </div>

        <section v-else class="panel timeline-panel">
          <div v-for="item in timelineItems" :key="item.toolName + item.failureCategory" class="timeline-item">
            <span class="timeline-item__dot" :style="{ background: accentColor(item.failureCategory) }" />
            <div class="timeline-item__body">
              <div class="timeline-item__head">
                <code class="timeline-item__tool" :style="{ color: accentColor(item.failureCategory) }">
                  {{ item.toolName }}
                </code>
                <span class="timeline-item__badge" :style="accentStyle(item.failureCategory)">
                  {{ item.failureLabel }}
                </span>
                <span class="timeline-item__recur">复发 {{ item.occurrenceCount }} 次</span>
              </div>
              <div class="timeline-item__range">
                首次 {{ formatTime(item.firstSeenAt) }}
                <span class="timeline-item__arrow">→</span>
                最近 {{ formatTime(item.lastSeenAt) }}
              </div>
              <div class="timeline-item__hint">{{ item.recoveryHint }}</div>
            </div>
          </div>
        </section>
      </div>
    </NSpin>
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

.panel {
  border-radius: 16px;
  background: rgb(var(--container-bg-color));
  box-shadow: var(--shadow-card);
  padding: 15px 17px 16px;
}

.panel__title {
  margin-bottom: 12px;
  font-size: 14.5px;
  font-weight: 600;
  color: rgb(var(--text-1));
}

.dist-bar {
  display: flex;
  height: 14px;
  border-radius: 999px;
  overflow: hidden;
}

.dist-bar__seg {
  height: 100%;
}

.dist-bar__seg + .dist-bar__seg {
  margin-left: 2px;
}

.dist-legend {
  display: flex;
  flex-wrap: wrap;
  gap: 8px 18px;
  margin-top: 12px;
}

.dist-legend__item {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
}

.dist-legend__dot {
  height: 8px;
  width: 8px;
  border-radius: 999px;
}

.dist-legend__label {
  color: rgb(var(--text-1));
}

.dist-legend__value {
  color: rgb(var(--text-3));
}

.top-tool {
  display: flex;
  align-items: center;
  gap: 11px;
  border: 1px solid rgb(15 23 42 / 0.06);
  border-radius: 12px;
  background: rgb(15 23 42 / 0.02);
  padding: 11px 13px;
}

.top-tool__rank {
  display: inline-flex;
  height: 22px;
  width: 22px;
  flex-shrink: 0;
  align-items: center;
  justify-content: center;
  border-radius: 7px;
  background: var(--accent-soft);
  color: var(--accent);
  font-size: 12px;
  font-weight: 700;
}

.top-tool__name {
  display: block;
  overflow: hidden;
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 12.5px;
  font-weight: 600;
  color: rgb(var(--text-1));
  text-overflow: ellipsis;
  white-space: nowrap;
}

.top-tool__track {
  height: 5px;
  margin-top: 7px;
  border-radius: 999px;
  background: rgb(15 23 42 / 0.07);
  overflow: hidden;
}

.top-tool__fill {
  height: 100%;
  border-radius: 999px;
  background: var(--accent);
}

.top-tool__total {
  flex-shrink: 0;
  font-size: 17px;
  font-weight: 700;
  color: var(--accent);
}

.view-pill {
  border-radius: 999px;
  padding: 6px 14px;
  font-size: 13px;
  transition: all 0.16s ease;
}

.view-pill--idle {
  border: 1px solid rgb(var(--primary-color) / 0.4);
  color: rgb(var(--primary-color));
}

.view-pill--idle:hover {
  background: rgb(var(--primary-color) / 0.06);
}

.exp-card {
  position: relative;
  display: flex;
  flex-direction: column;
  border: 1px solid rgb(15 23 42 / 0.07);
  border-radius: 14px;
  background: rgb(var(--container-bg-color));
  box-shadow: var(--shadow-card);
  padding: 13px 15px 12px 18px;
  overflow: hidden;
  transition:
    transform 0.16s ease,
    box-shadow 0.16s ease,
    border-color 0.16s ease;
}

.exp-card::before {
  content: '';
  position: absolute;
  left: 0;
  top: 10px;
  bottom: 10px;
  width: 3px;
  border-radius: 0 3px 3px 0;
  background: var(--accent);
}

.exp-card:hover {
  border-color: var(--accent);
  transform: translateY(-2px);
  box-shadow: var(--shadow-pop);
}

.exp-card__head {
  display: flex;
  align-items: center;
  gap: 8px;
}

.exp-card__icon {
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

.exp-card__tool {
  overflow: hidden;
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 13px;
  font-weight: 600;
  color: var(--accent);
  text-overflow: ellipsis;
  white-space: nowrap;
}

.exp-card__badge {
  flex-shrink: 0;
  border-radius: 999px;
  background: var(--accent-soft);
  color: var(--accent);
  padding: 1px 8px;
  font-size: 11px;
  font-weight: 600;
}

.exp-card__count {
  margin-left: auto;
  flex-shrink: 0;
  border-radius: 999px;
  background: rgb(15 23 42 / 0.05);
  padding: 1px 8px;
  font-size: 10.5px;
  color: rgb(var(--text-3));
}

.exp-card__hint {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  margin-top: 10px;
  border-left: 2px solid var(--accent);
  border-radius: 0 8px 8px 0;
  background: var(--accent-soft);
  padding: 7px 10px;
}

.exp-card__hint-label {
  flex-shrink: 0;
  font-size: 11px;
  font-weight: 600;
  color: var(--accent);
}

.exp-card__hint-text {
  font-size: 12.5px;
  line-height: 1.6;
  color: rgb(var(--text-1));
}

.exp-card__error {
  display: -webkit-box;
  margin-top: 9px;
  overflow: hidden;
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 11.5px;
  line-height: 1.55;
  color: rgb(var(--text-3));
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
}

.exp-card__foot {
  display: flex;
  justify-content: space-between;
  margin-top: 9px;
  padding-top: 8px;
  border-top: 1px solid rgb(15 23 42 / 0.06);
  font-size: 11px;
  color: rgb(var(--text-3));
}

.timeline-panel {
  padding: 18px 20px 6px;
}

.timeline-item {
  position: relative;
  display: flex;
  gap: 14px;
  padding-bottom: 20px;
}

.timeline-item:not(:last-child)::before {
  content: '';
  position: absolute;
  left: 5px;
  top: 16px;
  bottom: 0;
  width: 1px;
  background: rgb(15 23 42 / 0.1);
}

.timeline-item__dot {
  margin-top: 5px;
  height: 11px;
  width: 11px;
  flex-shrink: 0;
  border-radius: 999px;
  box-shadow: 0 0 0 3px rgb(var(--container-bg-color));
}

.timeline-item__body {
  min-width: 0;
  flex: 1;
}

.timeline-item__head {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
}

.timeline-item__tool {
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 13px;
  font-weight: 600;
}

.timeline-item__badge {
  border-radius: 999px;
  background: var(--accent-soft);
  color: var(--accent);
  padding: 1px 8px;
  font-size: 11px;
  font-weight: 600;
}

.timeline-item__recur {
  border-radius: 999px;
  background: rgb(15 23 42 / 0.05);
  padding: 1px 8px;
  font-size: 10.5px;
  color: rgb(var(--text-3));
}

.timeline-item__range {
  margin-top: 5px;
  font-size: 12px;
  color: rgb(var(--text-2));
}

.timeline-item__arrow {
  margin: 0 6px;
  color: rgb(var(--text-3));
}

.timeline-item__hint {
  margin-top: 4px;
  font-size: 12px;
  line-height: 1.6;
  color: rgb(var(--text-3));
}

.empty-state {
  display: flex;
  min-height: 320px;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 10px;
  border-radius: 16px;
  background: rgb(var(--container-bg-color));
  box-shadow: var(--shadow-card);
  padding: 48px 24px;
  text-align: center;
}

.empty-illustration {
  display: flex;
  height: 76px;
  width: 76px;
  align-items: center;
  justify-content: center;
  border-radius: 24px;
  background: var(--brand-grad);
  box-shadow: 0 14px 30px -12px rgb(76, 62, 226, 0.65);
}
</style>
