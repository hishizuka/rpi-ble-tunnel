#ifndef PILINK_MUX_H
#define PILINK_MUX_H

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#define MUX_STREAMS 8
#define MUX_SOCKS_PENDING 128
#define MUX_SOCKS_WAIT 30
#define MUX_WINDOW 16384
#define MUX_DATA 1024
#define MUX_HEADER 12

enum mux_type { MUX_OPEN = 1, MUX_OK, MUX_BYTES, MUX_WINDOW_UPDATE,
                MUX_FIN, MUX_RESET, MUX_PING, MUX_PONG, MUX_OPEN_TCP };
enum mux_reason { MUX_IO_ERROR = 1, MUX_LIMIT = 2, MUX_CANCEL = 3,
                  MUX_DNS_ERROR = 4, MUX_REFUSED = 5, MUX_TIMEOUT = 6,
                  MUX_UNREACHABLE = 7 };

struct mux_server;
/* The connector returns an owned, nonblocking socket, or -1 on failure. */
typedef int (*mux_connector)(uint32_t id, bool *connecting, void *context);
struct mux_server *mux_server_new(mux_connector connector, void *context);
/* Enable reverse TCP streams through a loopback SOCKS5 CONNECT listener. */
int mux_enable_socks(struct mux_server *server, uint16_t port);
uint16_t mux_socks_port(const struct mux_server *server);
void mux_server_free(struct mux_server *server);
int mux_ssh_connect(uint32_t id, bool *connecting, void *context);
/* Feed arbitrary byte boundaries; invalid frames return -1 with EPROTO. */
int mux_feed(struct mux_server *server, const uint8_t *bytes, size_t length);
/* Advance TCP sockets and return one fairly scheduled frame, or zero. */
int mux_tcp_step(struct mux_server *server);
size_t mux_next_frame(struct mux_server *server, uint8_t bytes[MUX_HEADER + MUX_DATA]);
/* Drive a nonblocking wire socket. Return -1 on error or wire EOF. */
int mux_pump(struct mux_server *server, int wire, bool packet, size_t mtu);
size_t mux_active(const struct mux_server *server);

#endif
