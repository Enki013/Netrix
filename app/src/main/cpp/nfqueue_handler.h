/**
 * nfqueue_handler.h
 * 
 * NFQUEUE handler for Android DPI bypass.
 * Uses raw netlink sockets for maximum compatibility.
 * 
 * Note: Requires ROOT access to function properly.
 */

#ifndef NFQUEUE_HANDLER_H
#define NFQUEUE_HANDLER_H

#include <stdint.h>
#include <stdbool.h>

#ifdef __cplusplus
extern "C" {
#endif

// Verdict types
typedef enum {
    NFQUEUE_DROP = 0,      // Drop the packet
    NFQUEUE_ACCEPT = 1,    // Accept the packet
    NFQUEUE_REPEAT = 4,    // Re-queue the packet
    NFQUEUE_STOLEN = 3     // Packet stolen (will be handled elsewhere)
} NfqueueVerdict;

// Packet info structure passed to callback
typedef struct {
    uint32_t packet_id;        // Unique packet ID for verdict
    uint32_t mark;             // Packet mark
    uint8_t* payload;          // Packet payload (IP header + data)
    uint32_t payload_len;      // Payload length
    uint8_t protocol;          // IP protocol (6=TCP, 17=UDP)
    uint32_t src_ip;           // Source IP (network byte order)
    uint32_t dst_ip;           // Destination IP (network byte order)
    uint16_t src_port;         // Source port (host byte order)
    uint16_t dst_port;         // Destination port (host byte order)
} NfqueuePacket;

// Callback type for packet handling
// Return: verdict (ACCEPT, DROP, etc.)
typedef NfqueueVerdict (*nfqueue_callback_t)(NfqueuePacket* packet, void* user_data);

/**
 * Initialize NFQUEUE handler
 * @param queue_num Queue number (0-65535)
 * @return 0 on success, negative on error
 */
int nfqueue_init(uint16_t queue_num);

/**
 * Set packet callback function
 * @param callback Function to call for each packet
 * @param user_data User data passed to callback
 */
void nfqueue_set_callback(nfqueue_callback_t callback, void* user_data);

/**
 * Start processing packets (blocking call)
 * @return 0 on clean exit, negative on error
 */
int nfqueue_start(void);

/**
 * Stop processing packets
 */
void nfqueue_stop(void);

/**
 * Clean up and release resources
 */
void nfqueue_cleanup(void);

/**
 * Check if NFQUEUE is running
 * @return true if running
 */
bool nfqueue_is_running(void);

/**
 * Manually set verdict for a packet
 * Used when callback returns STOLEN
 * @param packet_id Packet ID
 * @param verdict Verdict to set
 * @param modified_payload Modified payload (NULL to use original)
 * @param modified_len Modified payload length
 * @return 0 on success
 */
int nfqueue_set_verdict_manual(
    uint32_t packet_id, 
    NfqueueVerdict verdict,
    uint8_t* modified_payload,
    uint32_t modified_len
);

/**
 * Get last error message
 * @return Error string
 */
const char* nfqueue_get_error(void);

#ifdef __cplusplus
}
#endif

#endif // NFQUEUE_HANDLER_H

