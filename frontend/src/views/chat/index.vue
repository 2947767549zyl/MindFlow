<script setup lang="ts">
import { computed, ref } from 'vue';
import { useRoute } from 'vue-router';
import ChatList from './modules/chat-list.vue';
import InputBox from './modules/input-box.vue';
import ConversationSidebar from './modules/conversation-sidebar.vue';
import ReferencePreviewPage from './modules/reference-preview-page.vue';

const route = useRoute();
const showReferencePreview = computed(() => route.query.preview === 'reference');
const sidebarCollapsed = ref(false);
</script>

<template>
  <div v-if="showReferencePreview" class="h-full">
    <ReferencePreviewPage />
  </div>
  <div v-else class="h-full min-h-0 flex">
    <ConversationSidebar v-model:collapsed="sidebarCollapsed" />
    <div class="relative min-w-0 flex flex-col flex-1">
      <button
        v-show="sidebarCollapsed"
        class="absolute left-3 top-3 z-20 h-9 w-9 inline-flex items-center justify-center rounded-xl bg-white text-#666 shadow-[0_1px_2px_rgba(15,23,42,0.06),0_4px_12px_rgba(15,23,42,0.08)] ring-1 ring-#0f172a14 transition-all duration-150 active:scale-95 hover:scale-105 dark:bg-#262626 dark:text-#bbb hover:text-[rgb(var(--primary-color))]"
        aria-label="展开对话列表"
        @click="sidebarCollapsed = false"
      >
        <icon-material-symbols:left-panel-open-outline-rounded class="text-18px" />
      </button>
      <ChatList />
      <InputBox />
    </div>
  </div>
</template>

<style scoped></style>
