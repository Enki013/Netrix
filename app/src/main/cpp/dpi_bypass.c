/**
 * dpi_bypass.c
 * 
 * Native DPI bypass implementation.
 * Modifies packets at kernel level for effective DPI circumvention.
 */

#include "dpi_bypass.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <strings.h>
#include <unistd.h>
#include <errno.h>
#include <time.h>
#include <pthread.h>
#include <arpa/inet.h>
#include <linux/ip.h>
#include <linux/tcp.h>
#include <sys/socket.h>

#include <android/log.h>

#define LOG_TAG "DpiBypass"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// Maximum whitelist entries
#define MAX_WHITELIST 256
#define MAX_HOSTNAME_LEN 256

// Packet mark to identify our own packets (avoid re-capture)
#define OUR_PACKET_MARK 0x10DEAD

// Global state
static struct {
    DpiBypassSettings settings;
    DpiBypassStats stats;
    pthread_mutex_t lock;
    char whitelist[MAX_WHITELIST][MAX_HOSTNAME_LEN];
    int whitelist_count;
    // Raw socket for packet injection
    int raw_socket;
    uint32_t packet_mark;
    bool raw_socket_initialized;
} g_bypass = {
    .settings = {
        .method = BYPASS_SPLIT,
        .first_packet_size = 2,
        .split_delay_ms = 50,
        .split_count = 4,
        .desync_https = true,
        .desync_http = true,
        .mix_host_case = true,
        .block_quic = true
    },
    .stats = {0},
    .lock = PTHREAD_MUTEX_INITIALIZER,
    .whitelist_count = 0,
    .raw_socket = -1,
    .packet_mark = OUR_PACKET_MARK,
    .raw_socket_initialized = false
};

// Forward declarations
static bool should_bypass(NfqueuePacket* packet, char* hostname, int hostname_len);
static uint8_t* apply_split(uint8_t* payload, uint32_t len, uint32_t* new_len);
static uint8_t* apply_split_reverse(uint8_t* payload, uint32_t len, uint32_t* new_len);
static uint8_t* apply_disorder(uint8_t* payload, uint32_t len, uint32_t* new_len);
static uint8_t* apply_disorder_reverse(uint8_t* payload, uint32_t len, uint32_t* new_len);
static void mix_hostname_case(uint8_t* data, uint32_t len);
static uint16_t calculate_tcp_checksum(struct iphdr* ip, struct tcphdr* tcp, 
                                       uint8_t* payload, uint32_t payload_len);
static uint16_t calculate_ip_checksum(struct iphdr* ip);

// New injection-based functions
static int apply_split_with_injection(uint8_t* payload, uint32_t len, uint32_t dst_ip, bool reverse);
static int apply_disorder_with_injection(uint8_t* payload, uint32_t len, uint32_t dst_ip, bool reverse);
static uint8_t* create_tcp_fragment(uint8_t* orig_packet, uint32_t orig_len,
                                    uint8_t* tcp_data, uint32_t tcp_data_len,
                                    uint32_t seq_offset, uint32_t* out_len);
static void delay_ms(uint32_t ms);

/**
 * Initialize DPI bypass
 */
void dpi_bypass_init(DpiBypassSettings* settings) {
    pthread_mutex_lock(&g_bypass.lock);
    
    if (settings != NULL) {
        memcpy(&g_bypass.settings, settings, sizeof(DpiBypassSettings));
    }
    
    memset(&g_bypass.stats, 0, sizeof(DpiBypassStats));
    
    LOGI("DPI bypass initialized: method=%d, split_size=%d, delay=%d",
         g_bypass.settings.method,
         g_bypass.settings.first_packet_size,
         g_bypass.settings.split_delay_ms);
    
    pthread_mutex_unlock(&g_bypass.lock);
}

/**
 * Update settings
 */
void dpi_bypass_update_settings(DpiBypassSettings* settings) {
    if (settings == NULL) return;
    
    pthread_mutex_lock(&g_bypass.lock);
    memcpy(&g_bypass.settings, settings, sizeof(DpiBypassSettings));
    pthread_mutex_unlock(&g_bypass.lock);
    
    LOGI("DPI bypass settings updated: method=%d", settings->method);
}

/**
 * Get current settings
 */
DpiBypassSettings* dpi_bypass_get_settings(void) {
    return &g_bypass.settings;
}

// Helper to format TCP flags
static const char* tcp_flags_str(struct tcphdr* tcp) {
    static char buf[32];
    snprintf(buf, sizeof(buf), "%s%s%s%s%s%s",
             tcp->syn ? "SYN " : "",
             tcp->ack ? "ACK " : "",
             tcp->psh ? "PSH " : "",
             tcp->fin ? "FIN " : "",
             tcp->rst ? "RST " : "",
             tcp->urg ? "URG " : "");
    return buf;
}

// Packet counter for logging
static uint64_t g_pkt_id = 0;

/**
 * Main packet processing callback
 */
NfqueueVerdict dpi_bypass_process_packet(NfqueuePacket* packet, void* user_data) {
    (void)user_data;
    
    uint64_t pkt_id = ++g_pkt_id;
    
    if (packet == NULL || packet->payload == NULL || packet->payload_len < 40) {
        LOGD("[PKT#%llu] SKIP: Invalid packet (null or too small)", (unsigned long long)pkt_id);
        return NFQUEUE_ACCEPT;
    }
    
    pthread_mutex_lock(&g_bypass.lock);
    g_bypass.stats.packets_total++;
    g_bypass.stats.bytes_total += packet->payload_len;
    pthread_mutex_unlock(&g_bypass.lock);
    
    // Parse IP header
    struct iphdr* ip = (struct iphdr*)packet->payload;
    if (ip->version != 4) {
        LOGD("[PKT#%llu] SKIP: Not IPv4 (version=%d)", (unsigned long long)pkt_id, ip->version);
        return NFQUEUE_ACCEPT;  // Only IPv4 supported
    }
    
    uint32_t ip_hdr_len = ip->ihl * 4;
    if (ip_hdr_len < 20 || packet->payload_len < ip_hdr_len) {
        LOGD("[PKT#%llu] SKIP: Invalid IP header", (unsigned long long)pkt_id);
        return NFQUEUE_ACCEPT;
    }
    
    // Log packet info
    LOGI("[PKT#%llu] %d.%d.%d.%d:%d -> %d.%d.%d.%d:%d proto=%d len=%u",
         (unsigned long long)pkt_id,
         (ip->saddr) & 0xFF, (ip->saddr >> 8) & 0xFF,
         (ip->saddr >> 16) & 0xFF, (ip->saddr >> 24) & 0xFF,
         packet->src_port,
         (ip->daddr) & 0xFF, (ip->daddr >> 8) & 0xFF,
         (ip->daddr >> 16) & 0xFF, (ip->daddr >> 24) & 0xFF,
         packet->dst_port,
         ip->protocol, packet->payload_len);
    
    // Block QUIC if enabled
    if (g_bypass.settings.block_quic && ip->protocol == IPPROTO_UDP) {
        if (packet->dst_port == 443 || packet->dst_port == 80) {
            LOGI("[PKT#%llu] DROP: QUIC blocked (UDP port %d)",
                 (unsigned long long)pkt_id, packet->dst_port);
            
            pthread_mutex_lock(&g_bypass.lock);
            g_bypass.stats.packets_dropped++;
            pthread_mutex_unlock(&g_bypass.lock);
            
            return NFQUEUE_DROP;
        }
    }
    
    // Only process TCP
    if (ip->protocol != IPPROTO_TCP) {
        LOGD("[PKT#%llu] ACCEPT: Not TCP (proto=%d)", (unsigned long long)pkt_id, ip->protocol);
        return NFQUEUE_ACCEPT;
    }
    
    // Parse TCP header
    struct tcphdr* tcp = (struct tcphdr*)(packet->payload + ip_hdr_len);
    uint32_t tcp_hdr_len = tcp->doff * 4;
    
    if (tcp_hdr_len < 20 || packet->payload_len < ip_hdr_len + tcp_hdr_len) {
        LOGD("[PKT#%llu] ACCEPT: Invalid TCP header", (unsigned long long)pkt_id);
        return NFQUEUE_ACCEPT;
    }
    
    // Check if there's TCP payload
    uint32_t tcp_data_len = packet->payload_len - ip_hdr_len - tcp_hdr_len;
    
    // Log TCP details
    LOGI("[PKT#%llu] TCP: port=%d flags=[%s] seq=%u ack=%u data_len=%u",
         (unsigned long long)pkt_id,
         packet->dst_port,
         tcp_flags_str(tcp),
         ntohl(tcp->seq),
         ntohl(tcp->ack_seq),
         tcp_data_len);
    
    if (tcp_data_len == 0) {
        LOGD("[PKT#%llu] ACCEPT: No TCP payload (control packet)", (unsigned long long)pkt_id);
        return NFQUEUE_ACCEPT;  // No data to process
    }
    
    // Check if we should bypass
    char hostname[MAX_HOSTNAME_LEN] = {0};
    if (!should_bypass(packet, hostname, sizeof(hostname))) {
        LOGI("[PKT#%llu] ACCEPT: Bypass not needed (host=%s)", 
             (unsigned long long)pkt_id, hostname[0] ? hostname : "N/A");
        return NFQUEUE_ACCEPT;
    }
    
    LOGI("[PKT#%llu] >>> BYPASS: %s -> %s (method=%d, data=%u bytes)", 
         (unsigned long long)pkt_id,
         hostname[0] ? hostname : "unknown",
         packet->dst_port == 443 ? "HTTPS" : "HTTP",
         g_bypass.settings.method,
         tcp_data_len);
    
    // Initialize raw socket if needed
    if (!g_bypass.raw_socket_initialized) {
        if (dpi_raw_socket_init() < 0) {
            LOGE("Failed to initialize raw socket, falling back to ACCEPT");
            return NFQUEUE_ACCEPT;
        }
    }
    
    // Apply bypass method using raw socket injection
    int result = -1;
    
    switch (g_bypass.settings.method) {
        case BYPASS_SPLIT:
            result = apply_split_with_injection(packet->payload, packet->payload_len, 
                                                packet->dst_ip, false);
            break;
            
        case BYPASS_SPLIT_REVERSE:
            result = apply_split_with_injection(packet->payload, packet->payload_len, 
                                                packet->dst_ip, true);
            break;
            
        case BYPASS_DISORDER:
            result = apply_disorder_with_injection(packet->payload, packet->payload_len, 
                                                   packet->dst_ip, false);
            break;
            
        case BYPASS_DISORDER_REVERSE:
            result = apply_disorder_with_injection(packet->payload, packet->payload_len, 
                                                   packet->dst_ip, true);
            break;
            
        default:
            return NFQUEUE_ACCEPT;
    }
    
    if (result == 0) {
        pthread_mutex_lock(&g_bypass.lock);
        g_bypass.stats.packets_bypassed++;
        pthread_mutex_unlock(&g_bypass.lock);
        
        // DROP original packet - we sent our own fragments
        return NFQUEUE_DROP;
    }
    
    // Injection failed, accept original packet
    LOGD("Injection failed, accepting original packet");
    return NFQUEUE_ACCEPT;
}

/**
 * Check if packet should be bypassed
 */
static bool should_bypass(NfqueuePacket* packet, char* hostname, int hostname_len) {
    bool is_https = (packet->dst_port == 443);
    bool is_http = (packet->dst_port == 80);
    
    LOGD("[BYPASS-CHECK] port=%d, is_https=%d, is_http=%d", 
         packet->dst_port, is_https, is_http);
    
    // Check port settings
    if (is_https && !g_bypass.settings.desync_https) {
        LOGD("[BYPASS-CHECK] SKIP: HTTPS desync disabled");
        return false;
    }
    if (is_http && !g_bypass.settings.desync_http) {
        LOGD("[BYPASS-CHECK] SKIP: HTTP desync disabled");
        return false;
    }
    if (!is_https && !is_http) {
        LOGD("[BYPASS-CHECK] SKIP: Not HTTP/HTTPS port");
        return false;
    }
    
    // Get TCP data
    struct iphdr* ip = (struct iphdr*)packet->payload;
    uint32_t ip_hdr_len = ip->ihl * 4;
    struct tcphdr* tcp = (struct tcphdr*)(packet->payload + ip_hdr_len);
    uint32_t tcp_hdr_len = tcp->doff * 4;
    
    uint8_t* tcp_data = packet->payload + ip_hdr_len + tcp_hdr_len;
    uint32_t tcp_data_len = packet->payload_len - ip_hdr_len - tcp_hdr_len;
    
    // For HTTPS, check if TLS ClientHello
    if (is_https) {
        bool is_client_hello = dpi_is_tls_client_hello(tcp_data, tcp_data_len);
        LOGD("[BYPASS-CHECK] TLS check: data[0]=0x%02X, data[5]=0x%02X, is_client_hello=%d",
             tcp_data_len > 0 ? tcp_data[0] : 0,
             tcp_data_len > 5 ? tcp_data[5] : 0,
             is_client_hello);
        
        if (!is_client_hello) {
            LOGD("[BYPASS-CHECK] SKIP: Not TLS ClientHello");
            return false;
        }
        
        int sni_len = dpi_extract_sni(tcp_data, tcp_data_len, hostname, hostname_len);
        LOGI("[BYPASS-CHECK] SNI extracted: '%s' (len=%d)", 
             hostname[0] ? hostname : "(empty)", sni_len);
    } else {
        // Extract HTTP Host header
        LOGD("[BYPASS-CHECK] Searching for HTTP Host header...");
        const char* host_start = memmem(tcp_data, tcp_data_len, "Host:", 5);
        if (host_start == NULL) {
            host_start = memmem(tcp_data, tcp_data_len, "host:", 5);
        }
        if (host_start != NULL) {
            host_start += 5;
            while (*host_start == ' ') host_start++;
            const char* host_end = strchr(host_start, '\r');
            if (host_end == NULL) host_end = strchr(host_start, '\n');
            if (host_end != NULL) {
                int len = host_end - host_start;
                if (len > 0 && len < hostname_len) {
                    strncpy(hostname, host_start, len);
                    hostname[len] = '\0';
                }
            }
            LOGI("[BYPASS-CHECK] HTTP Host: '%s'", hostname);
        } else {
            LOGD("[BYPASS-CHECK] No Host header found");
            // Log first 50 bytes of HTTP request for debugging
            char preview[51];
            int preview_len = tcp_data_len > 50 ? 50 : tcp_data_len;
            memcpy(preview, tcp_data, preview_len);
            preview[preview_len] = '\0';
            // Replace non-printable chars
            for (int i = 0; i < preview_len; i++) {
                if (preview[i] < 32 || preview[i] > 126) preview[i] = '.';
            }
            LOGD("[BYPASS-CHECK] HTTP preview: %s", preview);
        }
    }
    
    // Check whitelist
    if (hostname[0] != '\0' && dpi_is_whitelisted(hostname)) {
        LOGI("[BYPASS-CHECK] SKIP: Whitelisted host '%s'", hostname);
        return false;
    }
    
    LOGI("[BYPASS-CHECK] PROCEED: Will apply bypass for '%s'", 
         hostname[0] ? hostname : "unknown");
    return true;
}

/**
 * Apply SPLIT bypass - sends first N bytes as separate fragment
 */
static uint8_t* apply_split(uint8_t* payload, uint32_t len, uint32_t* new_len) {
    // For true kernel-level fragmentation, we modify the TCP sequence
    // This implementation modifies the first packet size
    
    struct iphdr* ip = (struct iphdr*)payload;
    uint32_t ip_hdr_len = ip->ihl * 4;
    struct tcphdr* tcp = (struct tcphdr*)(payload + ip_hdr_len);
    uint32_t tcp_hdr_len = tcp->doff * 4;
    
    uint8_t* tcp_data = payload + ip_hdr_len + tcp_hdr_len;
    uint32_t tcp_data_len = len - ip_hdr_len - tcp_hdr_len;
    
    uint16_t split_pos = g_bypass.settings.first_packet_size;
    if (split_pos >= tcp_data_len) {
        split_pos = tcp_data_len > 1 ? 1 : tcp_data_len;
    }
    
    // Create new packet with only first fragment
    uint32_t new_packet_len = ip_hdr_len + tcp_hdr_len + split_pos;
    uint8_t* new_packet = (uint8_t*)malloc(new_packet_len);
    if (new_packet == NULL) return NULL;
    
    memcpy(new_packet, payload, ip_hdr_len + tcp_hdr_len);
    memcpy(new_packet + ip_hdr_len + tcp_hdr_len, tcp_data, split_pos);
    
    // Update IP total length
    struct iphdr* new_ip = (struct iphdr*)new_packet;
    new_ip->tot_len = htons(new_packet_len);
    new_ip->check = 0;
    new_ip->check = calculate_ip_checksum(new_ip);
    
    // Update TCP checksum
    struct tcphdr* new_tcp = (struct tcphdr*)(new_packet + ip_hdr_len);
    new_tcp->check = 0;
    new_tcp->check = calculate_tcp_checksum(new_ip, new_tcp,
                                            new_packet + ip_hdr_len + tcp_hdr_len,
                                            split_pos);
    
    *new_len = new_packet_len;
    return new_packet;
}

/**
 * Apply SPLIT_REVERSE - sends second fragment first
 * Note: True reverse fragmentation requires sequence manipulation
 */
static uint8_t* apply_split_reverse(uint8_t* payload, uint32_t len, uint32_t* new_len) {
    // Similar to split but we mark the packet for reverse order
    // The actual reordering happens at packet injection level
    return apply_split(payload, len, new_len);
}

/**
 * Apply DISORDER - splits into multiple small fragments
 */
static uint8_t* apply_disorder(uint8_t* payload, uint32_t len, uint32_t* new_len) {
    struct iphdr* ip = (struct iphdr*)payload;
    uint32_t ip_hdr_len = ip->ihl * 4;
    struct tcphdr* tcp = (struct tcphdr*)(payload + ip_hdr_len);
    uint32_t tcp_hdr_len = tcp->doff * 4;
    
    uint8_t* tcp_data = payload + ip_hdr_len + tcp_hdr_len;
    uint32_t tcp_data_len = len - ip_hdr_len - tcp_hdr_len;
    
    uint8_t count = g_bypass.settings.split_count;
    if (count < 2) count = 2;
    if (count > 20) count = 20;
    
    uint32_t chunk_size = tcp_data_len / count;
    if (chunk_size < 1) chunk_size = 1;
    
    // Create packet with only first chunk
    uint32_t first_chunk = chunk_size;
    if (first_chunk > tcp_data_len) first_chunk = tcp_data_len;
    
    uint32_t new_packet_len = ip_hdr_len + tcp_hdr_len + first_chunk;
    uint8_t* new_packet = (uint8_t*)malloc(new_packet_len);
    if (new_packet == NULL) return NULL;
    
    memcpy(new_packet, payload, ip_hdr_len + tcp_hdr_len);
    memcpy(new_packet + ip_hdr_len + tcp_hdr_len, tcp_data, first_chunk);
    
    // Update headers
    struct iphdr* new_ip = (struct iphdr*)new_packet;
    new_ip->tot_len = htons(new_packet_len);
    new_ip->check = 0;
    new_ip->check = calculate_ip_checksum(new_ip);
    
    struct tcphdr* new_tcp = (struct tcphdr*)(new_packet + ip_hdr_len);
    new_tcp->check = 0;
    new_tcp->check = calculate_tcp_checksum(new_ip, new_tcp,
                                            new_packet + ip_hdr_len + tcp_hdr_len,
                                            first_chunk);
    
    *new_len = new_packet_len;
    return new_packet;
}

/**
 * Apply DISORDER_REVERSE - sends fragments in reverse order
 */
static uint8_t* apply_disorder_reverse(uint8_t* payload, uint32_t len, uint32_t* new_len) {
    // Similar approach, marked for reverse processing
    return apply_disorder(payload, len, new_len);
}

// ============================================================================
// Injection-based bypass functions (send via raw socket)
// ============================================================================

/**
 * Delay in milliseconds
 */
static void delay_ms(uint32_t ms) {
    if (ms == 0) return;
    struct timespec ts;
    ts.tv_sec = ms / 1000;
    ts.tv_nsec = (ms % 1000) * 1000000;
    nanosleep(&ts, NULL);
}

/**
 * Create a TCP fragment packet from original packet
 * @param orig_packet Original IP packet
 * @param orig_len Original packet length
 * @param tcp_data TCP payload data for this fragment
 * @param tcp_data_len Length of TCP payload
 * @param seq_offset Offset to add to sequence number
 * @param out_len Output: length of new packet
 * @return Allocated packet (caller must free) or NULL on error
 */
static uint8_t* create_tcp_fragment(uint8_t* orig_packet, uint32_t orig_len,
                                    uint8_t* tcp_data, uint32_t tcp_data_len,
                                    uint32_t seq_offset, uint32_t* out_len) {
    if (orig_packet == NULL || orig_len < 40) {
        LOGE("[FRAGMENT] ERROR: Invalid original packet");
        return NULL;
    }
    
    struct iphdr* orig_ip = (struct iphdr*)orig_packet;
    uint32_t ip_hdr_len = orig_ip->ihl * 4;
    struct tcphdr* orig_tcp = (struct tcphdr*)(orig_packet + ip_hdr_len);
    uint32_t tcp_hdr_len = orig_tcp->doff * 4;
    uint32_t orig_seq = ntohl(orig_tcp->seq);
    
    // Calculate new packet size
    uint32_t new_len = ip_hdr_len + tcp_hdr_len + tcp_data_len;
    uint8_t* new_packet = (uint8_t*)malloc(new_len);
    if (new_packet == NULL) {
        LOGE("[FRAGMENT] ERROR: malloc failed for %u bytes", new_len);
        return NULL;
    }
    
    // Copy IP header
    memcpy(new_packet, orig_packet, ip_hdr_len);
    
    // Copy TCP header
    memcpy(new_packet + ip_hdr_len, orig_packet + ip_hdr_len, tcp_hdr_len);
    
    // Copy TCP payload
    if (tcp_data_len > 0) {
        memcpy(new_packet + ip_hdr_len + tcp_hdr_len, tcp_data, tcp_data_len);
    }
    
    // Update IP header
    struct iphdr* new_ip = (struct iphdr*)new_packet;
    new_ip->tot_len = htons(new_len);
    new_ip->id = htons(ntohs(orig_ip->id) + (seq_offset > 0 ? 1 : 0));  // Different ID for each fragment
    new_ip->check = 0;
    uint16_t ip_checksum = calculate_ip_checksum(new_ip);
    new_ip->check = ip_checksum;
    
    // Update TCP header - adjust sequence number
    struct tcphdr* new_tcp = (struct tcphdr*)(new_packet + ip_hdr_len);
    new_tcp->seq = htonl(orig_seq + seq_offset);
    new_tcp->check = 0;
    uint16_t tcp_checksum = calculate_tcp_checksum(new_ip, new_tcp,
                                            new_packet + ip_hdr_len + tcp_hdr_len,
                                            tcp_data_len);
    new_tcp->check = tcp_checksum;
    
    LOGI("[FRAGMENT] Created: data_len=%u, seq=%u->%u (offset=%u), total_len=%u, ip_csum=0x%04X, tcp_csum=0x%04X",
         tcp_data_len, orig_seq, orig_seq + seq_offset, seq_offset, new_len, ip_checksum, tcp_checksum);
    
    *out_len = new_len;
    return new_packet;
}

/**
 * Apply SPLIT bypass with raw socket injection
 * Sends first fragment, delays, then sends second fragment
 * @param payload Original IP packet
 * @param len Packet length
 * @param dst_ip Destination IP (network byte order)
 * @param reverse If true, send second fragment first
 * @return 0 on success, -1 on error
 */
static int apply_split_with_injection(uint8_t* payload, uint32_t len, uint32_t dst_ip, bool reverse) {
    LOGI("[SPLIT] === Starting SPLIT injection ===");
    
    if (payload == NULL || len < 40) {
        LOGE("[SPLIT] ERROR: Invalid payload");
        return -1;
    }
    
    struct iphdr* ip = (struct iphdr*)payload;
    uint32_t ip_hdr_len = ip->ihl * 4;
    struct tcphdr* tcp = (struct tcphdr*)(payload + ip_hdr_len);
    uint32_t tcp_hdr_len = tcp->doff * 4;
    
    uint8_t* tcp_data = payload + ip_hdr_len + tcp_hdr_len;
    uint32_t tcp_data_len = len - ip_hdr_len - tcp_hdr_len;
    
    LOGI("[SPLIT] Original: total=%u, ip_hdr=%u, tcp_hdr=%u, data=%u, seq=%u",
         len, ip_hdr_len, tcp_hdr_len, tcp_data_len, ntohl(tcp->seq));
    
    if (tcp_data_len < 2) {
        LOGD("[SPLIT] SKIP: TCP data too short (%u bytes)", tcp_data_len);
        return -1;
    }
    
    // Calculate split position
    uint16_t split_pos = g_bypass.settings.first_packet_size;
    if (split_pos >= tcp_data_len) {
        split_pos = tcp_data_len > 1 ? (tcp_data_len / 2) : 1;
    }
    if (split_pos < 1) split_pos = 1;
    
    LOGI("[SPLIT] Split position: %u bytes (frag1=%u, frag2=%u), delay=%ums, reverse=%d", 
         split_pos, split_pos, tcp_data_len - split_pos, 
         g_bypass.settings.split_delay_ms, reverse);
    
    // Create first fragment (bytes 0 to split_pos-1)
    LOGI("[SPLIT] Creating fragment 1 (bytes 0-%u)...", split_pos - 1);
    uint32_t frag1_len = 0;
    uint8_t* frag1 = create_tcp_fragment(payload, len, tcp_data, split_pos, 0, &frag1_len);
    if (frag1 == NULL) {
        LOGE("[SPLIT] ERROR: Failed to create fragment 1");
        return -1;
    }
    
    // Create second fragment (bytes split_pos to end)
    LOGI("[SPLIT] Creating fragment 2 (bytes %u-%u)...", split_pos, tcp_data_len - 1);
    uint32_t frag2_len = 0;
    uint8_t* frag2 = create_tcp_fragment(payload, len, 
                                         tcp_data + split_pos, 
                                         tcp_data_len - split_pos, 
                                         split_pos, &frag2_len);
    if (frag2 == NULL) {
        LOGE("[SPLIT] ERROR: Failed to create fragment 2");
        free(frag1);
        return -1;
    }
    
    // Apply host case mixing if enabled (to second fragment which has more data)
    if (g_bypass.settings.mix_host_case) {
        struct iphdr* f2_ip = (struct iphdr*)frag2;
        uint32_t f2_ip_len = f2_ip->ihl * 4;
        struct tcphdr* f2_tcp = (struct tcphdr*)(frag2 + f2_ip_len);
        uint32_t f2_tcp_len = f2_tcp->doff * 4;
        mix_hostname_case(frag2 + f2_ip_len + f2_tcp_len, frag2_len - f2_ip_len - f2_tcp_len);
        // Recalculate TCP checksum after mixing
        f2_tcp->check = 0;
        f2_tcp->check = calculate_tcp_checksum(f2_ip, f2_tcp,
                                               frag2 + f2_ip_len + f2_tcp_len,
                                               frag2_len - f2_ip_len - f2_tcp_len);
        LOGD("[SPLIT] Applied host case mixing to fragment 2");
    }
    
    int result = 0;
    int send1_result, send2_result;
    
    if (reverse) {
        // Send second fragment first (reverse order)
        LOGI("[SPLIT] Sending fragment 2 first (reverse order)...");
        send2_result = dpi_send_raw_packet(frag2, frag2_len, dst_ip);
        if (send2_result < 0) {
            LOGE("[SPLIT] ERROR: Failed to send fragment 2");
            result = -1;
        } else {
            LOGI("[SPLIT] Fragment 2 sent OK (%u bytes)", frag2_len);
        }
        
        if (result == 0) {
            LOGD("[SPLIT] Delaying %u ms...", g_bypass.settings.split_delay_ms);
            delay_ms(g_bypass.settings.split_delay_ms);
            
            LOGI("[SPLIT] Sending fragment 1...");
            send1_result = dpi_send_raw_packet(frag1, frag1_len, dst_ip);
            if (send1_result < 0) {
                LOGE("[SPLIT] ERROR: Failed to send fragment 1");
                result = -1;
            } else {
                LOGI("[SPLIT] Fragment 1 sent OK (%u bytes)", frag1_len);
            }
        }
    } else {
        // Send first fragment first (normal order)
        LOGI("[SPLIT] Sending fragment 1 first...");
        send1_result = dpi_send_raw_packet(frag1, frag1_len, dst_ip);
        if (send1_result < 0) {
            LOGE("[SPLIT] ERROR: Failed to send fragment 1");
            result = -1;
        } else {
            LOGI("[SPLIT] Fragment 1 sent OK (%u bytes)", frag1_len);
        }
        
        if (result == 0) {
            LOGD("[SPLIT] Delaying %u ms...", g_bypass.settings.split_delay_ms);
            delay_ms(g_bypass.settings.split_delay_ms);
            
            LOGI("[SPLIT] Sending fragment 2...");
            send2_result = dpi_send_raw_packet(frag2, frag2_len, dst_ip);
            if (send2_result < 0) {
                LOGE("[SPLIT] ERROR: Failed to send fragment 2");
                result = -1;
            } else {
                LOGI("[SPLIT] Fragment 2 sent OK (%u bytes)", frag2_len);
            }
        }
    }
    
    free(frag1);
    free(frag2);
    
    if (result == 0) {
        LOGI("[SPLIT] === SPLIT injection SUCCESSFUL ===");
    } else {
        LOGE("[SPLIT] === SPLIT injection FAILED ===");
    }
    
    return result;
}

/**
 * Apply DISORDER bypass with raw socket injection
 * Sends multiple small fragments
 * @param payload Original IP packet
 * @param len Packet length
 * @param dst_ip Destination IP (network byte order)
 * @param reverse If true, send fragments in reverse order
 * @return 0 on success, -1 on error
 */
static int apply_disorder_with_injection(uint8_t* payload, uint32_t len, uint32_t dst_ip, bool reverse) {
    LOGI("[DISORDER] === Starting DISORDER injection ===");
    
    if (payload == NULL || len < 40) {
        LOGE("[DISORDER] ERROR: Invalid payload");
        return -1;
    }
    
    struct iphdr* ip = (struct iphdr*)payload;
    uint32_t ip_hdr_len = ip->ihl * 4;
    struct tcphdr* tcp = (struct tcphdr*)(payload + ip_hdr_len);
    uint32_t tcp_hdr_len = tcp->doff * 4;
    
    uint8_t* tcp_data = payload + ip_hdr_len + tcp_hdr_len;
    uint32_t tcp_data_len = len - ip_hdr_len - tcp_hdr_len;
    
    LOGI("[DISORDER] Original: total=%u, ip_hdr=%u, tcp_hdr=%u, data=%u, seq=%u",
         len, ip_hdr_len, tcp_hdr_len, tcp_data_len, ntohl(tcp->seq));
    
    if (tcp_data_len < 2) {
        LOGD("[DISORDER] SKIP: TCP data too short (%u bytes)", tcp_data_len);
        return -1;
    }
    
    // Calculate number of fragments and chunk size
    uint8_t count = g_bypass.settings.split_count;
    if (count < 2) count = 2;
    if (count > 10) count = 10;  // Limit to prevent too many fragments
    
    uint32_t chunk_size = tcp_data_len / count;
    if (chunk_size < 1) chunk_size = 1;
    
    LOGI("[DISORDER] Plan: %u fragments, chunk_size=%u, delay=%ums, reverse=%d", 
         count, chunk_size, g_bypass.settings.split_delay_ms, reverse);
    
    // Create all fragments
    uint8_t* fragments[10] = {0};
    uint32_t frag_lens[10] = {0};
    uint32_t offset = 0;
    int actual_count = 0;
    
    for (int i = 0; i < count && offset < tcp_data_len; i++) {
        uint32_t this_chunk = chunk_size;
        if (i == count - 1 || offset + chunk_size >= tcp_data_len) {
            this_chunk = tcp_data_len - offset;  // Last chunk gets remainder
        }
        
        LOGI("[DISORDER] Creating fragment %d (bytes %u-%u, size=%u)...", 
             i, offset, offset + this_chunk - 1, this_chunk);
        
        fragments[i] = create_tcp_fragment(payload, len,
                                           tcp_data + offset, this_chunk,
                                           offset, &frag_lens[i]);
        if (fragments[i] == NULL) {
            LOGE("[DISORDER] ERROR: Failed to create fragment %d", i);
            // Cleanup
            for (int j = 0; j < i; j++) {
                free(fragments[j]);
            }
            return -1;
        }
        
        offset += this_chunk;
        actual_count++;
    }
    
    LOGI("[DISORDER] Created %d fragments", actual_count);
    
    // Apply host case mixing to first fragment (contains Host header start)
    if (g_bypass.settings.mix_host_case && actual_count > 0) {
        struct iphdr* f_ip = (struct iphdr*)fragments[0];
        uint32_t f_ip_len = f_ip->ihl * 4;
        struct tcphdr* f_tcp = (struct tcphdr*)(fragments[0] + f_ip_len);
        uint32_t f_tcp_len = f_tcp->doff * 4;
        mix_hostname_case(fragments[0] + f_ip_len + f_tcp_len, 
                         frag_lens[0] - f_ip_len - f_tcp_len);
        f_tcp->check = 0;
        f_tcp->check = calculate_tcp_checksum(f_ip, f_tcp,
                                              fragments[0] + f_ip_len + f_tcp_len,
                                              frag_lens[0] - f_ip_len - f_tcp_len);
        LOGD("[DISORDER] Applied host case mixing to fragment 0");
    }
    
    // Send fragments
    int result = 0;
    int sent_count = 0;
    
    if (reverse) {
        // Send in reverse order
        LOGI("[DISORDER] Sending %d fragments in REVERSE order...", actual_count);
        for (int i = actual_count - 1; i >= 0 && result == 0; i--) {
            LOGI("[DISORDER] Sending fragment %d (%u bytes)...", i, frag_lens[i]);
            if (dpi_send_raw_packet(fragments[i], frag_lens[i], dst_ip) < 0) {
                LOGE("[DISORDER] ERROR: Failed to send fragment %d", i);
                result = -1;
            } else {
                sent_count++;
                LOGI("[DISORDER] Fragment %d sent OK", i);
            }
            if (i > 0 && result == 0) {
                delay_ms(g_bypass.settings.split_delay_ms);
            }
        }
    } else {
        // Send in normal order
        LOGI("[DISORDER] Sending %d fragments in NORMAL order...", actual_count);
        for (int i = 0; i < actual_count && result == 0; i++) {
            LOGI("[DISORDER] Sending fragment %d (%u bytes)...", i, frag_lens[i]);
            if (dpi_send_raw_packet(fragments[i], frag_lens[i], dst_ip) < 0) {
                LOGE("[DISORDER] ERROR: Failed to send fragment %d", i);
                result = -1;
            } else {
                sent_count++;
                LOGI("[DISORDER] Fragment %d sent OK", i);
            }
            if (i < actual_count - 1 && result == 0) {
                delay_ms(g_bypass.settings.split_delay_ms);
            }
        }
    }
    
    // Cleanup
    for (int i = 0; i < actual_count; i++) {
        free(fragments[i]);
    }
    
    if (result == 0) {
        LOGI("[DISORDER] === DISORDER injection SUCCESSFUL: sent %d/%d fragments ===", 
             sent_count, actual_count);
    } else {
        LOGE("[DISORDER] === DISORDER injection FAILED: sent %d/%d fragments ===",
             sent_count, actual_count);
    }
    
    return result;
}

/**
 * Mix case of hostname in HTTP Host header
 */
static void mix_hostname_case(uint8_t* data, uint32_t len) {
    // Find Host header
    uint8_t* host = memmem(data, len, "Host:", 5);
    if (host == NULL) {
        host = memmem(data, len, "host:", 5);
    }
    if (host == NULL) return;
    
    host += 5;
    while (*host == ' ' && (host - data) < len) host++;
    
    // Mix case: Host -> hoSt
    int i = 0;
    while ((host - data + i) < len && host[i] != '\r' && host[i] != '\n') {
        if (i % 2 == 0 && host[i] >= 'a' && host[i] <= 'z') {
            host[i] = host[i] - 32;  // To uppercase
        } else if (i % 2 == 1 && host[i] >= 'A' && host[i] <= 'Z') {
            host[i] = host[i] + 32;  // To lowercase
        }
        i++;
    }
}

/**
 * Check if TLS ClientHello
 */
bool dpi_is_tls_client_hello(const uint8_t* data, uint32_t len) {
    if (data == NULL || len < 6) return false;
    // ContentType=Handshake(0x16), HandshakeType=ClientHello(0x01)
    return data[0] == 0x16 && data[5] == 0x01;
}

/**
 * Extract SNI from TLS ClientHello
 */
int dpi_extract_sni(const uint8_t* data, uint32_t len, char* sni_buf, uint32_t sni_buf_len) {
    if (data == NULL || len < 43 || sni_buf == NULL) return 0;
    
    uint32_t offset = 43;
    
    // Skip session ID
    if (offset >= len) return 0;
    uint8_t session_id_len = data[offset];
    offset += 1 + session_id_len;
    
    // Skip cipher suites
    if (offset + 2 > len) return 0;
    uint16_t cipher_len = (data[offset] << 8) | data[offset + 1];
    offset += 2 + cipher_len;
    
    // Skip compression methods
    if (offset >= len) return 0;
    uint8_t comp_len = data[offset];
    offset += 1 + comp_len;
    
    // Extensions length
    if (offset + 2 > len) return 0;
    uint16_t ext_len = (data[offset] << 8) | data[offset + 1];
    offset += 2;
    uint32_t ext_end = offset + ext_len;
    
    // Find SNI extension (type 0x0000)
    while (offset + 4 < ext_end && offset + 4 < len) {
        uint16_t ext_type = (data[offset] << 8) | data[offset + 1];
        uint16_t ext_data_len = (data[offset + 2] << 8) | data[offset + 3];
        
        if (ext_type == 0x0000 && ext_data_len > 5) {
            uint32_t sni_start = offset + 4;
            if (sni_start + 5 >= len) break;
            
            uint8_t name_type = data[sni_start + 2];
            uint16_t name_len = (data[sni_start + 3] << 8) | data[sni_start + 4];
            
            if (name_type == 0 && name_len > 0) {
                uint32_t hostname_offset = sni_start + 5;
                if (hostname_offset + name_len <= len && name_len < sni_buf_len) {
                    memcpy(sni_buf, data + hostname_offset, name_len);
                    sni_buf[name_len] = '\0';
                    return name_len;
                }
            }
        }
        
        offset += 4 + ext_data_len;
    }
    
    return 0;
}

/**
 * Check whitelist
 */
bool dpi_is_whitelisted(const char* hostname) {
    if (hostname == NULL || hostname[0] == '\0') return false;
    
    pthread_mutex_lock(&g_bypass.lock);
    
    for (int i = 0; i < g_bypass.whitelist_count; i++) {
        if (strcasestr(hostname, g_bypass.whitelist[i]) != NULL) {
            pthread_mutex_unlock(&g_bypass.lock);
            return true;
        }
    }
    
    pthread_mutex_unlock(&g_bypass.lock);
    return false;
}

/**
 * Add to whitelist
 */
void dpi_whitelist_add(const char* hostname) {
    if (hostname == NULL || hostname[0] == '\0') return;
    
    pthread_mutex_lock(&g_bypass.lock);
    
    if (g_bypass.whitelist_count < MAX_WHITELIST) {
        strncpy(g_bypass.whitelist[g_bypass.whitelist_count], hostname, MAX_HOSTNAME_LEN - 1);
        g_bypass.whitelist[g_bypass.whitelist_count][MAX_HOSTNAME_LEN - 1] = '\0';
        g_bypass.whitelist_count++;
    }
    
    pthread_mutex_unlock(&g_bypass.lock);
}

/**
 * Clear whitelist
 */
void dpi_whitelist_clear(void) {
    pthread_mutex_lock(&g_bypass.lock);
    g_bypass.whitelist_count = 0;
    pthread_mutex_unlock(&g_bypass.lock);
}

/**
 * Get statistics
 */
DpiBypassStats dpi_bypass_get_stats(void) {
    pthread_mutex_lock(&g_bypass.lock);
    DpiBypassStats stats = g_bypass.stats;
    pthread_mutex_unlock(&g_bypass.lock);
    return stats;
}

/**
 * Reset statistics
 */
void dpi_bypass_reset_stats(void) {
    pthread_mutex_lock(&g_bypass.lock);
    memset(&g_bypass.stats, 0, sizeof(DpiBypassStats));
    pthread_mutex_unlock(&g_bypass.lock);
}

// ============================================================================
// Checksum calculations
// ============================================================================

/**
 * Calculate IP header checksum
 */
static uint16_t calculate_ip_checksum(struct iphdr* ip) {
    uint32_t sum = 0;
    uint16_t* ptr = (uint16_t*)ip;
    int len = ip->ihl * 4;
    
    while (len > 1) {
        sum += *ptr++;
        len -= 2;
    }
    
    if (len == 1) {
        sum += *(uint8_t*)ptr;
    }
    
    while (sum >> 16) {
        sum = (sum & 0xFFFF) + (sum >> 16);
    }
    
    return ~sum;
}

/**
 * Calculate TCP checksum
 */
static uint16_t calculate_tcp_checksum(struct iphdr* ip, struct tcphdr* tcp,
                                       uint8_t* payload, uint32_t payload_len) {
    uint32_t sum = 0;
    uint32_t tcp_len = tcp->doff * 4 + payload_len;
    
    // Pseudo header
    sum += (ip->saddr >> 16) & 0xFFFF;
    sum += ip->saddr & 0xFFFF;
    sum += (ip->daddr >> 16) & 0xFFFF;
    sum += ip->daddr & 0xFFFF;
    sum += htons(IPPROTO_TCP);
    sum += htons(tcp_len);
    
    // TCP header
    uint16_t* ptr = (uint16_t*)tcp;
    int len = tcp->doff * 4;
    while (len > 1) {
        sum += *ptr++;
        len -= 2;
    }
    
    // Payload
    ptr = (uint16_t*)payload;
    len = payload_len;
    while (len > 1) {
        sum += *ptr++;
        len -= 2;
    }
    
    if (len == 1) {
        sum += *(uint8_t*)ptr;
    }
    
    while (sum >> 16) {
        sum = (sum & 0xFFFF) + (sum >> 16);
    }
    
    return ~sum;
}

// ============================================================================
// Raw Socket Functions
// ============================================================================

/**
 * Initialize raw socket for packet injection
 */
int dpi_raw_socket_init(void) {
    pthread_mutex_lock(&g_bypass.lock);
    
    LOGI("=== RAW SOCKET INIT ===");
    
    if (g_bypass.raw_socket_initialized) {
        LOGI("Raw socket already initialized");
        pthread_mutex_unlock(&g_bypass.lock);
        return 0;  // Already initialized
    }
    
    // Create raw socket with IP_HDRINCL (we provide IP header)
    LOGI("Creating raw socket (SOCK_RAW, IPPROTO_RAW)...");
    g_bypass.raw_socket = socket(AF_INET, SOCK_RAW, IPPROTO_RAW);
    if (g_bypass.raw_socket < 0) {
        LOGE("!!! FAILED to create raw socket: %s (errno=%d)", strerror(errno), errno);
        pthread_mutex_unlock(&g_bypass.lock);
        return -1;
    }
    LOGI("Raw socket created: fd=%d", g_bypass.raw_socket);
    
    // Set IP_HDRINCL option (we include IP header in packet)
    int one = 1;
    if (setsockopt(g_bypass.raw_socket, IPPROTO_IP, IP_HDRINCL, &one, sizeof(one)) < 0) {
        LOGE("!!! FAILED to set IP_HDRINCL: %s", strerror(errno));
        close(g_bypass.raw_socket);
        g_bypass.raw_socket = -1;
        pthread_mutex_unlock(&g_bypass.lock);
        return -1;
    }
    LOGI("IP_HDRINCL set OK");
    
    // Set socket mark (so iptables can identify our packets)
    if (setsockopt(g_bypass.raw_socket, SOL_SOCKET, SO_MARK, &g_bypass.packet_mark, sizeof(g_bypass.packet_mark)) < 0) {
        LOGI("Warning: Failed to set SO_MARK: %s (may cause packet loops)", strerror(errno));
        // Not fatal, continue without mark
    } else {
        LOGI("SO_MARK set to 0x%X", g_bypass.packet_mark);
    }
    
    g_bypass.raw_socket_initialized = true;
    LOGI("=== RAW SOCKET READY: fd=%d ===", g_bypass.raw_socket);
    
    pthread_mutex_unlock(&g_bypass.lock);
    return 0;
}

/**
 * Close raw socket
 */
void dpi_raw_socket_cleanup(void) {
    pthread_mutex_lock(&g_bypass.lock);
    
    if (g_bypass.raw_socket >= 0) {
        close(g_bypass.raw_socket);
        g_bypass.raw_socket = -1;
    }
    g_bypass.raw_socket_initialized = false;
    
    LOGI("Raw socket cleaned up");
    pthread_mutex_unlock(&g_bypass.lock);
}

/**
 * Send raw packet
 */
int dpi_send_raw_packet(const uint8_t* packet, uint32_t len, uint32_t dst_ip) {
    if (!g_bypass.raw_socket_initialized || g_bypass.raw_socket < 0) {
        LOGE("!!! Raw socket not initialized, cannot send packet !!!");
        return -1;
    }
    
    if (packet == NULL || len < 20) {
        LOGE("Invalid packet: ptr=%p, len=%u", packet, len);
        return -1;
    }
    
    struct sockaddr_in dst_addr;
    memset(&dst_addr, 0, sizeof(dst_addr));
    dst_addr.sin_family = AF_INET;
    dst_addr.sin_addr.s_addr = dst_ip;
    
    LOGD("Sending raw packet: len=%u, dst=%d.%d.%d.%d",
         len,
         (dst_ip) & 0xFF,
         (dst_ip >> 8) & 0xFF,
         (dst_ip >> 16) & 0xFF,
         (dst_ip >> 24) & 0xFF);
    
    ssize_t sent = sendto(g_bypass.raw_socket, packet, len, 0,
                          (struct sockaddr*)&dst_addr, sizeof(dst_addr));
    
    if (sent < 0) {
        LOGE("!!! sendto FAILED: %s (errno=%d) !!!", strerror(errno), errno);
        return -1;
    }
    
    if ((uint32_t)sent != len) {
        LOGD("Partial send: %zd/%u", sent, len);
    } else {
        LOGD("Sent OK: %zd bytes", sent);
    }
    
    return 0;
}

/**
 * Set packet mark
 */
void dpi_set_packet_mark(uint32_t mark) {
    pthread_mutex_lock(&g_bypass.lock);
    g_bypass.packet_mark = mark;
    
    // Update socket option if already initialized
    if (g_bypass.raw_socket >= 0) {
        setsockopt(g_bypass.raw_socket, SOL_SOCKET, SO_MARK, &mark, sizeof(mark));
    }
    
    pthread_mutex_unlock(&g_bypass.lock);
}

