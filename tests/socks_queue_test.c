/* Include the implementation to expire queue deadlines without a 30-second sleep. */
#include "../pi/mux.c"
#include <assert.h>
#include <stdio.h>

static void feed_control(struct mux_server *m, uint8_t type, uint32_t id, uint32_t value)
{
    uint8_t bytes[16] = {'P', 'L', 1, type};
    write32(bytes + 4, id); write32(bytes + 8, 4); write32(bytes + 12, value);
    assert(mux_feed(m, bytes, sizeof(bytes)) == 0);
}

static void receive(struct mux_server *m, int fd, uint8_t *bytes, size_t length)
{
    size_t offset = 0;
    for (int i = 0; i < 1000 && offset < length; ++i) {
        assert(mux_tcp_step(m) == 0);
        ssize_t count = recv(fd, bytes + offset, length - offset, 0);
        if (count > 0) offset += (size_t)count;
        else {
            assert(count < 0 && (errno == EAGAIN || errno == EWOULDBLOCK));
            usleep(1000);
        }
    }
    assert(offset == length);
}

static int request(struct mux_server *m, uint16_t port)
{
    int fd = socket(AF_INET, SOCK_STREAM, 0), enabled = 1;
    struct sockaddr_in address = {.sin_family = AF_INET,
        .sin_addr.s_addr = htonl(INADDR_LOOPBACK), .sin_port = htons(mux_socks_port(m))};
    assert(fd >= 0 && connect(fd, (struct sockaddr *)&address, sizeof(address)) == 0);
    assert(setsockopt(fd, IPPROTO_TCP, TCP_NODELAY, &enabled, sizeof(enabled)) == 0);
    assert(fcntl(fd, F_SETFL, O_NONBLOCK) == 0);
    uint8_t greeting[] = {5, 1, 0}, reply[2];
    assert(send(fd, greeting, sizeof(greeting), 0) == sizeof(greeting));
    receive(m, fd, reply, sizeof(reply));
    assert(reply[0] == 5 && reply[1] == 0);
    uint8_t command[] = {5, 1, 0, 1, 127, 0, 0, 1, port >> 8, port & 255};
    uint64_t ticket = m->next_ticket;
    assert(send(fd, command, sizeof(command), 0) == sizeof(command));
    for (int i = 0; i < 1000 && m->next_ticket == ticket; ++i) {
        assert(mux_tcp_step(m) == 0);
        if (m->next_ticket == ticket) usleep(1000);
    }
    assert(m->next_ticket == ticket + 1);
    return fd;
}

static uint32_t acknowledge(struct mux_server *m, int fd, uint16_t port)
{
    uint8_t bytes[MUX_HEADER + MUX_DATA], reply[10];
    size_t length = mux_next_frame(m, bytes);
    assert(length > MUX_HEADER && bytes[3] == MUX_OPEN_TCP);
    assert(((unsigned)bytes[length - 2] << 8 | bytes[length - 1]) == port);
    uint32_t id = read32(bytes + 4);
    feed_control(m, MUX_OK, id, MUX_WINDOW);
    receive(m, fd, reply, sizeof(reply));
    assert(reply[1] == 0);
    return id;
}

static size_t waiting(struct mux_server *m)
{
    size_t count = 0;
    for (size_t i = 0; i < MUX_SOCKS_PENDING; ++i)
        if (m->pending[i].ready) count++;
    return count;
}

int main(void)
{
    struct mux_server *m = mux_server_new(NULL, NULL);
    assert(m && mux_enable_socks(m, 0) == 0);
    int held[MUX_STREAMS - 1];
    uint32_t ids[MUX_STREAMS - 1];
    for (size_t i = 0; i < MUX_STREAMS - 1; ++i) {
        held[i] = request(m, (uint16_t)(7000 + i));
        ids[i] = acknowledge(m, held[i], (uint16_t)(7000 + i));
    }
    int first = request(m, 8001), second = request(m, 8002);
    assert(waiting(m) == 2 && mux_active(m) == 7);
    uint8_t bytes[MUX_HEADER + MUX_DATA];
    assert(mux_next_frame(m, bytes) == 0);
    feed_control(m, MUX_RESET, ids[0], MUX_CANCEL);
    assert(mux_tcp_step(m) == 0);
    acknowledge(m, first, 8001);
    assert(waiting(m) == 1 && mux_active(m) == 7);
    feed_control(m, MUX_RESET, ids[1], MUX_CANCEL);
    assert(mux_tcp_step(m) == 0);
    acknowledge(m, second, 8002);
    assert(waiting(m) == 0 && mux_active(m) == 7);
    close(first); close(second);
    puts("PASS reserved SSH capacity and FIFO SOCKS promotion");

    /* Keep seven logical streams busy and verify bounded pending storage. */
    int pending[MUX_SOCKS_PENDING];
    for (size_t i = 0; i < MUX_SOCKS_PENDING; ++i) pending[i] = request(m, 9000);
    assert(waiting(m) == MUX_SOCKS_PENDING);
    int extra = socket(AF_INET, SOCK_STREAM, 0);
    struct sockaddr_in address = {.sin_family = AF_INET, .sin_addr.s_addr = htonl(INADDR_LOOPBACK),
                                 .sin_port = htons(mux_socks_port(m))};
    assert(connect(extra, (struct sockaddr *)&address, sizeof(address)) == 0);
    assert(mux_tcp_step(m) == 0);
    assert(recv(extra, bytes, 1, 0) == 0);
    close(extra);
    assert(mux_active(m) == 7);
    puts("PASS queue overflow preserves active connections");

    for (size_t i = 0; i < MUX_SOCKS_PENDING; ++i)
        if (m->pending[i].ready) m->pending[i].deadline = 0;
    for (size_t i = 0; i < MUX_SOCKS_PENDING; ++i) {
        uint8_t reply[10];
        receive(m, pending[i], reply, sizeof(reply));
        assert(reply[0] == 5 && reply[1] == 6);
        assert(recv(pending[i], bytes, 1, 0) == 0);
        close(pending[i]);
    }
    assert(waiting(m) == 0 && mux_active(m) == 7);
    puts("PASS queue timeout replies and preserves active connections");
    mux_server_free(m);
    for (size_t i = 0; i < MUX_STREAMS - 1; ++i) close(held[i]);
    return 0;
}
