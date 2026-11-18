package com.sol.assistant

class SolEngine(private val db: SolDatabase) {
    data class Reply(val text: String, val callName: String? = null)

    fun execute(raw: String): Reply {
        val s = raw.trim().replace('ي','ی').replace('ك','ک').replace(Regex("\\s+"), " ")
        val learning = Regex("هر وقت (?:میگم|می‌گم|گفتم) (.+?) (?:منظورم|یعنی) (.+?)(?: است)?$").find(s)
        if (learning != null) {
            val alias = learning.groupValues[1].trim()
            val meaning = learning.groupValues[2].trim()
            db.remember("alias:$alias", meaning, "alias")
            return Reply("یاد گرفتم. از این به بعد «$alias» یعنی «$meaning».")
        }
        if (s.startsWith("یاد بگیر ") || s.startsWith("به خاطر بسپار ")) {
            val fact = s.substringAfter("یاد بگیر ", s.substringAfter("به خاطر بسپار ", "")).trim()
            if (fact.isNotBlank()) {
                db.remember("fact:${System.currentTimeMillis()}", fact)
                return Reply("این نکته را در حافظهٔ محلی ذخیره کردم: $fact")
            }
        }
        if (s.contains("حافظه") || s.contains("چی یادت")) {
            val rows = db.allMemories()
            return Reply(if (rows.isEmpty()) "هنوز چیزی در حافظه ندارم." else
                "موارد ذخیره‌شده:\n" + rows.take(20).joinToString("\n") { "• ${it.second}" })
        }
        if (s.startsWith("فراموش کن ")) {
            val key = "alias:" + s.removePrefix("فراموش کن ").trim()
            return Reply(if (db.forget(key)) "از حافظه حذف شد." else "این عبارت را پیدا نکردم.")
        }
        if (s.contains("اسم تو") || s.contains("نام تو")) return Reply("من Sol هستم؛ دستیار شخصی شما.")
        if (s.contains("سلام") || s == "درود") return Reply("سلام. آماده‌ام؛ چه کاری انجام بدهم؟")
        if (s.contains("ساعت") || s.contains("تاریخ")) {
            val t = java.text.SimpleDateFormat("HH:mm  yyyy/MM/dd", java.util.Locale("fa","IR")).format(java.util.Date())
            return Reply("زمان و تاریخ دستگاه: $t")
        }
        if (s.contains("تماس بگیر") || s.contains("زنگ بزن")) {
            val name = s.substringAfter("با ", s.substringAfter("به ", "")).substringBefore(" تماس").substringBefore(" زنگ").trim()
            return if (name.isBlank()) Reply("نام مخاطب را بگو؛ مثلاً: با علی تماس بگیر.")
                else Reply("برای تماس با $name تأیید لازم است.", name)
        }
        val alias = db.allMemories().firstOrNull { it.first.startsWith("alias:") && s.contains(it.first.removePrefix("alias:")) }
        if (alias != null) return Reply("طبق یادگیری تو، «${alias.first.removePrefix("alias:")}» یعنی «${alias.second}».")
        return Reply("این فرمان را هنوز نمی‌شناسم. می‌توانی با «هر وقت می‌گم ... منظورم ... است» چیزی به من یاد بدهی.")
    }
}
