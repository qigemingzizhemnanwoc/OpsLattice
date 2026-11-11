import request from '@/utils/request'

export const queryAgentAPI = (data) =>
  request.post('/assistant/agent/chat', data, { timeout: 120000 })

export const getAgentStatusAPI = () =>
  request.get('/assistant/agent/status')

export const getAgentToolsAPI = () =>
  request.get('/assistant/agent/tools')

export const listAgentConversationsAPI = (params) =>
  request.get('/assistant/agent/conversations', { params })

export const getAgentMessagesAPI = (conversationId) =>
  request.get(`/assistant/agent/conversations/${conversationId}/messages`)

export const deleteAgentConversationAPI = (conversationId) =>
  request.delete(`/assistant/agent/conversations/${conversationId}`)
