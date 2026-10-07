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
static void stop(int signal_number) { (void)signal_number; stopping = 1; }

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
    signal(SIGINT, stop); signal(SIGTERM, stop);
    int listener = socket(AF_INET, SOCK_STREAM, 0), enabled = 1;
    if (listener < 0) return 1;
    setsockopt(listener, SOL_SOCKET, SO_REUSEADDR, &enabled, sizeof(enabled));
    struct sockaddr_in address = {.sin_family = AF_INET, .sin_port = htons((uint16_t)wire_port),
                                 .sin_addr.s_addr = htonl(INADDR_LOOPBACK)};
    if (bind(listener, (struct sockaddr *)&address, sizeof(address)) < 0 || listen(listener, 4) < 0) {
        perror("test listener"); close(listener); return 1;
    }
    setbuf(stdout, NULL);
    printf("READY mux test server 127.0.0.1:%ld\n", wire_port);
    while (!stopping) {
        struct pollfd waiting = {.fd = listener, .events = POLLIN};
        if (poll(&waiting, 1, 100) <= 0) continue;
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
            /* A bounded delay avoids spinning when every stream is idle. */
            poll(NULL, 0, 2);
        }
        mux_server_free(server);
        close(fd);
    }
    close(listener);
    return 0;
}
