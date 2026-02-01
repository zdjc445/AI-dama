package com.example.aidama.ui

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.IntSize
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class MosaicUiState(
    val images: List<Uri> = emptyList(),
    val selectedIndex: Int = -1,
    val isOcrLoading: Boolean = false,
    val loadingMessage: String = "",
    val isEffectView: Boolean = false,
    val isExporting: Boolean = false,
    val zoomLevel: Float = 1f,
    val panOffset: Offset = Offset.Zero,
    val editMode: EditMode = EditMode.AI,
    val isAiProcessed: Boolean = false,
    val showChatOverlay: Boolean = false,
    val showAssocDialog: Boolean = false,
    val showTypeMatchDialog: Boolean = false,
    val batchRuleMust: List<String> = emptyList(),
    val batchRuleDemand: List<String> = emptyList(),
    val batchRuleCustom: List<String> = emptyList(),
    val showBatchRuleDialog: Boolean = false,
    val assocNames: String = "",
    val assocTargets: Set<Int> = emptySet(),
    val typeColorMap: Map<String, Color> = emptyMap(),
    val showAnalysisNotice: Boolean = false,
    val showTypeChangeNotice: Boolean = false,
    val typeChangeNotice: String = "",

    // 状态位：控制分类的显示与隐藏
    val isBusinessPrivacyTriggered: Boolean = false,
    val isRelatedPartyTriggered: Boolean = false
)

class MosaicViewModel : ViewModel() {
    var state by mutableStateOf(MosaicUiState())
        private set

    val ocrCacheMap = mutableMapOf<Uri, Pair<List<OcrRect>, IntSize>>()
    var currentOcrRects by mutableStateOf<List<OcrRect>>(emptyList())
    var originalImageSize by mutableStateOf<IntSize?>(null)
    var selectedOcrIndices by mutableStateOf<Set<Int>>(emptySet())
    val multiImageMaskMap = mutableStateMapOf<Uri, Set<Int>>()

    private val undoStack = mutableListOf<Set<Int>>()
    private val redoStack = mutableListOf<Set<Int>>()
    private var pendingRuleReport: String = ""

    // --- 1. 动态分类统计 (实现“突然出现”的效果) ---
    val categorySummary: Map<String, Pair<Int, Boolean>> by derivedStateOf {
        val result = mutableMapOf<String, Pair<Int, Boolean>>()
        if (currentOcrRects.isEmpty()) return@derivedStateOf emptyMap<String, Pair<Int, Boolean>>()

        // 获取 JSON 原始定义的类型分组
        val typeGroups = currentOcrRects.indices.groupBy { currentOcrRects[it].type }
            .filterKeys { it != "none" && it != "未分类" }

        // 处理：业务隐私 (初始完全不考虑)
        if (state.isBusinessPrivacyTriggered) {
            val bizIdx = typeGroups["业务隐私"] ?: emptyList()
            if (bizIdx.isNotEmpty()) {
                result["业务隐私"] = Pair(bizIdx.size, bizIdx.all { selectedOcrIndices.contains(it) })
            }
        }

        // 处理：关联方 与 名称 的合并/剥离 (保留现有逻辑)
        val relatedIdx = typeGroups["关联方"] ?: emptyList()
        val nameIdx = typeGroups["名称"] ?: emptyList()

        if (!state.isRelatedPartyTriggered) {
            val combinedName = relatedIdx + nameIdx
            if (combinedName.isNotEmpty()) {
                result["名称"] = Pair(combinedName.size, combinedName.all { selectedOcrIndices.contains(it) })
            }
        } else {
            if (nameIdx.isNotEmpty()) result["名称"] = Pair(nameIdx.size, nameIdx.all { selectedOcrIndices.contains(it) })
            if (relatedIdx.isNotEmpty()) result["关联方"] = Pair(relatedIdx.size, relatedIdx.all { selectedOcrIndices.contains(it) })
        }

        // 处理：其他常规分类 (包括会议内容，不再接收合并)
        typeGroups.forEach { (type, indices) ->
            if (type !in listOf("业务隐私", "关联方", "名称")) {
                result[type] = Pair(indices.size, indices.all { selectedOcrIndices.contains(it) })
            }
        }
        result
    }

    // --- 2. 剧本触发逻辑 ---

    private fun triggerScenarioA() {
        viewModelScope.launch {
            setLoading(true, "AI 正在分析...")
            delay(1500)

            // 1. 改变状态位，使“业务隐私”在类型栏出现
            state = state.copy(
                isBusinessPrivacyTriggered = true,
                isEffectView = true,
                showAnalysisNotice = true
            )

            // 2. 原始对话还原
            ChatRepository.addMessage(true, "打码了“布局调整”")
            delay(500)
            val reply = "“布局调整”是业务类隐私内容。\n猜测用户希望将业务内容打码，更新标签。\n\n识别到关联打码内容：\n• 设计采用生动的色彩\n• 创造无限可能\n• 视觉元素\n\n理由：防止商业创意被窃取。"
            ChatRepository.addMessage(false, reply)

            // 3. 自动打码新出现的分类
            val targets = currentOcrRects.indices.filter { currentOcrRects[it].type == "业务隐私" }.toSet()
            updateSelection(selectedOcrIndices + targets)

            refreshTypeColors()
            setLoading(false)
            delay(3000); state = state.copy(showAnalysisNotice = false)
        }
    }

    private fun triggerScenarioB() {
        viewModelScope.launch {
            setLoading(true, "AI 正在分析...")
            delay(1500)
            state = state.copy(isRelatedPartyTriggered = true, showAnalysisNotice = true)
            ChatRepository.addMessage(true, "点击了已经打码了的“李华”")
            delay(500)
            val reply = "“李华”是关联方个人信息。\n猜测用户希望展示关联方个人，更新标签关联方个人。\n\n识别到这些关联打码内容：\n• 李华、李经理\n• 启迪科技\n\n取消对这些内容的打码。\n理由：希望展示关联方信息。"
            ChatRepository.addMessage(false, reply)
            val targetsToRemove = currentOcrRects.indices.filter { currentOcrRects[it].type == "关联方" }.toSet()
            updateSelection(selectedOcrIndices - targetsToRemove)
            refreshTypeColors()
            setLoading(false)
            delay(3000); state = state.copy(showAnalysisNotice = false)
        }
    }

    // --- 3. 交互逻辑修正 ---

    fun handleAiAction(context: Context) {
        setLoading(true, "AI 正在分析中...")
        viewModelScope.launch {
            delay(800)
            // 修改：一键打码时不考虑“业务隐私”类型
            val high = currentOcrRects.indices.filter {
                currentOcrRects[it].sensitivity == "high" && currentOcrRects[it].type != "业务隐私"
            }.toSet()
            updateSelection(selectedOcrIndices + high)
            state = state.copy(isAiProcessed = true, isEffectView = true)
            refreshTypeColors()
            setLoading(false)
        }
    }

    fun handleCategoryClick(type: String) {
        val targetTypes = mutableListOf(type)
        // 注意：这里删除了“会议内容合并业务隐私”的逻辑
        if (type == "名称" && !state.isRelatedPartyTriggered) targetTypes.add("关联方")

        val indices = currentOcrRects.indices.filter { targetTypes.contains(currentOcrRects[it].type) }.toSet()
        val isAllSelected = indices.all { selectedOcrIndices.contains(it) }
        updateSelection(if (isAllSelected) selectedOcrIndices - indices else selectedOcrIndices + indices)
        toggleEffectView(true)
    }

    fun handleOcrRectClick(index: Int) {
        val clickedRect = currentOcrRects[index]
        if (clickedRect.text.contains("布局调整") && !state.isBusinessPrivacyTriggered) { triggerScenarioA(); return }
        if ((clickedRect.text.contains("李经理") || clickedRect.text.contains("李华")) && selectedOcrIndices.contains(index) && !state.isRelatedPartyTriggered) { triggerScenarioB(); return }
        val isNowSelected = !selectedOcrIndices.contains(index)
        updateSelection(if (isNowSelected) selectedOcrIndices + index else selectedOcrIndices - index)
        toggleEffectView(true)
    }

    // --- 4. 分析总结与批量报告 ---

    private fun postAiAnalysisToChat(imageIndex: Int, rects: List<OcrRect>) {
        val num = imageIndex + 1
        ChatRepository.addMessage(true, "上传并分析了图片$num")
        val sb = StringBuilder("识别到图片$num 内容，AI 建议关注：\n\n")
        val grouped = rects.filter { it.type != "未分类" && it.type != "none" }.groupBy { it.type }
        grouped.forEach { (t, r) ->
            // 初始总结也隐藏“业务隐私”
            if (t == "业务隐私" && !state.isBusinessPrivacyTriggered) return@forEach

            val displayT = if (t == "关联方" && !state.isRelatedPartyTriggered) "名称" else t
            sb.append("• $displayT: ${r.map { it.text }.distinct().take(3).joinToString("、")}\n")
        }
        ChatRepository.addMessage(false, sb.toString())
        state = state.copy(showAnalysisNotice = true)
        viewModelScope.launch { delay(3500); state = state.copy(showAnalysisNotice = false) }
    }

    fun handleBatchProcessing(context: Context) {
        if (state.images.isEmpty()) return
        val m = mutableListOf<String>(); val d = mutableListOf<String>(); val c = mutableListOf<String>()
        val grouped = currentOcrRects.filter { it.type != "none" && it.type != "未分类" }.groupBy { it.type }
        grouped.forEach { (type, rects) ->
            // 如果还未触发业务隐私，批量规则里也不提它
            if (type == "业务隐私" && !state.isBusinessPrivacyTriggered) return@forEach

            val count = currentOcrRects.indices.filter { currentOcrRects[it].type == type }.count { selectedOcrIndices.contains(it) }
            when {
                count == rects.size -> m.add(type)
                count > 0 -> d.add(type)
                else -> c.add(type)
            }
        }
        state = state.copy(batchRuleMust = m, batchRuleDemand = d, batchRuleCustom = c, showBatchRuleDialog = true)

        val report = StringBuilder("AI 为当前图片制定的打码规则：\n\n")
        if (m.isNotEmpty()) report.append("🔴 **必打码**：${m.joinToString("、")}\n")
        if (d.isNotEmpty()) report.append("🟠 **按需打码**：${d.joinToString("、")}\n")
        if (c.isNotEmpty()) report.append("🔵 **不打码**：${c.joinToString("、")}\n")
        pendingRuleReport = report.toString()
    }

    fun navigateToChatAndReport() {
        state = state.copy(showBatchRuleDialog = false, editMode = EditMode.AI, showChatOverlay = true)
        viewModelScope.launch {
            delay(300)
            if (pendingRuleReport.isNotEmpty()) ChatRepository.addMessage(false, pendingRuleReport)
        }
    }

    // --- 5. 基础方法 (保持不变) ---

    fun generateAiImageSummary() {
        if (state.selectedIndex != -1) {
            val uri = state.images[state.selectedIndex]
            ocrCacheMap[uri]?.let { postAiAnalysisToChat(state.selectedIndex, it.first) }
        }
    }

    fun applyOcrData(rects: List<OcrRect>, size: IntSize) {
        currentOcrRects = rects
        originalImageSize = size
        refreshTypeColors()
    }

    fun addImages(context: Context, uris: List<Uri>) {
        val start = state.images.size
        state = state.copy(images = state.images + uris)
        viewModelScope.launch {
            uris.forEachIndexed { i, uri ->
                try {
                    // 尝试本地OCR
                    val json = MosaicUtils.runLocalOcr(context, uri)
                    
                    if (json != null) {
                        // 本地OCR成功
                        val data = MosaicUtils.parseOcrJson(json)
                        ocrCacheMap[uri] = Pair(data.first, data.second)
                        postAiAnalysisToChat(start + i, data.first)
                        android.util.Log.d("MosaicViewModel", "Local OCR succeeded for image ${i + 1}")
                    } else {
                        // 本地OCR失败，回退到assets中的预生成JSON
                        android.util.Log.w("MosaicViewModel", "Local OCR failed, falling back to assets for image ${i + 1}")
                        val key = MosaicUtils.getFileNameKey(context, uri)
                        MosaicUtils.loadJsonFromAssets(context, "${key}_ai.json")?.let { assetJson ->
                            val data = MosaicUtils.parseOcrJson(assetJson)
                            ocrCacheMap[uri] = Pair(data.first, data.second)
                            postAiAnalysisToChat(start + i, data.first)
                        }
                    }
                } catch (e: Exception) {
                    android.util.Log.e("MosaicViewModel", "Error processing image ${i + 1}: ${e.message}", e)
                    // 发生异常时也尝试回退到assets
                    try {
                        val key = MosaicUtils.getFileNameKey(context, uri)
                        MosaicUtils.loadJsonFromAssets(context, "${key}_ai.json")?.let { assetJson ->
                            val data = MosaicUtils.parseOcrJson(assetJson)
                            ocrCacheMap[uri] = Pair(data.first, data.second)
                            postAiAnalysisToChat(start + i, data.first)
                        }
                    } catch (fallbackEx: Exception) {
                        android.util.Log.e("MosaicViewModel", "Fallback also failed: ${fallbackEx.message}", fallbackEx)
                    }
                }
            }
            selectImage(start + uris.size - 1)
        }
    }

    fun selectImage(index: Int) {
        if (index !in state.images.indices) return
        val uri = state.images[index]
        state = state.copy(selectedIndex = index, zoomLevel = 1f, panOffset = Offset.Zero, isEffectView = false, editMode = EditMode.AI, isBusinessPrivacyTriggered = false, isRelatedPartyTriggered = false)
        undoStack.clear(); redoStack.clear(); selectedOcrIndices = multiImageMaskMap[uri] ?: emptySet()
        ocrCacheMap[uri]?.let { applyOcrData(it.first, it.second) }
    }

    fun refreshTypeColors() {
        val types = currentOcrRects.map { it.type }.filter { it != "none" && it != "未分类" }.distinct().sorted()
        state = state.copy(typeColorMap = types.mapIndexed { i, t -> t to MosaicUtils.getColorForIndex(i) }.toMap())
    }
    fun updateSelection(new: Set<Int>) { undoStack.add(selectedOcrIndices); if (undoStack.size > 30) undoStack.removeAt(0); redoStack.clear(); selectedOcrIndices = new; saveToMap() }
    private fun saveToMap() { state.images.getOrNull(state.selectedIndex)?.let { multiImageMaskMap[it] = selectedOcrIndices } }
    fun undo() { if (undoStack.isNotEmpty()) { redoStack.add(selectedOcrIndices); selectedOcrIndices = undoStack.removeAt(undoStack.lastIndex); saveToMap() } }
    fun redo() { if (redoStack.isNotEmpty()) { undoStack.add(selectedOcrIndices); selectedOcrIndices = redoStack.removeAt(redoStack.lastIndex); saveToMap() } }
    fun toggleEffectView(isEffect: Boolean) { state = state.copy(isEffectView = isEffect) }
    fun setEditMode(mode: EditMode) { state = state.copy(editMode = mode) }
    fun setZoom(level: Float, size: IntSize) { val z = level.coerceIn(0.5f, 3f); state = state.copy(zoomLevel = z, panOffset = MosaicUtils.coercePanOffset(state.panOffset, z, size)) }
    fun setPan(offset: Offset, size: IntSize) { state = state.copy(panOffset = MosaicUtils.coercePanOffset(offset, state.zoomLevel, size)) }
    fun setShowChat(show: Boolean) { state = state.copy(showChatOverlay = show) }
    fun setLoading(isLoading: Boolean, message: String = "") { state = state.copy(isOcrLoading = isLoading, loadingMessage = message) }
    fun dismissBatchDialog() { state = state.copy(showBatchRuleDialog = false) }
    fun confirmBatchApply(context: Context) { state = state.copy(showBatchRuleDialog = false); setLoading(true, "应用中..."); viewModelScope.launch { delay(1000); val t = state.batchRuleMust.toSet(); state.images.forEach { u -> if (u == state.images.getOrNull(state.selectedIndex)) return@forEach; val k = MosaicUtils.getFileNameKey(context, u); MosaicUtils.loadJsonFromAssets(context, "${k}_ai.json")?.let { val (r, _) = MosaicUtils.parseOcrJson(it); multiImageMaskMap[u] = r.indices.filter { i -> t.contains(r[i].type) }.toSet() } }; setLoading(false); Toast.makeText(context, "完成", Toast.LENGTH_SHORT).show() } }
    fun startBatchExport(context: Context) { if (state.images.isEmpty()) return; viewModelScope.launch { state = state.copy(isEffectView = true, isExporting = true); setLoading(true, "导出中..."); var count = 0; state.images.forEach { uri -> val data = ocrCacheMap[uri] ?: run { val key = MosaicUtils.getFileNameKey(context, uri); MosaicUtils.loadJsonFromAssets(context, "${key}_ai.json")?.let { val parsed = MosaicUtils.parseOcrJson(it); Pair(parsed.first, parsed.second) } }; if (data != null) { val masks = multiImageMaskMap[uri] ?: emptySet(); val success = withContext(Dispatchers.IO) { MosaicUtils.saveImageToGallery(context, uri, data.first, masks) }; if (success) count++ } }; setLoading(false); Toast.makeText(context, "成功 $count 张", Toast.LENGTH_SHORT).show(); state = state.copy(isExporting = false) } }
    fun removeImage(index: Int) { val uri = state.images[index]; val l = state.images.toMutableList().apply { removeAt(index) }; multiImageMaskMap.remove(uri); ocrCacheMap.remove(uri); state = state.copy(images = l); if (l.isEmpty()) state = state.copy(selectedIndex = -1) else selectImage(index.coerceAtMost(l.size - 1)) }
    fun dismissAssoc() { state = state.copy(showAssocDialog = false) }
    fun handleUserMessage(text: String, context: Context) {
        ChatRepository.addMessage(isUser = true, text = text)
        viewModelScope.launch {
            delay(600)
            if (text.contains("好了")) { state = state.copy(showChatOverlay = false); handleBatchProcessing(context); return@launch }
            val types = currentOcrRects.map { it.type }.filter { it != "none" && it != "未分类" }.distinct()
            val matched = types.filter { text.contains(it) }
            if (matched.isNotEmpty()) {
                matched.forEach { type ->
                    val targets = currentOcrRects.indices.filter { currentOcrRects[it].type == type }.toSet()
                    if (text.contains("撤销")) { updateSelection(selectedOcrIndices - targets); ChatRepository.addMessage(false, "已撤销 ${type}。") }
                    else { updateSelection(selectedOcrIndices + targets); state = state.copy(isEffectView = true); ChatRepository.addMessage(false, "已打码 ${type}。") }
                }
                refreshTypeColors(); return@launch
            }
            ChatRepository.addMessage(false, "请指定打码类型。")
        }
    }
}