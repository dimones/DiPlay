#include <arpa/inet.h>
#include <errno.h>
#include <ifaddrs.h>
#include <net/if_dl.h>
#include <pcap/pcap.h>
#include <poll.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <time.h>
#include <unistd.h>

/* Bounded Ethernet relay on an explicitly selected iPhone NCM interface.
 * No route/configuration changes; no packet recording. Run only after verifying
 * the selected interface belongs to the attached iPhone in IORegistry. */
static volatile sig_atomic_t stopped;
static void stop(int signo) { (void)signo; stopped = 1; }
static double now(void) {
    struct timespec t; clock_gettime(CLOCK_MONOTONIC, &t);
    return t.tv_sec + t.tv_nsec / 1e9;
}
static int send_all(int fd, const void *data, size_t n) {
    const char *p = data;
    while (n && !stopped) {
        ssize_t sent = send(fd, p, n, 0);
        if (sent < 0 && errno == EINTR) continue;
        if (sent <= 0) return -1;
        p += sent; n -= sent;
    }
    return n ? -1 : 0;
}
int main(int argc, char **argv) {
    if (argc != 2 || strlen(argv[1]) > 15 || strncmp(argv[1], "en", 2) != 0) {
        fprintf(stderr, "Usage: %s <verified-iPhone-NCM-interface>\n", argv[0]); return 2;
    }
    for (const char *p = argv[1] + 2; *p; p++) if (*p < '0' || *p > '9') return 2;
    setvbuf(stdout, NULL, _IOLBF, 0);
    signal(SIGINT, stop); signal(SIGTERM, stop); signal(SIGPIPE, SIG_IGN);
    unsigned char mac[6] = {0}, lab[16];
    inet_pton(AF_INET6, "fe80::d1:1", lab);
    struct ifaddrs *interfaces = NULL;
    if (getifaddrs(&interfaces)) return 1;
    int found = 0;
    for (struct ifaddrs *it = interfaces; it; it = it->ifa_next) {
        if (strcmp(it->ifa_name, argv[1]) || !it->ifa_addr || it->ifa_addr->sa_family != AF_LINK) continue;
        struct sockaddr_dl *dl = (struct sockaddr_dl *)it->ifa_addr;
        if (dl->sdl_alen == 6) { memcpy(mac, LLADDR(dl), 6); found = 1; break; }
    }
    freeifaddrs(interfaces);
    if (!found) { fputs("Interface has no Ethernet address\n", stderr); return 1; }
    char error[PCAP_ERRBUF_SIZE] = {0};
    pcap_t *capture = pcap_create(argv[1], error);
    if (!capture) { fprintf(stderr, "%s\n", error); return 1; }
    int listener = -1, peer = -1, rc = 1;
    pcap_set_snaplen(capture, 2048); pcap_set_promisc(capture, 0);
    pcap_set_timeout(capture, 20); pcap_set_immediate_mode(capture, 1);
    if (pcap_activate(capture) < 0) { fprintf(stderr, "%s\n", pcap_geterr(capture)); goto done; }
    if (pcap_datalink(capture) != DLT_EN10MB || pcap_setdirection(capture, PCAP_D_IN)) goto done;
    struct bpf_program filter;
    if (pcap_compile(capture, &filter,
        "ip6 and (dst host fe80::d1:1 or dst net ff02::/16)", 1, PCAP_NETMASK_UNKNOWN)) goto done;
    int filtered = pcap_setfilter(capture, &filter);
    pcap_freecode(&filter); if (filtered) goto done;
    if (pcap_setnonblock(capture, 1, error)) goto done;
    listener = socket(AF_INET, SOCK_STREAM, 0); if (listener < 0) goto done;
    int yes = 1; setsockopt(listener, SOL_SOCKET, SO_REUSEADDR, &yes, sizeof(yes));
    struct sockaddr_in addr = { .sin_len = sizeof(addr), .sin_family = AF_INET,
        .sin_port = htons(19778), .sin_addr.s_addr = htonl(INADDR_LOOPBACK) };
    if (bind(listener, (struct sockaddr *)&addr, sizeof(addr)) || listen(listener, 1)) goto done;
    printf("LISTEN 127.0.0.1:19778 interface=%s; IPv6 lab address fe80::d1:1\n", argv[1]);
    struct pollfd waiting = { .fd = listener, .events = POLLIN };
    if (poll(&waiting, 1, 120000) <= 0 || stopped) goto done;
    peer = accept(listener, NULL, NULL); close(listener); listener = -1;
    if (peer < 0) goto done;
    struct timeval timeout = { .tv_sec = 3 };
    setsockopt(peer, SOL_SOCKET, SO_SNDTIMEO, &timeout, sizeof(timeout));
    unsigned char hello[10] = {'D','I','P','N'}; memcpy(hello + 4, mac, 6);
    if (send_all(peer, hello, sizeof(hello))) goto done;
    unsigned char buffer[2052]; size_t used = 0;
    unsigned long long tx = 0, rx = 0, dropped = 0;
    const double deadline = now() + 180.0;
    while (!stopped && now() < deadline) {
        struct pollfd fds[2] = {{.fd=peer,.events=POLLIN}, {.fd=pcap_get_selectable_fd(capture),.events=POLLIN}};
        int ready = poll(fds, 2, 20);
        if (ready < 0 && errno == EINTR) continue;
        if (ready < 0 || (fds[0].revents & (POLLERR|POLLNVAL))) break;
        if (fds[0].revents & (POLLIN|POLLHUP)) {
            ssize_t n = recv(peer, buffer + used, sizeof(buffer) - used, 0);
            if (n <= 0) { rc = 0; break; }
            used += n;
            while (used >= 4) {
                uint32_t size; memcpy(&size, buffer, 4); size = ntohl(size);
                if (size < 54 || size > 2048) goto summary;
                if (used < size + 4) break;
                const unsigned char *frame = buffer + 4;
                /* Only the lab's IPv6 packets may leave this process. */
                const unsigned char zero[16] = {0};
                int dad = size >= 78 && !memcmp(frame + 22, zero, 16) && frame[20] == 58 &&
                    frame[54] == 135 && !memcmp(frame + 62, lab, 16);
                if (frame[12] != 0x86 || frame[13] != 0xdd || memcmp(frame + 6, mac, 6) ||
                    (memcmp(frame + 22, lab, 16) && !dad) ||
                    !(frame[38] == 0xff || (frame[38] == 0xfe && (frame[39] & 0xc0) == 0x80))) {
                    /* The Android tunnel also emits automatic IPv6 control traffic.
                     * Ignore packets outside the explicit lab address instead of ending the session. */
                    if (dropped++ < 3) {
                        char source[INET6_ADDRSTRLEN]; inet_ntop(AF_INET6, frame + 22, source, sizeof(source));
                        fprintf(stderr, "Ignored non-lab IPv6 source=%s next_header=%u\n", source, frame[20]);
                    }
                } else if (pcap_inject(capture, frame, size) != (int)size) {
                    fprintf(stderr, "NCM inject failed: %s\n", pcap_geterr(capture)); goto summary;
                } else tx++;
                used -= size + 4; memmove(buffer, buffer + size + 4, used);
            }
        }
        for (int batch = 0; batch < 64; batch++) {
            struct pcap_pkthdr *header; const unsigned char *data;
            int next = pcap_next_ex(capture, &header, &data);
            if (next == 0) break;
            if (next < 0) { fprintf(stderr, "NCM receive failed: %s\n", pcap_geterr(capture)); goto summary; }
            if (header->caplen != header->len || header->caplen < 54 || header->caplen > 2048) continue;
            uint32_t length = htonl(header->caplen);
            if (send_all(peer, &length, 4) || send_all(peer, data, header->caplen)) goto summary;
            rx++;
        }
    }
summary:
    printf("CLOSED frames_to_phone=%llu frames_to_android=%llu dropped=%llu\n", tx, rx, dropped);
done:
    if (peer >= 0) close(peer);
    if (listener >= 0) close(listener);
    pcap_close(capture);
    return rc;
}
