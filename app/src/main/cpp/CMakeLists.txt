# CMakeLists.txt for netrix Native NFQUEUE Support
cmake_minimum_required(VERSION 3.22.1)

project("nfqueue_handler" C)

# Android log library
find_library(log-lib log)

# ============================================================================
# Shared Library (for JNI - used in non-root mode)
# ============================================================================

set(JNI_SOURCES
    nfqueue_handler.c
    nfqueue_jni.c
    dpi_bypass.c
)

add_library(
    nfqueue_handler
    SHARED
    ${JNI_SOURCES}
)

target_compile_options(nfqueue_handler PRIVATE
    -Wall
    -Wextra
    -O2
    -fPIC
)

target_include_directories(nfqueue_handler PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
)

target_link_libraries(
    nfqueue_handler
    ${log-lib}
)

# ============================================================================
# Standalone Daemon Executable (for root mode)
# This binary runs as root and bypasses SELinux restrictions
# ============================================================================

set(DAEMON_SOURCES
    daemon/nfqueue_daemon.c
    nfqueue_handler.c
    dpi_bypass.c
)

add_executable(
    nfqueue_daemon
    ${DAEMON_SOURCES}
)

target_compile_options(nfqueue_daemon PRIVATE
    -Wall
    -Wextra
    -O2
    -static-libgcc
)

target_include_directories(nfqueue_daemon PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
)

# Link statically for standalone binary
target_link_libraries(
    nfqueue_daemon
    ${log-lib}
)

# Strip the binary for smaller size
add_custom_command(TARGET nfqueue_daemon POST_BUILD
    COMMAND ${CMAKE_STRIP} $<TARGET_FILE:nfqueue_daemon>
    COMMENT "Stripping daemon binary..."
)

# Copy daemon to assets directory after build
# This will be handled by Gradle instead
# add_custom_command(TARGET nfqueue_daemon POST_BUILD
#     COMMAND ${CMAKE_COMMAND} -E copy $<TARGET_FILE:nfqueue_daemon> 
#             ${CMAKE_SOURCE_DIR}/../assets/${ANDROID_ABI}/nfqueue_daemon
# )

# ============================================================================
# Notes
# ============================================================================
# 
# This implementation uses raw netlink sockets for maximum compatibility.
# The daemon binary must be run as root to access NFQUEUE.
# 
# Usage:
#   1. Copy daemon to /data/local/tmp/
#   2. chmod 755 /data/local/tmp/nfqueue_daemon
#   3. su -c /data/local/tmp/nfqueue_daemon -d
#   4. Connect via Unix socket /data/local/tmp/netrix.sock

