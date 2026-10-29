// 智能体安全防护系统前端逻辑

const { createApp, ref, computed, watch, onMounted, nextTick } = Vue;

createApp({
    setup() {
        // 响应式数据
        const activeTab = ref('samples'); // 当前激活的页签
        const inputMessage = ref('');
        const messages = ref([]);
        const whiteSamples = ref([]);
        const blackSamples = ref([]);
        const isLoading = ref(false);
        const connectionStatus = ref('connected');
        const lastBlockedTime = ref(null);
        const blockedMessages = ref(0);
        const guardrailsBlocked = ref(0);
        const llmBlocked = ref(0);
        const piiBlocked = ref(0);
        const contentFilterBlocked = ref(0);
        const totalMessages = ref(0);
        const messagesContainer = ref(null);
        const inputTextarea = ref(null);
        // 从 localStorage 读取 conversation_id，没有则生成新的并持久化
        const getOrCreateConversationId = () => {
            const stored = localStorage.getItem('conversation_id');
            if (stored) return stored;
            const newId = 'demo-conversation-' + Date.now();
            localStorage.setItem('conversation_id', newId);
            return newId;
        };
        const currentConversationId = ref(getOrCreateConversationId());

        // 新建会话：生成新 ID 并清空当前对话记录
        const startNewConversation = () => {
            const newId = 'demo-conversation-' + Date.now();
            localStorage.setItem('conversation_id', newId);
            currentConversationId.value = newId;
            messages.value = [];
            initializeWelcomeMessage();
            showNotification('已开启新会话', 'success');
            return newId;
        };
        const isInputExpanded = ref(false);
        const showExpandButton = ref(false);
        const showGuardrailsPanel = ref(true);
        const guardrailsRules = ref(null);
        const loadingRules = ref(false);
        const resettingRules = ref(false);
        const reloadingRules = ref(false);
        const activeRuleTab = ref('default');
        const activePIITab = ref('input');

        // 内容过滤状态
        const contentFilterCategories = ref([]);
        const loadingContentFilter = ref(false);
        const revectorizing = ref(false);
        const resettingContentFilter = ref(false);
        const showAddContentFilterModal = ref(false);
        const newContentFilterCategory = ref({ name: '', threshold: 0.75, description: '', anchorsText: '' });
        const showViewContentFilterModal = ref(false);
        const viewingContentFilterCategory = ref(null);
        const showAddAnchorModal = ref(false);
        const newAnchorText = ref('');
        const currentCategoryForAnchor = ref(null);
        const showEditContentFilterModal = ref(false);
        const editingContentFilterCategory = ref({ name: '', threshold: 0.75, description: '', category_type: '', anchorsText: '' });
        const showViewAnchorsModal = ref(false);
        const viewingAnchorsCategory = ref(null);

        // 白名单规则状态
        const whitelistRules = ref([]);
        const loadingWhitelistRules = ref(false);
        const resettingWhitelistRules = ref(false);
        const showAddWhitelistModal = ref(false);
        const showEditWhitelistModal = ref(false);
        const showViewWhitelistModal = ref(false);
        const newWhitelistRule = ref({ name: '', description: '', pattern: '' });
        const editingWhitelistRule = ref({ name: '', description: '', pattern: '' });
        const viewingWhitelistRule = ref({ name: '', description: '', pattern: '' });

        // 列表分页状态
        const whitelistPage = ref(1);
        const whitelistPageSize = 10;
        const defaultRulesPage = ref(1);
        const customRulesPage = ref(1);
        const rulesPageSize = 10;
        const contentFilterPage = ref(1);
        const contentFilterPageSize = 10;
        const activeContentFilterTab = ref('system');
        const systemCategoriesPage = ref(1);
        const customCategoriesPage = ref(1);
        const activeContentFilterControlTab = ref('input');

        // 模态框状态
        const showAddRuleModal = ref(false);
        const showEditRuleModal = ref(false);
        const showPatternsModal = ref(false);
        const showConfirmModal = ref(false);
        const confirmModalConfig = ref({ title: '', message: '', type: 'warning', onConfirm: null });
        const newRule = ref({ name: '', description: '', patternsText: '', responseMessage: '' });
        const editingRule = ref({ name: '', description: '', patternsText: '', responseMessage: '', originalPatterns: [] });
        const viewingPatternsRule = ref(null);
        const showViewRuleModal = ref(false);
        const viewingRule = ref({ name: '', description: '', patterns: [], patternsText: '', responseMessage: '', isDefault: false });
        const showIntroModal = ref(false);

        // 安全护栏配置抽屉状态
        const showConfigDrawer = ref(false);
        const activeConfigMenu = ref('white_list');
        const savingConfig = ref(false);
        const securityCheckPrompt = ref('');
        const originalPrompt = ref('');
        const piiEntityOptions = ref([]);

        // 提示词防御编辑状态
        const isEditingPrompt = ref(false);
        const showPromptPreviewModal = ref(false);
        const isDrawerMaximized = ref(false);
        const refreshingPrompt = ref(false);
        const resettingPrompt = ref(false);
        const editingPromptBackup = ref('');
        const guardrailConfig = ref({
            white_list: { enabled: false, patterns: [], patternsText: '' },
            black_list: { enabled: true },
            pii_detection: {
                input_enabled: false,
                output_enabled: false,
                input_entities: [],
                output_entities: [],
                input_action_mode: 'detect',
                output_action_mode: 'detect'
            },
            content_filter: {
                input_enabled: false,
                output_enabled: false,
                action_mode: 'block'
            },
            prompt_defense: { enabled: true }
        });

        // 保存原始配置，用于判断是否有修改
        const originalConfig = ref(null);

        // 监控面板抽屉状态
        const showDashboardDrawer = ref(false);
        const isDashboardMaximized = ref(false);

        // 监控面板状态
        const dashboardPeriod = ref('24h');
        const dashboardLoading = ref(false);
        const dashboardStats = ref({
            total_requests: 0,
            blocked_requests: 0,
            attack_requests: 0,
            safe_requests: 0,
            safety_rate: 100.0,
            guardrails_blocked: 0,
            llm_blocked: 0,
            pii_blocked: 0,
        });
        const dashboardLogs = ref([]);
        const logPage = ref(1);
        const logLimit = ref(20);
        const logTotal = ref(0);
        const logTotalPages = computed(() => Math.max(1, Math.ceil(logTotal.value / logLimit.value)));
        const logFilter = ref({ attack_type: '' });
        const hasMoreLogs = computed(() => logPage.value * logLimit.value < logTotal.value);

        // 图表实例引用
        const attackTypeChart = ref(null);
        const blockedByChart = ref(null);
        const trendChart = ref(null);
        let attackTypeChartInstance = null;
        let blockedByChartInstance = null;
        let trendChartInstance = null;
        let dashboardRefreshTimer = null;
        const attackTypeOptions = ref([]);

        const configMenus = [
            { key: 'white_list', label: '白名单放行', icon: '⚪' },
            { key: 'black_list', label: '黑名单过滤', icon: '⚫' },
            { key: 'content_filter', label: '内容过滤', icon: '🔍' },
            { key: 'pii_detection', label: '敏感信息检测', icon: '🔒' },
            { key: 'prompt_defense', label: '提示词防御', icon: '🛡️' }
        ];

        const piiCategories = computed(() => {
            const cats = new Set();
            piiEntityOptions.value.forEach(opt => cats.add(opt.category));
            return Array.from(cats);
        });

        const piiOptionsByCategory = computed(() => {
            const result = {};
            piiEntityOptions.value.forEach(opt => {
                if (!result[opt.category]) {
                    result[opt.category] = [];
                }
                result[opt.category].push(opt);
            });
            return result;
        });

        // 分页计算属性
        const whitelistTotalPages = computed(() => Math.max(1, Math.ceil(whitelistRules.value.length / whitelistPageSize)));
        const paginatedWhitelistRules = computed(() => whitelistRules.value.slice((whitelistPage.value - 1) * whitelistPageSize, whitelistPage.value * whitelistPageSize));

        const defaultRulesTotalPages = computed(() => Math.max(1, Math.ceil((guardrailsRules.value?.default_rules?.length || 0) / rulesPageSize)));
        const paginatedDefaultRules = computed(() => (guardrailsRules.value?.default_rules || []).slice((defaultRulesPage.value - 1) * rulesPageSize, defaultRulesPage.value * rulesPageSize));

        const customRulesTotalPages = computed(() => Math.max(1, Math.ceil((guardrailsRules.value?.custom_rules?.length || 0) / rulesPageSize)));
        const paginatedCustomRules = computed(() => (guardrailsRules.value?.custom_rules || []).slice((customRulesPage.value - 1) * rulesPageSize, customRulesPage.value * rulesPageSize));

        const contentFilterTotalPages = computed(() => Math.max(1, Math.ceil(contentFilterCategories.value.length / contentFilterPageSize)));
        const paginatedContentFilterCategories = computed(() => contentFilterCategories.value.slice((contentFilterPage.value - 1) * contentFilterPageSize, contentFilterPage.value * contentFilterPageSize));

        const systemCategories = computed(() => contentFilterCategories.value.filter(c => c.category_type === 'system'));
        const customCategories = computed(() => contentFilterCategories.value.filter(c => c.category_type === 'custom'));
        const systemCategoriesTotalPages = computed(() => Math.max(1, Math.ceil(systemCategories.value.length / contentFilterPageSize)));
        const paginatedSystemCategories = computed(() => systemCategories.value.slice((systemCategoriesPage.value - 1) * contentFilterPageSize, systemCategoriesPage.value * contentFilterPageSize));
        const customCategoriesTotalPages = computed(() => Math.max(1, Math.ceil(customCategories.value.length / contentFilterPageSize)));
        const paginatedCustomCategories = computed(() => customCategories.value.slice((customCategoriesPage.value - 1) * contentFilterPageSize, customCategoriesPage.value * contentFilterPageSize));

        // AbortController 用于取消请求
        let abortController = null;

        // 样本页签状态
        const activeSampleTab = ref('white');  // 默认显示正常样本

        
        // 计算属性
        const connectionStatusText = computed(() => {
            const statusMap = {
                connected: '已连接',
                connecting: '连接中',
                disconnected: '已断开'
            };
            return statusMap[connectionStatus.value] || '未知状态';
        });

        const successRate = computed(() => {
            if (totalMessages.value === 0) return 100;
            return Math.round(((totalMessages.value - blockedMessages.value) / totalMessages.value) * 100);
        });

        const securityLevel = computed(() => {
            const rate = successRate.value;
            if (rate >= 90) return 'high';
            if (rate >= 70) return 'medium';
            return 'low';
        });

        const securityTitle = computed(() => {
            const level = securityLevel.value;
            const titles = {
                high: '安全级别：高',
                medium: '安全级别：中',
                low: '安全级别：低'
            };
            return titles[level] || '安全级别：未知';
        });

        const securityDescription = computed(() => {
            const level = securityLevel.value;
            const descriptions = {
                high: '系统运行正常，安全防护有效',
                medium: '存在少量异常请求，建议关注',
                low: '检测到较多异常请求，需要检查'
            };
            return descriptions[level] || '安全状态未知';
        });

        const promptStats = computed(() => {
            const text = securityCheckPrompt.value || '';
            const sectionMatches = text.match(/^##\s+.+$/gm) || [];
            const attackMatches = text.match(/^-\s+.*(?:攻击|诱导|扮演|对话|陷阱|劫持|泄露|注入|绕过|越狱|预设|两难|对立).*/gm) || [];
            const exemptMatches = text.match(/^-\s+.*(?:业务|豁免|不违规|正常|不计入|优先判定).*/gm) || [];
            return {
                charCount: text.length,
                sectionCount: sectionMatches.length,
                attackCount: attackMatches.length,
                exemptCount: exemptMatches.length
            };
        });

        // 方法
        const scrollToBottom = () => {
            nextTick(() => {
                if (messagesContainer.value) {
                    messagesContainer.value.scrollTop = messagesContainer.value.scrollHeight;
                }
            });
        };

        // 调整textarea高度
        const adjustTextareaHeight = () => {
            nextTick(() => {
                const textarea = inputTextarea.value;
                if (!textarea) return;

                // 重置高度
                textarea.style.height = 'auto';

                // 计算基础高度（42px为最小高度）
                const minHeight = 42;
                // 最大高度：展开时为60vh，否则为168px（4行 * 42px）
                const maxHeight = isInputExpanded.value ?
                    (window.innerHeight * 0.6) - 40 : 168;

                // 计算内容高度
                const contentHeight = textarea.scrollHeight;

                // 设置高度
                const newHeight = Math.max(minHeight, Math.min(contentHeight, maxHeight));
                textarea.style.height = newHeight + 'px';

                // 设置滚动条
                textarea.style.overflowY = contentHeight > maxHeight ? 'auto' : 'hidden';

                // 计算行数：每行大约20px高度（包括padding和字体大小）
                // 当内容高度超过4行的高度时显示放大按钮
                const lineHeight = 20;
                const estimatedLines = Math.ceil(contentHeight / lineHeight);
                showExpandButton.value = !isInputExpanded.value && estimatedLines >= 4;
            });
        };

        // 键盘输入处理
        const onTextareaKeydown = (e) => {
            // Enter键发送消息，保持Shift+Enter换行
            if (e.key === 'Enter' && !e.shiftKey && !e.isComposing) {
                e.preventDefault();
                const message = inputMessage.value.trim();
                // 只有在有内容且不在加载状态时才发送
                if (message && !isLoading.value) {
                    sendMessage();
                }
            }
            // 调整高度
            setTimeout(adjustTextareaHeight, 0);
        };

        // 切换输入框展开状态
        const toggleInputExpand = () => {
            isInputExpanded.value = !isInputExpanded.value;
            nextTick(() => {
                adjustTextareaHeight();
                // 保持输入框焦点
                if (inputTextarea.value) {
                    inputTextarea.value.focus();
                }
            });
        };

        // ============ GuardRails规则管理方法 ============

        /*
         * 切换规则面板显示状态
         */
        const toggleGuardrailsPanel = () => {
            showGuardrailsPanel.value = !showGuardrailsPanel.value;
            // 如果打开面板且没有加载过规则，则自动加载
            if (showGuardrailsPanel.value && !guardrailsRules.value) {
                refreshGuardrailsRules(false); // 不显示通知
            }
        };

        /*
         * 检查是否是默认规则
         */
        const isDefaultRule = (ruleName) => {
            if (!guardrailsRules.value?.default_rules) return false;
            return guardrailsRules.value.default_rules.some(
                rule => rule.name === ruleName
            );
        };

        /*
         * 刷新规则列表
         */
        const refreshGuardrailsRules = async (showNotify = true) => {
            try {
                loadingRules.value = true;
                const response = await fetch('/guardrails/rules');

                if (!response.ok) {
                    throw new Error(`HTTP error! status: ${response.status}`);
                }

                const data = await response.json();
                guardrailsRules.value = data;
                defaultRulesPage.value = 1;
                customRulesPage.value = 1;
                if (showNotify) {
                    showNotification('规则列表刷新成功', 'success');
                }
                return data;
            } catch (error) {
                console.error('刷新规则列表失败:', error);
                if (showNotify) {
                    showNotification('刷新规则列表失败', 'error');
                }
                return null;
            } finally {
                loadingRules.value = false;
            }
        };

        /*
         * 打开添加规则模态框
         */
        const openAddRuleModal = () => {
            newRule.value = { name: '', description: '', patternsText: '', responseMessage: '' };
            showAddRuleModal.value = true;
        };

        /*
         * 关闭添加规则模态框
         */
        const closeAddRuleModal = () => {
            showAddRuleModal.value = false;
            newRule.value = { name: '', description: '', patternsText: '', responseMessage: '' };
        };

        /*
         * 保存新规则
         */
        const saveNewRule = async () => {
            if (!newRule.value.name.trim()) {
                showNotification('请输入规则名称', 'warning');
                return;
            }

            // 解析模式文本（每行一个）
            const patterns = newRule.value.patternsText
                .split('\n')
                .map(p => p.trim())
                .filter(p => p.length > 0);

            if (patterns.length === 0) {
                showNotification('请至少输入一个正则表达式模式', 'warning');
                return;
            }

            const success = await addGuardrailRule(
                newRule.value.name.trim(),
                patterns,
                newRule.value.description,
                newRule.value.responseMessage || null
            );
            if (success) {
                closeAddRuleModal();
            }
        };

        /*
         * 打开编辑规则模态框
         */
        const openEditRuleModal = (rule) => {
            editingRule.value = {
                name: rule.name,
                description: rule.description,
                patternsText: (rule.patterns || []).join('\n'),
                responseMessage: rule.response_message || '',
                originalPatterns: rule.patterns || []
            };
            showEditRuleModal.value = true;
        };

        /*
         * 关闭编辑规则模态框
         */
        const closeEditRuleModal = () => {
            showEditRuleModal.value = false;
            editingRule.value = { name: '', description: '', patternsText: '', responseMessage: '', originalPatterns: [] };
        };

        /*
         * 保存编辑的规则
         */
        const saveEditedRule = async () => {
            // 解析模式文本（每行一个）
            const patterns = editingRule.value.patternsText
                .split('\n')
                .map(p => p.trim())
                .filter(p => p.length > 0);

            if (patterns.length === 0) {
                showNotification('请至少输入一个正则表达式模式', 'warning');
                return;
            }

            const success = await updateGuardrailRule(
                editingRule.value.name,
                patterns,
                editingRule.value.description,
                editingRule.value.responseMessage || null
            );
            if (success) {
                closeEditRuleModal();
            }
        };

        /*
         * 打开查看所有模式模态框
         */
        const openPatternsModal = (rule) => {
            viewingPatternsRule.value = rule;
            showPatternsModal.value = true;
        };

        /*
         * 关闭查看所有模式模态框
         */
        const closePatternsModal = () => {
            showPatternsModal.value = false;
            viewingPatternsRule.value = null;
        };

        /*
         * 打开查看规则详情模态框（只读）
         */
        const openViewRuleModal = (rule, isDefault = false) => {
            viewingRule.value = {
                name: rule.name,
                description: rule.description || '',
                patterns: rule.patterns || [],
                patternsText: (rule.patterns || []).join('\n'),
                responseMessage: rule.response_message || '',
                isDefault: isDefault
            };
            showViewRuleModal.value = true;
        };

        /*
         * 关闭查看规则详情模态框
         */
        const closeViewRuleModal = () => {
            showViewRuleModal.value = false;
            viewingRule.value = { name: '', description: '', patterns: [], patternsText: '', responseMessage: '', isDefault: false };
        };

        /*
         * 添加新规则
         */
        const addGuardrailRule = async (ruleName, patterns, description, responseMessage) => {
            try {
                const response = await fetch(`/guardrails/rules/${ruleName}`, {
                    method: 'POST',
                    headers: {
                        'Content-Type': 'application/json',
                    },
                    body: JSON.stringify({ patterns, is_new: true, description, response_message: responseMessage })
                });

                const data = await response.json();
                if (response.ok) {
                    if (data.status === 'success') {
                        showNotification(data.message, 'success');
                        await refreshGuardrailsRules();
                        return true;
                    } else {
                        showNotification(data.message, 'warning');
                        return false;
                    }
                } else {
                    throw new Error(data.detail || '添加规则失败');
                }
            } catch (error) {
                console.error('添加规则失败:', error);
                showNotification(`添加规则失败: ${error.message}`, 'error');
                return false;
            }
        };

        /*
         * 更新规则
         */
        const updateGuardrailRule = async (ruleName, patterns, description, responseMessage) => {
            try {
                const response = await fetch(`/guardrails/rules/${ruleName}`, {
                    method: 'POST',
                    headers: {
                        'Content-Type': 'application/json',
                    },
                    body: JSON.stringify({ patterns, is_new: false, description, response_message: responseMessage })
                });

                const data = await response.json();
                if (response.ok) {
                    if (data.status === 'success') {
                        showNotification(data.message, 'success');
                        await refreshGuardrailsRules();
                        return true;
                    } else {
                        showNotification(data.message, 'warning');
                        return false;
                    }
                } else {
                    throw new Error(data.detail || '更新规则失败');
                }
            } catch (error) {
                console.error('更新规则失败:', error);
                showNotification(`更新规则失败: ${error.message}`, 'error');
                return false;
            }
        };

        /*
         * 删除规则
         */
        const deleteGuardrailRule = async (ruleName) => {
            // 检查是否是默认规则（不能删除）
            if (isDefaultRule(ruleName)) {
                showNotification('不能删除默认规则，但可以编辑其模式', 'warning');
                return;
            }

            if (!confirm(`确定要删除规则 "${ruleName}" 吗？`)) {
                return;
            }

            try {
                const response = await fetch(`/guardrails/rules/${ruleName}`, {
                    method: 'DELETE',
                });

                const data = await response.json();
                if (response.ok) {
                    showNotification(data.message, 'success');
                    await refreshGuardrailsRules();
                    return true;
                } else {
                    throw new Error(data.detail || '删除规则失败');
                }
            } catch (error) {
                console.error('删除规则失败:', error);
                showNotification(`删除规则失败: ${error.message}`, 'error');
                return false;
            }
        };

        /*
         * 重置规则为默认值
         */
        const resetGuardrailsRules = async () => {
            // 显示确认对话框
            confirmModalConfig.value = {
                title: '重置默认规则',
                message: '确定要重置所有默认规则为初始默认值吗？此操作将删除数据库中的默认规则并重新从配置文件下发，且不可恢复！',
                type: 'warning',
                onConfirm: async () => {
                    showConfirmModal.value = false;
                    await executeReset();
                }
            };
            showConfirmModal.value = true;
        };

        const executeReset = async () => {
            try {
                resettingRules.value = true;
                const response = await fetch('/guardrails/rules/reset', {
                    method: 'POST',
                    headers: {
                        'Content-Type': 'application/json',
                    },
                    body: JSON.stringify({})
                });

                const data = await response.json();
                if (response.ok) {
                    showNotification(data.message, 'success');
                    await refreshGuardrailsRules();
                    return true;
                } else {
                    throw new Error(data.detail || '重置规则失败');
                }
            } catch (error) {
                console.error('重置规则失败:', error);
                showNotification(`重置规则失败: ${error.message}`, 'error');
                return false;
            } finally {
                resettingRules.value = false;
            }
        };

        /*
         * 重新加载规则
         */
        const reloadGuardrailsRules = async () => {
            try {
                reloadingRules.value = true;
                const response = await fetch('/guardrails/rules/reload', {
                    method: 'POST',
                    headers: {
                        'Content-Type': 'application/json',
                    },
                    body: JSON.stringify({})
                });

                const data = await response.json();
                if (response.ok) {
                    showNotification(data.message, 'success');
                    return true;
                } else {
                    throw new Error(data.detail || '重新加载规则失败');
                }
            } catch (error) {
                console.error('重新加载规则失败:', error);
                showNotification(`重新加载规则失败: ${error.message}`, 'error');
                return false;
            } finally {
                reloadingRules.value = false;
            }
        };

        const formatTime = () => {
            const now = new Date();
            return `${now.getHours().toString().padStart(2, '0')}:${now.getMinutes().toString().padStart(2, '0')}`;
        };

        const loadSamples = async () => {
            try {
                const response = await fetch('/samples');
                if (!response.ok) throw new Error('加载样本失败');

                const data = await response.json();
                whiteSamples.value = data.white_samples || [];
                blackSamples.value = data.black_samples || [];
            } catch (error) {
                console.error('加载样本时出错:', error);
                showNotification('无法加载测试用例，请检查后端连接', 'error');
            }
        };

        const loadStatistics = async () => {
            try {
                const response = await fetch('/statistics');
                if (!response.ok) throw new Error('加载统计数据失败');

                const data = await response.json();
                totalMessages.value = data.total_requests || 0;
                blockedMessages.value = data.blocked_requests || 0;
                guardrailsBlocked.value = data.guardrails_blocked || 0;
                llmBlocked.value = data.llm_blocked || 0;
                piiBlocked.value = data.pii_blocked || 0;
                contentFilterBlocked.value = data.content_filter_blocked || 0;
            } catch (error) {
                console.error('加载统计数据时出错:', error);
                // 如果加载失败，保持当前值（可能是网络问题）
            }
        };

        const selectSample = (text, type) => {
            inputMessage.value = text;
        };

        const sendMessage = async () => {
            const message = inputMessage.value.trim();
            if (!message || isLoading.value) return;

            // 重置输入
            inputMessage.value = '';
            adjustTextareaHeight();

            // 添加用户消息
            addMessage(message, 'user', '用户');

            // 设置加载状态
            isLoading.value = true;

            // 创建新的 AbortController
            abortController = new AbortController();

            try {
                // 发送请求到后端
                const response = await fetch('/chat/stream', {
                    method: 'POST',
                    headers: {
                        'Content-Type': 'application/json',
                    },
                    body: JSON.stringify({
                        message: message,
                        conversation_id: currentConversationId.value
                    }),
                    signal: abortController.signal
                });

                if (!response.ok) {
                    throw new Error(`HTTP error! status: ${response.status}`);
                }

                // 创建占位消息用于流式更新（显示加载状态）
                const assistantMessageIndex = messages.value.length;
                addMessage('', 'assistant', '安全助手', true);

                // 处理流式响应
                const reader = response.body.getReader();
                const decoder = new TextDecoder();
                let assistantResponse = '';
                let isRejected = false;
                let rejectInfo = null;
                let doneBuffer = '';

                while (true) {
                    const { done, value } = await reader.read();
                    if (done) break;

                    const chunk = decoder.decode(value);
                    const lines = chunk.split('\n');

                    for (const line of lines) {
                        // SSE 行可能以 \r\n 结尾，需要去除末尾的 \r
                        const trimmedLine = line.replace(/\r$/, '');
                        if (trimmedLine.startsWith('data: ')) {
                            const data = trimmedLine.slice(6);

                            // 处理 [DONE]，考虑跨 chunk 的情况
                            if (data === '[DONE]') {
                                doneBuffer = '';
                                break;
                            }
                            if ('[DONE]'.startsWith(doneBuffer + data) && data !== '') {
                                doneBuffer += data;
                                continue;
                            }
                            doneBuffer = '';

                            if (data.startsWith('[PII_INFO]')) {
                                try {
                                    const piiInfo = JSON.parse(data.slice(10));
                                    const entityNames = piiInfo.entities.join(', ');
                                    showNotification(`检测到敏感信息: ${entityNames}（共${piiInfo.count}处）`, 'warning');
                                } catch (e) {
                                    console.error('解析PII信息失败:', e);
                                }
                                continue;
                            }

                            // 尝试将每个 data 块解析为 JSON（拦截响应通常是独立的一个 JSON 事件）
                            if (!isRejected && data.trimStart().startsWith('{')) {
                                try {
                                    const parsed = JSON.parse(data);
                                    if (parsed && parsed.status === 'reject') {
                                        isRejected = true;
                                        rejectInfo = parsed;
                                        continue;
                                    }
                                } catch (e) {
                                    // 不是 JSON，按普通文本处理
                                }
                            }

                            if (!isRejected) {
                                assistantResponse += data;
                                messages.value[assistantMessageIndex].text = assistantResponse;
                                if (assistantResponse) {
                                    messages.value[assistantMessageIndex].isLoading = false;
                                }
                                scrollToBottom();
                                await new Promise(r => setTimeout(r, 10));
                            }
                        }
                    }
                }

                // 关闭加载状态
                messages.value[assistantMessageIndex].isLoading = false;

                if (isRejected && rejectInfo) {
                    // 被拦截，显示友好的拒绝消息
                    const defaultMessage = '我无法回答此类问题。若您有其他问题或需要办理业务，我很乐意为您效劳。';
                    const customMessage = rejectInfo.response_message;
                    messages.value[assistantMessageIndex].text = customMessage || defaultMessage;
                    messages.value[assistantMessageIndex].type = 'blocked';

                    let rejectionMessage;
                    if (rejectInfo.blocked_by === 'guardrails') {
                        const rules = rejectInfo.violated_rules || [];
                        const ruleNames = rules.length > 0 ? rules.join(', ') : '未知规则';
                        rejectionMessage = `输入被黑名单过滤拦截，匹配规则：${ruleNames}`;
                    } else if (rejectInfo.blocked_by === 'llm') {
                        rejectionMessage = '输入被提示词防御拦截';
                    } else if (rejectInfo.blocked_by === 'content_filter') {
                        const rules = rejectInfo.violated_rules || [];
                        const ruleNames = rules.length > 0 ? rules.join(', ') : '未知类别';
                        const isOutput = (rejectInfo.detected_issues || '').includes('输出');
                        const direction = isOutput ? '输出' : '输入';
                        rejectionMessage = `${direction}被内容过滤拦截，触发类别：${ruleNames}`;
                    } else if (rejectInfo.blocked_by === 'pii') {
                        const rules = rejectInfo.violated_rules || [];
                        const ruleNames = rules.length > 0 ? rules.join(', ') : '未知实体';
                        const isOutput = (rejectInfo.detected_issues || '').includes('output');
                        const direction = isOutput ? '输出' : '输入';
                        rejectionMessage = `${direction}被敏感信息检测拦截，匹配实体：${ruleNames}`;
                    } else {
                        rejectionMessage = '请求被安全系统拦截';
                    }

                    lastBlockedTime.value = formatTime();
                    showNotification(rejectionMessage, 'warning');
                } else {
                    // 非拦截情况，检查是否包含安全警告文本
                    if (assistantResponse.includes('安全警告：检测到非法输入')) {
                        messages.value[assistantMessageIndex].type = 'blocked';
                        messages.value[assistantMessageIndex].text = '我无法回答此类问题。若您有其他问题或需要办理业务，我很乐意为您效劳。';
                        lastBlockedTime.value = formatTime();
                        showNotification('消息被安全系统拦截', 'warning');
                    } else {
                        messages.value[assistantMessageIndex].text = assistantResponse;
                        showNotification('消息处理完成', 'success');
                    }
                }

                // 刷新统计数据
                await loadStatistics();

            } catch (error) {
                if (error.name === 'AbortError') {
                    // 用户主动取消
                    showNotification('已停止响应', 'info');
                } else {
                    console.error('发送消息时出错:', error);
                    addMessage('抱歉，处理您的请求时出现错误。请稍后再试。', 'assistant', '系统');
                    showNotification('网络错误，请检查连接', 'error');
                }
            } finally {
                isLoading.value = false;
                abortController = null;
                scrollToBottom();
            }
        };

        const stopGeneration = () => {
            if (abortController) {
                abortController.abort();

                // 找到正在加载的消息并更新状态
                const loadingMessage = messages.value.find(m => m.isLoading);
                if (loadingMessage) {
                    if (!loadingMessage.text) {
                        // 如果没有内容，更新为已取消的提示
                        loadingMessage.text = '生成已停止';
                        loadingMessage.isLoading = false;
                    } else {
                        // 如果有部分内容，只关闭加载状态
                        loadingMessage.isLoading = false;
                    }
                }
            }
        };

        const addMessage = (text, type, sender, isLoading = false) => {
            const message = {
                text: text,
                type: type,
                sender: sender,
                time: formatTime(),
                isLoading: isLoading
            };

            messages.value.push(message);
            scrollToBottom();
        };

        const renderMarkdown = (text) => {
            if (!text) return '';
            // 使用marked库渲染Markdown
            if (typeof marked !== 'undefined') {
                // 配置marked选项
                marked.setOptions({
                    breaks: true,  // 支持换行
                    gfm: true      // 支持GitHub风格的Markdown
                });
                // 先转义 PII 标签（如 <DATE_TIME>），避免被当作 HTML 标签解析后消失
                const escaped = text.replace(/<([A-Z_]+)>/g, '&lt;$1&gt;');
                return marked.parse(escaped);
            }
            // 如果marked库未加载，返回原始文本
            return text;
        };

        const clearChat = () => {
            if (messages.value.length === 0) return;

            if (confirm('确定要清空聊天记录吗？')) {
                messages.value = [];
                showNotification('聊天记录已清空', 'info');
            }
        };

        const switchSampleTab = (tab) => {
            activeSampleTab.value = tab;
        };

        const showNotification = (message, type = 'info') => {
            const icons = {
                success: '✅',
                error: '❌',
                warning: '⚠️',
                info: 'ℹ️'
            };
            showToast(message, type);
        };

        const showToast = (message, type = 'info') => {
            const container = document.getElementById('toast-container');
            if (!container) return;

            const toast = document.createElement('div');
            toast.className = `toast toast-${type}`;

            const icons = {
                success: '✓',
                error: '✕',
                warning: '⚠',
                info: 'ℹ'
            };

            toast.innerHTML = `
                <span class="toast-icon">${icons[type] || 'ℹ'}</span>
                <span class="toast-message">${message}</span>
            `;

            container.appendChild(toast);

            // 动画显示
            setTimeout(() => toast.classList.add('show'), 10);

            // 3秒后移除
            setTimeout(() => {
                toast.classList.remove('show');
                setTimeout(() => toast.remove(), 300);
            }, 3000);
        };

        const checkConnection = async () => {
            try {
                const response = await fetch('/health', {
                    method: 'GET',
                    signal: AbortSignal.timeout(5000) // 5秒超时
                });

                if (response.ok) {
                    connectionStatus.value = 'connected';
                    return true;
                } else {
                    connectionStatus.value = 'disconnected';
                    return false;
                }
            } catch (error) {
                // 只在真正断开时才更新状态，避免临时网络波动
                if (error.name !== 'AbortError' && error.name !== 'TimeoutError') {
                    connectionStatus.value = 'disconnected';
                }
                return false;
            }
        };

        const initializeWelcomeMessage = () => {
            if (messages.value.length === 0) {
                addMessage('您好！我是安全防护智能体。我具有多重安全防护机制，可以检测并阻止恶意请求。您可以使用左侧的测试用例来体验安全防护功能。', 'assistant', '安全助手');
            }
        };

        // 初始化
        onMounted(async () => {
            // 检查连接
            await checkConnection();

            // 加载样本数据
            await loadSamples();

            // 加载统计数据
            await loadStatistics();

            // 加载内容过滤配置（用于首页警告条状态）
            await loadContentFilterConfig();

            // 加载规则数据（如果规则面板默认展开）
            if (showGuardrailsPanel.value) {
                await refreshGuardrailsRules(false); // 不显示通知
            }

            // 初始化欢迎消息
            initializeWelcomeMessage();

            // 定期检查连接状态
            setInterval(checkConnection, 30000);
        });

        // 监视输入内容变化以调整高度
        watch(inputMessage, () => {
            setTimeout(adjustTextareaHeight, 0);
        });

        // 监视展开状态变化
        watch(isInputExpanded, adjustTextareaHeight);

        // ============ 安全护栏配置抽屉方法 ============

        const openConfigDrawer = async () => {
            showConfigDrawer.value = true;
            await loadGuardrailConfig();
            await loadContentFilterConfig();
            await loadContentFilterCategories();
            await loadPIIConfig();
            await loadPromptDefenseConfig();
            await refreshGuardrailsRules(false);
            await refreshWhitelistRules(false);
        };

        const closeConfigDrawer = () => {
            showConfigDrawer.value = false;
        };

        const loadGuardrailConfig = async () => {
            try {
                const response = await fetch('/config/guardrail');
                if (!response.ok) throw new Error('加载配置失败');
                const data = await response.json();

                // 保存PII实体选项
                piiEntityOptions.value = data.pii_entity_options || [];

                // 更新Guardrail相关配置字段（保留PII配置）
                const config = data.config || {};
                guardrailConfig.value.white_list = {
                    enabled: config.white_list?.enabled ?? false
                };
                guardrailConfig.value.black_list = {
                    enabled: config.black_list?.enabled ?? true
                };

                // 保存原始值，用于判断是否有修改
                if (!originalConfig.value) originalConfig.value = {};
                originalConfig.value.white_list = {
                    enabled: guardrailConfig.value.white_list.enabled
                };
                originalConfig.value.black_list = {
                    enabled: guardrailConfig.value.black_list.enabled
                };
            } catch (error) {
                console.error('加载安全护栏配置失败:', error);
                showNotification('加载配置失败', 'error');
            }
        };

        const loadPromptDefenseConfig = async () => {
            try {
                const response = await fetch('/config/prompt_defense');
                if (!response.ok) throw new Error('加载提示词防御配置失败');
                const data = await response.json();

                securityCheckPrompt.value = data.prompt_content || '';
                originalPrompt.value = securityCheckPrompt.value;
                guardrailConfig.value.prompt_defense = {
                    enabled: data.enabled ?? true
                };
                if (!originalConfig.value) originalConfig.value = {};
                originalConfig.value.prompt_defense = {
                    enabled: guardrailConfig.value.prompt_defense.enabled
                };
            } catch (error) {
                console.error('加载提示词防御配置失败:', error);
                showNotification('加载提示词防御配置失败', 'error');
            }
        };

        const editPrompt = () => {
            editingPromptBackup.value = securityCheckPrompt.value;
            isEditingPrompt.value = true;
        };

        const cancelEditPrompt = () => {
            securityCheckPrompt.value = editingPromptBackup.value;
            isEditingPrompt.value = false;
        };

        const savePrompt = async () => {
            if (securityCheckPrompt.value === editingPromptBackup.value) {
                showNotification('提示词内容未发生变化，无需保存', 'info');
                isEditingPrompt.value = false;
                return;
            }
            try {
                savingConfig.value = true;
                const response = await fetch('/config/prompt_defense', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({
                        enabled: guardrailConfig.value.prompt_defense.enabled,
                        prompt_content: securityCheckPrompt.value
                    })
                });
                const data = await response.json();
                if (response.ok && data.status === 'success') {
                    originalPrompt.value = securityCheckPrompt.value;
                    showNotification('提示词已保存并生效', 'success');
                    isEditingPrompt.value = false;
                } else {
                    throw new Error(data.detail || '保存失败');
                }
            } catch (error) {
                console.error('保存提示词失败:', error);
                showNotification(`保存提示词失败: ${error.message}`, 'error');
            } finally {
                savingConfig.value = false;
            }
        };

        const refreshPrompt = async () => {
            try {
                refreshingPrompt.value = true;
                await loadPromptDefenseConfig();
                showNotification('提示词已刷新', 'success');
            } catch (error) {
                console.error('刷新提示词失败:', error);
                showNotification('刷新提示词失败', 'error');
            } finally {
                refreshingPrompt.value = false;
            }
        };

        const resetPrompt = async () => {
            confirmModalConfig.value = {
                title: '重置提示词防御',
                message: '确定要重置提示词为系统默认值吗？当前自定义内容将丢失，此操作不可恢复！',
                type: 'warning',
                onConfirm: async () => {
                    showConfirmModal.value = false;
                    try {
                        resettingPrompt.value = true;
                        const response = await fetch('/config/prompt_defense/reset', {
                            method: 'POST',
                            headers: { 'Content-Type': 'application/json' }
                        });
                        const data = await response.json();
                        if (response.ok && data.status === 'success') {
                            securityCheckPrompt.value = data.config.prompt_content || '';
                            originalPrompt.value = securityCheckPrompt.value;
                            showNotification(data.message, 'success');
                            isEditingPrompt.value = false;
                        } else {
                            throw new Error(data.detail || '重置失败');
                        }
                    } catch (error) {
                        console.error('重置提示词失败:', error);
                        showNotification(`重置提示词失败: ${error.message}`, 'error');
                    } finally {
                        resettingPrompt.value = false;
                    }
                }
            };
            showConfirmModal.value = true;
        };

        const previewPrompt = () => {
            showPromptPreviewModal.value = true;
        };

        const closePromptPreview = () => {
            showPromptPreviewModal.value = false;
        };

        const toggleDrawerMaximize = () => {
            isDrawerMaximized.value = !isDrawerMaximized.value;
        };

        const loadPIIConfig = async () => {
            try {
                const response = await fetch('/config/pii');
                if (!response.ok) throw new Error('加载PII配置失败');
                const data = await response.json();

                // 更新PII配置
                guardrailConfig.value.pii_detection = {
                    input_enabled: data.input_enabled ?? false,
                    output_enabled: data.output_enabled ?? false,
                    input_entities: data.input_entities || [],
                    output_entities: data.output_entities || [],
                    input_action_mode: data.input_action_mode || 'detect',
                    output_action_mode: data.output_action_mode || 'detect'
                };

                // 保存原始值
                if (!originalConfig.value) originalConfig.value = {};
                originalConfig.value.pii_detection = {
                    input_enabled: guardrailConfig.value.pii_detection.input_enabled,
                    output_enabled: guardrailConfig.value.pii_detection.output_enabled,
                    input_entities: [...guardrailConfig.value.pii_detection.input_entities],
                    output_entities: [...guardrailConfig.value.pii_detection.output_entities],
                    input_action_mode: guardrailConfig.value.pii_detection.input_action_mode,
                    output_action_mode: guardrailConfig.value.pii_detection.output_action_mode
                };
            } catch (error) {
                console.error('加载PII配置失败:', error);
                showNotification('加载PII配置失败', 'error');
            }
        };

        const loadContentFilterConfig = async () => {
            try {
                const response = await fetch('/config/content_filter');
                if (!response.ok) throw new Error('加载内容过滤配置失败');
                const data = await response.json();

                guardrailConfig.value.content_filter = {
                    input_enabled: data.input_enabled ?? true,
                    output_enabled: data.output_enabled ?? true,
                    action_mode: data.action_mode || 'block'
                };

                if (!originalConfig.value) originalConfig.value = {};
                originalConfig.value.content_filter = {
                    input_enabled: guardrailConfig.value.content_filter.input_enabled,
                    output_enabled: guardrailConfig.value.content_filter.output_enabled,
                    action_mode: guardrailConfig.value.content_filter.action_mode
                };
            } catch (error) {
                console.error('加载内容过滤配置失败:', error);
                showNotification('加载内容过滤配置失败', 'error');
            }
        };

        const saveGuardrailConfig = async () => {
            try {
                savingConfig.value = true;
                let hasModified = false;

                // 判断Guardrail（白名单/黑名单）是否有修改
                // 白名单只保存 enabled 开关，规则通过独立 API 管理
                const whiteChanged = !originalConfig.value?.white_list
                    || originalConfig.value.white_list.enabled !== guardrailConfig.value.white_list.enabled;
                const blackChanged = !originalConfig.value?.black_list
                    || originalConfig.value.black_list.enabled !== guardrailConfig.value.black_list.enabled;

                if (whiteChanged || blackChanged) {
                    const guardrailPayload = {
                        white_list: {
                            enabled: guardrailConfig.value.white_list.enabled
                        },
                        black_list: {
                            enabled: guardrailConfig.value.black_list.enabled
                        }
                    };

                    const guardrailResponse = await fetch('/config/guardrail', {
                        method: 'POST',
                        headers: { 'Content-Type': 'application/json' },
                        body: JSON.stringify(guardrailPayload)
                    });

                    const guardrailData = await guardrailResponse.json();
                    if (!guardrailResponse.ok || guardrailData.status !== 'success') {
                        throw new Error(guardrailData.detail || '保存Guardrail配置失败');
                    }
                    if (!originalConfig.value) originalConfig.value = {};
                    originalConfig.value.white_list = { enabled: guardrailConfig.value.white_list.enabled };
                    originalConfig.value.black_list = { enabled: guardrailConfig.value.black_list.enabled };
                    hasModified = true;
                }

                // 判断PII是否有修改
                const piiChanged = !originalConfig.value?.pii_detection
                    || originalConfig.value.pii_detection.input_enabled !== guardrailConfig.value.pii_detection.input_enabled
                    || originalConfig.value.pii_detection.output_enabled !== guardrailConfig.value.pii_detection.output_enabled
                    || originalConfig.value.pii_detection.input_action_mode !== guardrailConfig.value.pii_detection.input_action_mode
                    || originalConfig.value.pii_detection.output_action_mode !== guardrailConfig.value.pii_detection.output_action_mode
                    || JSON.stringify(originalConfig.value.pii_detection.input_entities) !== JSON.stringify(guardrailConfig.value.pii_detection.input_entities)
                    || JSON.stringify(originalConfig.value.pii_detection.output_entities) !== JSON.stringify(guardrailConfig.value.pii_detection.output_entities);

                if (piiChanged) {
                    const piiPayload = {
                        input_enabled: guardrailConfig.value.pii_detection.input_enabled,
                        output_enabled: guardrailConfig.value.pii_detection.output_enabled,
                        input_entities: guardrailConfig.value.pii_detection.input_entities,
                        output_entities: guardrailConfig.value.pii_detection.output_entities,
                        input_action_mode: guardrailConfig.value.pii_detection.input_action_mode || 'detect',
                        output_action_mode: guardrailConfig.value.pii_detection.output_action_mode || 'detect'
                    };

                    const piiResponse = await fetch('/config/pii', {
                        method: 'POST',
                        headers: { 'Content-Type': 'application/json' },
                        body: JSON.stringify(piiPayload)
                    });

                    const piiData = await piiResponse.json();
                    if (!piiResponse.ok || piiData.status !== 'success') {
                        throw new Error(piiData.detail || '保存PII配置失败');
                    }
                    if (!originalConfig.value) originalConfig.value = {};
                    originalConfig.value.pii_detection = {
                        input_enabled: guardrailConfig.value.pii_detection.input_enabled,
                        output_enabled: guardrailConfig.value.pii_detection.output_enabled,
                        input_entities: [...guardrailConfig.value.pii_detection.input_entities],
                        output_entities: [...guardrailConfig.value.pii_detection.output_entities],
                        input_action_mode: guardrailConfig.value.pii_detection.input_action_mode,
                        output_action_mode: guardrailConfig.value.pii_detection.output_action_mode
                    };
                    hasModified = true;
                }

                // 判断内容过滤是否有修改
                const contentFilterChanged = !originalConfig.value?.content_filter
                    || originalConfig.value.content_filter.input_enabled !== guardrailConfig.value.content_filter.input_enabled
                    || originalConfig.value.content_filter.output_enabled !== guardrailConfig.value.content_filter.output_enabled
                    || originalConfig.value.content_filter.action_mode !== guardrailConfig.value.content_filter.action_mode;

                if (contentFilterChanged) {
                    const contentFilterPayload = {
                        input_enabled: guardrailConfig.value.content_filter.input_enabled,
                        output_enabled: guardrailConfig.value.content_filter.output_enabled,
                        action_mode: guardrailConfig.value.content_filter.action_mode || 'block'
                    };

                    const contentFilterResponse = await fetch('/config/content_filter', {
                        method: 'POST',
                        headers: { 'Content-Type': 'application/json' },
                        body: JSON.stringify(contentFilterPayload)
                    });

                    const contentFilterData = await contentFilterResponse.json();
                    if (!contentFilterResponse.ok || contentFilterData.status !== 'success') {
                        throw new Error(contentFilterData.detail || '保存内容过滤配置失败');
                    }
                    if (!originalConfig.value) originalConfig.value = {};
                    originalConfig.value.content_filter = {
                        input_enabled: guardrailConfig.value.content_filter.input_enabled,
                        output_enabled: guardrailConfig.value.content_filter.output_enabled,
                        action_mode: guardrailConfig.value.content_filter.action_mode
                    };
                    hasModified = true;

                    // 内容过滤输出检测开启警告
                    if (guardrailConfig.value.content_filter.output_enabled) {
                        showNotification('内容过滤输出检测已开启，流式响应将切换为伪流式模式，首字节返回时间可能显著增加', 'warning');
                    }
                }

                // 判断提示词防御是否有修改
                const promptChanged = !originalConfig.value?.prompt_defense
                    || originalConfig.value.prompt_defense.enabled !== guardrailConfig.value.prompt_defense.enabled
                    || originalPrompt.value !== securityCheckPrompt.value;

                if (promptChanged) {
                    const promptDefensePayload = {
                        enabled: guardrailConfig.value.prompt_defense.enabled,
                        prompt_content: securityCheckPrompt.value
                    };

                    const promptDefenseResponse = await fetch('/config/prompt_defense', {
                        method: 'POST',
                        headers: { 'Content-Type': 'application/json' },
                        body: JSON.stringify(promptDefensePayload)
                    });

                    const promptDefenseData = await promptDefenseResponse.json();
                    if (!promptDefenseResponse.ok || promptDefenseData.status !== 'success') {
                        throw new Error(promptDefenseData.detail || '保存提示词防御配置失败');
                    }
                    if (!originalConfig.value) originalConfig.value = {};
                    originalConfig.value.prompt_defense = {
                        enabled: guardrailConfig.value.prompt_defense.enabled
                    };
                    originalPrompt.value = securityCheckPrompt.value;
                    hasModified = true;
                }

                if (hasModified) {
                    showNotification('配置已保存并生效', 'success');
                } else {
                    showNotification('没有修改需要保存', 'info');
                }
                closeConfigDrawer();
            } catch (error) {
                console.error('保存配置失败:', error);
                showNotification(`保存配置失败: ${error.message}`, 'error');
            } finally {
                savingConfig.value = false;
            }
        };

        const selectAllInputPII = () => {
            guardrailConfig.value.pii_detection.input_entities = piiEntityOptions.value.map(opt => opt.value);
        };

        const deselectAllInputPII = () => {
            guardrailConfig.value.pii_detection.input_entities = [];
        };

        const selectAllOutputPII = () => {
            guardrailConfig.value.pii_detection.output_entities = piiEntityOptions.value.map(opt => opt.value);
        };

        const deselectAllOutputPII = () => {
            guardrailConfig.value.pii_detection.output_entities = [];
        };

        const switchRuleTab = (tab) => { activeRuleTab.value = tab; };
        const switchPIITab = (tab) => { activePIITab.value = tab; };

        // 分页方法
        const prevWhitelistPage = () => { if (whitelistPage.value > 1) whitelistPage.value--; };
        const nextWhitelistPage = () => { if (whitelistPage.value < whitelistTotalPages.value) whitelistPage.value++; };

        const prevDefaultRulesPage = () => { if (defaultRulesPage.value > 1) defaultRulesPage.value--; };
        const nextDefaultRulesPage = () => { if (defaultRulesPage.value < defaultRulesTotalPages.value) defaultRulesPage.value++; };

        const prevCustomRulesPage = () => { if (customRulesPage.value > 1) customRulesPage.value--; };
        const nextCustomRulesPage = () => { if (customRulesPage.value < customRulesTotalPages.value) customRulesPage.value++; };

        const prevContentFilterPage = () => { if (contentFilterPage.value > 1) contentFilterPage.value--; };
        const nextContentFilterPage = () => { if (contentFilterPage.value < contentFilterTotalPages.value) contentFilterPage.value++; };

        const switchContentFilterTab = (tab) => { activeContentFilterTab.value = tab; };
        const switchContentFilterControlTab = (tab) => { activeContentFilterControlTab.value = tab; };
        const prevSystemCategoriesPage = () => { if (systemCategoriesPage.value > 1) systemCategoriesPage.value--; };
        const nextSystemCategoriesPage = () => { if (systemCategoriesPage.value < systemCategoriesTotalPages.value) systemCategoriesPage.value++; };
        const prevCustomCategoriesPage = () => { if (customCategoriesPage.value > 1) customCategoriesPage.value--; };
        const nextCustomCategoriesPage = () => { if (customCategoriesPage.value < customCategoriesTotalPages.value) customCategoriesPage.value++; };

        // ============ 内容过滤管理方法 ============
        const loadContentFilterCategories = async (showNotify = false) => {
            try {
                loadingContentFilter.value = true;
                const response = await fetch('/content_filter/categories');
                if (!response.ok) throw new Error('加载内容过滤分类失败');
                const data = await response.json();
                contentFilterCategories.value = data.categories || [];
                contentFilterPage.value = 1;
                if (showNotify) {
                    showNotification('分类列表刷新成功', 'success');
                }
            } catch (error) {
                console.error('加载内容过滤分类失败:', error);
                showNotification('加载内容过滤分类失败', 'error');
            } finally {
                loadingContentFilter.value = false;
            }
        };

        const openAddContentFilterModal = () => {
            newContentFilterCategory.value = { name: '', threshold: 0.75, description: '', anchorsText: '' };
            showAddContentFilterModal.value = true;
        };
        const closeAddContentFilterModal = () => {
            showAddContentFilterModal.value = false;
        };

        const createContentFilterCategory = async () => {
            const cat = newContentFilterCategory.value;
            if (!cat.name.trim()) {
                showNotification('请输入类别名称', 'warning');
                return;
            }
            const anchors = cat.anchorsText.split('\n').map(a => a.trim()).filter(a => a.length > 0);
            if (anchors.length === 0) {
                showNotification('请至少输入一个锚点文本', 'warning');
                return;
            }
            try {
                const response = await fetch('/content_filter/categories', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({
                        name: cat.name.trim(),
                        threshold: parseFloat(cat.threshold) || 0.75,
                        description: cat.description,
                        anchors
                    })
                });
                const data = await response.json();
                if (response.ok && data.status === 'success') {
                    showNotification(data.message, 'success');
                    closeAddContentFilterModal();
                    await loadContentFilterCategories();
                } else {
                    throw new Error(data.detail || '创建类别失败');
                }
            } catch (error) {
                console.error('创建内容过滤类别失败:', error);
                showNotification(`创建失败: ${error.message}`, 'error');
            }
        };

        const deleteContentFilterCategory = async (name) => {
            if (!confirm(`确定要删除类别 "${name}" 吗？此操作不可恢复。`)) return;
            try {
                const response = await fetch(`/content_filter/categories/${encodeURIComponent(name)}`, {
                    method: 'DELETE'
                });
                const data = await response.json();
                if (response.ok && data.status === 'success') {
                    showNotification(data.message, 'success');
                    await loadContentFilterCategories();
                } else {
                    throw new Error(data.detail || '删除类别失败');
                }
            } catch (error) {
                console.error('删除内容过滤类别失败:', error);
                showNotification(`删除失败: ${error.message}`, 'error');
            }
        };

        const openViewContentFilterModal = (category) => {
            viewingContentFilterCategory.value = { ...category };
            showViewContentFilterModal.value = true;
        };
        const closeViewContentFilterModal = () => {
            showViewContentFilterModal.value = false;
            viewingContentFilterCategory.value = null;
        };

        const openAddAnchorModal = (category) => {
            currentCategoryForAnchor.value = category;
            newAnchorText.value = '';
            showAddAnchorModal.value = true;
        };
        const closeAddAnchorModal = () => {
            showAddAnchorModal.value = false;
            currentCategoryForAnchor.value = null;
        };

        const openEditContentFilterModal = (category) => {
            editingContentFilterCategory.value = {
                name: category.name,
                threshold: category.threshold,
                description: category.description || '',
                category_type: category.category_type,
                anchorsText: (category.anchors || []).map(a => a.text).join('\n')
            };
            showEditContentFilterModal.value = true;
        };
        const closeEditContentFilterModal = () => {
            showEditContentFilterModal.value = false;
            editingContentFilterCategory.value = { name: '', threshold: 0.75, description: '', category_type: '', anchorsText: '' };
        };
        const saveEditedContentFilterCategory = async () => {
            const cat = editingContentFilterCategory.value;
            if (!cat.name.trim()) {
                showNotification('类别名称不能为空', 'warning');
                return;
            }
            const anchors = cat.anchorsText.split('\n').map(a => a.trim()).filter(a => a.length > 0);
            try {
                const response = await fetch(`/content_filter/categories/${encodeURIComponent(cat.name)}`, {
                    method: 'PUT',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({
                        threshold: parseFloat(cat.threshold) || 0.75,
                        description: cat.description,
                        anchors: anchors
                    })
                });
                const data = await response.json();
                if (response.ok && data.status === 'success') {
                    showNotification(data.message, 'success');
                    closeEditContentFilterModal();
                    await loadContentFilterCategories();
                } else {
                    throw new Error(data.detail || '更新类别失败');
                }
            } catch (error) {
                console.error('更新内容过滤类别失败:', error);
                showNotification(`更新失败: ${error.message}`, 'error');
            }
        };

        const openViewAnchorsModal = (category) => {
            viewingAnchorsCategory.value = category;
            showViewAnchorsModal.value = true;
        };
        const closeViewAnchorsModal = () => {
            showViewAnchorsModal.value = false;
            viewingAnchorsCategory.value = null;
        };

        const addAnchor = async () => {
            if (!newAnchorText.value.trim()) {
                showNotification('请输入锚点文本', 'warning');
                return;
            }
            try {
                const response = await fetch(`/content_filter/categories/${encodeURIComponent(currentCategoryForAnchor.value.name)}/anchors`, {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ text: newAnchorText.value.trim() })
                });
                const data = await response.json();
                if (response.ok && data.status === 'success') {
                    showNotification(data.message, 'success');
                    closeAddAnchorModal();
                    await loadContentFilterCategories();
                } else {
                    throw new Error(data.detail || '添加锚点失败');
                }
            } catch (error) {
                console.error('添加锚点失败:', error);
                showNotification(`添加失败: ${error.message}`, 'error');
            }
        };

        const deleteAnchor = async (categoryName, anchorId) => {
            if (!confirm('确定要删除此锚点吗？')) return;
            try {
                const response = await fetch(`/content_filter/categories/${encodeURIComponent(categoryName)}/anchors/${anchorId}`, {
                    method: 'DELETE'
                });
                const data = await response.json();
                if (response.ok && data.status === 'success') {
                    showNotification(data.message, 'success');
                    await loadContentFilterCategories();
                } else {
                    throw new Error(data.detail || '删除锚点失败');
                }
            } catch (error) {
                console.error('删除锚点失败:', error);
                showNotification(`删除失败: ${error.message}`, 'error');
            }
        };

        const revectorize = async () => {
            if (!confirm('重新向量化会重置所有锚点的向量并重新计算，可能需要一些时间，确定继续吗？')) return;
            try {
                revectorizing.value = true;
                const response = await fetch('/content_filter/revectorize', { method: 'POST' });
                const data = await response.json();
                if (response.ok && data.status === 'success') {
                    showNotification(`重新向量化完成: ${data.vectorized} 个锚点`, 'success');
                    await loadContentFilterCategories();
                } else {
                    throw new Error(data.detail || '重新向量化失败');
                }
            } catch (error) {
                console.error('重新向量化失败:', error);
                showNotification(`重新向量化失败: ${error.message}`, 'error');
            } finally {
                revectorizing.value = false;
            }
        };

        const resetContentFilterCategories = async () => {
            confirmModalConfig.value = {
                title: '重置内容过滤类别',
                message: '重置会将所有系统默认类别恢复为原始配置，此操作不可恢复！',
                type: 'warning',
                onConfirm: async () => {
                    showConfirmModal.value = false;
                    try {
                        resettingContentFilter.value = true;
                        const response = await fetch('/content_filter/categories/reset', { method: 'POST' });
                        const data = await response.json();
                        if (response.ok && data.status === 'success') {
                            showNotification(`重置完成: ${data.categories_reset} 个类别, ${data.anchors_created} 个锚点`, 'success');
                            await loadContentFilterCategories();
                        } else {
                            throw new Error(data.detail || '重置失败');
                        }
                    } catch (error) {
                        console.error('重置内容过滤类别失败:', error);
                        showNotification(`重置失败: ${error.message}`, 'error');
                    } finally {
                        resettingContentFilter.value = false;
                    }
                }
            };
            showConfirmModal.value = true;
        };

        // ============ 白名单规则管理方法 ============

        const refreshWhitelistRules = async (showNotify = true) => {
            try {
                loadingWhitelistRules.value = true;
                const response = await fetch('/whitelist/rules');
                if (!response.ok) {
                    throw new Error(`HTTP error! status: ${response.status}`);
                }
                const data = await response.json();
                whitelistRules.value = data.rules || [];
                whitelistPage.value = 1;
                if (showNotify) {
                    showNotification('白名单规则刷新成功', 'success');
                }
            } catch (error) {
                console.error('刷新白名单规则失败:', error);
                if (showNotify) {
                    showNotification('刷新白名单规则失败', 'error');
                }
            } finally {
                loadingWhitelistRules.value = false;
            }
        };

        const openAddWhitelistModal = () => {
            newWhitelistRule.value = { name: '', description: '', pattern: '' };
            showAddWhitelistModal.value = true;
        };

        const closeAddWhitelistModal = () => {
            showAddWhitelistModal.value = false;
            newWhitelistRule.value = { name: '', description: '', pattern: '' };
        };

        const saveNewWhitelistRule = async () => {
            if (!newWhitelistRule.value.name.trim()) {
                showNotification('请输入规则名称', 'warning');
                return;
            }
            if (!newWhitelistRule.value.pattern.trim()) {
                showNotification('请输入正则表达式模式', 'warning');
                return;
            }
            try {
                const response = await fetch(`/whitelist/rules/${encodeURIComponent(newWhitelistRule.value.name.trim())}`, {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({
                        pattern: newWhitelistRule.value.pattern.trim(),
                        description: newWhitelistRule.value.description.trim() || undefined
                    })
                });
                const data = await response.json();
                if (response.ok && data.status === 'success') {
                    showNotification(data.message, 'success');
                    closeAddWhitelistModal();
                    await refreshWhitelistRules(false);
                } else {
                    throw new Error(data.detail || '保存失败');
                }
            } catch (error) {
                console.error('保存白名单规则失败:', error);
                showNotification(`保存失败: ${error.message}`, 'error');
            }
        };

        const openEditWhitelistModal = (rule) => {
            editingWhitelistRule.value = {
                name: rule.name,
                description: rule.description || '',
                pattern: rule.pattern || ''
            };
            showEditWhitelistModal.value = true;
        };

        const closeEditWhitelistModal = () => {
            showEditWhitelistModal.value = false;
            editingWhitelistRule.value = { name: '', description: '', pattern: '' };
        };

        const openViewWhitelistModal = (rule) => {
            viewingWhitelistRule.value = {
                name: rule.name,
                description: rule.description || '',
                pattern: rule.pattern || ''
            };
            showViewWhitelistModal.value = true;
        };

        const closeViewWhitelistModal = () => {
            showViewWhitelistModal.value = false;
            viewingWhitelistRule.value = { name: '', description: '', pattern: '' };
        };

        const saveEditedWhitelistRule = async () => {
            if (!editingWhitelistRule.value.pattern.trim()) {
                showNotification('请输入正则表达式模式', 'warning');
                return;
            }
            try {
                const response = await fetch(`/whitelist/rules/${encodeURIComponent(editingWhitelistRule.value.name)}`, {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({
                        pattern: editingWhitelistRule.value.pattern.trim(),
                        description: editingWhitelistRule.value.description.trim() || undefined
                    })
                });
                const data = await response.json();
                if (response.ok && data.status === 'success') {
                    showNotification(data.message, 'success');
                    closeEditWhitelistModal();
                    await refreshWhitelistRules(false);
                } else {
                    throw new Error(data.detail || '保存失败');
                }
            } catch (error) {
                console.error('保存白名单规则失败:', error);
                showNotification(`保存失败: ${error.message}`, 'error');
            }
        };

        const deleteWhitelistRule = async (ruleName) => {
            if (!confirm(`确定要删除白名单规则 "${ruleName}" 吗？`)) {
                return;
            }
            try {
                const response = await fetch(`/whitelist/rules/${encodeURIComponent(ruleName)}`, {
                    method: 'DELETE'
                });
                const data = await response.json();
                if (response.ok && data.status === 'success') {
                    showNotification(data.message, 'success');
                    await refreshWhitelistRules(false);
                } else {
                    throw new Error(data.detail || '删除失败');
                }
            } catch (error) {
                console.error('删除白名单规则失败:', error);
                showNotification(`删除失败: ${error.message}`, 'error');
            }
        };

        const resetWhitelistRules = async () => {
            confirmModalConfig.value = {
                title: '清空白名单规则',
                message: '确定要清空所有白名单规则吗？此操作不可恢复！',
                type: 'warning',
                onConfirm: async () => {
                    showConfirmModal.value = false;
                    try {
                        resettingWhitelistRules.value = true;
                        const response = await fetch('/whitelist/rules/reset', {
                            method: 'POST',
                            headers: { 'Content-Type': 'application/json' },
                            body: JSON.stringify({})
                        });
                        const data = await response.json();
                        if (response.ok && data.status === 'success') {
                            showNotification(data.message, 'success');
                            await refreshWhitelistRules(false);
                        } else {
                            throw new Error(data.detail || '清空失败');
                        }
                    } catch (error) {
                        console.error('清空白名单规则失败:', error);
                        showNotification(`清空失败: ${error.message}`, 'error');
                    } finally {
                        resettingWhitelistRules.value = false;
                    }
                }
            };
            showConfirmModal.value = true;
        };

        // ============ 监控面板方法 ============

        const openDashboardDrawer = () => {
            showDashboardDrawer.value = true;
            refreshDashboard();
            startDashboardAutoRefresh();
        };

        const closeDashboardDrawer = () => {
            showDashboardDrawer.value = false;
            stopDashboardAutoRefresh();
            destroyCharts();
        };

        const toggleDashboardMaximize = () => {
            isDashboardMaximized.value = !isDashboardMaximized.value;
        };

        const setDashboardPeriod = (period) => {
            dashboardPeriod.value = period;
            logPage.value = 1;
            refreshDashboard();
        };

        const startDashboardAutoRefresh = () => {
            stopDashboardAutoRefresh();
            dashboardRefreshTimer = setInterval(() => {
                if (showDashboardDrawer.value && !dashboardLoading.value) {
                    refreshDashboard(false);
                }
            }, 30000);
        };

        const stopDashboardAutoRefresh = () => {
            if (dashboardRefreshTimer) {
                clearInterval(dashboardRefreshTimer);
                dashboardRefreshTimer = null;
            }
        };

        const refreshDashboard = async (showLoading = true) => {
            if (showLoading) dashboardLoading.value = true;
            try {
                const period = dashboardPeriod.value;
                const [statsRes, attackRes, blockedRes, trendsRes, logsRes] = await Promise.all([
                    fetch(`/dashboard/statistics?period=${period}`),
                    fetch(`/dashboard/attack-types?period=${period}`),
                    fetch(`/dashboard/blocked-by?period=${period}`),
                    fetch(`/dashboard/trends?period=${period}`),
                    fetch(`/dashboard/logs?limit=${logLimit.value}&offset=${(logPage.value - 1) * logLimit.value}&attack_type=${logFilter.value.attack_type}&period=${period}`),
                ]);

                const stats = statsRes.ok ? await statsRes.json() : {};
                const attackTypes = attackRes.ok ? await attackRes.json() : { distribution: {} };
                const blockedBy = blockedRes.ok ? await blockedRes.json() : { distribution: {} };
                const trends = trendsRes.ok ? await trendsRes.json() : { data: [] };
                const logs = logsRes.ok ? await logsRes.json() : { logs: [], total: 0 };

                dashboardStats.value = {
                    total_requests: stats.total_requests || 0,
                    blocked_requests: stats.blocked_requests || 0,
                    attack_requests: stats.attack_requests || 0,
                    safe_requests: stats.safe_requests || 0,
                    safety_rate: typeof stats.safety_rate === 'number' ? stats.safety_rate : 100.0,
                    guardrails_blocked: stats.guardrails_blocked || 0,
                    llm_blocked: stats.llm_blocked || 0,
                    pii_blocked: stats.pii_blocked || 0,
                    content_filter_blocked: stats.content_filter_blocked || 0,
                };

                logTotal.value = logs.total || 0;
                dashboardLogs.value = logs.logs || [];
                attackTypeOptions.value = Object.keys(attackTypes.distribution || {}).sort();

                nextTick(() => {
                    updateCharts(attackTypes.distribution || {}, blockedBy.distribution || {}, trends.data || []);
                });
            } catch (error) {
                console.error('刷新监控面板失败:', error);
                showNotification('刷新监控面板失败', 'error');
            } finally {
                if (showLoading) dashboardLoading.value = false;
            }
        };

        const refreshLogs = async () => {
            logPage.value = 1;
            await refreshDashboard(false);
        };

        const prevLogPage = () => {
            if (logPage.value > 1) {
                logPage.value--;
                refreshDashboard(false);
            }
        };

        const nextLogPage = () => {
            if (logPage.value < logTotalPages.value) {
                logPage.value++;
                refreshDashboard(false);
            }
        };

        const destroyCharts = () => {
            if (attackTypeChartInstance) {
                attackTypeChartInstance.destroy();
                attackTypeChartInstance = null;
            }
            if (blockedByChartInstance) {
                blockedByChartInstance.destroy();
                blockedByChartInstance = null;
            }
            if (trendChartInstance) {
                trendChartInstance.destroy();
                trendChartInstance = null;
            }
        };

        const updateCharts = (attackDistribution, blockedDistribution, trendsData) => {
            const colors = {
                indigo: '#4F46E5',
                slate: '#334155',
                danger: '#ef4444',
                warning: '#f59e0b',
                success: '#10b981',
                info: '#3b82f6',
                purple: '#8b5cf6',
                teal: '#14b8a6',
            };

            // 攻击类型分布饼图
            const attackLabels = {
                prompt_injection: '提示词注入',
                financial_fraud: '金融欺诈',
                data_privacy: '数据隐私',
                malicious_instructions: '恶意指令',
                social_engineering: '社会工程',
                unknown: '未知',
            };
            const attackEntries = Object.entries(attackDistribution).filter(([, v]) => v > 0);
            const attackData = attackEntries.map(([, v]) => v);
            const attackLabelList = attackEntries.map(([k]) => attackLabels[k] || k);
            const attackColors = [
                '#ef4444', '#f59e0b', '#3b82f6', '#8b5cf6', '#14b8a6', '#ec4899',
                '#f97316', '#84cc16', '#06b6d4', '#6366f1', '#d946ef', '#22c55e',
                '#0ea5e9', '#a855f7', '#f43f5e', '#10b981', '#64748b', '#b45309',
            ];

            if (attackTypeChartInstance) {
                attackTypeChartInstance.data.labels = attackLabelList;
                attackTypeChartInstance.data.datasets[0].data = attackData;
                attackTypeChartInstance.data.datasets[0].backgroundColor = attackColors.slice(0, attackData.length);
                attackTypeChartInstance.update('none');
            } else if (attackTypeChart.value) {
                attackTypeChartInstance = new Chart(attackTypeChart.value, {
                    type: 'doughnut',
                    data: {
                        labels: attackLabelList,
                        datasets: [{
                            data: attackData,
                            backgroundColor: attackColors.slice(0, attackData.length),
                            borderWidth: 0,
                            hoverOffset: 8,
                        }],
                    },
                    options: {
                        responsive: true,
                        maintainAspectRatio: false,
                        cutout: '60%',
                        plugins: {
                            legend: {
                                position: 'right',
                                labels: {
                                    padding: 12,
                                    font: { size: 11 },
                                    usePointStyle: true,
                                    pointStyle: 'circle',
                                    boxWidth: 8,
                                },
                            },
                            tooltip: {
                                callbacks: {
                                    label: (ctx) => `${ctx.label}: ${ctx.raw} 次`,
                                },
                            },
                        },
                        layout: {
                            padding: { left: 8, right: 8, top: 8, bottom: 8 }
                        }
                    },
                });
            }

            // 拦截来源分布饼图
            const blockedLabels = {
                guardrails: '黑名单过滤',
                llm: '提示词防御',
                content_filter: '内容过滤',
                pii: '敏感信息拦截',
                unknown: '未知',
            };
            const blockedEntries = Object.entries(blockedDistribution).filter(([, v]) => v > 0);
            const blockedData = blockedEntries.map(([, v]) => v);
            const blockedLabelList = blockedEntries.map(([k]) => blockedLabels[k] || k);
            const blockedColors = ['#4F46E5', '#334155', '#10b981', '#94a3b8'];

            if (blockedByChartInstance) {
                blockedByChartInstance.data.labels = blockedLabelList;
                blockedByChartInstance.data.datasets[0].data = blockedData;
                blockedByChartInstance.data.datasets[0].backgroundColor = blockedColors.slice(0, blockedData.length);
                blockedByChartInstance.update('none');
            } else if (blockedByChart.value) {
                blockedByChartInstance = new Chart(blockedByChart.value, {
                    type: 'pie',
                    data: {
                        labels: blockedLabelList,
                        datasets: [{
                            data: blockedData,
                            backgroundColor: blockedColors.slice(0, blockedData.length),
                            borderWidth: 2,
                            borderColor: '#ffffff',
                            hoverOffset: 8,
                        }],
                    },
                    options: {
                        responsive: true,
                        maintainAspectRatio: false,
                        plugins: {
                            legend: {
                                position: 'right',
                                labels: {
                                    padding: 12,
                                    font: { size: 11 },
                                    usePointStyle: true,
                                    pointStyle: 'circle',
                                    boxWidth: 8,
                                },
                            },
                            tooltip: {
                                callbacks: {
                                    label: (ctx) => `${ctx.label}: ${ctx.raw} 次`,
                                },
                            },
                        },
                        layout: {
                            padding: { left: 8, right: 8, top: 8, bottom: 8 }
                        }
                    },
                });
            }

            // 安全趋势折线图
            const trendLabels = trendsData.map(d => {
                const date = new Date(d.time);
                const pad = (n) => n.toString().padStart(2, '0');
                return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}`;
            });
            const trendTotal = trendsData.map(d => d.total);
            const trendBlocked = trendsData.map(d => d.blocked);
            const trendSafe = trendsData.map(d => d.safe);

            if (trendChartInstance) {
                trendChartInstance.data.labels = trendLabels;
                trendChartInstance.data.datasets[0].data = trendTotal;
                trendChartInstance.data.datasets[1].data = trendBlocked;
                trendChartInstance.data.datasets[2].data = trendSafe;
                trendChartInstance.update('none');
            } else if (trendChart.value) {
                trendChartInstance = new Chart(trendChart.value, {
                    type: 'line',
                    data: {
                        labels: trendLabels,
                        datasets: [
                            {
                                label: '总请求',
                                data: trendTotal,
                                borderColor: '#4F46E5',
                                backgroundColor: 'rgba(79, 70, 229, 0.08)',
                                fill: true,
                                tension: 0.4,
                                pointRadius: 3,
                                pointHoverRadius: 6,
                            },
                            {
                                label: '已拦截',
                                data: trendBlocked,
                                borderColor: '#ef4444',
                                backgroundColor: 'rgba(239, 68, 68, 0.05)',
                                fill: true,
                                tension: 0.4,
                                pointRadius: 3,
                                pointHoverRadius: 6,
                            },
                            {
                                label: '安全请求',
                                data: trendSafe,
                                borderColor: '#10b981',
                                backgroundColor: 'rgba(16, 185, 129, 0.05)',
                                fill: true,
                                tension: 0.4,
                                pointRadius: 3,
                                pointHoverRadius: 6,
                            },
                        ],
                    },
                    options: {
                        responsive: true,
                        maintainAspectRatio: false,
                        interaction: { mode: 'index', intersect: false },
                        plugins: {
                            legend: { position: 'top', align: 'end', labels: { usePointStyle: true, padding: 16 } },
                            tooltip: {
                                backgroundColor: 'rgba(15, 23, 42, 0.9)',
                                titleFont: { size: 13 },
                                bodyFont: { size: 12 },
                                padding: 12,
                                cornerRadius: 8,
                            },
                        },
                        scales: {
                            x: {
                                grid: { display: false },
                                ticks: {
                                    color: '#94a3b8',
                                    font: { size: 10 },
                                    maxRotation: 45,
                                    minRotation: 45,
                                    autoSkip: true,
                                    maxTicksLimit: 12,
                                },
                            },
                            y: {
                                beginAtZero: true,
                                grid: { color: 'rgba(0, 0, 0, 0.04)' },
                                ticks: { color: '#94a3b8', font: { size: 11 } },
                            },
                        },
                    },
                });
            }
        };

        const formatDashboardTime = (isoString) => {
            if (!isoString) return '-';
            const date = new Date(isoString);
            const pad = (n) => n.toString().padStart(2, '0');
            return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`;
        };

        const formatAttackType = (type) => {
            const map = {
                prompt_injection: '提示词注入',
                financial_fraud: '金融欺诈',
                data_privacy: '数据隐私',
                malicious_instructions: '恶意指令',
                social_engineering: '社会工程',
                unknown: '未知',
            };
            return map[type] || type || '-';
        };

        const formatBlockedBy = (source) => {
            const map = {
                guardrails: '黑名单过滤',
                llm: '提示词防御',
                content_filter: '内容过滤',
                pii: '敏感信息检测',
                unknown: '未知',
            };
            return map[source] || source || '-';
        };

        const truncate = (str, max) => {
            if (!str) return '-';
            return str.length > max ? str.slice(0, max) + '...' : str;
        };

        // 返回模板使用的数据和方法
        return {
            inputMessage,
            messages,
            whiteSamples,
            blackSamples,
            isLoading,
            connectionStatus,
            connectionStatusText,
            lastBlockedTime,
            blockedMessages,
            guardrailsBlocked,
            llmBlocked,
            piiBlocked,
            contentFilterBlocked,
            totalMessages,
            successRate,
            securityLevel,
            securityTitle,
            securityDescription,
            promptStats,
            messagesContainer,
            activeTab,
            activeSampleTab,
            isInputExpanded,
            showExpandButton,
            inputTextarea,
            selectSample,
            sendMessage,
            stopGeneration,
            clearChat,
            startNewConversation,
            checkConnection,
            switchSampleTab,
            adjustTextareaHeight,
            onTextareaKeydown,
            toggleInputExpand,
            renderMarkdown,
            showGuardrailsPanel,
            guardrailsRules,
            toggleGuardrailsPanel,
            isDefaultRule,
            refreshGuardrailsRules,
            openAddRuleModal,
            closeAddRuleModal,
            saveNewRule,
            openEditRuleModal,
            closeEditRuleModal,
            saveEditedRule,
            openPatternsModal,
            closePatternsModal,
            deleteGuardrailRule,
            resetGuardrailsRules,
            reloadGuardrailsRules,
            showAddRuleModal,
            showEditRuleModal,
            showPatternsModal,
            showConfirmModal,
            confirmModalConfig,
            newRule,
            editingRule,
            viewingPatternsRule,
            showViewRuleModal,
            viewingRule,
            openViewRuleModal,
            closeViewRuleModal,
            showIntroModal,
            loadingRules,
            resettingRules,
            reloadingRules,
            activeRuleTab,
            switchRuleTab,
            activePIITab,
            switchPIITab,
            // 分页
            whitelistPage,
            whitelistPageSize,
            whitelistTotalPages,
            paginatedWhitelistRules,
            prevWhitelistPage,
            nextWhitelistPage,
            defaultRulesPage,
            rulesPageSize,
            defaultRulesTotalPages,
            paginatedDefaultRules,
            prevDefaultRulesPage,
            nextDefaultRulesPage,
            customRulesPage,
            customRulesTotalPages,
            paginatedCustomRules,
            prevCustomRulesPage,
            nextCustomRulesPage,
            contentFilterPage,
            contentFilterPageSize,
            contentFilterTotalPages,
            paginatedContentFilterCategories,
            prevContentFilterPage,
            nextContentFilterPage,
            activeContentFilterTab,
            activeContentFilterControlTab,
            switchContentFilterControlTab,
            systemCategories,
            customCategories,
            systemCategoriesPage,
            customCategoriesPage,
            systemCategoriesTotalPages,
            paginatedSystemCategories,
            customCategoriesTotalPages,
            paginatedCustomCategories,
            switchContentFilterTab,
            prevSystemCategoriesPage,
            nextSystemCategoriesPage,
            prevCustomCategoriesPage,
            nextCustomCategoriesPage,
            // 内容过滤
            contentFilterCategories,
            loadingContentFilter,
            revectorizing,
            showAddContentFilterModal,
            newContentFilterCategory,
            showViewContentFilterModal,
            viewingContentFilterCategory,
            showAddAnchorModal,
            newAnchorText,
            currentCategoryForAnchor,
            loadContentFilterConfig,
            loadContentFilterCategories,
            createContentFilterCategory,
            deleteContentFilterCategory,
            addAnchor,
            deleteAnchor,
            revectorize,
            resettingContentFilter,
            resetContentFilterCategories,
            openAddContentFilterModal,
            closeAddContentFilterModal,
            openViewContentFilterModal,
            closeViewContentFilterModal,
            openAddAnchorModal,
            closeAddAnchorModal,
            showEditContentFilterModal,
            editingContentFilterCategory,
            openEditContentFilterModal,
            closeEditContentFilterModal,
            saveEditedContentFilterCategory,
            showViewAnchorsModal,
            viewingAnchorsCategory,
            openViewAnchorsModal,
            closeViewAnchorsModal,
            // 白名单规则
            whitelistRules,
            loadingWhitelistRules,
            resettingWhitelistRules,
            showAddWhitelistModal,
            showEditWhitelistModal,
            showViewWhitelistModal,
            newWhitelistRule,
            editingWhitelistRule,
            viewingWhitelistRule,
            refreshWhitelistRules,
            openAddWhitelistModal,
            closeAddWhitelistModal,
            saveNewWhitelistRule,
            openEditWhitelistModal,
            closeEditWhitelistModal,
            saveEditedWhitelistRule,
            openViewWhitelistModal,
            closeViewWhitelistModal,
            deleteWhitelistRule,
            resetWhitelistRules,
            // 安全护栏配置抽屉
            showConfigDrawer,
            activeConfigMenu,
            savingConfig,
            securityCheckPrompt,
            piiEntityOptions,
            guardrailConfig,
            configMenus,
            piiCategories,
            piiOptionsByCategory,
            openConfigDrawer,
            closeConfigDrawer,
            saveGuardrailConfig,
            loadContentFilterCategories,
            selectAllInputPII,
            deselectAllInputPII,
            selectAllOutputPII,
            deselectAllOutputPII,
            // 提示词防御
            isEditingPrompt,
            showPromptPreviewModal,
            isDrawerMaximized,
            refreshingPrompt,
            resettingPrompt,
            editPrompt,
            cancelEditPrompt,
            savePrompt,
            refreshPrompt,
            resetPrompt,
            previewPrompt,
            closePromptPreview,
            toggleDrawerMaximize,
            // 监控面板
            showDashboardDrawer,
            isDashboardMaximized,
            openDashboardDrawer,
            closeDashboardDrawer,
            toggleDashboardMaximize,
            dashboardPeriod,
            dashboardLoading,
            dashboardStats,
            dashboardLogs,
            logPage,
            logLimit,
            logTotal,
            logTotalPages,
            logFilter,
            hasMoreLogs,
            attackTypeOptions,
            attackTypeChart,
            blockedByChart,
            trendChart,
            setDashboardPeriod,
            refreshDashboard,
            refreshLogs,
            prevLogPage,
            nextLogPage,
            formatDashboardTime,
            formatAttackType,
            formatBlockedBy,
            truncate,
        };
    },

    // 自定义指令：自动聚焦
    directives: {
        focus: {
            mounted(el) {
                el.focus();
            }
        }
    }
}).mount('#app');

// 键盘快捷键支持
document.addEventListener('keydown', (e) => {
    // Ctrl + K 聚焦输入框
    if (e.ctrlKey && e.key === 'k') {
        e.preventDefault();
        const textarea = document.querySelector('textarea');
        if (textarea) textarea.focus();
    }
});
