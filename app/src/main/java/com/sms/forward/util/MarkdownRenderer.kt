package com.sms.forward.util

/**
 * 极简 Markdown → HTML 渲染器（仅用于应用内离线展示 README）。
 * 支持：标题、代码块、行内代码、粗体、斜体、链接、无序/有序列表、
 * 引用、分隔线、表格、段落。覆盖本项目 README 用到的语法，不追求完整 Markdown。
 */
object MarkdownRenderer {

    fun render(md: String, dark: Boolean = false): String {
        val esc = htmlEscape(md)
        val blocks = splitBlocks(esc)
        val body = buildString {
            for (b in blocks) append(b)
        }
        val fg = if (dark) "#E6E6E6" else "#1C1C1E"
        val bg = if (dark) "#121212" else "#FFFFFF"
        val muted = if (dark) "#9AA0A6" else "#5F6368"
        val codeBg = if (dark) "#1E1E1E" else "#F2F2F4"
        val border = if (dark) "#3A3A3C" else "#DADCE0"
        val link = if (dark) "#8AB4F8" else "#1A73E8"
        return """<!DOCTYPE html><html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<style>
  body{font-family:-apple-system,'Segoe UI',Roboto,'PingFang SC','Microsoft YaHei',sans-serif;
       color:$fg;background:$bg;line-height:1.65;padding:16px;font-size:15px;word-wrap:break-word;}
  h1{font-size:23px;margin:18px 0 10px;} h2{font-size:20px;margin:22px 0 10px;border-bottom:1px solid $border;padding-bottom:6px;}
  h3{font-size:17px;margin:18px 0 8px;} h4{font-size:15px;margin:14px 0 6px;}
  p{margin:8px 0;} a{color:$link;word-break:break-all;}
  pre{background:$codeBg;border:1px solid $border;border-radius:8px;padding:12px;overflow-x:auto;
      font-family:'Consolas','Courier New',monospace;font-size:13px;line-height:1.5;}
  code{background:$codeBg;border-radius:4px;padding:1px 5px;font-family:'Consolas','Courier New',monospace;font-size:13px;}
  pre code{background:none;padding:0;}
  blockquote{border-left:4px solid $border;margin:10px 0;padding:2px 14px;color:$muted;}
  ul,ol{padding-left:24px;margin:8px 0;} li{margin:4px 0;}
  table{border-collapse:collapse;width:100%;margin:12px 0;font-size:13px;display:block;overflow-x:auto;}
  th,td{border:1px solid $border;padding:7px 10px;text-align:left;}
  th{background:$codeBg;} hr{border:none;border-top:1px solid $border;margin:18px 0;}
</style></head><body>${body}</body></html>"""
    }

    private fun htmlEscape(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    /** 行内：行内代码、粗体、斜体、链接 */
    private fun inline(s: String): String {
        var t = s
        // 行内代码
        t = t.replace(Regex("`([^`]+)`")) { "<code>${it.groupValues[1]}</code>" }
        // 链接 [text](url)
        t = t.replace(Regex("\\[([^]]+)]\\(([^)]+)\\)")) {
            "<a href=\"${it.groupValues[2]}\">${it.groupValues[1]}</a>"
        }
        // 粗体
        t = t.replace(Regex("\\*\\*([^*]+)\\*\\*"), "<strong>${'$'}1</strong>")
        // 斜体
        t = t.replace(Regex("(?<![*])\\*([^*]+)\\*(?![*])"), "<em>${'$'}1</em>")
        return t
    }

    private fun splitBlocks(md: String): List<String> {
        val lines = md.replace("\r\n", "\n").split("\n")
        val out = mutableListOf<String>()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val trim = line.trim()
            // 代码块
            if (trim.startsWith("```")) {
                val code = StringBuilder()
                i++
                while (i < lines.size && !lines[i].trim().startsWith("```")) {
                    code.append(lines[i]).append("\n"); i++
                }
                i++ // 跳过结束 ```
                out.add("<pre><code>${code.trimEnd()}</code></pre>")
                continue
            }
            // 表格
            if (trim.startsWith("|") && i + 1 < lines.size && Regex("^\\s*\\|?[\\s:|-]+\\|?\\s*$").matches(lines[i + 1])) {
                val header = parseRow(trim)
                i += 2
                val rows = mutableListOf<List<String>>()
                while (i < lines.size && lines[i].trim().startsWith("|")) {
                    rows.add(parseRow(lines[i].trim())); i++
                }
                out.add(buildTable(header, rows))
                continue
            }
            // 标题
            val h = Regex("^(#{1,6})\\s+(.*)$").find(trim)
            if (h != null) {
                val level = h.groupValues[1].length
                out.add("<h$level>${inline(h.groupValues[2])}</h$level>")
                i++; continue
            }
            // 分隔线
            if (Regex("^-{3,}$").matches(trim)) {
                out.add("<hr/>"); i++; continue
            }
            // 引用
            if (trim.startsWith(">")) {
                val q = StringBuilder()
                while (i < lines.size && lines[i].trim().startsWith(">")) {
                    q.append(lines[i].trim().removePrefix(">").trim()).append(" "); i++
                }
                out.add("<blockquote><p>${inline(q.toString().trim())}</p></blockquote>")
                continue
            }
            // 无序列表
            if (Regex("^[-*+]\\s+").containsMatchIn(trim)) {
                val items = StringBuilder()
                while (i < lines.size && Regex("^[-*+]\\s+").containsMatchIn(lines[i].trim())) {
                    val txt = Regex("^[-*+]\\s+").replace(lines[i].trim(), "")
                    items.append("<li>${inline(txt)}</li>"); i++
                }
                out.add("<ul>$items</ul>")
                continue
            }
            // 有序列表
            if (Regex("^\\d+\\.\\s+").containsMatchIn(trim)) {
                val items = StringBuilder()
                while (i < lines.size && Regex("^\\d+\\.\\s+").containsMatchIn(lines[i].trim())) {
                    val txt = Regex("^\\d+\\.\\s+").replace(lines[i].trim(), "")
                    items.append("<li>${inline(txt)}</li>"); i++
                }
                out.add("<ol>$items</ol>")
                continue
            }
            // 空行
            if (trim.isEmpty()) { i++; continue }
            // 普通段落（合并连续非空非特殊行）
            val para = StringBuilder(trim)
            i++
            while (i < lines.size && lines[i].trim().isNotEmpty() &&
                !isBlockStart(lines[i].trim())
            ) {
                para.append(" ").append(lines[i].trim()); i++
            }
            out.add("<p>${inline(para.toString())}</p>")
        }
        return out
    }

    private fun isBlockStart(t: String): Boolean =
        t.startsWith("```") || t.startsWith("|") || t.startsWith("#") ||
            t.startsWith(">") || Regex("^[-*+]\\s+").containsMatchIn(t) ||
            Regex("^\\d+\\.\\s+").containsMatchIn(t) || Regex("^-{3,}$").matches(t)

    private fun parseRow(row: String): List<String> =
        row.trim().removePrefix("|").removeSuffix("|").split("|").map { it.trim() }

    private fun buildTable(header: List<String>, rows: List<List<String>>): String {
        val th = header.joinToString("") { "<th>${inline(it)}</th>" }
        val trs = rows.joinToString("") { r ->
            "<tr>" + r.joinToString("") { "<td>${inline(it)}</td>" } + "</tr>"
        }
        return "<table><thead><tr>$th</tr></thead><tbody>$trs</tbody></table>"
    }
}
