#include "echo.h"

#include <assert.h>
#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <string.h>
#include <sys/socket.h>
#include <unistd.h>

int main(void)
{
    int sockets[2];
    /* Datagram socket pairs provide atomic SDUs without Bluetooth hardware. */
    assert(socketpair(AF_UNIX, SOCK_DGRAM, 0, sockets) == 0);
    for (int i = 0; i < 2; ++i)
        assert(fcntl(sockets[i], F_SETFL, O_NONBLOCK) == 0);
    struct echo_buffer buffer = {.send_mtu = 17};
    assert(echo_receive(sockets[0], &buffer) == 0);
    uint8_t payload[2048], returned[2048];
    for (size_t i = 0; i < sizeof(payload); ++i) payload[i] = (uint8_t)i;
    assert(send(sockets[1], payload, sizeof(payload), 0) == sizeof(payload));
    assert(echo_receive(sockets[0], &buffer) == 1);
    assert(echo_receive(sockets[0], &buffer) == -1 && errno == EBUSY);
    size_t received = 0;
    while (buffer.length) {
        assert(echo_send(sockets[0], &buffer) == 1);
        ssize_t count = recv(sockets[1], returned + received, sizeof(returned) - received, 0);
        assert(count > 0 && count <= 17);
        received += (size_t)count;
    }
    assert(received == sizeof(payload));
    assert(memcmp(payload, returned, sizeof(payload)) == 0);

    /* A full socket send queue must preserve pending bytes for a later retry. */
    uint8_t filler[512] = {0};
    while (send(sockets[0], filler, sizeof(filler), 0) >= 0) {}
    assert(errno == EAGAIN || errno == EWOULDBLOCK || errno == ENOBUFS);
    memcpy(buffer.data, payload, 512);
    buffer.length = 512;
    buffer.send_mtu = 512;
    int result = echo_send(sockets[0], &buffer);
    /* Darwin reports ENOBUFS for a full Unix datagram receive queue. */
    assert(result == 0 || (result == -1 && errno == ENOBUFS));
    assert(buffer.length == 512 && buffer.offset == 0);
    while (recv(sockets[1], filler, sizeof(filler), 0) >= 0) {}
    assert(echo_send(sockets[0], &buffer) == 1);
    assert(recv(sockets[1], returned, sizeof(returned), 0) == 512);
    assert(memcmp(payload, returned, 512) == 0);
    close(sockets[0]); close(sockets[1]);
    puts("packet echo: binary data, MTU splitting, backpressure OK");
    return 0;
}
