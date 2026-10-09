#include "mux.h"

#include <arpa/inet.h>
#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <sys/socket.h>
#include <unistd.h>

static volatile sig_atomic_t stopping;
static int stop_write = -1;
static void stop(int signal_number)
{
    (void)signal_number;
    int saved_errno = errno;
    stopping = 1;
    /* Wake poll even if the signal arrives just before it starts waiting. */
    if (stop_write >= 0) { ssize_t ignored = write(stop_write, "s", 1); (void)ignored; }
    errno = saved_errno;
}

int main(int argc, char **argv)
{
    if (argc != 3 && argc != 4) { fprintf(stderr, "Usage: mux_test_server WIRE_PORT TARGET_PORT [SOCKS_PORT]\n"); return 2; }
    char *end;
    long wire_port = strtol(argv[1], &end, 10);
    if (*end || wire_port < 1 || wire_port > 65535) return 2;
    long target_port = strtol(argv[2], &end, 10);
    if (*end || target_port < 1 || target_port > 65535) return 2;
    uint16_t target = (uint16_t)target_port;
    long socks_port = argc == 4 ? strtol(argv[3], &end, 10) : 0;
    if (argc == 4 && (*end || socks_port < 1 || socks_port > 65535)) return 2;
    int listener = socket(AF_INET, SOCK_STREAM, 0), enabled = 1;
    if (listener < 0) return 1;
    setsockopt(listener, SOL_SOCKET, SO_REUSEADDR, &enabled, sizeof(enabled));
    struct sockaddr_in address = {.sin_family = AF_INET, .sin_port = htons((uint16_t)wire_port),
                                 .sin_addr.s_addr = htonl(INADDR_LOOPBACK)};
    if (bind(listener, (struct sockaddr *)&address, sizeof(address)) < 0 || listen(listener, 4) < 0) {
        perror("test listener"); close(listener); return 1;
    }
    int control[2];
    if (pipe(control) < 0) { close(listener); return 1; }
    if (fcntl(control[0], F_SETFL, O_NONBLOCK) < 0 || fcntl(control[1], F_SETFL, O_NONBLOCK) < 0) {
        close(control[0]); close(control[1]); close(listener); return 1;
    }
    stop_write = control[1];
    signal(SIGINT, stop); signal(SIGTERM, stop);
    setbuf(stdout, NULL);
    printf("READY mux test server 127.0.0.1:%ld\n", wire_port);
    while (!stopping) {
        struct pollfd waiting[2] = {{.fd = listener, .events = POLLIN},
                                   {.fd = control[0], .events = POLLIN}};
        if (poll(waiting, 2, -1) <= 0) continue;
        if (stopping) break;
        int fd = accept(listener, NULL, NULL);
        if (fd < 0) continue;
        if (fcntl(fd, F_SETFL, O_NONBLOCK) < 0) { close(fd); continue; }
#ifdef SO_NOSIGPIPE
        setsockopt(fd, SOL_SOCKET, SO_NOSIGPIPE, &enabled, sizeof(enabled));
#endif
        struct mux_server *server = mux_server_new(mux_ssh_connect, &target);
        if (!server) { close(fd); break; }
        if (socks_port && mux_enable_socks(server, (uint16_t)socks_port) < 0) {
            perror("SOCKS test listener"); mux_server_free(server); close(fd); break;
        }
        if (socks_port) printf("SOCKS5 READY 127.0.0.1:%ld\n", socks_port);
        while (!stopping && mux_pump(server, fd, false, 127) == 0) {
            struct pollfd fds[MUX_POLL_MAX + 1];
            size_t count = mux_pollfds(server, fd, fds);
            fds[count++] = (struct pollfd){.fd = control[0], .events = POLLIN};
            if (poll(fds, count, mux_poll_timeout(server)) < 0 && errno != EINTR) break;
        }
        mux_server_free(server);
        close(fd);
    }
    stop_write = -1;
    close(control[0]); close(control[1]); close(listener);
    return 0;
}
