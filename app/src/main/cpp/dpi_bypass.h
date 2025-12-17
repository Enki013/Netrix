/**
 * dpi_bypass.h
 * 
 * Native DPI bypass implementation for kernel-level packet manipulation.
 * Supports: SPLIT, SPLIT_REVERSE, DISORDER, DISORDER_REVERSE
 */

#ifndef DPI_BYPASS_H
#define DPI_BYPASS_H

#include <stdint.h>
#include <stdbool.h>
#include "nfqueue_handler.h"

#ifdef __cplusplus
extern "C" {
#endif

// Bypass methods
typedef enum {
    BYPASS_NONE = 0,
    BYPASS_SPLIT = 1,
    BYPASS_SPLIT_REVERSE = 2,
    BYPASS_DISORDER = 3,
    BYPASS_DISORDER_REVERSE = 4
} BypassMethod;

// DPI bypass settings
typedef struct {
    BypassMethod method;           // Bypass method to use
    uint16_t first_packet_size;    // Split position (default: 2)
    uint32_t split_delay_ms;       // Delay between fragments (default: 50)
    uint8_t split_count;           // Number of fragments for disorder (default: 4)
    bool desync_https;             // Apply to HTTPS (port 443)
    bool desync_http;              // Apply to HTTP (port 80)
    bool mix_host_case;            // Mix case of Host header
    bool block_quic;               // Block QUIC (UDP 443)
} DpiBypassSettings;

// Statistics
typedef struct {
    uint64_t packets_total;
    uint64_t packets_bypassed;
    uint64_t packets_dropped;
    uint64_t bytes_total;
} DpiBypassStats;

/**
 * Initialize DPI bypass with settings
 * @param settings Bypass settings
 */
void dpi_bypass_init(DpiBypassSettings* settings);

/**
 * Update bypass settings
 * @param settings New settings
 */
void dpi_bypass_update_settings(DpiBypassSettings* settings);

/**
 * Get current settings
 * @return Current settings
 */
DpiBypassSettings* dpi_bypass_get_settings(void);

/**
 * Process packet and apply bypass if needed
 * This is the main callback for NFQUEUE
 * 
 * @param packet Packet from NFQUEUE
 * @param user_data User data (unused)
 * @return Verdict
 */
NfqueueVerdict dpi_bypass_process_packet(NfqueuePacket* packet, void* user_data);

/**
 * Check if packet is TLS ClientHello
 * @param data TCP payload
 * @param len Payload length
 * @return true if ClientHello
 */
bool dpi_is_tls_client_hello(const uint8_t* data, uint32_t len);

/**
 * Extract SNI from TLS ClientHello
 * @param data TCP payload
 * @param len Payload length
 * @param sni_buf Buffer to store SNI
 * @param sni_buf_len Buffer length
 * @return SNI length, 0 if not found
 */
int dpi_extract_sni(const uint8_t* data, uint32_t len, 
                    char* sni_buf, uint32_t sni_buf_len);

/**
 * Check if host is whitelisted
 * @param hostname Hostname to check
 * @return true if whitelisted
 */
bool dpi_is_whitelisted(const char* hostname);

/**
 * Add hostname to whitelist
 * @param hostname Hostname to add
 */
void dpi_whitelist_add(const char* hostname);

/**
 * Clear whitelist
 */
void dpi_whitelist_clear(void);

/**
 * Get bypass statistics
 * @return Statistics struct
 */
DpiBypassStats dpi_bypass_get_stats(void);

/**
 * Reset statistics
 */
void dpi_bypass_reset_stats(void);

/**
 * Initialize raw socket for packet injection
 * Must be called before processing packets
 * @return 0 on success, -1 on error
 */
int dpi_raw_socket_init(void);

/**
 * Close raw socket
 */
void dpi_raw_socket_cleanup(void);

/**
 * Send raw packet
 * @param packet IP packet data
 * @param len Packet length
 * @param dst_ip Destination IP (network byte order)
 * @return 0 on success, -1 on error
 */
int dpi_send_raw_packet(const uint8_t* packet, uint32_t len, uint32_t dst_ip);

/**
 * Set packet mark (to avoid re-capturing our own packets)
 * @param mark Mark value
 */
void dpi_set_packet_mark(uint32_t mark);

#ifdef __cplusplus
}
#endif

#endif // DPI_BYPASS_H

