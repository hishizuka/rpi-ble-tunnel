#include "mux.h"

#include <assert.h>
#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <string.h>
#include <sys/socket.h>
#include <unistd.h>

struct peers { int fd[MUX_STREAMS + 1]; size_t count; };
static int connector(uint32_t id, bool *connecting, void *context)
{
    (void)id;
    struct peers *peers = context;
    int sockets[2];
    assert(socketpair(AF_UNIX, SOCK_STREAM, 0, sockets) == 0);
    assert(fcntl(sockets[0], F_SETFL, O_NONBLOCK) == 0);
    assert(fcntl(sockets[1], F_SETFL, O_NONBLOCK) == 0);
    peers->fd[peers->count++] = sockets[1];
    *connecting = false;
    return sockets[0];
}
static uint32_t get32(const uint8_t *bytes)
{
    return bytes[0] | (uint32_t)bytes[1] << 8 | (uint32_t)bytes[2] << 16 | (uint32_t)bytes[3] << 24;
}
static void put32(uint8_t *bytes, uint32_t value)
{
    for (int i = 0; i < 4; ++i) bytes[i] = (uint8_t)(value >> (i * 8));
}
static size_t frame(uint8_t *bytes, uint8_t type, uint32_t id, const uint8_t *data, size_t size)
{
    bytes[0] = 'P'; bytes[1] = 'L'; bytes[2] = 1; bytes[3] = type;
    put32(bytes + 4, id); put32(bytes + 8, (uint32_t)size);
    if (size) memcpy(bytes + 12, data, size);
    return size + 12;
}
static int feed_control(struct mux_server *m, uint8_t type, uint32_t id, uint32_t value)
{
    uint8_t bytes[16], payload[4]; put32(payload, value);
    size_t length = frame(bytes, type, id, payload, type == MUX_FIN ? 0 : 4);
    /* Exercise every possible boundary, including split integers. */
    for (size_t i = 0; i < length; ++i) if (mux_feed(m, bytes + i, 1) < 0) return -1;
    return 0;
}
static void expect_frame(struct mux_server *m, uint8_t type, uint32_t id, uint32_t value)
{
    uint8_t bytes[MUX_HEADER + MUX_DATA];
    size_t count = mux_next_frame(m, bytes);
    assert(count >= MUX_HEADER && bytes[3] == type && get32(bytes + 4) == id);
    if (count == 16) assert(get32(bytes + 12) == value);
}
static void close_peers(struct peers *peers)
{
    for (size_t i = 0; i < peers->count; ++i) close(peers->fd[i]);
}
static void test_flow(void)
{
    struct peers peers = {0};
    struct mux_server *m = mux_server_new(connector, &peers); assert(m);
    assert(feed_control(m, MUX_OPEN, 1, MUX_WINDOW) == 0);
    expect_frame(m, MUX_OK, 1, MUX_WINDOW);
    uint8_t payload[MUX_DATA], bytes[MUX_HEADER + MUX_DATA];
    for (size_t i = 0; i < sizeof(payload); ++i) payload[i] = (uint8_t)(i * 37);
    for (int i = 0; i < MUX_WINDOW / MUX_DATA; ++i) {
        assert(send(peers.fd[0], payload, sizeof(payload), 0) == MUX_DATA);
        assert(mux_tcp_step(m) == 0);
        assert(mux_next_frame(m, bytes) == MUX_HEADER + MUX_DATA);
        assert(bytes[3] == MUX_BYTES && memcmp(bytes + 12, payload, MUX_DATA) == 0);
    }
    assert(send(peers.fd[0], payload, sizeof(payload), 0) == MUX_DATA);
    assert(mux_tcp_step(m) == 0 && mux_next_frame(m, bytes) == 0);
    assert(feed_control(m, MUX_OPEN, 3, MUX_WINDOW) == 0);
    expect_frame(m, MUX_OK, 3, MUX_WINDOW);
    assert(feed_control(m, MUX_PING, 0, 0xdeadbeef) == 0);
    expect_frame(m, MUX_PONG, 0, 0xdeadbeef);
    assert(feed_control(m, MUX_WINDOW_UPDATE, 1, MUX_DATA) == 0);
    assert(mux_tcp_step(m) == 0 && mux_next_frame(m, bytes) == MUX_HEADER + MUX_DATA);
    assert(bytes[3] == MUX_BYTES && get32(bytes + 4) == 1);

    size_t length = frame(bytes, MUX_BYTES, 3, payload, sizeof(payload));
    assert(mux_feed(m, bytes, length) == 0 && mux_tcp_step(m) == 0);
    assert(recv(peers.fd[1], bytes, sizeof(bytes), 0) == MUX_DATA);
    assert(memcmp(bytes, payload, MUX_DATA) == 0);
    expect_frame(m, MUX_WINDOW_UPDATE, 3, MUX_DATA);
    assert(feed_control(m, MUX_FIN, 3, 0) == 0 && mux_tcp_step(m) == 0);
    assert(recv(peers.fd[1], bytes, sizeof(bytes), 0) == 0);
    assert(shutdown(peers.fd[1], SHUT_WR) == 0 && mux_tcp_step(m) == 0);
    expect_frame(m, MUX_FIN, 3, 0);
    assert(mux_tcp_step(m) == 0 && mux_active(m) == 1);
    assert(feed_control(m, MUX_RESET, 1, MUX_CANCEL) == 0 && mux_active(m) == 0);
    /* A late WINDOW for a retired stream must not close another stream. */
    assert(feed_control(m, MUX_WINDOW_UPDATE, 1, 1) == 0);
    assert(feed_control(m, MUX_OPEN, 5, MUX_WINDOW) == 0);
    expect_frame(m, MUX_OK, 5, MUX_WINDOW);
    mux_server_free(m); close_peers(&peers);
    puts("PASS binary flow, stream isolation, bounded credits, half-close and reuse");
}
static void test_limits(void)
{
    struct peers peers = {0};
    struct mux_server *m = mux_server_new(connector, &peers);
    for (uint32_t id = 1; id < 17; id += 2) {
        assert(feed_control(m, MUX_OPEN, id, MUX_WINDOW) == 0);
        expect_frame(m, MUX_OK, id, MUX_WINDOW);
    }
    assert(mux_active(m) == MUX_STREAMS);
    assert(feed_control(m, MUX_OPEN, 17, MUX_WINDOW) == 0);
    expect_frame(m, MUX_RESET, 17, MUX_LIMIT);
    assert(mux_active(m) == MUX_STREAMS);
    assert(feed_control(m, MUX_WINDOW_UPDATE, 1, 1) < 0 && errno == EPROTO);
    mux_server_free(m); close_peers(&peers);
    puts("PASS ninth stream rejected and inflated WINDOW rejected");
}
static void test_invalid(void)
{
    const uint8_t types[] = {MUX_OPEN, MUX_OPEN, MUX_BYTES, MUX_OK, MUX_WINDOW_UPDATE, MUX_FIN, 255};
    const uint32_t ids[] = {0, 2, 1, 1, 1, 1, 1};
    for (size_t i = 0; i < sizeof(types); ++i) {
        struct peers peers = {0};
        struct mux_server *m = mux_server_new(connector, &peers);
        assert(feed_control(m, types[i], ids[i], MUX_WINDOW) < 0 && errno == EPROTO);
        mux_server_free(m); close_peers(&peers);
    }
    struct peers peers = {0};
    struct mux_server *m = mux_server_new(connector, &peers);
    uint8_t bytes[12] = {'P', 'L', 1, MUX_BYTES}; put32(bytes + 4, 1); put32(bytes + 8, 0xffffffff);
    assert(mux_feed(m, bytes, sizeof(bytes)) < 0 && errno == EPROTO);
    mux_server_free(m);
    m = mux_server_new(connector, &peers);
    assert(feed_control(m, MUX_OPEN, 1, MUX_WINDOW) == 0);
    expect_frame(m, MUX_OK, 1, MUX_WINDOW);
    assert(feed_control(m, MUX_OPEN, 1, MUX_WINDOW) < 0);
    mux_server_free(m); close_peers(&peers);
    puts("PASS malformed headers, unknown streams, even IDs and duplicate OPEN rejected");
}
static void test_packet(void)
{
    struct peers peers = {0};
    struct mux_server *m = mux_server_new(connector, &peers);
    int wire[2];
#ifdef __linux__
    assert(socketpair(AF_UNIX, SOCK_SEQPACKET, 0, wire) == 0);
#else
    /* macOS has no Unix sequenced-packet socket; datagrams retain SDU boundaries. */
    assert(socketpair(AF_UNIX, SOCK_DGRAM, 0, wire) == 0);
#endif
    assert(fcntl(wire[0], F_SETFL, O_NONBLOCK) == 0);
    assert(fcntl(wire[1], F_SETFL, O_NONBLOCK) == 0);
    uint8_t bytes[16], payload[4]; put32(payload, MUX_WINDOW);
    frame(bytes, MUX_OPEN, 1, payload, 4);
    for (size_t i = 0; i < sizeof(bytes); ++i) assert(send(wire[1], bytes + i, 1, 0) == 1);
    assert(mux_pump(m, wire[0], true, 7) == 0);
    uint8_t received[16]; size_t offset = 0;
    while (offset < sizeof(received)) {
        ssize_t size = recv(wire[1], received + offset, sizeof(received) - offset, 0);
        assert(size > 0 && size <= 7); offset += (size_t)size;
    }
    assert(received[3] == MUX_OK && get32(received + 12) == MUX_WINDOW);
    close(wire[1]);
#ifdef __linux__
    assert(mux_pump(m, wire[0], true, 7) < 0);
#endif
    close(wire[0]); mux_server_free(m); close_peers(&peers);
#ifdef __linux__
    puts("PASS sequenced-packet wire, SDU splitting and transport loss");
#else
    puts("PASS packet wire and SDU splitting (Unix datagram substitute)");
#endif
}
static void test_receive_limit(void)
{
    struct peers peers = {0};
    struct mux_server *m = mux_server_new(connector, &peers);
    assert(feed_control(m, MUX_OPEN, 1, MUX_WINDOW) == 0);
    expect_frame(m, MUX_OK, 1, MUX_WINDOW);
    uint8_t bytes[MUX_HEADER + MUX_DATA], payload[MUX_DATA] = {0};
    size_t size = frame(bytes, MUX_BYTES, 1, payload, sizeof(payload));
    for (int i = 0; i < MUX_WINDOW / MUX_DATA; ++i) assert(mux_feed(m, bytes, size) == 0);
    assert(mux_feed(m, bytes, size) < 0 && errno == EPROTO);
    mux_server_free(m); close_peers(&peers);
    peers.count = 0;
    m = mux_server_new(connector, &peers);
    assert(feed_control(m, MUX_OPEN, 1, MUX_WINDOW) == 0);
    expect_frame(m, MUX_OK, 1, MUX_WINDOW);
    assert(feed_control(m, MUX_FIN, 1, 0) == 0);
    assert(mux_feed(m, bytes, size) < 0 && errno == EPROTO);
    mux_server_free(m); close_peers(&peers);
    puts("PASS receive-window overflow and DATA after FIN rejected");
}
static void test_coalesced_fin_open(void)
{
    struct peers peers = {0};
    struct mux_server *m = mux_server_new(connector, &peers);
    for (uint32_t id = 1; id < 17; id += 2) {
        assert(feed_control(m, MUX_OPEN, id, MUX_WINDOW) == 0);
        expect_frame(m, MUX_OK, id, MUX_WINDOW);
    }
    for (size_t i = 0; i < MUX_STREAMS; ++i) assert(shutdown(peers.fd[i], SHUT_WR) == 0);
    assert(mux_tcp_step(m) == 0);
    for (uint32_t id = 1; id < 17; id += 2) expect_frame(m, MUX_FIN, id, 0);
    assert(mux_active(m) == MUX_STREAMS);
    uint8_t combined[12 * MUX_STREAMS + 16], grant[4]; size_t length = 0;
    for (uint32_t id = 1; id < 17; id += 2) length += frame(combined + length, MUX_FIN, id, NULL, 0);
    put32(grant, MUX_WINDOW);
    length += frame(combined + length, MUX_OPEN, 17, grant, 4);
    /* No TCP polling between the final FIN and OPEN in the same wire read. */
    assert(mux_feed(m, combined, length) == 0);
    expect_frame(m, MUX_OK, 17, MUX_WINDOW);
    assert(mux_active(m) == 1);
    mux_server_free(m); close_peers(&peers);
    puts("PASS coalesced FIN/OPEN immediately reuses completed stream slots");
}
int main(void)
{
    test_flow(); test_limits(); test_invalid(); test_receive_limit(); test_packet(); test_coalesced_fin_open();
    return 0;
}
