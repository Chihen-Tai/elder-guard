package org.elderguard.redirect

/**
 * Plain words for the redirect history. Never a score, and never "this WAS an ad": the guard only sees one app
 * opening another, so blocked switches are "疑似廣告跳轉" / "未經確認的 App 跳轉".
 */
object RedirectText {
    fun decision(d: Decision, outcome: String?) = when {
        outcome == "user_allow" || outcome == "user_allow_always" || outcome == "user_open_anyway" -> "您允許了"
        outcome == "user_block" -> "您選擇不要"
        outcome == "dismissed" -> "沒有回答"
        d == Decision.BLOCK -> "已阻止"
        d == Decision.ASK -> "問過您"
        else -> "允許"
    }

    fun reason(r: ReasonCode?): String = when (r) {
        ReasonCode.USER_CLICK -> "您剛剛有點按"
        ReasonCode.NO_USER_INTERACTION -> "您沒有點按，它自己跳過去"
        ReasonCode.INTERACTION_UNOBSERVABLE -> "看不出您有沒有點按"
        ReasonCode.AD_PAGE_REDIRECT -> "從廣告畫面跳過去"
        ReasonCode.SHAKE_REDIRECT -> "手機一晃動就跳過去"
        ReasonCode.APP_STORE_REDIRECT -> "被帶到應用程式商店"
        ReasonCode.SHOPPING_REDIRECT -> "被帶到購物 App"
        ReasonCode.BROWSER_REDIRECT -> "被帶到瀏覽器"
        ReasonCode.REPEATED_REDIRECT -> "同樣的跳轉一再發生"
        ReasonCode.LOOP_DETECTED -> "一直重複跳，已回到桌面"
        ReasonCode.INSTANT_LAUNCH -> "一打開就馬上跳走"
        ReasonCode.SOURCE_HISTORY -> "這個 App 之前也亂跳過"
        ReasonCode.KNOWN_ALLOW_RULE -> "您設定過允許"
        ReasonCode.KNOWN_BLOCK_RULE -> "您設定過不允許"
        ReasonCode.USER_ALLOW_ONCE -> "您按了「仍然開啟」"
        ReasonCode.USER_ALLOW_ALWAYS -> "您設定以後都允許"
        ReasonCode.TEMPORARY_UNLOCK -> "跳轉保護暫停中"
        ReasonCode.NOTIFICATION_CLICK -> "您點了通知"
        ReasonCode.SHARE_FLOW -> "您用「分享」選的"
        ReasonCode.AUTH_FLOW -> "登入過程"
        ReasonCode.SYSTEM_FLOW -> "手機系統畫面"
        ReasonCode.LAUNCHER_FLOW -> "您從桌面打開的"
        ReasonCode.RECENTS_FLOW -> "您從最近使用的 App 切換的"
        ReasonCode.RETURN_FLOW -> "回到原本的 App"
        ReasonCode.FINANCE_CAUTION -> "可能和付款、銀行有關，\n所以先問您"
        ReasonCode.BACKGROUND_LAUNCH -> "在背景偷偷打開"
        ReasonCode.USER_BLOCKED -> "您選擇不要"
        ReasonCode.GUARD_RECOVERY -> "守門員帶您回來"
        ReasonCode.REPEATED_TAPLESS -> "它常常沒等您點就打開"
        ReasonCode.NOT_PROTECTED, null -> ""
    }

    fun headline(d: Decision) = when (d) {
        Decision.BLOCK -> "疑似廣告跳轉"
        Decision.ASK -> "未經確認的 App 跳轉"
        Decision.ALLOW -> "正常開啟"
    }
}
