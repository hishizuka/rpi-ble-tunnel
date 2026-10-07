#include "bridge.h"

#include <assert.h>
#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <stdio.h>
#include <string.h>
#include <sys/socket.h>
#include <unistd.h>

#ifndef MSG_NOSIGNAL
#define MSG_NOSIGNAL 0
#endif

static short poll_events(unsigned events)
{
    return ((events & BRIDGE_READ) ? POLLIN : 0) | ((events & BRIDGE_WRITE) ? POLLOUT : 0);
}

static unsigned ready_events(short events)
{
    return ((events & (POLLIN | POLLHUP)) ? BRIDGE_READ : 0) | ((events & POLLOUT) ? BRIDGE_WRITE : 0);
}

int main(void)
{
    int packet[2], tcp[2];
#ifdef __APPLE__
    assert(socketpair(AF_UNIX, SOCK_DGRAM, 0, packet) == 0);
#else
    assert(socketpair(AF_UNIX, SOCK_SEQPACKET, 0, packet) == 0);
#endif
    assert(socketpair(AF_UNIX, SOCK_STREAM, 0, tcp) == 0);
    for (int i = 0; i < 2; ++i) {
        assert(fcntl(packet[i], F_SETFL, O_NONBLOCK) == 0);
        assert(fcntl(tcp[i], F_SETFL, O_NONBLOCK) == 0);
    }
    int small_buffer = 4096;
    assert(setsockopt(tcp[0], SOL_SOCKET, SO_SNDBUF, &small_buffer, sizeof(small_buffer)) == 0);
    struct bridge bridge;
    bridge_init(&bridge, packet[0], tcp[0], 127);
    unsigned char client[65536], server[65536], received_tcp[65536], received_packet[65536];
    for (size_t i = 0; i < sizeof(client); ++i) {
        client[i] = (unsigned char)(i * 73 + i / 251);
        server[i] = (unsigned char)(i * 29 + i / 257);
    }
    size_t client_sent = 0, server_sent = 0, tcp_received = 0, packet_received = 0;
    bool server_closed = false;
    for (int iteration = 0; !bridge_finished(&bridge); ++iteration) {
        assert(iteration < 200000);
        if (client_sent < sizeof(client)) {
            size_t count = sizeof(client) - client_sent;
            if (count > 2041) count = 2041;
            ssize_t sent = send(packet[1], client + client_sent, count, MSG_NOSIGNAL);
            if (sent >= 0) { assert((size_t)sent == count); client_sent += (size_t)sent; }
            else assert(errno == EAGAIN || errno == EWOULDBLOCK || errno == ENOBUFS);
        }
        if (server_sent < sizeof(server)) {
            size_t count = sizeof(server) - server_sent;
            if (count > 4637) count = 4637;
            ssize_t sent = send(tcp[1], server + server_sent, count, MSG_NOSIGNAL);
            if (sent >= 0) server_sent += (size_t)sent;
            else assert(errno == EAGAIN || errno == EWOULDBLOCK);
        }
        /* Stall the TCP reader first to exercise independent-direction backpressure. */
        if (iteration > 200 && tcp_received < sizeof(client)) {
            size_t count = sizeof(client) - tcp_received;
            if (count > 37) count = 37;
            ssize_t read_count = recv(tcp[1], received_tcp + tcp_received, count, 0);
            if (read_count > 0) tcp_received += (size_t)read_count;
            else assert(read_count < 0 && (errno == EAGAIN || errno == EWOULDBLOCK));
        }
        if (packet_received < sizeof(server)) {
            ssize_t count = recv(packet[1], received_packet + packet_received,
                                 sizeof(server) - packet_received, 0);
            if (count > 0) { assert(count <= 127); packet_received += (size_t)count; }
            else assert(count < 0 && (errno == EAGAIN || errno == EWOULDBLOCK));
        }
        if (!server_closed && server_sent == sizeof(server) && tcp_received == sizeof(client)) {
            assert(shutdown(tcp[1], SHUT_WR) == 0);
            server_closed = true;
        }
        struct pollfd fds[] = {
            {.fd = packet[0], .events = poll_events(bridge_packet_events(&bridge))},
            {.fd = tcp[0], .events = poll_events(bridge_tcp_events(&bridge))}
        };
        assert(poll(fds, 2, 0) >= 0);
        assert(!(fds[0].revents & (POLLERR | POLLNVAL)));
        assert(!(fds[1].revents & (POLLERR | POLLNVAL)));
        assert(bridge_packet_step(&bridge, ready_events(fds[0].revents)) == 0);
        assert(bridge_tcp_step(&bridge, ready_events(fds[1].revents)) == 0);
    }
    /* Bytes sent before TCP EOF must remain readable after the bridge finishes. */
    while (packet_received < sizeof(server)) {
        ssize_t count = recv(packet[1], received_packet + packet_received,
                             sizeof(server) - packet_received, 0);
        assert(count > 0);
        packet_received += (size_t)count;
    }
    assert(tcp_received == sizeof(client));
    assert(memcmp(client, received_tcp, sizeof(client)) == 0);
    assert(memcmp(server, received_packet, sizeof(server)) == 0);
    assert(bridge.packet_to_tcp == sizeof(client));
    assert(bridge.tcp_to_packet == sizeof(server));
    for (int i = 0; i < 2; ++i) { close(packet[i]); close(tcp[i]); }
    puts("duplex bridge: 64 KiB each way, partial TCP writes, MTU splitting, backpressure, EOF drain OK");
    return 0;
}
