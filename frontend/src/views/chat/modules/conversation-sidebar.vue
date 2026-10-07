<script setup lang="ts">
defineOptions({
  name: 'ConversationSidebar'
});

const collapsed = defineModel<boolean>('collapsed', { default: false });

const chatStore = useChatStore();
const { conversationId, sessionsLoading, filteredSessions, activeTab } = storeToRefs(chatStore);

const keyword = ref('');

const visibleSessions = computed(() => {
  const kw = keyword.value.trim().toLowerCase();
  if (!kw) return filteredSessions.value;
  return filteredSessions.value.filter(session => (session.title || '').toLowerCase().includes(kw));
});

onMounted(() => {
  chatStore.loadSessions();
});

function handleCollapse() {
  collapsed.value = true;
}

function handleNewChat() {
  chatStore.createNewSession();
}

function handleSelect(cid: string) {
  chatStore.switchSession(cid);
}

function handleArchive(cid: string) {
  chatStore.archiveSession(cid);
}

function handleUnarchive(cid: string) {
  chatStore.unarchiveSession(cid);
}

function setActiveTab(tab: 'active' | 'archived') {
  activeTab.value = tab;
}

function formatDate(dateStr?: string) {
  if (!dateStr) return '';
  const date = dayjs(dateStr);
  const now = dayjs();
  if (date.isSame(now, 'day')) {
    return date.format('HH:mm');
  }
  if (date.isSame(now, 'week')) {
    return date.format('ddd');
  }
  if (date.isSame(now, 'year')) {
    return date.format('MM-DD');
  }
  return date.format('YYYY-MM-DD');
}
</script>

<template>
  <div
    class="relative h-full shrink-0 flex flex-col overflow-hidden border-r border-#e0f2fe bg-gradient-to-b from-#f8fafc to-#f1f5f9 transition-[width] duration-200 ease-out dark:border-#334155 dark:from-[#1e293b] dark:to-[#0f172a]"
    :class="collapsed ? 'w-0 min-w-0 border-r-0' : 'w-[276px] min-w-[276px]'"
  >
    <div class="flex w-[276px] flex-1 flex-col overflow-hidden" :class="{ 'pointer-events-none invisible': collapsed }">
    <div class="flex items-center gap-2 px-3 pt-3 pb-2.5">
      <div class="h-9 min-w-0 flex flex-1 items-center gap-2 rounded-full border border-[rgb(15_23_42_/_0.1)] bg-[rgb(15_23_42_/_0.025)] px-3.5">
        <icon-material-symbols:search-rounded class="shrink-0 text-16px color-#9aa0aa" />
        <input
          v-model="keyword"
          placeholder="搜索会话"
          class="min-w-0 w-full border-none bg-transparent text-body outline-none placeholder:text-#b3b8c2"
          style="color: rgb(var(--text-1))"
        />
      </div>
      <button
        type="button"
        class="gradient-action h-9 shrink-0 rounded-full px-3.5 text-body font-600 text-white"
        @click="handleNewChat"
      >
        新对话
      </button>
      <NButton text size="tiny" @click="handleCollapse">
        <template #icon>
          <icon-material-symbols:left-panel-close-outline-rounded />
        </template>
      </NButton>
    </div>

    <div class="mx-3 mb-2.5 flex items-center gap-3">
      <button
        v-for="tab in ([{ key: 'active', label: '活跃' }, { key: 'archived', label: '已归档' }] as const)"
        :key="tab.key"
        type="button"
        class="text-body transition-colors"
        :class="activeTab === tab.key ? 'font-600' : 'color-#9aa0aa hover:color-#6b7280'"
        :style="activeTab === tab.key ? 'color: rgb(var(--primary-color))' : ''"
        @click="setActiveTab(tab.key)"
      >
        {{ tab.label }}
      </button>
    </div>

    <!-- List -->
    <div class="flex-1 overflow-y-auto px-2">
      <NSpin :show="sessionsLoading" class="h-full">
        <TransitionGroup name="session-list" tag="div">
          <div
            v-if="visibleSessions.length === 0 && !sessionsLoading"
            class="flex flex-col items-center justify-center gap-3 py-16"
          >
            <icon-material-symbols:chat-outline-rounded class="text-40px color-#d4d4d8 dark:color-#444" />
            <span class="text-body color-#a3a3a3 dark:color-#7a7a7a">{{ activeTab === 'active' ? '暂无对话记录' : '暂无归档对话' }}</span>
          </div>

          <div
            v-for="session in visibleSessions"
            :key="session.conversationId"
            class="conv-card group relative mx-3 mb-2 flex min-h-48px cursor-pointer items-center gap-2 overflow-hidden rounded-xl px-3.5 py-2.5 transition-all"
            :class="{ 'conv-card--active': session.conversationId === conversationId }"
            @click="handleSelect(session.conversationId)"
          >
            <span
              v-if="session.conversationId === conversationId"
              class="absolute left-0 top-0 h-full w-[4px] bg-[rgb(var(--primary-color))]"
            />
            <div class="min-w-0 flex flex-1 flex-col">
              <div class="truncate text-body-lg font-500 leading-[1.4]" style="color: rgb(var(--text-1))">
                {{ session.title }}
              </div>
              <div class="mt-1 text-body-sm" style="color: rgb(var(--text-3))">{{ formatDate(session.updatedAt) }}</div>
            </div>

            <!-- Action button -->
            <NPopconfirm v-if="activeTab === 'active'" @positive-click="handleArchive(session.conversationId)">
              <template #trigger>
                <NButton
                  class="shrink-0 transition-opacity"
                  :class="session.conversationId === conversationId ? '' : 'opacity-0 group-hover:opacity-100'"
                  text
                  size="tiny"
                  @click.stop
                >
                  <template #icon>
                    <icon-material-symbols:archive-outline-rounded class="text-15px color-#999 hover:color-#666" />
                  </template>
                </NButton>
              </template>
              归档后可在「已归档」中找回
            </NPopconfirm>
            <NButton
              v-else
              class="shrink-0 opacity-0 transition-opacity group-hover:opacity-100"
              text
              size="tiny"
              @click.stop="handleUnarchive(session.conversationId)"
            >
              <template #icon>
                <icon-material-symbols:unarchive-outline-rounded class="text-15px color-#999 hover:color-#666" />
              </template>
            </NButton>
          </div>
        </TransitionGroup>
      </NSpin>
    </div>
    </div>

  </div>
</template>

<style scoped>
.conv-card {
  border: 1px solid rgb(15 23 42 / 0.06);
  background: #fff;
  box-shadow: 0 1px 2px rgb(16 24 40 / 0.04);
}

.conv-card:hover {
  background: #f3f4f6;
}

.conv-card--active {
  border-color: transparent;
  background: #eef2ff;
  box-shadow: none;
}

.session-list-enter-active,
.session-list-leave-active {
  transition: all 0.2s ease;
}
.session-list-enter-from,
.session-list-leave-to {
  opacity: 0;
  transform: translateX(-8px);
}
</style>
