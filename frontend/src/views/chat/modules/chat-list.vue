<script setup lang="ts">
import { NScrollbar } from 'naive-ui';
import { VueMarkdownItProvider } from '@/vendor/vue-markdown-shiki';
import ChatMessage from './chat-message.vue';

defineOptions({
  name: 'ChatList'
});

const chatStore = useChatStore();
const { list, sessionId, conversationId } = storeToRefs(chatStore);

const loading = ref(false);
const scrollbarRef = ref<InstanceType<typeof NScrollbar>>();

watch(() => [...list.value], scrollToBottom);

function scrollToBottom() {
  setTimeout(() => {
    scrollbarRef.value?.scrollBy({
      top: 999999999999999,
      behavior: 'auto'
    });
  }, 100);
}

const range = ref<[number, number] | null>(null);

function getRetrievalQueryFallback(index: number) {
  for (let i = index - 1; i >= 0; i -= 1) {
    const candidate = list.value[i];
    if (candidate?.role === 'user') {
      return candidate.content || '';
    }
  }
  return '';
}

const params = computed(() => {
  const p: Record<string, string> = {};
  if (range.value) {
    p.start_date = dayjs(range.value[0]).format('YYYY-MM-DD');
    p.end_date = dayjs(range.value[1]).format('YYYY-MM-DD');
  }
  if (conversationId.value) {
    p.conversationId = conversationId.value;
  }
  return p;
});

watchEffect(() => {
  getList();
});

async function getList() {
  loading.value = true;
  const { error, data } = await request<Api.Chat.Message[]>({
    url: 'users/conversation',
    params: params.value
  });
  if (!error) {
    list.value = data;
  }
  loading.value = false;
}

onMounted(() => {
  chatStore.scrollToBottom = scrollToBottom;
});

const showEmpty = computed(() => !loading.value && list.value.length === 0);
</script>

<template>
  <Suspense>
    <div class="flex h-0 flex-1 flex-col">
      <!-- Date filter in header -->
      <Teleport defer to="#header-extra">
        <div v-if="!showEmpty" class="flex items-center px-4">
          <NForm :model="params" label-placement="left" :show-feedback="false" inline>
            <NFormItem label="时间" size="small">
              <NDatePicker v-model:value="range" type="daterange" clearable size="small" />
            </NFormItem>
          </NForm>
        </div>
      </Teleport>

      <!-- Greeting + gradient divider -->
      <div class="shrink-0 px-6 pt-5 pb-3">
        <div class="mx-auto max-w-[860px] w-full">
          <h2 class="m-0 text-center text-title font-600" style="color: rgb(var(--text-1))">今天想探索什么？</h2>
          <div class="brand-divider mt-3.5" />
        </div>
      </div>

      <!-- Empty state -->
      <div v-if="showEmpty" class="flex flex-1 flex-col items-center justify-center gap-4 pb-16">
        <div class="empty-illustration">
          <icon-material-symbols:chat-outline-rounded class="text-34px text-white" />
        </div>
        <div class="text-title-sm font-600" style="color: rgb(var(--text-1))">
          {{ conversationId ? '开始新对话' : '选择或创建一个对话' }}
        </div>
        <div class="text-body" style="color: rgb(var(--text-3))">
          在左侧选择一个对话，或点击「新对话」开始
        </div>
      </div>

      <!-- Message list -->
      <NScrollbar v-else ref="scrollbarRef" class="flex-1">
        <NSpin :show="loading">
          <div class="mx-auto w-full max-w-[860px] px-6 pb-4">
            <VueMarkdownItProvider>
              <ChatMessage
                v-for="(item, index) in list"
                :key="index"
                :msg="item"
                :session-id="sessionId"
                :retrieval-query-fallback="getRetrievalQueryFallback(index)"
              />
            </VueMarkdownItProvider>
          </div>
        </NSpin>
      </NScrollbar>
    </div>
  </Suspense>
</template>

<style scoped>
.brand-divider {
  height: 2px;
  border-radius: 999px;
  background: linear-gradient(90deg, rgba(56, 72, 216, 0) 0%, rgba(56, 72, 216, 0.55) 50%, rgba(122, 72, 232, 0) 100%);
}

.empty-illustration {
  display: flex;
  height: 76px;
  width: 76px;
  align-items: center;
  justify-content: center;
  border-radius: 24px;
  background: var(--brand-grad);
  box-shadow: 0 14px 30px -12px rgba(76, 62, 226, 0.65);
}

.chat-message-item {
  animation: chat-fade-in 0.28s ease both;
}

@keyframes chat-fade-in {
  from {
    opacity: 0;
    transform: translateY(6px);
  }
  to {
    opacity: 1;
    transform: translateY(0);
  }
}
</style>
