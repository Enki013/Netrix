/**
 * nfqueue_daemon.c
 * 
 * Standalone NFQUEUE daemon for Android.
 * Runs as root to bypass SELinux restrictions.
 * Communicates with the app via Unix socket.
 * 
 * Usage: su -c /data/local/tmp/nfqueue_daemon
 */

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <signal.h>
#include <pthread.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <sys/stat.h>
#include <errno.h>
#include <fcntl.h>

// Include NFQUEUE handler
#include "../nfqueue_handler.h"
#include "../dpi_bypass.h"

#define SOCKET_PATH "/data/local/tmp/netrix.sock"
#define PID_FILE "/data/local/tmp/netrix.pid"
#define LOG_FILE "/data/local/tmp/netrix.log"
#define BUFFER_SIZE 4096
#define MAX_CLIENTS 5

// Logging
static FILE* log_file = NULL;

#define LOG(fmt, ...) do { \
    if (log_file) { \
        fprintf(log_file, "[DAEMON] " fmt "\n", ##__VA_ARGS__); \
        fflush(log_file); \
    } \
    fprintf(stderr, "[DAEMON] " fmt "\n", ##__VA_ARGS__); \
} while(0)

// Global state
static volatile int running = 1;
static volatile int nfqueue_active = 0;
static int server_socket = -1;
static pthread_t nfqueue_thread;
static pthread_mutex_t state_lock = PTHREAD_MUTEX_INITIALIZER;

// Forward declarations
static void signal_handler(int sig);
static int setup_server_socket(void);
static void handle_client(int client_fd);
static void* nfqueue_thread_func(void* arg);
static int parse_and_execute_command(const char* cmd, char* response, size_t resp_size);
static void cleanup(void);
static void write_pid_file(void);
static int setup_iptables(void);
static int clear_iptables(void);

/**
 * Main entry point
 */
int main(int argc, char* argv[]) {
    // Daemonize if requested
    if (argc > 1 && strcmp(argv[1], "-d") == 0) {
        if (fork() != 0) {
            exit(0);  // Parent exits
        }
        setsid();
    }
    
    // Open log file
    log_file = fopen(LOG_FILE, "a");
    if (!log_file) {
        log_file = stderr;
    }
    
    LOG("Starting NFQUEUE daemon...");
    
    // Write PID file
    write_pid_file();
    
    // Setup signal handlers
    signal(SIGINT, signal_handler);
    signal(SIGTERM, signal_handler);
    signal(SIGPIPE, SIG_IGN);
    
    // Initialize DPI bypass with defaults
    DpiBypassSettings settings = {
        .method = BYPASS_SPLIT,
        .first_packet_size = 2,
        .split_delay_ms = 50,
        .split_count = 4,
        .desync_https = true,
        .desync_http = true,
        .mix_host_case = true,
        .block_quic = true
    };
    dpi_bypass_init(&settings);
    
    // Setup server socket
    server_socket = setup_server_socket();
    if (server_socket < 0) {
        LOG("Failed to setup server socket");
        cleanup();
        return 1;
    }
    
    LOG("Daemon started, listening on %s", SOCKET_PATH);
    
    // Main loop - accept client connections
    while (running) {
        struct sockaddr_un client_addr;
        socklen_t client_len = sizeof(client_addr);
        
        int client_fd = accept(server_socket, (struct sockaddr*)&client_addr, &client_len);
        if (client_fd < 0) {
            if (errno == EINTR) continue;
            if (!running) break;
            LOG("Accept error: %s", strerror(errno));
            continue;
        }
        
        LOG("Client connected");
        handle_client(client_fd);
        close(client_fd);
        LOG("Client disconnected");
    }
    
    cleanup();
    LOG("Daemon stopped");
    
    if (log_file && log_file != stderr) {
        fclose(log_file);
    }
    
    return 0;
}

/**
 * Signal handler
 */
static void signal_handler(int sig) {
    LOG("Received signal %d", sig);
    running = 0;
    
    // Close server socket to unblock accept()
    if (server_socket >= 0) {
        shutdown(server_socket, SHUT_RDWR);
        close(server_socket);
        server_socket = -1;
    }
}

/**
 * Setup Unix domain socket server
 */
static int setup_server_socket(void) {
    // Remove old socket file
    unlink(SOCKET_PATH);
    
    int sock = socket(AF_UNIX, SOCK_STREAM, 0);
    if (sock < 0) {
        LOG("Failed to create socket: %s", strerror(errno));
        return -1;
    }
    
    struct sockaddr_un addr;
    memset(&addr, 0, sizeof(addr));
    addr.sun_family = AF_UNIX;
    strncpy(addr.sun_path, SOCKET_PATH, sizeof(addr.sun_path) - 1);
    
    if (bind(sock, (struct sockaddr*)&addr, sizeof(addr)) < 0) {
        LOG("Failed to bind socket: %s", strerror(errno));
        close(sock);
        return -1;
    }
    
    // Set socket permissions (readable/writable by all for app access)
    chmod(SOCKET_PATH, 0666);
    
    if (listen(sock, MAX_CLIENTS) < 0) {
        LOG("Failed to listen: %s", strerror(errno));
        close(sock);
        return -1;
    }
    
    return sock;
}

/**
 * Handle client connection
 */
static void handle_client(int client_fd) {
    char buffer[BUFFER_SIZE];
    char response[BUFFER_SIZE];
    
    while (running) {
        memset(buffer, 0, sizeof(buffer));
        
        ssize_t len = recv(client_fd, buffer, sizeof(buffer) - 1, 0);
        if (len <= 0) {
            if (len < 0 && errno == EINTR) continue;
            break;  // Client disconnected
        }
        
        buffer[len] = '\0';
        LOG("Received: %s", buffer);
        
        // Parse and execute command
        memset(response, 0, sizeof(response));
        parse_and_execute_command(buffer, response, sizeof(response));
        
        // Send response
        if (response[0]) {
            send(client_fd, response, strlen(response), 0);
        }
    }
}

// Simple packet counter callback for debugging
static uint64_t g_packet_count = 0;

static NfqueueVerdict debug_packet_callback(NfqueuePacket* packet, void* user_data) {
    g_packet_count++;
    
    if (g_packet_count <= 5 || g_packet_count % 100 == 0) {
        LOG("[PACKET #%llu] dst=%d.%d.%d.%d:%d proto=%d len=%u",
            (unsigned long long)g_packet_count,
            (packet->dst_ip) & 0xFF,
            (packet->dst_ip >> 8) & 0xFF,
            (packet->dst_ip >> 16) & 0xFF,
            (packet->dst_ip >> 24) & 0xFF,
            packet->dst_port,
            packet->protocol,
            packet->payload_len);
    }
    
    // Call the real bypass function
    return dpi_bypass_process_packet(packet, user_data);
}

/**
 * NFQUEUE processing thread
 */
static void* nfqueue_thread_func(void* arg) {
    (void)arg;
    
    LOG("=== NFQUEUE THREAD STARTED ===");
    
    // Initialize raw socket for packet injection
    LOG("Initializing raw socket...");
    if (dpi_raw_socket_init() < 0) {
        LOG("!!! CRITICAL: Failed to initialize raw socket !!!");
        LOG("Bypass will NOT work - packets will be dropped!");
    } else {
        LOG("Raw socket initialized OK");
    }
    
    // Set callback with debug wrapper
    LOG("Setting packet callback...");
    nfqueue_set_callback(debug_packet_callback, NULL);
    
    LOG("Starting NFQUEUE packet loop (blocking)...");
    
    // Start processing (blocking)
    int result = nfqueue_start();
    
    LOG("=== NFQUEUE THREAD STOPPED: result=%d, packets=%llu ===", 
        result, (unsigned long long)g_packet_count);
    
    // Cleanup raw socket
    dpi_raw_socket_cleanup();
    
    pthread_mutex_lock(&state_lock);
    nfqueue_active = 0;
    pthread_mutex_unlock(&state_lock);
    
    return NULL;
}

/**
 * Parse JSON command and execute
 * Simple JSON parser for our protocol
 */
static int parse_and_execute_command(const char* cmd, char* response, size_t resp_size) {
    // Find command type
    if (strstr(cmd, "\"cmd\":\"start\"") || strstr(cmd, "\"cmd\": \"start\"")) {
        // START command
        pthread_mutex_lock(&state_lock);
        
        if (nfqueue_active) {
            snprintf(response, resp_size, "{\"status\":\"ok\",\"message\":\"already running\"}");
            pthread_mutex_unlock(&state_lock);
            return 0;
        }
        
        // Setup iptables
        if (setup_iptables() < 0) {
            snprintf(response, resp_size, "{\"status\":\"error\",\"message\":\"iptables setup failed\"}");
            pthread_mutex_unlock(&state_lock);
            return -1;
        }
        
        // Initialize NFQUEUE
        LOG("Initializing NFQUEUE (queue=0)...");
        int nfq_result = nfqueue_init(0);
        if (nfq_result < 0) {
            LOG("!!! NFQUEUE INIT FAILED: %s !!!", nfqueue_get_error());
            snprintf(response, resp_size, "{\"status\":\"error\",\"message\":\"%s\"}", nfqueue_get_error());
            clear_iptables();
            pthread_mutex_unlock(&state_lock);
            return -1;
        }
        LOG("NFQUEUE initialized OK");
        
        nfqueue_active = 1;
        pthread_mutex_unlock(&state_lock);
        
        // Start NFQUEUE thread
        if (pthread_create(&nfqueue_thread, NULL, nfqueue_thread_func, NULL) != 0) {
            nfqueue_cleanup();
            clear_iptables();
            pthread_mutex_lock(&state_lock);
            nfqueue_active = 0;
            pthread_mutex_unlock(&state_lock);
            snprintf(response, resp_size, "{\"status\":\"error\",\"message\":\"thread creation failed\"}");
            return -1;
        }
        
        LOG("NFQUEUE started");
        snprintf(response, resp_size, "{\"status\":\"ok\",\"running\":true}");
        
    } else if (strstr(cmd, "\"cmd\":\"stop\"") || strstr(cmd, "\"cmd\": \"stop\"")) {
        // STOP command
        pthread_mutex_lock(&state_lock);
        
        if (!nfqueue_active) {
            snprintf(response, resp_size, "{\"status\":\"ok\",\"message\":\"not running\"}");
            pthread_mutex_unlock(&state_lock);
            return 0;
        }
        
        pthread_mutex_unlock(&state_lock);
        
        // Stop NFQUEUE
        nfqueue_stop();
        pthread_join(nfqueue_thread, NULL);
        nfqueue_cleanup();
        
        // Clear iptables
        clear_iptables();
        
        pthread_mutex_lock(&state_lock);
        nfqueue_active = 0;
        pthread_mutex_unlock(&state_lock);
        
        LOG("NFQUEUE stopped");
        snprintf(response, resp_size, "{\"status\":\"ok\",\"running\":false}");
        
    } else if (strstr(cmd, "\"cmd\":\"status\"") || strstr(cmd, "\"cmd\": \"status\"")) {
        // STATUS command
        pthread_mutex_lock(&state_lock);
        int is_running = nfqueue_active;
        pthread_mutex_unlock(&state_lock);
        
        DpiBypassStats stats = dpi_bypass_get_stats();
        snprintf(response, resp_size, 
                "{\"status\":\"ok\",\"running\":%s,\"packets\":%llu,\"bypassed\":%llu}",
                is_running ? "true" : "false",
                (unsigned long long)stats.packets_total,
                (unsigned long long)stats.packets_bypassed);
        
    } else if (strstr(cmd, "\"cmd\":\"settings\"") || strstr(cmd, "\"cmd\": \"settings\"")) {
        // UPDATE SETTINGS command
        // Parse settings from JSON
        DpiBypassSettings settings;
        DpiBypassSettings* current = dpi_bypass_get_settings();
        memcpy(&settings, current, sizeof(settings));
        
        // Parse method
        if (strstr(cmd, "\"method\":\"SPLIT\"")) settings.method = BYPASS_SPLIT;
        else if (strstr(cmd, "\"method\":\"SPLIT_REVERSE\"")) settings.method = BYPASS_SPLIT_REVERSE;
        else if (strstr(cmd, "\"method\":\"DISORDER\"")) settings.method = BYPASS_DISORDER;
        else if (strstr(cmd, "\"method\":\"DISORDER_REVERSE\"")) settings.method = BYPASS_DISORDER_REVERSE;
        
        // Parse other settings (simplified)
        char* ptr;
        if ((ptr = strstr(cmd, "\"first_packet_size\":")) != NULL) {
            settings.first_packet_size = atoi(ptr + 20);
        }
        if ((ptr = strstr(cmd, "\"split_delay\":")) != NULL) {
            settings.split_delay_ms = atoi(ptr + 14);
        }
        if ((ptr = strstr(cmd, "\"split_count\":")) != NULL) {
            settings.split_count = atoi(ptr + 14);
        }
        if (strstr(cmd, "\"desync_https\":true")) settings.desync_https = true;
        if (strstr(cmd, "\"desync_https\":false")) settings.desync_https = false;
        if (strstr(cmd, "\"desync_http\":true")) settings.desync_http = true;
        if (strstr(cmd, "\"desync_http\":false")) settings.desync_http = false;
        if (strstr(cmd, "\"block_quic\":true")) settings.block_quic = true;
        if (strstr(cmd, "\"block_quic\":false")) settings.block_quic = false;
        
        dpi_bypass_update_settings(&settings);
        LOG("Settings updated");
        snprintf(response, resp_size, "{\"status\":\"ok\"}");
        
    } else if (strstr(cmd, "\"cmd\":\"ping\"") || strstr(cmd, "\"cmd\": \"ping\"")) {
        // PING command (keepalive)
        snprintf(response, resp_size, "{\"status\":\"ok\",\"pong\":true}");
        
    } else if (strstr(cmd, "\"cmd\":\"exit\"") || strstr(cmd, "\"cmd\": \"exit\"")) {
        // EXIT command - shutdown daemon
        LOG("Exit command received");
        running = 0;
        snprintf(response, resp_size, "{\"status\":\"ok\",\"exiting\":true}");
        
    } else {
        snprintf(response, resp_size, "{\"status\":\"error\",\"message\":\"unknown command\"}");
        return -1;
    }
    
    return 0;
}

// Packet mark used by our raw socket (must match dpi_bypass.c)
#define OUR_PACKET_MARK 0x10DEAD

/**
 * Setup iptables rules
 */
static int setup_iptables(void) {
    LOG("=== SETTING UP IPTABLES ===");
    
    // Clear existing rules first
    clear_iptables();
    
    // Check if iptables is available
    int check = system("which iptables > /dev/null 2>&1");
    if (check != 0) {
        LOG("!!! ERROR: iptables not found in PATH !!!");
        return -1;
    }
    LOG("iptables found OK");
    
    // IMPORTANT: First rule - ACCEPT packets with our mark (avoid re-capturing our own injected packets)
    char mark_cmd[256];
    snprintf(mark_cmd, sizeof(mark_cmd),
             "iptables -I OUTPUT -m mark --mark 0x%X -j ACCEPT 2>&1",
             OUR_PACKET_MARK);
    LOG("Running: %s", mark_cmd);
    int ret0 = system(mark_cmd);
    LOG("Mark rule result: %d", ret0);
    if (ret0 != 0) {
        LOG("Warning: Could not add mark exception rule (may need xt_mark module)");
        // Continue anyway, mark may not be supported on this kernel
    }
    
    // Add NFQUEUE rules for HTTPS and HTTP (after mark exception)
    LOG("Adding NFQUEUE rule for port 443...");
    int ret1 = system("iptables -A OUTPUT -p tcp --dport 443 -j NFQUEUE --queue-num 0 --queue-bypass 2>&1");
    LOG("Port 443 rule result: %d", ret1);
    
    LOG("Adding NFQUEUE rule for port 80...");
    int ret2 = system("iptables -A OUTPUT -p tcp --dport 80 -j NFQUEUE --queue-num 0 --queue-bypass 2>&1");
    LOG("Port 80 rule result: %d", ret2);
    
    if (ret1 != 0 || ret2 != 0) {
        LOG("!!! ERROR: iptables NFQUEUE rules failed: %d, %d !!!", ret1, ret2);
        // Try without --queue-bypass
        LOG("Trying without --queue-bypass...");
        ret1 = system("iptables -A OUTPUT -p tcp --dport 443 -j NFQUEUE --queue-num 0 2>&1");
        ret2 = system("iptables -A OUTPUT -p tcp --dport 80 -j NFQUEUE --queue-num 0 2>&1");
        LOG("Retry results: %d, %d", ret1, ret2);
        
        if (ret1 != 0 || ret2 != 0) {
            LOG("!!! CRITICAL: Cannot setup iptables rules !!!");
            return -1;
        }
    }
    
    // Verify rules
    LOG("Verifying iptables rules...");
    system("iptables -L OUTPUT -n -v 2>&1 | head -10");
    
    LOG("=== IPTABLES SETUP COMPLETE (mark=0x%X) ===", OUR_PACKET_MARK);
    return 0;
}

/**
 * Clear iptables rules
 */
static int clear_iptables(void) {
    LOG("Clearing iptables...");
    
    char mark_cmd[256];
    snprintf(mark_cmd, sizeof(mark_cmd),
             "iptables -D OUTPUT -m mark --mark 0x%X -j ACCEPT 2>/dev/null",
             OUR_PACKET_MARK);
    
    // Remove NFQUEUE rules (run multiple times to clear all)
    for (int i = 0; i < 5; i++) {
        system(mark_cmd);
        system("iptables -D OUTPUT -p tcp --dport 443 -j NFQUEUE --queue-num 0 2>/dev/null");
        system("iptables -D OUTPUT -p tcp --dport 443 -j NFQUEUE --queue-num 0 --queue-bypass 2>/dev/null");
        system("iptables -D OUTPUT -p tcp --dport 80 -j NFQUEUE --queue-num 0 2>/dev/null");
        system("iptables -D OUTPUT -p tcp --dport 80 -j NFQUEUE --queue-num 0 --queue-bypass 2>/dev/null");
    }
    
    return 0;
}

/**
 * Write PID file
 */
static void write_pid_file(void) {
    FILE* f = fopen(PID_FILE, "w");
    if (f) {
        fprintf(f, "%d", getpid());
        fclose(f);
    }
}

/**
 * Cleanup resources
 */
static void cleanup(void) {
    LOG("Cleaning up...");
    
    // Stop NFQUEUE if running
    pthread_mutex_lock(&state_lock);
    if (nfqueue_active) {
        pthread_mutex_unlock(&state_lock);
        nfqueue_stop();
        pthread_join(nfqueue_thread, NULL);
        nfqueue_cleanup();
    } else {
        pthread_mutex_unlock(&state_lock);
    }
    
    // Clear iptables
    clear_iptables();
    
    // Close server socket
    if (server_socket >= 0) {
        close(server_socket);
        server_socket = -1;
    }
    
    // Remove socket and PID files
    unlink(SOCKET_PATH);
    unlink(PID_FILE);
}

