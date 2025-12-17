/**
 * nfqueue_handler.c
 * 
 * NFQUEUE handler implementation using raw netlink sockets.
 * This approach works on Android without requiring libnetfilter_queue.
 * 
 * Based on netfilter netlink protocol documentation.
 * Requires ROOT access.
 */

#include "nfqueue_handler.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <errno.h>
#include <sys/socket.h>
#include <linux/netlink.h>
#include <linux/netfilter.h>
#include <linux/netfilter/nfnetlink.h>
#include <linux/netfilter/nfnetlink_queue.h>
#include <arpa/inet.h>
#include <pthread.h>

#include <android/log.h>

#define LOG_TAG "NfqueueHandler"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

// Buffer sizes
#define RECV_BUFFER_SIZE 65536
#define SEND_BUFFER_SIZE 4096

// Netlink message alignment
#define NLMSG_ALIGN_SIZE(len) (((len) + NLMSG_ALIGNTO - 1) & ~(NLMSG_ALIGNTO - 1))
#define NFA_ALIGN_SIZE(len) (((len) + NFA_ALIGNTO - 1) & ~(NFA_ALIGNTO - 1))
#define NFA_ALIGNTO 4

// Global state
static struct {
    int nl_socket;
    uint16_t queue_num;
    volatile bool running;
    nfqueue_callback_t callback;
    void* user_data;
    char error_msg[256];
    pthread_mutex_t lock;
    uint8_t recv_buffer[RECV_BUFFER_SIZE];
    uint8_t send_buffer[SEND_BUFFER_SIZE];
} g_nfq = {
    .nl_socket = -1,
    .queue_num = 0,
    .running = false,
    .callback = NULL,
    .user_data = NULL,
    .error_msg = "",
    .lock = PTHREAD_MUTEX_INITIALIZER
};

// Forward declarations
static int send_config_cmd(uint8_t cmd, uint16_t queue_num, uint16_t pf);
static int set_queue_mode(uint16_t queue_num, uint8_t mode, uint32_t range);
static int parse_packet(struct nlmsghdr* nlh, NfqueuePacket* pkt);
static int send_verdict(uint32_t packet_id, uint32_t verdict, uint8_t* payload, uint32_t len);

/**
 * Initialize NFQUEUE handler
 */
int nfqueue_init(uint16_t queue_num) {
    pthread_mutex_lock(&g_nfq.lock);
    
    if (g_nfq.nl_socket >= 0) {
        snprintf(g_nfq.error_msg, sizeof(g_nfq.error_msg), "Already initialized");
        pthread_mutex_unlock(&g_nfq.lock);
        return -1;
    }
    
    g_nfq.queue_num = queue_num;
    
    // Create netlink socket
    g_nfq.nl_socket = socket(AF_NETLINK, SOCK_RAW, NETLINK_NETFILTER);
    if (g_nfq.nl_socket < 0) {
        snprintf(g_nfq.error_msg, sizeof(g_nfq.error_msg), 
                 "Failed to create netlink socket: %s", strerror(errno));
        pthread_mutex_unlock(&g_nfq.lock);
        return -1;
    }
    
    // Set socket buffer sizes
    int bufsize = RECV_BUFFER_SIZE;
    setsockopt(g_nfq.nl_socket, SOL_SOCKET, SO_RCVBUF, &bufsize, sizeof(bufsize));
    setsockopt(g_nfq.nl_socket, SOL_SOCKET, SO_SNDBUF, &bufsize, sizeof(bufsize));
    
    // Bind to netlink
    struct sockaddr_nl addr;
    memset(&addr, 0, sizeof(addr));
    addr.nl_family = AF_NETLINK;
    addr.nl_pid = getpid();
    addr.nl_groups = 0;
    
    if (bind(g_nfq.nl_socket, (struct sockaddr*)&addr, sizeof(addr)) < 0) {
        snprintf(g_nfq.error_msg, sizeof(g_nfq.error_msg),
                 "Failed to bind netlink socket: %s", strerror(errno));
        close(g_nfq.nl_socket);
        g_nfq.nl_socket = -1;
        pthread_mutex_unlock(&g_nfq.lock);
        return -1;
    }
    
    // Unbind from PF_INET (if bound)
    send_config_cmd(NFQNL_CFG_CMD_PF_UNBIND, 0, PF_INET);
    
    // Bind to PF_INET
    if (send_config_cmd(NFQNL_CFG_CMD_PF_BIND, 0, PF_INET) < 0) {
        snprintf(g_nfq.error_msg, sizeof(g_nfq.error_msg),
                 "Failed to bind to PF_INET");
        close(g_nfq.nl_socket);
        g_nfq.nl_socket = -1;
        pthread_mutex_unlock(&g_nfq.lock);
        return -1;
    }
    
    // Bind to queue
    if (send_config_cmd(NFQNL_CFG_CMD_BIND, queue_num, 0) < 0) {
        snprintf(g_nfq.error_msg, sizeof(g_nfq.error_msg),
                 "Failed to bind to queue %d", queue_num);
        close(g_nfq.nl_socket);
        g_nfq.nl_socket = -1;
        pthread_mutex_unlock(&g_nfq.lock);
        return -1;
    }
    
    // Set copy mode (copy entire packet)
    if (set_queue_mode(queue_num, NFQNL_COPY_PACKET, 0xFFFF) < 0) {
        snprintf(g_nfq.error_msg, sizeof(g_nfq.error_msg),
                 "Failed to set queue mode");
        send_config_cmd(NFQNL_CFG_CMD_UNBIND, queue_num, 0);
        close(g_nfq.nl_socket);
        g_nfq.nl_socket = -1;
        pthread_mutex_unlock(&g_nfq.lock);
        return -1;
    }
    
    LOGI("NFQUEUE initialized: queue=%d", queue_num);
    pthread_mutex_unlock(&g_nfq.lock);
    return 0;
}

/**
 * Set packet callback
 */
void nfqueue_set_callback(nfqueue_callback_t callback, void* user_data) {
    pthread_mutex_lock(&g_nfq.lock);
    g_nfq.callback = callback;
    g_nfq.user_data = user_data;
    pthread_mutex_unlock(&g_nfq.lock);
}

/**
 * Start processing packets
 */
int nfqueue_start(void) {
    if (g_nfq.nl_socket < 0) {
        snprintf(g_nfq.error_msg, sizeof(g_nfq.error_msg), "Not initialized");
        return -1;
    }
    
    g_nfq.running = true;
    LOGI("NFQUEUE started");
    
    struct sockaddr_nl peer;
    socklen_t peer_len = sizeof(peer);
    
    while (g_nfq.running) {
        ssize_t len = recvfrom(g_nfq.nl_socket, g_nfq.recv_buffer, 
                               RECV_BUFFER_SIZE, 0,
                               (struct sockaddr*)&peer, &peer_len);
        
        if (len < 0) {
            if (errno == EINTR || errno == EAGAIN) {
                continue;
            }
            if (!g_nfq.running) break;
            LOGE("recvfrom error: %s", strerror(errno));
            continue;
        }
        
        if (len == 0) continue;
        
        // Process netlink messages
        struct nlmsghdr* nlh = (struct nlmsghdr*)g_nfq.recv_buffer;
        
        while (NLMSG_OK(nlh, len)) {
            if (nlh->nlmsg_type == NLMSG_ERROR) {
                struct nlmsgerr* err = (struct nlmsgerr*)NLMSG_DATA(nlh);
                if (err->error != 0) {
                    LOGE("Netlink error: %d", err->error);
                }
            } else if ((nlh->nlmsg_type & 0xFF) == NFNL_SUBSYS_QUEUE) {
                NfqueuePacket pkt;
                memset(&pkt, 0, sizeof(pkt));
                
                if (parse_packet(nlh, &pkt) == 0) {
                    NfqueueVerdict verdict = NFQUEUE_ACCEPT;
                    
                    if (g_nfq.callback) {
                        verdict = g_nfq.callback(&pkt, g_nfq.user_data);
                    }
                    
                    if (verdict != NFQUEUE_STOLEN) {
                        send_verdict(pkt.packet_id, verdict, NULL, 0);
                    }
                }
            }
            
            nlh = NLMSG_NEXT(nlh, len);
        }
    }
    
    LOGI("NFQUEUE stopped");
    return 0;
}

/**
 * Stop processing
 */
void nfqueue_stop(void) {
    g_nfq.running = false;
    
    // Wake up blocked recvfrom by sending empty message to self
    if (g_nfq.nl_socket >= 0) {
        shutdown(g_nfq.nl_socket, SHUT_RDWR);
    }
}

/**
 * Cleanup
 */
void nfqueue_cleanup(void) {
    pthread_mutex_lock(&g_nfq.lock);
    
    nfqueue_stop();
    
    if (g_nfq.nl_socket >= 0) {
        send_config_cmd(NFQNL_CFG_CMD_UNBIND, g_nfq.queue_num, 0);
        close(g_nfq.nl_socket);
        g_nfq.nl_socket = -1;
    }
    
    g_nfq.callback = NULL;
    g_nfq.user_data = NULL;
    
    LOGI("NFQUEUE cleaned up");
    pthread_mutex_unlock(&g_nfq.lock);
}

/**
 * Check if running
 */
bool nfqueue_is_running(void) {
    return g_nfq.running;
}

/**
 * Manual verdict
 */
int nfqueue_set_verdict_manual(uint32_t packet_id, NfqueueVerdict verdict,
                               uint8_t* modified_payload, uint32_t modified_len) {
    return send_verdict(packet_id, verdict, modified_payload, modified_len);
}

/**
 * Get error message
 */
const char* nfqueue_get_error(void) {
    return g_nfq.error_msg;
}

// ============================================================================
// Internal functions
// ============================================================================

/**
 * Send config command
 */
static int send_config_cmd(uint8_t cmd, uint16_t queue_num, uint16_t pf) {
    struct {
        struct nlmsghdr nlh;
        struct nfgenmsg nfg;
        struct nlattr attr;
        struct nfqnl_msg_config_cmd cfg_cmd;
    } req;
    
    memset(&req, 0, sizeof(req));
    
    req.nlh.nlmsg_len = sizeof(req);
    req.nlh.nlmsg_type = (NFNL_SUBSYS_QUEUE << 8) | NFQNL_MSG_CONFIG;
    req.nlh.nlmsg_flags = NLM_F_REQUEST | NLM_F_ACK;
    req.nlh.nlmsg_seq = 0;
    req.nlh.nlmsg_pid = getpid();
    
    req.nfg.nfgen_family = AF_UNSPEC;
    req.nfg.version = NFNETLINK_V0;
    req.nfg.res_id = htons(queue_num);
    
    req.attr.nla_len = sizeof(req.attr) + sizeof(req.cfg_cmd);
    req.attr.nla_type = NFQA_CFG_CMD;
    
    req.cfg_cmd.command = cmd;
    req.cfg_cmd.pf = htons(pf);
    
    struct sockaddr_nl peer;
    memset(&peer, 0, sizeof(peer));
    peer.nl_family = AF_NETLINK;
    
    if (sendto(g_nfq.nl_socket, &req, sizeof(req), 0,
               (struct sockaddr*)&peer, sizeof(peer)) < 0) {
        LOGE("sendto config cmd failed: %s", strerror(errno));
        return -1;
    }
    
    return 0;
}

/**
 * Set queue mode
 */
static int set_queue_mode(uint16_t queue_num, uint8_t mode, uint32_t range) {
    struct {
        struct nlmsghdr nlh;
        struct nfgenmsg nfg;
        struct nlattr attr;
        struct nfqnl_msg_config_params params;
    } req;
    
    memset(&req, 0, sizeof(req));
    
    req.nlh.nlmsg_len = sizeof(req);
    req.nlh.nlmsg_type = (NFNL_SUBSYS_QUEUE << 8) | NFQNL_MSG_CONFIG;
    req.nlh.nlmsg_flags = NLM_F_REQUEST | NLM_F_ACK;
    req.nlh.nlmsg_seq = 0;
    req.nlh.nlmsg_pid = getpid();
    
    req.nfg.nfgen_family = AF_UNSPEC;
    req.nfg.version = NFNETLINK_V0;
    req.nfg.res_id = htons(queue_num);
    
    req.attr.nla_len = sizeof(req.attr) + sizeof(req.params);
    req.attr.nla_type = NFQA_CFG_PARAMS;
    
    req.params.copy_mode = mode;
    req.params.copy_range = htonl(range);
    
    struct sockaddr_nl peer;
    memset(&peer, 0, sizeof(peer));
    peer.nl_family = AF_NETLINK;
    
    if (sendto(g_nfq.nl_socket, &req, sizeof(req), 0,
               (struct sockaddr*)&peer, sizeof(peer)) < 0) {
        LOGE("sendto queue mode failed: %s", strerror(errno));
        return -1;
    }
    
    return 0;
}

/**
 * Parse packet from netlink message
 */
static int parse_packet(struct nlmsghdr* nlh, NfqueuePacket* pkt) {
    struct nfgenmsg* nfg = (struct nfgenmsg*)NLMSG_DATA(nlh);
    struct nlattr* attr = (struct nlattr*)((uint8_t*)nfg + NLMSG_ALIGN(sizeof(*nfg)));
    int attr_len = nlh->nlmsg_len - NLMSG_HDRLEN - NLMSG_ALIGN(sizeof(*nfg));
    
    while (attr_len > 0 && attr->nla_len >= sizeof(*attr)) {
        int type = attr->nla_type & NLA_TYPE_MASK;
        int len = attr->nla_len - sizeof(*attr);
        uint8_t* data = (uint8_t*)attr + sizeof(*attr);
        
        switch (type) {
            case NFQA_PACKET_HDR: {
                struct nfqnl_msg_packet_hdr* ph = (struct nfqnl_msg_packet_hdr*)data;
                pkt->packet_id = ntohl(ph->packet_id);
                break;
            }
            case NFQA_MARK:
                pkt->mark = ntohl(*(uint32_t*)data);
                break;
            case NFQA_PAYLOAD:
                pkt->payload = data;
                pkt->payload_len = len;
                
                // Parse IP header
                if (len >= 20) {
                    uint8_t ihl = (data[0] & 0x0F) * 4;
                    pkt->protocol = data[9];
                    pkt->src_ip = *(uint32_t*)(data + 12);
                    pkt->dst_ip = *(uint32_t*)(data + 16);
                    
                    // Parse TCP/UDP ports
                    if (len >= ihl + 4) {
                        pkt->src_port = ntohs(*(uint16_t*)(data + ihl));
                        pkt->dst_port = ntohs(*(uint16_t*)(data + ihl + 2));
                    }
                }
                break;
        }
        
        int padded_len = NFA_ALIGN_SIZE(attr->nla_len);
        attr = (struct nlattr*)((uint8_t*)attr + padded_len);
        attr_len -= padded_len;
    }
    
    return (pkt->packet_id != 0) ? 0 : -1;
}

/**
 * Send verdict
 */
static int send_verdict(uint32_t packet_id, uint32_t verdict, 
                        uint8_t* payload, uint32_t payload_len) {
    // Calculate message size
    size_t msg_len = NLMSG_ALIGN(sizeof(struct nlmsghdr)) +
                     NLMSG_ALIGN(sizeof(struct nfgenmsg)) +
                     NFA_ALIGN_SIZE(sizeof(struct nlattr) + sizeof(struct nfqnl_msg_verdict_hdr));
    
    if (payload && payload_len > 0) {
        msg_len += NFA_ALIGN_SIZE(sizeof(struct nlattr) + payload_len);
    }
    
    if (msg_len > SEND_BUFFER_SIZE) {
        LOGE("Message too large: %zu", msg_len);
        return -1;
    }
    
    memset(g_nfq.send_buffer, 0, msg_len);
    
    struct nlmsghdr* nlh = (struct nlmsghdr*)g_nfq.send_buffer;
    nlh->nlmsg_len = msg_len;
    nlh->nlmsg_type = (NFNL_SUBSYS_QUEUE << 8) | NFQNL_MSG_VERDICT;
    nlh->nlmsg_flags = NLM_F_REQUEST;
    nlh->nlmsg_seq = 0;
    nlh->nlmsg_pid = getpid();
    
    struct nfgenmsg* nfg = (struct nfgenmsg*)NLMSG_DATA(nlh);
    nfg->nfgen_family = AF_UNSPEC;
    nfg->version = NFNETLINK_V0;
    nfg->res_id = htons(g_nfq.queue_num);
    
    // Verdict attribute
    struct nlattr* attr = (struct nlattr*)((uint8_t*)nfg + NLMSG_ALIGN(sizeof(*nfg)));
    attr->nla_len = sizeof(*attr) + sizeof(struct nfqnl_msg_verdict_hdr);
    attr->nla_type = NFQA_VERDICT_HDR;
    
    struct nfqnl_msg_verdict_hdr* vh = (struct nfqnl_msg_verdict_hdr*)((uint8_t*)attr + sizeof(*attr));
    vh->verdict = htonl(verdict);
    vh->id = htonl(packet_id);
    
    // Payload attribute (if modified)
    if (payload && payload_len > 0) {
        attr = (struct nlattr*)((uint8_t*)attr + NFA_ALIGN_SIZE(attr->nla_len));
        attr->nla_len = sizeof(*attr) + payload_len;
        attr->nla_type = NFQA_PAYLOAD;
        memcpy((uint8_t*)attr + sizeof(*attr), payload, payload_len);
    }
    
    struct sockaddr_nl peer;
    memset(&peer, 0, sizeof(peer));
    peer.nl_family = AF_NETLINK;
    
    if (sendto(g_nfq.nl_socket, g_nfq.send_buffer, msg_len, 0,
               (struct sockaddr*)&peer, sizeof(peer)) < 0) {
        LOGE("sendto verdict failed: %s", strerror(errno));
        return -1;
    }
    
    return 0;
}

