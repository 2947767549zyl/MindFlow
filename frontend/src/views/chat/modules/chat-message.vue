<script setup lang="ts">
// eslint-disable-next-line @typescript-eslint/no-unused-vars
import { h, nextTick } from 'vue';
import { router } from '@/router';
import { request } from '@/service/request';
import { formatDate } from '@/utils/common';
import { VueMarkdownIt } from '@/vendor/vue-markdown-shiki';
defineOptions({ name: 'ChatMessage' });

const props = defineProps<{
  msg: Api.Chat.Message,
  sessionId?: string,
  retrievalQueryFallback?: string
}>();

const authStore = useAuthStore();

function handleCopy(content: string) {
  navigator.clipboard.writeText(content);
  window.$message?.success('已复制');
}

const chatStore = useChatStore();
const feedbackSubmitting = ref<Record<string, boolean>>({});

function getMessageFeedbackKey(message: Api.Chat.Message) {
  return message.generationId || `${message.conversationId || 'unknown'}:${message.timestamp || ''}`;
}

async function handleFeedback(message: Api.Chat.Message, rating: 'good' | 'bad') {
  if (message.role !== 'assistant') {
    return;
  }

  const key = getMessageFeedbackKey(message);
  if (feedbackSubmitting.value[key]) {
    return;
  }

  feedbackSubmitting.value = {
    ...feedbackSubmitting.value,
    [key]: true
  };

  const { error } = await request({
    url: 'chat/feedback',
    method: 'POST',
    data: {
      rating,
      reason: rating === 'good' ? '用户点击点赞，表示认可本次回答' : '用户点击点踩，表示不满意本次回答',
      conversationId: message.conversationId || props.sessionId,
      generationId: message.generationId,
      answerExcerpt: (message.content || '').slice(0, 300)
    }
  });

  feedbackSubmitting.value = {
    ...feedbackSubmitting.value,
    [key]: false
  };

  if (error) {
    window.$message?.error('反馈记录失败');
    return;
  }

  message.feedbackRating = rating;
  window.$message?.success(rating === 'good' ? '已记录点赞反馈' : '已记录点踩反馈');
}

// 存储文件名和对应的事件处理
const sourceFiles = ref<Array<{fileName: string, id: string, referenceNumber: number, fileMd5?: string, pageNumber?: number}>>([]);
const bareUrlPattern = /https?:\/\/[A-Za-z0-9\-._~:/?#\[\]@!$&'()*+,;=%]+/g;
const toolNameLabels: Record<string, string> = {
  search_knowledge: '检索知识库',
  generate_summary: '生成知识摘要',
  submit_feedback: '记录反馈',
  knowledge_stats: '读取知识库统计'
};
const toolStatusLabels: Record<Api.Chat.AgentToolEvent['status'], string> = {
  executing: '执行中',
  success: '已完成',
  failed: '失败'
};

const toolEvents = computed(() => props.msg.toolEvents || []);

function getToolLabel(tool: string) {
  return toolNameLabels[tool] || tool;
}

function getToolStatusLabel(status: Api.Chat.AgentToolEvent['status']) {
  return toolStatusLabels[status] || status;
}

const agentRounds = computed(() => props.msg.agentRounds || []);
const findings = computed(() => props.msg.findings || []);
const sortedRounds = computed(() => [...agentRounds.value].sort((a, b) => a.round - b.round));
const hasAgentProcess = computed(
  () => toolEvents.value.length > 0 || agentRounds.value.length > 0 || findings.value.length > 0
);
const processCollapsed = ref(false);
const expandedResults = ref<Record<string, boolean>>({});

const processSummary = computed(() => {
  const parts: string[] = [];
  if (toolEvents.value.length > 0) {
    parts.push(`${toolEvents.value.length} 个工具`);
  }
  if (sortedRounds.value.length > 0) {
    parts.push(`${sortedRounds.value.length} 轮`);
  }
  if (findings.value.length > 0) {
    parts.push(`${findings.value.length} 条发现`);
  }
  return parts.join(' · ');
});

function toggleProcessPanel() {
  processCollapsed.value = !processCollapsed.value;
}

function formatElapsed(ms?: number) {
  if (!ms || ms <= 0) {
    return '';
  }
  return `${(ms / 1000).toFixed(1)}s`;
}

function formatTokenCount(value?: number) {
  return Number(value || 0).toLocaleString('en-US');
}

function getToolEventKey(event: Api.Chat.AgentToolEvent) {
  return event.id || event.tool;
}

function isResultExpanded(event: Api.Chat.AgentToolEvent) {
  return Boolean(expandedResults.value[getToolEventKey(event)]);
}

function isResultClamped(event: Api.Chat.AgentToolEvent) {
  const preview = event.resultPreview || '';
  return preview.length > 80 || preview.includes('\n');
}

function toggleResultPreview(event: Api.Chat.AgentToolEvent) {
  const key = getToolEventKey(event);
  expandedResults.value = {
    ...expandedResults.value,
    [key]: !expandedResults.value[key]
  };
}

function splitTrailingUrlPunctuation(rawUrl: string) {
  let url = rawUrl;
  let trailing = '';

  while (url) {
    const lastChar = url.at(-1);
    if (!lastChar) break;

    if (/[，。！？；：、,.!?;:]/.test(lastChar)) {
      trailing = `${lastChar}${trailing}`;
      url = url.slice(0, -1);
      continue;
    }

    if (lastChar === ')' || lastChar === '）') {
      const openingChar = lastChar === ')' ? '(' : '（';
      const closingChar = lastChar;
      const openingCount = (url.match(new RegExp(`\\${openingChar}`, 'g')) || []).length;
      const closingCount = (url.match(new RegExp(`\\${closingChar}`, 'g')) || []).length;

      if (closingCount > openingCount) {
        trailing = `${lastChar}${trailing}`;
        url = url.slice(0, -1);
        continue;
      }
    }

    break;
  }

  return { url, trailing };
}

function normalizeBareUrls(text: string) {
  return text.replace(bareUrlPattern, (match, offset: number, source: string) => {
    const previousChar = source[offset - 1] || '';
    const previousTwoChars = source.slice(Math.max(0, offset - 2), offset);
    const previousTenChars = source.slice(Math.max(0, offset - 10), offset).toLowerCase();

    if (previousChar === '<' || previousTwoChars === '](' || /(?:href|src)=["']?$/.test(previousTenChars)) {
      return match;
    }

    const { url, trailing } = splitTrailingUrlPunctuation(match);
    return url ? `<${url}>${trailing}` : match;
  });
}

function createSourceLink(
  sourceNum: string,
  fileName: string,
  extras?: { fileMd5?: string; pageNumber?: number; displayName?: string }
): string {
  const linkClass = 'source-file-link';
  const trimmedFileName = fileName.trim();
  const fileId = `source-file-${sourceFiles.value.length}`;
  const referenceNumber = parseInt(sourceNum, 10);

  sourceFiles.value.push({
    fileName: trimmedFileName,
    id: fileId,
    referenceNumber,
    fileMd5: extras?.fileMd5,
    pageNumber: extras?.pageNumber
  });

  return `来源#${sourceNum}: <span class="${linkClass}" data-file-id="${fileId}">${extras?.displayName || trimmedFileName}</span>`;
}

// 处理来源文件链接的函数
function processSourceLinks(text: string): string {
  // 重置来源文件列表，避免重复
  sourceFiles.value = [];

  // 支持单个来源，也支持一个括号里包含多个来源：
  // (来源#1: test.pdf | 第5页; 来源#2: other.pdf | 第8页)
  const entryBoundary = '(?=\\s*(?:[;；,，、。！？!?\\)）]|$))';
  const pagePattern = new RegExp(
    `来源#(\\d+):\\s*([^|;；,，、。！？!?\\n\\r]+?)\\s*\\|\\s*第(\\d+)页${entryBoundary}`,
    'g'
  );
  const md5Pattern = new RegExp(
    `来源#(\\d+):\\s*([^|;；,，、。！？!?\\n\\r]+?)\\s*\\|\\s*MD5:\\s*([a-fA-F0-9]+)${entryBoundary}`,
    'g'
  );
  const simplePattern = new RegExp(
    `来源#(\\d+):\\s*([^<>\\n\\r|;；,，、。！？!?]+?)${entryBoundary}`,
    'g'
  );

  let processedText = text.replace(pagePattern, (_match, sourceNum, fileName, pageNum) => {
    return createSourceLink(sourceNum, fileName, {
      pageNumber: parseInt(pageNum, 10),
      displayName: `${fileName.trim()} (第${pageNum}页)`
    });
  });

  processedText = processedText.replace(md5Pattern, (_match, sourceNum, fileName, fileMd5) => {
    return createSourceLink(sourceNum, fileName, {
      fileMd5: fileMd5.trim()
    });
  });

  processedText = processedText.replace(simplePattern, (_match, sourceNum, fileName) => {
    return createSourceLink(sourceNum, fileName);
  });

  return processedText;
}

const content = computed(() => {
  chatStore.scrollToBottom?.();
  const rawContent = props.msg.content ?? '';

  // 只对助手消息处理来源链接
  if (props.msg.role === 'assistant') {
    return normalizeBareUrls(processSourceLinks(rawContent));
  }

  return rawContent;
});

function extractContextAnchorText(target: HTMLElement) {
  const scope = target.closest('li, p, blockquote, td, th');
  const rawText = scope?.textContent?.replace(/\s+/g, ' ').trim() || '';
  if (!rawText) return '';

  const beforeCitation = rawText.split(/(?:\(|（)?来源#\d+:/)[0] || rawText;
  return beforeCitation
    .replace(/^\s*\d+\.\s*/, '')
    .replace(/[（(]\s*$/, '')
    .replace(/\s+/g, ' ')
    .trim();
}

function openReferencePreviewPage(payload: {
  retrievalMode?: Api.Chat.ReferenceEvidence['retrievalMode'];
  retrievalLabel?: string | null;
  retrievalQuery?: string | null;
  evidenceSnippet?: string | null;
  matchedChunkText?: string | null;
  score?: number | null;
  chunkId?: number | null;
  fileName: string;
  fileMd5?: string | null;
  pageNumber?: number | null;
  anchorText?: string | null;
  sessionId?: string;
  referenceNumber: number;
}) {
  const previewKey = `reference-preview:${Date.now()}:${Math.random().toString(36).slice(2, 8)}`;
  localStorage.setItem(previewKey, JSON.stringify(payload));

  const routeLocation = router.resolve({
    path: '/chat',
    query: {
      preview: 'reference',
      previewKey
    }
  });

  window.open(routeLocation.href, '_blank', 'noopener,noreferrer');
}

function showWikiSource(title: string, markdown: string) {
  window.$dialog?.info({
    title: `Wiki 概念页：${title}`,
    content: () => h(VueMarkdownIt, { content: markdown }),
    positiveText: '关闭',
    style: { width: '760px' }
  });
}

// 处理内容点击事件（事件委托）
function handleContentClick(event: MouseEvent) {  const target = event.target as HTMLElement;

  // 检查点击的是否是文件链接
  if (target.classList.contains('source-file-link')) {
    const fileId = target.getAttribute('data-file-id');
    if (fileId) {
      const file = sourceFiles.value.find(f => f.id === fileId);
      if (file) {
        const contextAnchorText = extractContextAnchorText(target);
        handleSourceFileClick({
          fileName: file.fileName,
          referenceNumber: file.referenceNumber,
          fileMd5: file.fileMd5,
          anchorText: contextAnchorText
        });
      }
    }
  }
}

// 处理来源文件点击事件
async function handleSourceFileClick(fileInfo: {
  fileName: string;
  referenceNumber: number;
  fileMd5?: string;
  anchorText?: string;
}) {
  const { fileName, referenceNumber, fileMd5: extractedMd5, anchorText: clickedAnchorText } = fileInfo;
  const persistedDetail = props.msg.referenceMappings?.[String(referenceNumber)] || props.msg.referenceMappings?.[referenceNumber];
  const referenceSessionId = props.msg.generationId || props.msg.conversationId || props.sessionId;
  console.log('点击了来源文件:', fileName, '引用编号:', referenceNumber, '提取的MD5:', extractedMd5, '会话ID:', referenceSessionId);

  if (persistedDetail?.retrievalMode === 'wiki') {
    showWikiSource(
      persistedDetail.fileName || fileName,
      persistedDetail.matchedChunkText || persistedDetail.evidenceSnippet || ''
    );
    return;
  }

  try {
    let detail: Api.Document.ReferenceDetailResponse | null = null;
    const fallbackRetrievalQuery = props.retrievalQueryFallback || '';

    if (referenceSessionId && (!persistedDetail?.retrievalQuery || !persistedDetail?.matchedChunkText || !persistedDetail?.evidenceSnippet)) {
      try {
        const { error: detailError, data: detailData } = await request<Api.Document.ReferenceDetailResponse>({
          url: 'documents/reference-detail',
          params: {
            sessionId: referenceSessionId,
            referenceNumber: referenceNumber.toString()
          }
        });

        if (!detailError && detailData?.fileMd5) {
          detail = detailData;
        }
      } catch (detailErr) {
        console.warn('通过API查询引用详情失败:', detailErr);
      }
    }

    if (persistedDetail?.fileMd5 && !detail) {
      openReferencePreviewPage({
        fileName: persistedDetail.fileName || fileName,
        fileMd5: persistedDetail.fileMd5,
        pageNumber: persistedDetail.pageNumber,
        anchorText: persistedDetail.anchorText || clickedAnchorText || '',
        retrievalMode: persistedDetail.retrievalMode,
        retrievalLabel: persistedDetail.retrievalLabel,
        retrievalQuery: persistedDetail.retrievalQuery || fallbackRetrievalQuery,
        evidenceSnippet: persistedDetail.evidenceSnippet,
        matchedChunkText: persistedDetail.matchedChunkText,
        score: persistedDetail.score,
        chunkId: persistedDetail.chunkId,
        sessionId: referenceSessionId,
        referenceNumber
      });
      return;
    }

    const targetMd5 = detail?.fileMd5 || extractedMd5 || null;
    openReferencePreviewPage({
      fileName: detail?.fileName || fileName,
      fileMd5: targetMd5,
      pageNumber: detail?.pageNumber,
      anchorText: detail?.anchorText || clickedAnchorText || '',
      retrievalMode: detail?.retrievalMode,
      retrievalLabel: detail?.retrievalLabel,
      retrievalQuery: detail?.retrievalQuery || fallbackRetrievalQuery,
      evidenceSnippet: detail?.evidenceSnippet,
      matchedChunkText: detail?.matchedChunkText,
      score: detail?.score,
      chunkId: detail?.chunkId,
      sessionId: referenceSessionId,
      referenceNumber
    });
  } catch (err) {
    console.error('文件下载失败:', err);
    window.$message?.error(`文件下载失败: ${fileName}`);
  }
}
</script>

<template>
  <div class="mb-6 flex-col gap-2">
    <div v-if="msg.role === 'assistant'" class="flex items-center gap-3">
      <span class="ai-avatar"><SystemLogo class="text-17px" /></span>
      <div class="flex-col gap-0.5">
        <NText class="text-body-lg font-600" style="color: rgb(var(--primary-color))">MindFlow</NText>
        <NText class="text-body-sm color-gray-500">{{ formatDate(msg.timestamp) }}</NText>
      </div>
    </div>
    <div v-if="msg.role === 'assistant' && hasAgentProcess" class="agent-process ml-12 mt-3">
      <button
        type="button"
        class="agent-process__header"
        :aria-expanded="!processCollapsed"
        @click="toggleProcessPanel"
      >
        <icon-material-symbols:settings-rounded class="agent-process__header-icon" />
        <span class="agent-process__header-title">执行过程</span>
        <span v-if="processSummary" class="agent-process__header-meta">· {{ processSummary }}</span>
        <icon-material-symbols:expand-more-rounded
          class="agent-process__header-caret"
          :class="{ 'agent-process__header-caret--open': !processCollapsed }"
        />
      </button>
      <div v-show="!processCollapsed" class="agent-process__body">
        <div v-if="sortedRounds.length > 0" class="agent-process__rounds">
          <div v-for="round in sortedRounds" :key="round.round" class="agent-process__round">
            <template v-if="round.phase === 'finished'">
              第 {{ round.round }} 轮 · {{ formatElapsed(round.elapsedMs) }} · ↑{{ formatTokenCount(round.promptTokens) }} / ↓{{ formatTokenCount(round.completionTokens) }} tokens
            </template>
            <template v-else>第 {{ round.round }} 轮 · 进行中…</template>
          </div>
        </div>
        <div v-if="toolEvents.length > 0" class="agent-process__tools">
          <div v-for="event in toolEvents" :key="event.id || event.tool" class="agent-tool">
            <div class="agent-tool__head">
              <span class="agent-tool__pill" :class="`agent-tool__pill--${event.status}`">
                <icon-eos-icons:three-dots-loading v-if="event.status === 'executing'" class="text-12px" />
                <icon-material-symbols:check-circle-rounded v-else-if="event.status === 'success'" class="text-12px" />
                <icon-material-symbols:error-rounded v-else class="text-12px" />
                {{ getToolStatusLabel(event.status) }}
              </span>
              <span class="agent-tool__name">{{ getToolLabel(event.tool) }}</span>
              <span v-if="event.elapsedMs" class="agent-tool__elapsed">{{ formatElapsed(event.elapsedMs) }}</span>
            </div>
            <div v-if="event.arguments" class="agent-tool__mono agent-tool__args" :title="event.arguments">
              {{ event.arguments }}
            </div>
            <div v-if="event.resultPreview" class="agent-tool__result">
              <div class="agent-tool__mono agent-tool__result-text" :class="{ 'is-clamped': !isResultExpanded(event) }">
                {{ event.resultPreview }}
              </div>
              <button
                v-if="isResultClamped(event)"
                type="button"
                class="agent-tool__toggle"
                @click="toggleResultPreview(event)"
              >
                {{ isResultExpanded(event) ? '收起' : '展开' }}
              </button>
            </div>
          </div>
        </div>
        <ul v-if="findings.length > 0" class="agent-process__findings">
          <li v-for="(finding, index) in findings" :key="index" class="agent-process__finding">
            <span class="agent-process__finding-tag">第{{ finding.round }}轮 · {{ getToolLabel(finding.tool) }}</span>
            <span>{{ finding.summary }}</span>
          </li>
        </ul>
      </div>
    </div>
    <NText v-if="msg.status === 'pending' || (msg.status === 'loading' && msg.role === 'assistant' && !msg.content)">
      <icon-eos-icons:three-dots-loading class="ml-12 mt-2 text-8" />
    </NText>
    <NText v-else-if="msg.status === 'error'" class="ml-12 mt-2 text-body italic color-#d03050">
      {{ msg.content || '服务器繁忙，请稍后再试' }}
    </NText>
    <div v-else-if="msg.role === 'assistant'" class="mt-2.5 pl-12" @click="handleContentClick">
      <div class="ai-card">
        <VueMarkdownIt :content="content" />
      </div>
    </div>
    <div v-else-if="msg.role === 'user'" class="mt-1 flex justify-end">
      <div class="user-bubble">{{ content }}</div>
    </div>
    <NDivider class="ml-12 w-[calc(100%-3rem)] mb-0! mt-2!" />
    <div class="ml-12 flex gap-2">
      <NButton quaternary title="复制回答" aria-label="复制回答" @click="handleCopy(msg.content)">
        <template #icon>
          <icon-mynaui:copy />
        </template>
      </NButton>
      <NButton
        v-if="msg.role === 'assistant'"
        quaternary
        title="点赞"
        aria-label="点赞"
        :type="msg.feedbackRating === 'good' ? 'primary' : 'default'"
        :loading="feedbackSubmitting[getMessageFeedbackKey(msg)]"
        @click="handleFeedback(msg, 'good')"
      >
        <template #icon>
          <icon-material-symbols:thumb-up-outline-rounded />
        </template>
      </NButton>
      <NButton
        v-if="msg.role === 'assistant'"
        quaternary
        title="点踩"
        aria-label="点踩"
        :type="msg.feedbackRating === 'bad' ? 'error' : 'default'"
        :loading="feedbackSubmitting[getMessageFeedbackKey(msg)]"
        @click="handleFeedback(msg, 'bad')"
      >
        <template #icon>
          <icon-material-symbols:thumb-down-outline-rounded />
        </template>
      </NButton>
    </div>
  </div>
</template>

<style scoped lang="scss">
:deep(.source-file-link) {
  color: #1890ff;
  cursor: pointer;
  text-decoration: underline;
  transition: color 0.2s;

  &:hover {
    color: #40a9ff;
    text-decoration: none;
  }

  &:active {
    color: #096dd9;
  }
}

.agent-process {
  max-width: 100%;
  border: 1px solid rgb(var(--border-color) / 0.16);
  border-radius: 8px;
  background: rgb(var(--card-color));
  overflow: hidden;
}

.agent-process__header {
  display: flex;
  width: 100%;
  align-items: center;
  gap: 6px;
  border: none;
  background: transparent;
  padding: 6px 10px;
  cursor: pointer;
  font-size: 12px;
  line-height: 18px;
  text-align: left;
  color: rgb(var(--text-color-2));
  transition: background-color 0.15s ease;

  &:hover {
    background: rgb(var(--border-color) / 0.08);
  }
}

.agent-process__header-icon {
  flex-shrink: 0;
  font-size: 14px;
  color: rgb(var(--primary-color));
}

.agent-process__header-title {
  flex-shrink: 0;
  font-weight: 600;
  color: rgb(var(--text-color));
}

.agent-process__header-meta {
  overflow: hidden;
  color: rgb(var(--text-color-3));
  text-overflow: ellipsis;
  white-space: nowrap;
}

.agent-process__header-caret {
  flex-shrink: 0;
  margin-left: auto;
  font-size: 16px;
  color: rgb(var(--text-color-3));
  transition: transform 0.18s ease;
}

.agent-process__header-caret--open {
  transform: rotate(180deg);
}

.agent-process__body {
  display: flex;
  flex-direction: column;
  gap: 8px;
  border-top: 1px solid rgb(var(--border-color) / 0.12);
  padding: 8px 10px;
  font-size: 12px;
  line-height: 18px;
  color: rgb(var(--text-color-2));
}

.agent-process__rounds {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.agent-process__round {
  color: rgb(var(--text-color-3));
  font-variant-numeric: tabular-nums;
}

.agent-process__tools {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.agent-tool {
  display: flex;
  flex-direction: column;
  gap: 4px;
  border: 1px solid rgb(var(--border-color) / 0.14);
  border-radius: 6px;
  background: rgb(var(--border-color) / 0.04);
  padding: 6px 8px;
}

.agent-tool__head {
  display: flex;
  min-width: 0;
  align-items: center;
  gap: 6px;
}

.agent-tool__pill {
  display: inline-flex;
  flex-shrink: 0;
  align-items: center;
  gap: 3px;
  border-radius: 999px;
  padding: 1px 7px;
  font-size: 11px;
  line-height: 16px;
}

.agent-tool__pill--executing {
  background: rgb(var(--primary-color) / 0.12);
  color: rgb(var(--primary-color));
}

.agent-tool__pill--success {
  background: rgb(24 160 88 / 0.12);
  color: #18a058;
}

.agent-tool__pill--failed {
  background: rgb(208 48 80 / 0.12);
  color: #d03050;
}

.agent-tool__name {
  overflow: hidden;
  font-weight: 500;
  color: rgb(var(--text-color));
  text-overflow: ellipsis;
  white-space: nowrap;
}

.agent-tool__elapsed {
  flex-shrink: 0;
  margin-left: auto;
  color: rgb(var(--text-color-3));
  font-variant-numeric: tabular-nums;
}

.agent-tool__mono {
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 11px;
  line-height: 16px;
  color: rgb(var(--text-color-3));
  word-break: break-all;
}

.agent-tool__args {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.agent-tool__result-text.is-clamped {
  display: -webkit-box;
  overflow: hidden;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
}

.agent-tool__toggle {
  margin-top: 2px;
  border: none;
  background: transparent;
  padding: 0;
  cursor: pointer;
  font-size: 11px;
  color: rgb(var(--primary-color));

  &:hover {
    text-decoration: underline;
  }
}

.agent-process__findings {
  display: flex;
  flex-direction: column;
  gap: 3px;
  margin: 0;
  padding-left: 16px;
  list-style: disc;
}

.agent-process__finding {
  color: rgb(var(--text-color-2));

  &::marker {
    color: rgb(var(--text-color-3));
  }
}

.agent-process__finding-tag {
  margin-right: 4px;
  color: rgb(var(--text-color-3));
}
</style>
