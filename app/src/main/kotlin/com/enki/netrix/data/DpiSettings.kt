package com.enki.netrix.data

data class DpiSettings(
    val bypassMode: BypassMode = BypassMode.FULL,
    val bufferSize: Int = 32768,
    val tcpFastOpen: Boolean = false,
    val enableTcpNodelay: Boolean = true,
    
    /** Root mode: Use NFQUEUE instead of VpnService (requires root) */
    val useRootMode: Boolean = false,
    
    val appTheme: AppTheme = AppTheme.SYSTEM,
    
    val whitelist: Set<String> = DEFAULT_WHITELIST,
    
    val desyncMethod: DesyncMethod = DesyncMethod.SPLIT,
    val desyncHttp: Boolean = true,
    val desyncHttps: Boolean = DEFAULT_DESYNC_HTTPS,
    val firstPacketSize: Int = 2,
    val splitDelay: Long = 50L,
    val mixHostCase: Boolean = true,
    val splitCount: Int = 4,
    val fakeHex: String = "474554202f20485454502f312e300d0a0d0a", 
    val fakeCount: Int = 1,
    val customDnsEnabled: Boolean = false, 
    val customDns: String = "94.140.14.14",
    val customDns2: String = "94.140.15.15",
    val blockQuic: Boolean = true,
    val enableLogs: Boolean = true
) {
    companion object {
        /**
         * Default whitelist - Government agencies, banks and critical services
         * These sites are exempt from DPI bypass.
         */
        val DEFAULT_WHITELIST = setOf(
            // Government Agencies
            "turkiye.gov.tr", "giris.turkiye.gov.tr",
            "cimer.gov.tr",
            "resmigazete.gov.tr",
            "tccb.gov.tr",
            "tbmm.gov.tr",
            "anayasa.gov.tr",
            "yargitay.gov.tr",
            "danistay.gov.tr",
            "sayistay.gov.tr",
            "ysk.gov.tr",
            "icisleri.gov.tr",
            "adalet.gov.tr",
            "uyap.gov.tr",
            "nvi.gov.tr",
            "egm.gov.tr",
            "jandarma.gov.tr",
            "msb.gov.tr",
            "disisleri.gov.tr",
            "meb.gov.tr",
            "eba.gov.tr",
            "saglik.gov.tr",
            "enabiz.gov.tr",
            "mhrs.gov.tr",
            "aile.gov.tr",
            "csgb.gov.tr",
            "sgk.gov.tr",
            "goc.gov.tr",
            "afad.gov.tr",
            "mgm.gov.tr",
            "tarimorman.gov.tr",
            "uab.gov.tr",
            "ticaret.gov.tr",
            "sanayi.gov.tr",
            "hmb.gov.tr",
            "gib.gov.tr", 
            "ivd.gib.gov.tr",
            "tcmb.gov.tr",
            "bddk.org.tr",
            "spk.gov.tr",
            "tbb.org.tr",
            "kgk.gov.tr",
            "iskur.gov.tr",
            "tse.org.tr",
            "turkpatent.gov.tr",
            "tubitak.gov.tr",
            // Savunma Sanayi
            "aselsan.com.tr",
            "tusas.com",
            "roketsan.com.tr",
            "havelsan.com.tr",
            "stm.com.tr",
            // Bankalar
            "ziraatbank.com.tr", "ziraat.com.tr",
            "vakifbank.com.tr",
            "halkbank.com.tr",
            "isbank.com.tr", "iscep.com.tr",
            "garanti.com.tr", "garantibbva.com.tr",
            "yapikredi.com.tr",
            "akbank.com", "akbank.com.tr",
            "qnbfinansbank.com", "qnb.com.tr",
            "denizbank.com",
            "teb.com.tr",
            "kuveytturk.com.tr",
            "albarakaturk.com.tr",
            "turkiyefinans.com.tr",
            "ziraatkatilim.com.tr",
            "vakifkatilim.com.tr",
            "emlakkatilim.com.tr",
            "ing.com.tr",
            "hsbc.com.tr",
            "odeabank.com.tr",
            "fibabanka.com.tr",
            "sekerbank.com.tr",
            "bkm.com.tr",
            // Chambers of Commerce
            "tobb.org.tr",
            "ito.org.tr",
            "ato.org.tr",
            "iso.org.tr",
            // Education
            "yok.gov.tr",
            "osym.gov.tr", "ais.osym.gov.tr",
            "anadolu.edu.tr",
            "metu.edu.tr", "odtu.edu.tr",
            "itu.edu.tr",
            "yildiz.edu.tr",
            "istanbul.edu.tr",
            "hacettepe.edu.tr",
            "gazi.edu.tr",
            "bogazici.edu.tr",
            "marmara.edu.tr",
            "ege.edu.tr",
            "deu.edu.tr",
            "ankara.edu.tr",
            "bilkent.edu.tr",
            "koc.edu.tr",
            "sabanciuniv.edu",
            "dergipark.org.tr",
            // Transportation
            "ptt.gov.tr",
            "pttavm.com",
            "kgm.gov.tr",
            "tcdd.gov.tr", 
            "tcddtasimacilik.gov.tr",
            "thy.com", 
            "turkishairlines.com",
            "anadolujet.com",
            "dhmi.gov.tr",
            "shgm.gov.tr",
            // Telecommunications
            "btk.gov.tr",
            "turksat.com.tr",
            "turktelekom.com.tr", "ttnet.com.tr",
            "turkcell.com.tr", "superonline.net",
            "vodafone.com.tr",
            "kablonet.com.tr",
            "millenicom.com.tr",
            "turk.net",
            // Enerji
            "epdk.gov.tr",
            "enerjisa.com.tr",
            "ckbogazici.com.tr",
            "bedas.com.tr",
            "ayedas.com.tr",
            "toroslar.com.tr",
            "baskent.com.tr",
            // Water and Natural Gas
            "iski.istanbul",
            "igdas.istanbul",
            "aski.gov.tr",
            "izsu.gov.tr",
            "buski.gov.tr",
            "diski.gov.tr",
            "gaski.gov.tr",
            "koski.gov.tr",
            // Belediyeler
            "istanbul.bel.tr",
            "ankara.bel.tr",
            "izmir.bel.tr",
            "bursa.bel.tr",
            "antalya.bel.tr",
            "adana.bel.tr",
            "gaziantep.bel.tr",
            "konya.bel.tr",
            "kayseri.bel.tr",
            // Sivil Toplum
            "kizilay.org.tr",
            "yesilay.org.tr",
            "barobirlik.org.tr",
            "istanbulbarosu.org.tr",
            "ankarabarosu.org.tr",
            // Medya
            "aa.com.tr",
            "trt.net.tr", "trthaber.com", "trtizle.com",
            "basinhaber.gov.tr",
            // Android / Google critical services. Global stream-level HTTPS desync can
            // break Play Services, Auth and Cronet background traffic, so keep these
            // off the default bypass path unless the user explicitly removes them.
            "google.com",
            "gstatic.com",
            "googleapis.com",
            "googleusercontent.com",
            "android.com",
            "gvt1.com",
            "play.google.com",
            // Modern CDN / media / social traffic is especially sensitive to
            // stream-level TLS ClientHello fragmentation in VpnService mode.
            // Keep these default-skipped so Netrix does not break everyday apps
            // with ERR_SSL_BAD_RECORD_MAC_ALERT / decode_error collateral damage.
            "youtube.com",
            "googlevideo.com",
            "ytimg.com",
            "ggpht.com",
            "cloudflare.com",
            "facebook.com",
            "fbcdn.net",
            "instagram.com",
            "cdninstagram.com",
            "whatsapp.net"
        )

        /**
         * Compatibility-safe default for the normal VpnService path.
         * Users can still enable HTTPS desync explicitly from settings when they
         * need aggressive bypass for a specific network, but it should not be on
         * by default because it can corrupt modern CDN/Cronet TLS streams.
         */
        const val DEFAULT_DESYNC_HTTPS = false

        /**
         * Normal VpnService mode is implemented as a userspace TCP/UDP proxy.
         * Keep high-volume background apps with very strict Cronet/QUIC/TLS
         * behavior outside the tunnel by default so Netrix cannot destabilize
         * sign-in, push, social feeds or media playback while the browser still
         * gets the VPN path.
         */
        val DEFAULT_DISALLOWED_VPN_PACKAGES = setOf(
            "com.google.android.gms",
            "com.google.android.googlequicksearchbox",
            "com.android.vending",
            "com.google.android.youtube",
            "com.google.android.apps.youtube.music",
            "com.google.android.videos",
            "com.instagram.android",
            "com.facebook.katana",
            "com.facebook.orca",
            "com.whatsapp"
        )
    }
}

enum class BypassMode { FULL }
enum class DesyncMethod {
    SPLIT,
    SPLIT_REVERSE,
    DISORDER,
    DISORDER_REVERSE,
    FAKE;

    fun withoutReverse(): DesyncMethod = when (this) {
        SPLIT_REVERSE -> SPLIT
        DISORDER_REVERSE -> DISORDER
        else -> this
    }

    fun safeForVpnStream(): DesyncMethod = when (this) {
        SPLIT, FAKE -> this
        SPLIT_REVERSE, DISORDER, DISORDER_REVERSE -> SPLIT
    }

    companion object {
        private fun parse(value: String?): DesyncMethod = try {
            valueOf(value ?: SPLIT.name)
        } catch (_: Exception) {
            SPLIT
        }

        fun fromVpnPreference(value: String?): DesyncMethod = parse(value).safeForVpnStream()

        fun fromRootPreference(value: String?): DesyncMethod = parse(value)

        fun fromPreference(value: String?): DesyncMethod = fromVpnPreference(value)
    }
}
enum class VpnState { DISCONNECTED, CONNECTING, CONNECTED, ERROR }
enum class AppTheme { SYSTEM, AMOLED, OCEAN, FOREST, SUNSET, LAVENDER }
