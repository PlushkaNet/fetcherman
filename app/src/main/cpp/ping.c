// ICMP echo ping using unprivileged ping sockets (SOCK_DGRAM + IPPROTO_ICMP),
//
// Return values (as jdouble):
//   > 0  RTT in milliseconds
//   -1   timeout
//   -2   unreachable (ICMP error from the peer confirmed it)
//   -3   socket creation or send failed (caller should fall back)

#include <jni.h>

#include <arpa/inet.h>
#include <errno.h>
#include <stdint.h>
#include <string.h>
#include <sys/select.h>
#include <sys/socket.h>
#include <sys/types.h>
#include <time.h>
#include <unistd.h>

#define ICMPV4_ECHO_REQUEST 8
#define ICMPV4_ECHO_REPLY 0
#define ICMPV4_DEST_UNREACHABLE 3
#define ICMPV4_TIME_EXCEEDED 11

#define ICMPV6_ECHO_REQUEST 128
#define ICMPV6_ECHO_REPLY 129
#define ICMPV6_DEST_UNREACHABLE 1
#define ICMPV6_TIME_EXCEEDED 3

#define RESULT_TIMEOUT (-1.0)
#define RESULT_UNREACHABLE (-2.0)
#define RESULT_SOCKET_FAIL (-3.0)

static uint16_t seq_counter = 0;

static double now_sec(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (double)ts.tv_sec + (double)ts.tv_nsec / 1e9;
}

static uint16_t checksum16(const void *data, size_t len) {
    const uint8_t *p = (const uint8_t *)data;
    uint32_t sum = 0;
    while (len > 1) {
        sum += ((uint32_t)p[0] << 8) | p[1];
        p += 2;
        len -= 2;
    }
    if (len) sum += (uint32_t)p[0] << 8;
    while (sum >> 16) sum = (sum & 0xffff) + (sum >> 16);
    return (uint16_t)(~sum & 0xffff);
}

// ICMPv6 checksum covers a pseudo-header (src, dst, length, next header).
static uint16_t icmpv6_checksum(const struct in6_addr *src, const struct in6_addr *dst,
                                const void *icmp, size_t icmp_len) {
    uint8_t pseudo[40 + 256];
    memset(pseudo, 0, 40);
    memcpy(pseudo, src, 16);
    memcpy(pseudo + 16, dst, 16);
    uint32_t len = htonl((uint32_t)icmp_len);
    memcpy(pseudo + 32, &len, 4);
    pseudo[39] = IPPROTO_ICMPV6;
    memcpy(pseudo + 40, icmp, icmp_len);
    return checksum16(pseudo, 40 + icmp_len);
}

JNIEXPORT jdouble JNICALL
Java_plushkanet_fetcherman_Ping_nativePing(JNIEnv *env, jclass clazz, jstring jip, jint family,
                                       jdouble timeout_sec) {
    const char *ip = (*env)->GetStringUTFChars(env, jip, NULL);
    if (ip == NULL) return RESULT_UNREACHABLE;

    int is_v6 = (family == 6);
    int sock = socket(is_v6 ? AF_INET6 : AF_INET, SOCK_DGRAM,
                      is_v6 ? IPPROTO_ICMPV6 : IPPROTO_ICMP);
    if (sock < 0) {
        (*env)->ReleaseStringUTFChars(env, jip, ip);
        return RESULT_SOCKET_FAIL;
    }

    struct sockaddr_storage dst;
    memset(&dst, 0, sizeof(dst));
    socklen_t dstlen = is_v6 ? sizeof(struct sockaddr_in6) : sizeof(struct sockaddr_in);
    if (is_v6) {
        struct sockaddr_in6 *a6 = (struct sockaddr_in6 *)&dst;
        a6->sin6_family = AF_INET6;
        if (inet_pton(AF_INET6, ip, &a6->sin6_addr) != 1) {
            close(sock);
            (*env)->ReleaseStringUTFChars(env, jip, ip);
            return RESULT_UNREACHABLE;
        }
    } else {
        struct sockaddr_in *a4 = (struct sockaddr_in *)&dst;
        a4->sin_family = AF_INET;
        if (inet_pton(AF_INET, ip, &a4->sin_addr) != 1) {
            close(sock);
            (*env)->ReleaseStringUTFChars(env, jip, ip);
            return RESULT_UNREACHABLE;
        }
    }

    // ICMP echo request: type(1) code(1) checksum(2) id(2) seq(2) + 56-byte
    // payload (8-byte monotonic timestamp + 48 bytes of padding).
    // The kernel fills in the id and (for IPv4) the checksum by itself on
    // ping sockets, so only the sequence number and the v6 checksum are set
    // here; replies are matched by sequence number only.
    uint8_t pkt[8 + 56];
    memset(pkt, 0, sizeof(pkt));
    pkt[0] = is_v6 ? ICMPV6_ECHO_REQUEST : ICMPV4_ECHO_REQUEST;
    uint16_t seq = seq_counter++;
    pkt[6] = (uint8_t)(seq >> 8);
    pkt[7] = (uint8_t)(seq & 0xff);
    double sent = now_sec();
    memcpy(pkt + 8, &sent, sizeof(sent));

    struct sockaddr_storage send_to;
    socklen_t send_to_len = dstlen;
    if (is_v6) {
        // Discover the source address for the pseudo-header checksum.
        if (connect(sock, (struct sockaddr *)&dst, dstlen) < 0) {
            close(sock);
            (*env)->ReleaseStringUTFChars(env, jip, ip);
            return RESULT_SOCKET_FAIL;
        }
        memcpy(&send_to, &dst, dstlen);
        socklen_t slen = dstlen;
        if (getsockname(sock, (struct sockaddr *)&dst, &slen) < 0) {
            close(sock);
            (*env)->ReleaseStringUTFChars(env, jip, ip);
            return RESULT_SOCKET_FAIL;
        }
        uint16_t csum = icmpv6_checksum(&((struct sockaddr_in6 *)&dst)->sin6_addr,
                                        &((struct sockaddr_in6 *)&send_to)->sin6_addr,
                                        pkt, sizeof(pkt));
        pkt[2] = (uint8_t)(csum >> 8);
        pkt[3] = (uint8_t)(csum & 0xff);
    } else {
        memcpy(&send_to, &dst, dstlen);
    }

    if (sendto(sock, pkt, sizeof(pkt), 0, (struct sockaddr *)&send_to, send_to_len) < 0) {
        close(sock);
        (*env)->ReleaseStringUTFChars(env, jip, ip);
        return RESULT_SOCKET_FAIL;
    }

    double deadline = now_sec() + timeout_sec;
    double rtt_ms = RESULT_TIMEOUT;
    uint8_t buf[1500];
    for (;;) {
        double left = deadline - now_sec();
        if (left <= 0) break;

        fd_set fds;
        FD_ZERO(&fds);
        FD_SET(sock, &fds);
        struct timeval tv;
        tv.tv_sec = (time_t)left;
        tv.tv_usec = (suseconds_t)((left - (double)tv.tv_sec) * 1e6);
        int n = select(sock + 1, &fds, NULL, NULL, &tv);
        if (n <= 0) break;  // timeout

        struct sockaddr_storage from;
        socklen_t fromlen = sizeof(from);
        ssize_t got = recvfrom(sock, buf, sizeof(buf), 0,
                               (struct sockaddr *)&from, &fromlen);
        if (got < 0) {
            // Connected ICMPv6 sockets surface unreachable errors as errno.
            if (errno == ECONNREFUSED || errno == EHOSTUNREACH || errno == ENETUNREACH ||
                errno == ECONNRESET) {
                rtt_ms = RESULT_UNREACHABLE;
            }
            continue;
        }
        if (got < 8) continue;

        uint8_t type = buf[0];
        // Note: on Linux ping sockets the kernel rewrites the ICMP id with its
        // own socket id, so only the sequence number can be matched.
        uint16_t rseq = (uint16_t)((buf[6] << 8) | buf[7]);
        if (rseq != seq) continue;  // somebody else's ping

        if (type == (is_v6 ? ICMPV6_ECHO_REPLY : ICMPV4_ECHO_REPLY)) {
            double sent_ts;
            memcpy(&sent_ts, buf + 8, sizeof(sent_ts));
            double rtt = now_sec() - sent_ts;
            if (rtt < 0) rtt = 0;
            rtt_ms = rtt * 1000.0;
            break;
        } else if (type == (is_v6 ? ICMPV6_DEST_UNREACHABLE : ICMPV4_DEST_UNREACHABLE) ||
                   type == (is_v6 ? ICMPV6_TIME_EXCEEDED : ICMPV4_TIME_EXCEEDED)) {
            rtt_ms = RESULT_UNREACHABLE;
            break;
        }
        // Anything else (e.g. a foreign echo request): keep waiting.
    }

    close(sock);
    (*env)->ReleaseStringUTFChars(env, jip, ip);
    return rtt_ms;
}