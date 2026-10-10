#ifndef RPI_BLE_TUNNEL_BRIDGE_H
#define RPI_BLE_TUNNEL_BRIDGE_H

#include "echo.h"
#include <stdbool.h>

enum bridge_event { BRIDGE_READ = 1, BRIDGE_WRITE = 2 };

struct bridge {
    int packet_fd;
    int tcp_fd;
    struct echo_buffer to_tcp;
    struct echo_buffer to_packet;
    bool packet_eof;
    bool tcp_eof;
    uint64_t packet_to_tcp;
    uint64_t tcp_to_packet;
};

void bridge_init(struct bridge *bridge, int packet_fd, int tcp_fd, size_t send_mtu);
unsigned bridge_packet_events(const struct bridge *bridge);
unsigned bridge_tcp_events(const struct bridge *bridge);
int bridge_packet_step(struct bridge *bridge, unsigned events);
int bridge_tcp_step(struct bridge *bridge, unsigned events);
bool bridge_finished(const struct bridge *bridge);

#endif
