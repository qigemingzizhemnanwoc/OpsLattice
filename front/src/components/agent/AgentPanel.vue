<template>
  <div class="agent-overlay">
    <div class="agent-shell">
      <div class="agent-sidebar-mask" v-if="sidebarOpen" @click="sidebarOpen = false"></div>
      <aside class="agent-sidebar" :class="{ 'agent-sidebar--open': sidebarOpen }">
        <div class="agent-sidebar-head">
          <span class="agent-logo">◆ 数据 Agent</span>
          <button class="agent-new-btn" @click="startNewConversation">+ 新对话</button>
        </div>
        <div class="agent-conv-list">
          <div
            v-for="conv in conversations"
            :key="conv.id"
            :class="['agent-conv', { 'agent-conv--active': currentConversationId === conv.id }]"
            @click="openConversation(conv)"
          >
            <span class="agent-conv-title">{{ conv.title }}</span>
            <button class="agent-conv-del" title="删除" @click.stop="removeConversation(conv)">×</button>
          </div>
          <div v-if="conversations.length === 0" class="agent-conv-empty">暂无数据对话</div>
        </div>
        <div class="agent-sidebar-foot">
          <span>只读查询 · 不修改业务数据</span>
        </div>
      </aside>

      <section class="agent-main">
        <header class="agent-header">
          <button class="agent-menu-btn" @click="sidebarOpen = true">☰</button>
          <div class="agent-title">
            <h2>数据 Agent</h2>
            <p>{{ statusText }} · 自动判断并调用库存/销售工具</p>
          </div>
          <div class="agent-header-right">
            <span class="agent-role">{{ roleText }}</span>
            <button class="agent-close-btn" @click="$emit('close')">退出</button>
          </div>
        </header>

        <div class="agent-config-warning" v-if="status && !status.configured">
          DeepSeek 尚未配置：请在 <code>back/assistant-llm.env</code> 设置
          <code>DEEPSEEK_API_KEY</code> 后重启后端。
        </div>

        <div class="agent-chat" ref="chatRef">
          <div v-if="messages.length === 0" class="agent-welcome">
            <div class="agent-welcome-icon">◈</div>
            <h3>可以帮你查库存和销售</h3>
            <p>我会先判断问题，再自动调用只读工具；权限与现有业务页面保持一致。</p>
            <p class="agent-scope">当前可用工具：{{ scopeText }}</p>
            <div class="agent-suggestions">
              <button v-for="item in suggestions" :key="item" @click="send(item)">{{ item }}</button>
            </div>
          </div>

          <div
            v-for="(message, index) in messages"
            :key="index"
            :class="['agent-msg', `agent-msg--${message.role}`]"
          >
            <div class="agent-bubble">
              <div class="agent-content">{{ message.content }}</div>
              <div v-if="message.toolCalls && message.toolCalls.length" class="agent-tool-list">
                <div v-for="call in message.toolCalls" :key="call.seq" class="agent-tool">
                  <div class="agent-tool-head">
                    <span class="agent-tool-name">{{ toolLabel(call.toolName) }}</span>
                    <span :class="['agent-tool-status', call.success ? 'is-ok' : 'is-fail']">
                      {{ call.success ? '成功' : '失败' }}
                    </span>
                    <span v-if="call.rowCount !== null && call.rowCount !== undefined" class="agent-tool-meta">
                      {{ call.rowCount }} 行
                    </span>
                    <span v-if="call.latencyMs" class="agent-tool-meta">{{ call.latencyMs }} ms</span>
                  </div>
                  <details class="agent-tool-detail">
                    <summary>参数与结果摘要</summary>
                    <div class="agent-tool-json">
                      <div class="agent-json-label">参数</div>
                      <pre>{{ call.arguments || '{}' }}</pre>
                      <div class="agent-json-label">结果</div>
                      <pre>{{ call.resultSummary || '（无结果摘要）' }}</pre>
                    </div>
                  </details>
                </div>
              </div>
            </div>
          </div>

          <div v-if="loading" class="agent-msg agent-msg--assistant">
            <div class="agent-bubble agent-bubble--loading">
              <span class="agent-dot"></span><span class="agent-dot"></span><span class="agent-dot"></span>
              正在判断并查询数据...
            </div>
          </div>
        </div>

        <div class="agent-input-area">
          <textarea
            v-model="inputText"
            class="agent-input"
            rows="2"
            placeholder="例如：哪些商品库存不足？ / 这些商品的销售情况怎么样？"
            :disabled="loading"
            @keydown.enter.exact.prevent="send()"
          ></textarea>
          <button class="agent-send-btn" :disabled="!inputText.trim() || loading" @click="send()">
            发送
          </button>
        </div>
        <p class="agent-input-hint">Enter 发送，Shift + Enter 换行 · 仅查询，不执行写操作</p>
      </section>
    </div>
  </div>
</template>

<script setup>
import { computed, nextTick, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useUserStore } from '@/stores/user'
import {
  deleteAgentConversationAPI,
  getAgentMessagesAPI,
  getAgentStatusAPI,
  getAgentToolsAPI,
  listAgentConversationsAPI,
  queryAgentAPI
} from '@/api/agent'

defineEmits(['close'])

const userStore = useUserStore()
const chatRef = ref(null)
const inputText = ref('')
const loading = ref(false)
const sidebarOpen = ref(false)
const messages = ref([])
const conversations = ref([])
const currentConversationId = ref(null)
const status = ref(null)
const tools = ref([])

const suggestions = [
  '哪些商品库存不足？',
  '这些商品的销售情况怎么样？',
  '查询电阻10K的库存',
  '整体销售情况怎么样？'
]

const roleLabels = {
  superadmin: '超级管理员',
  admin: '管理员',
  employee: '员工'
}

const deptLabels = {
  hr: '人事',
  purchase: '采购',
  sales: '销售',
  warehouse: '仓储',
  finance: '财务'
}

const toolLabels = {
  queryLowStockGoods: '查询库存不足商品',
  queryGoodsStock: '查询商品库存明细',
  queryGoodsSales: '查询商品销售情况',
  querySalesOverview: '查询销售统计概览'
}

const roleText = computed(() => {
  if (userStore.role === 'admin' && deptLabels[userStore.deptCode]) {
    return `${deptLabels[userStore.deptCode]}管理员`
  }
  return roleLabels[userStore.role] || '当前用户'
})

const statusText = computed(() => {
  if (!status.value) return '正在检查模型配置'
  return status.value.configured ? 'DeepSeek 已接入' : 'DeepSeek 未配置'
})

const scopeText = computed(() => {
  if (!tools.value.length) return '当前角色没有可用的数据查询工具'
  return tools.value.map(item => item.label || toolLabels[item.name] || item.name).join('、')
})

const toolLabel = (name) => toolLabels[name] || name

const scrollToBottom = async () => {
  await nextTick()
  if (chatRef.value) {
    chatRef.value.scrollTop = chatRef.value.scrollHeight
  }
}

const loadStatusAndTools = async () => {
  try {
    const [statusRes, toolsRes] = await Promise.all([getAgentStatusAPI(), getAgentToolsAPI()])
    if (statusRes.code === 200) status.value = statusRes.data
    if (toolsRes.code === 200) tools.value = toolsRes.data || []
  } catch (error) {
    console.warn('加载数据 Agent 配置失败', error)
  }
}

const loadConversations = async () => {
  try {
    const res = await listAgentConversationsAPI({ pageNum: 1, pageSize: 50 })
    if (res.code === 200) {
      conversations.value = res.data?.records || []
    }
  } catch (error) {
    console.warn('加载数据 Agent 会话失败', error)
  }
}

const startNewConversation = () => {
  currentConversationId.value = null
  messages.value = []
  inputText.value = ''
  sidebarOpen.value = false
}

const openConversation = async (conv) => {
  if (loading.value) return
  currentConversationId.value = conv.id
  sidebarOpen.value = false
  try {
    const res = await getAgentMessagesAPI(conv.id)
    if (res.code === 200) {
      messages.value = (res.data || []).map(item => ({
        role: item.role,
        content: item.content,
        toolCalls: []
      }))
      await scrollToBottom()
    }
  } catch (error) {
    ElMessage.error('加载会话失败')
  }
}

const removeConversation = async (conv) => {
  try {
    await ElMessageBox.confirm(`删除会话“${conv.title}”？`, '删除会话', {
      confirmButtonText: '删除',
      cancelButtonText: '取消',
      type: 'warning'
    })
  } catch {
    return
  }
  try {
    const res = await deleteAgentConversationAPI(conv.id)
    if (res.code === 200) {
      conversations.value = conversations.value.filter(item => item.id !== conv.id)
      if (currentConversationId.value === conv.id) {
        startNewConversation()
      }
      ElMessage.success('已删除')
    } else {
      ElMessage.error(res.msg || '删除失败')
    }
  } catch (error) {
    ElMessage.error('删除失败')
  }
}

const send = async (suggestion) => {
  const question = (suggestion || inputText.value).trim()
  if (!question || loading.value) return

  messages.value.push({ role: 'user', content: question, toolCalls: [] })
  inputText.value = ''
  loading.value = true
  await scrollToBottom()

  try {
    const res = await queryAgentAPI({
      question,
      conversationId: currentConversationId.value
    })
    if (res.code !== 200) {
      throw new Error(res.msg || '查询失败')
    }
    const data = res.data
    currentConversationId.value = data.conversationId || currentConversationId.value
    messages.value.push({
      role: 'assistant',
      content: data.answer || '模型未返回有效内容',
      toolCalls: data.toolCalls || []
    })
    await loadConversations()
  } catch (error) {
    messages.value.push({
      role: 'assistant',
      content: error.message || '请求失败，请稍后重试。',
      toolCalls: []
    })
  } finally {
    loading.value = false
    await scrollToBottom()
  }
}

onMounted(async () => {
  await Promise.all([loadStatusAndTools(), loadConversations()])
})
</script>

<style scoped>
.agent-overlay {
  position: fixed;
  inset: 0;
  z-index: 2000;
  display: flex;
  align-items: stretch;
  justify-content: center;
  background: rgba(15, 23, 42, 0.55);
  backdrop-filter: blur(4px);
}

.agent-shell {
  display: flex;
  width: min(1440px, 100%);
  height: 100%;
  background: #f8fafc;
  overflow: hidden;
}

.agent-sidebar {
  width: 260px;
  flex: 0 0 260px;
  display: flex;
  flex-direction: column;
  background: #0f172a;
  color: #e2e8f0;
}

.agent-sidebar-head {
  padding: 18px 16px;
  border-bottom: 1px solid rgba(148, 163, 184, 0.18);
}

.agent-logo {
  display: block;
  font-weight: 700;
  letter-spacing: 0.04em;
  margin-bottom: 12px;
  color: #e2e8f0;
}

.agent-new-btn {
  width: 100%;
  padding: 9px 12px;
  border: 1px solid rgba(96, 165, 250, 0.45);
  border-radius: 8px;
  background: rgba(37, 99, 235, 0.18);
  color: #bfdbfe;
  cursor: pointer;
  font-size: 13px;
}

.agent-new-btn:hover {
  background: rgba(37, 99, 235, 0.3);
}

.agent-conv-list {
  flex: 1;
  overflow-y: auto;
  padding: 10px 8px;
}

.agent-conv {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 10px;
  border-radius: 8px;
  cursor: pointer;
  color: #cbd5e1;
}

.agent-conv:hover,
.agent-conv--active {
  background: rgba(148, 163, 184, 0.14);
  color: #f8fafc;
}

.agent-conv-title {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 13px;
}

.agent-conv-del {
  border: 0;
  background: transparent;
  color: #94a3b8;
  cursor: pointer;
  font-size: 15px;
  line-height: 1;
  padding: 0 4px;
}

.agent-conv-del:hover {
  color: #f87171;
}

.agent-conv-empty {
  padding: 18px 10px;
  color: #64748b;
  font-size: 13px;
  text-align: center;
}

.agent-sidebar-foot {
  padding: 14px 16px;
  border-top: 1px solid rgba(148, 163, 184, 0.18);
  font-size: 12px;
  color: #64748b;
}

.agent-main {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  background: #ffffff;
}

.agent-header {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 16px 24px;
  border-bottom: 1px solid #e2e8f0;
  background: #ffffff;
}

.agent-menu-btn {
  display: none;
  border: 1px solid #e2e8f0;
  background: #fff;
  border-radius: 8px;
  padding: 6px 10px;
  cursor: pointer;
}

.agent-title {
  flex: 1;
  min-width: 0;
}

.agent-title h2 {
  margin: 0;
  font-size: 18px;
  color: #0f172a;
}

.agent-title p {
  margin: 4px 0 0;
  font-size: 12px;
  color: #64748b;
}

.agent-header-right {
  display: flex;
  align-items: center;
  gap: 10px;
}

.agent-role {
  padding: 5px 10px;
  border-radius: 999px;
  background: #eff6ff;
  color: #1d4ed8;
  font-size: 12px;
  font-weight: 600;
}

.agent-close-btn {
  border: 1px solid #e2e8f0;
  background: #fff;
  color: #475569;
  border-radius: 8px;
  padding: 7px 14px;
  cursor: pointer;
  font-size: 13px;
}

.agent-close-btn:hover {
  background: #f1f5f9;
}

.agent-config-warning {
  padding: 10px 24px;
  background: #fffbeb;
  border-bottom: 1px solid #fde68a;
  color: #92400e;
  font-size: 13px;
}

.agent-config-warning code {
  background: rgba(146, 64, 14, 0.1);
  padding: 1px 5px;
  border-radius: 4px;
}

.agent-chat {
  flex: 1;
  overflow-y: auto;
  padding: 24px;
  background: linear-gradient(180deg, #f8fafc 0%, #ffffff 180px);
}

.agent-welcome {
  max-width: 760px;
  margin: 8vh auto 0;
  text-align: center;
}

.agent-welcome-icon {
  font-size: 32px;
  color: #2563eb;
  margin-bottom: 12px;
}

.agent-welcome h3 {
  margin: 0 0 8px;
  color: #0f172a;
  font-size: 22px;
}

.agent-welcome p {
  color: #64748b;
  font-size: 14px;
  line-height: 1.7;
  margin: 6px 0;
}

.agent-scope {
  color: #1d4ed8 !important;
}

.agent-suggestions {
  display: flex;
  flex-wrap: wrap;
  justify-content: center;
  gap: 10px;
  margin-top: 22px;
}

.agent-suggestions button {
  border: 1px solid #dbeafe;
  background: #eff6ff;
  color: #1e40af;
  padding: 9px 14px;
  border-radius: 999px;
  cursor: pointer;
  font-size: 13px;
}

.agent-suggestions button:hover {
  background: #dbeafe;
}

.agent-msg {
  display: flex;
  margin-bottom: 18px;
}

.agent-msg--user {
  justify-content: flex-end;
}

.agent-msg--assistant {
  justify-content: flex-start;
}

.agent-bubble {
  max-width: min(860px, 82%);
  border-radius: 14px;
  padding: 13px 16px;
  line-height: 1.75;
  font-size: 14px;
  word-break: break-word;
}

.agent-msg--user .agent-bubble {
  background: #2563eb;
  color: #ffffff;
  border-bottom-right-radius: 4px;
}

.agent-msg--assistant .agent-bubble {
  background: #f1f5f9;
  color: #0f172a;
  border-bottom-left-radius: 4px;
}

.agent-content {
  white-space: pre-wrap;
}

.agent-bubble--loading {
  display: flex;
  align-items: center;
  gap: 5px;
  color: #64748b;
}

.agent-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: #3b82f6;
  animation: agent-bounce 1.2s infinite ease-in-out;
}

.agent-dot:nth-child(2) {
  animation-delay: 0.15s;
}

.agent-dot:nth-child(3) {
  animation-delay: 0.3s;
}

@keyframes agent-bounce {
  0%, 80%, 100% { transform: translateY(0); opacity: 0.45; }
  40% { transform: translateY(-4px); opacity: 1; }
}

.agent-tool-list {
  margin-top: 12px;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.agent-tool {
  border: 1px solid #dbeafe;
  background: #ffffff;
  border-radius: 10px;
  padding: 10px 12px;
}

.agent-tool-head {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  font-size: 12px;
}

.agent-tool-name {
  color: #1d4ed8;
  font-weight: 600;
}

.agent-tool-status {
  padding: 2px 7px;
  border-radius: 999px;
  font-size: 11px;
}

.agent-tool-status.is-ok {
  background: #dcfce7;
  color: #166534;
}

.agent-tool-status.is-fail {
  background: #fee2e2;
  color: #991b1b;
}

.agent-tool-meta {
  color: #64748b;
}

.agent-tool-detail {
  margin-top: 6px;
  font-size: 12px;
  color: #475569;
}

.agent-tool-detail summary {
  cursor: pointer;
  color: #64748b;
}

.agent-tool-json {
  margin-top: 6px;
}

.agent-json-label {
  color: #94a3b8;
  margin: 4px 0;
}

.agent-tool-json pre {
  margin: 0;
  padding: 8px;
  border-radius: 6px;
  background: #f8fafc;
  white-space: pre-wrap;
  word-break: break-all;
  max-height: 180px;
  overflow-y: auto;
  font-size: 11px;
  color: #334155;
}

.agent-input-area {
  display: flex;
  gap: 12px;
  padding: 14px 24px 4px;
  border-top: 1px solid #e2e8f0;
  background: #ffffff;
}

.agent-input {
  flex: 1;
  resize: none;
  border: 1px solid #cbd5e1;
  border-radius: 10px;
  padding: 10px 12px;
  font-family: inherit;
  font-size: 14px;
  line-height: 1.6;
  outline: none;
}

.agent-input:focus {
  border-color: #3b82f6;
  box-shadow: 0 0 0 3px rgba(59, 130, 246, 0.12);
}

.agent-send-btn {
  align-self: stretch;
  min-width: 82px;
  border: 0;
  border-radius: 10px;
  background: #2563eb;
  color: #ffffff;
  cursor: pointer;
  font-size: 14px;
  font-weight: 600;
}

.agent-send-btn:disabled {
  background: #cbd5e1;
  cursor: not-allowed;
}

.agent-input-hint {
  margin: 0;
  padding: 0 24px 12px;
  color: #94a3b8;
  font-size: 11px;
  background: #ffffff;
}

.agent-sidebar-mask {
  display: none;
}

@media (max-width: 900px) {
  .agent-sidebar {
    position: fixed;
    inset: 0 auto 0 0;
    z-index: 20;
    transform: translateX(-100%);
    transition: transform 0.2s ease;
  }

  .agent-sidebar--open {
    transform: translateX(0);
  }

  .agent-sidebar-mask {
    display: block;
    position: fixed;
    inset: 0;
    z-index: 19;
    background: rgba(15, 23, 42, 0.45);
  }

  .agent-menu-btn {
    display: inline-flex;
  }

  .agent-header {
    padding: 12px 14px;
  }

  .agent-chat {
    padding: 16px 14px;
  }

  .agent-input-area {
    padding: 12px 14px 4px;
  }

  .agent-input-hint {
    padding: 0 14px 10px;
  }

  .agent-bubble {
    max-width: 94%;
  }
}
</style>
