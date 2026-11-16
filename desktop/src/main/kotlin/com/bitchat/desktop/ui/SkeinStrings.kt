package com.bitchat.desktop.ui

import androidx.compose.ui.unit.LayoutDirection

enum class AppLanguage(val code: String, val label: String, val layoutDirection: LayoutDirection) {
    ENGLISH("en", "English", LayoutDirection.Ltr),
    ARABIC("ar", "العربية", LayoutDirection.Rtl),
}

enum class BroadcastPower(val label: String, val labelAr: String) {
    LOW("Low", "منخفض"),
    MEDIUM("Medium", "متوسط"),
    HIGH("High", "عالي"),
}

data class UiStrings(
    val appTitle: String,
    val tabChat: String,
    val tabDms: String,
    val tabPeople: String,
    val tabLocations: String,
    val tabHistory: String,
    val tabLinked: String,
    val tabBackups: String,
    val tabSettings: String,
    val send: String,
    val messageHint: String,
    val peersNearby: String,
    val nickname: String,
    val peerId: String,
    val showInDock: String,
    val startAtLogin: String,
    val crashReporting: String,
    val broadcastPower: String,
    val language: String,
    val exportBackup: String,
    val importBackup: String,
    val pairWithCode: String,
)

fun stringsFor(lang: AppLanguage): UiStrings = when (lang) {
    AppLanguage.ENGLISH -> UiStrings(
        appTitle = "Skein",
        tabChat = "Chat",
        tabDms = "DMs",
        tabPeople = "People",
        tabLocations = "Locations",
        tabHistory = "History",
        tabLinked = "Linked",
        tabBackups = "Backups",
        tabSettings = "Settings",
        send = "Send",
        messageHint = "Message",
        peersNearby = "Peers nearby",
        nickname = "Nickname",
        peerId = "Peer ID",
        showInDock = "Show in Dock (macOS)",
        startAtLogin = "Start at login (hidden in tray/menu bar)",
        crashReporting = "Send anonymous crash reports (Sentry)",
        broadcastPower = "Broadcast power",
        language = "Language",
        exportBackup = "Export encrypted backup (.skein)",
        importBackup = "Import backup (.skein)",
        pairWithCode = "Pair with 6-digit code",
    )
    AppLanguage.ARABIC -> UiStrings(
        appTitle = "سكين",
        tabChat = "الدردشة",
        tabDms = "الرسائل الخاصة",
        tabPeople = "الأشخاص",
        tabLocations = "المواقع",
        tabHistory = "السجل",
        tabLinked = "الأجهزة المرتبطة",
        tabBackups = "النسخ الاحتياطي",
        tabSettings = "الإعدادات",
        send = "إرسال",
        messageHint = "رسالة",
        peersNearby = "الأقران القريبون",
        nickname = "الاسم المستعار",
        peerId = "معرف النظير",
        showInDock = "إظهار في شريط Dock",
        startAtLogin = "التشغيل عند تسجيل الدخول",
        crashReporting = "إرسال تقارير الأعطال المجهولة",
        broadcastPower = "طاقة البث",
        language = "اللغة",
        exportBackup = "تصدير نسخة احتياطية مشفرة",
        importBackup = "استيراد نسخة احتياطية",
        pairWithCode = "الاقتران برمز من 6 أرقام",
    )
}
