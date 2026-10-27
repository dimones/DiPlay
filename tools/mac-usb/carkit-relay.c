#include <arpa/inet.h>
#include <errno.h>
#include <poll.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <sys/socket.h>
#include <time.h>
#include <unistd.h>
#include <libimobiledevice/libimobiledevice.h>
#include <libimobiledevice/lockdown.h>

/* One loopback client, one bounded CarKit session. No pairing records or payloads logged. */
static volatile sig_atomic_t stopped;
static void stop(int signo) { (void)signo; stopped = 1; }
static double now(void) {
    struct timespec t; clock_gettime(CLOCK_MONOTONIC, &t);
    return t.tv_sec + t.tv_nsec / 1e9;
}
static int send_all(int fd, const char *buf, size_t n) {
    while (n && !stopped) {
        ssize_t sent = send(fd, buf, n, 0);
        if (sent < 0 && errno == EINTR) continue;
        if (sent <= 0) return -1;
        buf += sent; n -= sent;
    }
    return n ? -1 : 0;
}
int main(void) {
    setvbuf(stdout, NULL, _IOLBF, 0);
    signal(SIGINT, stop); signal(SIGTERM, stop); signal(SIGPIPE, SIG_IGN);
    int listener = -1, peer = -1, rc = 1;
    idevice_t dev = NULL;
    lockdownd_client_t lockdown = NULL;
    lockdownd_service_descriptor_t service = NULL;
    idevice_connection_t connection = NULL;
    listener = socket(AF_INET, SOCK_STREAM, 0);
    if (listener < 0) goto done;
    int yes = 1;
    setsockopt(listener, SOL_SOCKET, SO_REUSEADDR, &yes, sizeof(yes));
    struct sockaddr_in addr = { .sin_len = sizeof(addr), .sin_family = AF_INET,
        .sin_port = htons(19777), .sin_addr.s_addr = htonl(INADDR_LOOPBACK) };
    if (bind(listener, (struct sockaddr *)&addr, sizeof(addr)) || listen(listener, 1)) {
        perror("listen"); goto done;
    }
    puts("LISTEN 127.0.0.1:19777; waiting up to 120 seconds");
    struct pollfd p = { .fd = listener, .events = POLLIN };
    if (poll(&p, 1, 120000) <= 0 || stopped) goto done;
    peer = accept(listener, NULL, NULL);
    close(listener); listener = -1;
    if (peer < 0) goto done;
    struct timeval timeout = { .tv_sec = 3, .tv_usec = 0 };
    setsockopt(peer, SOL_SOCKET, SO_SNDTIMEO, &timeout, sizeof(timeout));
    int error = idevice_new_with_options(&dev, NULL, IDEVICE_LOOKUP_USBMUX);
    printf("USBMUX discovery=%d\n", error); if (error) goto done;
    error = lockdownd_client_new_with_handshake(dev, &lockdown, "diplay-mac-usb-lab");
    printf("Lockdown handshake=%d\n", error); if (error) goto done;
    error = lockdownd_start_service(lockdown, "com.apple.carkit.service", &service);
    printf("CarKit StartService=%d\n", error); if (error || !service) goto done;
    error = idevice_connect(dev, service->port, &connection);
    printf("CarKit connect=%d\n", error); if (error) goto done;
    if (service->ssl_enabled) {
        error = idevice_connection_enable_ssl(connection);
        printf("CarKit TLS=%d\n", error); if (error) goto done;
    }
    puts("READY");
    const double deadline = now() + 180.0;
    unsigned long long tx = 0, rx = 0;
    char buffer[16384];
    while (!stopped && now() < deadline) {
        struct pollfd poller = { .fd = peer, .events = POLLIN };
        int ready = poll(&poller, 1, 0);
        if (ready < 0 && errno == EINTR) continue;
        if (ready < 0 || (poller.revents & (POLLERR | POLLNVAL))) break;
        if (poller.revents & (POLLIN | POLLHUP)) {
            ssize_t n = recv(peer, buffer, sizeof(buffer), 0);
            if (n <= 0) { puts("Android closed"); rc = 0; break; }
            uint32_t offset = 0;
            while (offset < (uint32_t)n) {
                uint32_t sent = 0;
                error = idevice_connection_send(connection, buffer + offset, (uint32_t)n - offset, &sent);
                if (error || !sent) { printf("USB send error=%d\n", error); goto transferred; }
                offset += sent; tx += sent;
            }
        }
        uint32_t received = 0;
        error = idevice_connection_receive_timeout(connection, buffer, sizeof(buffer), &received, 25);
        if (received) {
            rx += received;
            if (send_all(peer, buffer, received)) break;
        }
        if (error && error != IDEVICE_E_TIMEOUT) { printf("USB receive error=%d\n", error); break; }
    }
transferred:
    printf("CLOSED android_to_phone=%llu phone_to_android=%llu\n", tx, rx);
done:
    if (connection) idevice_disconnect(connection);
    if (service) lockdownd_service_descriptor_free(service);
    if (lockdown) lockdownd_client_free(lockdown);
    if (dev) idevice_free(dev);
    if (peer >= 0) close(peer);
    if (listener >= 0) close(listener);
    return rc;
}
