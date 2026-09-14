package com.androidengineers.agent_quickstart_android.accessibility

import android.view.accessibility.AccessibilityNodeInfo

fun AccessibilityNodeInfo?.toSimpleXml(): String {
    if (this == null) return ""
    val sb = java.lang.StringBuilder()
    dumpNode(this, sb, 0)
    return sb.toString()
}

private fun dumpNode(node: AccessibilityNodeInfo, sb: java.lang.StringBuilder, depth: Int) {
    if (!node.isVisibleToUser) return

    val className = node.className?.toString()?.substringAfterLast('.') ?: "View"
    val text = node.text?.toString()?.replace("\"", "'")?.trim() ?: ""
    val desc = node.contentDescription?.toString()?.replace("\"", "'")?.trim() ?: ""
    val isImportant = text.isNotEmpty() || desc.isNotEmpty() || node.isClickable || node.isFocused || node.isEditable
    
    if (isImportant) {
        sb.append("  ".repeat(depth))
        sb.append("<$className")
        if (text.isNotEmpty()) sb.append(" text=\"$text\"")
        if (desc.isNotEmpty()) sb.append(" desc=\"$desc\"")
        if (node.isClickable) sb.append(" clickable=\"true\"")
        if (node.isFocused) sb.append(" focused=\"true\"")
        if (node.isEditable) sb.append(" editable=\"true\"")
        sb.append(">\n")
    }

    for (i in 0 until node.childCount) {
        val child = node.getChild(i)
        if (child != null) {
            dumpNode(child, sb, if (isImportant) depth + 1 else depth)
        }
    }
    
    if (isImportant) {
        sb.append("  ".repeat(depth))
        sb.append("</$className>\n")
    }
}
