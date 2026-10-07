#include "bridge.h"

#include <errno.h>
#include <string.h>
#include <sys/socket.h>

#ifndef MSG_NOSIGNAL
#define MSG_NOSIGNAL 0
#endif

void bridge_init(struct bridge *bridge, int packet_fd, int tcp_fd, size_t send_mtu)
{
    memset(bridge, 0, sizeof(*bridge));
    bridge->packet_fd = packet_fd;
    bridge->tcp_fd = tcp_fd;
    bridge->to_packet.send_mtu = send_mtu;
}

unsigned bridge_packet_events(const struct bridge *bridge)
{
    unsigned events = 0;
    if (!bridge->packet_eof && !bridge->tcp_eof && !bridge->to_tcp.length)
        events |= BRIDGE_READ;
    if (!bridge->packet_eof && bridge->to_packet.length)
        events |= BRIDGE_WRITE;
    return events;
}

unsigned bridge_tcp_events(const struct bridge *bridge)
{
    unsigned events = 0;
    if (!bridge->tcp_eof && !bridge->packet_eof && !bridge->to_packet.length)
        events |= BRIDGE_READ;
    if (bridge->to_tcp.length)
        events |= BRIDGE_WRITE;
    return events;
}

bool bridge_finished(const struct bridge *bridge)
{
    /* CoC has a full-channel close; drain accepted bytes before terminating. */
    return (bridge->tcp_eof || bridge->packet_eof) &&
           !bridge->to_tcp.length && !bridge->to_packet.length;
}

int bridge_packet_step(struct bridge *bridge, unsigned events)
{
    if ((events & BRIDGE_WRITE) && bridge->to_packet.length && !bridge->packet_eof) {
        size_t count = bridge->to_packet.length - bridge->to_packet.offset;
        if (count > bridge->to_packet.send_mtu) count = bridge->to_packet.send_mtu;
        int result = echo_send(bridge->packet_fd, &bridge->to_packet);
        if (result < 0) return -1;
        if (result > 0) bridge->tcp_to_packet += count;
    }
    if ((events & BRIDGE_READ) && (bridge_packet_events(bridge) & BRIDGE_READ)) {
        int result = echo_receive(bridge->packet_fd, &bridge->to_tcp);
        if (result == -2) {
            bridge->packet_eof = true;
            /* A disconnected channel cannot accept queued outbound SDUs. */
            bridge->to_packet.length = bridge->to_packet.offset = 0;
        }
        else if (result < 0) return -1;
    }
    return 0;
}

int bridge_tcp_step(struct bridge *bridge, unsigned events)
{
    if ((events & BRIDGE_WRITE) && bridge->to_tcp.length) {
        struct echo_buffer *buffer = &bridge->to_tcp;
        ssize_t count;
        do {
            count = send(bridge->tcp_fd, buffer->data + buffer->offset,
                         buffer->length - buffer->offset, MSG_NOSIGNAL);
        } while (count < 0 && errno == EINTR);
        if (count < 0) {
            if (errno != EAGAIN && errno != EWOULDBLOCK) return -1;
        } else if (count == 0) {
            errno = EIO;
            return -1;
        } else {
            bridge->packet_to_tcp += (size_t)count;
            buffer->offset += (size_t)count;
            if (buffer->offset == buffer->length) buffer->offset = buffer->length = 0;
        }
    }
    if ((events & BRIDGE_READ) && (bridge_tcp_events(bridge) & BRIDGE_READ)) {
        struct echo_buffer *buffer = &bridge->to_packet;
        ssize_t count;
        do {
            count = recv(bridge->tcp_fd, buffer->data, 16384, 0);
        } while (count < 0 && errno == EINTR);
        if (count < 0) {
            if (errno != EAGAIN && errno != EWOULDBLOCK) return -1;
        } else if (count == 0) {
            bridge->tcp_eof = true;
        } else {
            buffer->length = (size_t)count;
            buffer->offset = 0;
        }
    }
    return 0;
}
