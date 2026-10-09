/* Inspect deadlines directly so timeout tests never wait for production limits. */
#include "../pi/mux.c"
#include <assert.h>
#include <signal.h>
#include <stdio.h>

static void pair(int fd[2])
{
    assert(socketpair(AF_UNIX, SOCK_STREAM, 0, fd) == 0);
    assert(fcntl(fd[0], F_SETFL, O_NONBLOCK) == 0);
    assert(fcntl(fd[1], F_SETFL, O_NONBLOCK) == 0);
}

static void test_idle_and_waiting_data(void)
{
    int wire[2], peer[2]; pair(wire); pair(peer);
    struct mux_server *m = mux_server_new(NULL, NULL);
    struct pollfd fds[MUX_POLL_MAX];
    assert(mux_pollfds(m, wire[0], fds) == 1 && fds[0].events == POLLIN);
    assert(mux_poll_timeout(m) == -1 && poll(fds, 1, 30) == 0);
    m->streams[0] = (struct mux_stream){.id = 2, .fd = peer[0], .initiator = true,
        .open_sent = true, .deadline = monotonic_time() + 30};
    assert(send(peer[1], "early", 5, 0) == 5);
    assert(mux_tcp_step(m) == 0);
    size_t count = mux_pollfds(m, wire[0], fds);
    assert(count == 2 && fds[1].events == 0 && poll(fds, count, 30) == 0);
    assert(mux_poll_timeout(m) > 0);
    close(peer[1]);
#ifdef __APPLE__
    /* Darwin cannot report buffered EOF with no read interest; retain the bounded deadline. */
    assert(poll(fds, count, 30) == 0);
    m->streams[0].deadline = 0;
    assert(mux_poll_timeout(m) == 0 && mux_tcp_step(m) == 0);
#else
    assert(poll(fds, count, 100) > 0 && mux_tcp_step(m) == 0);
#endif
    assert(mux_pollfds(m, wire[0], fds) == 1 && (fds[0].events & POLLOUT));
    uint8_t bytes[MUX_HEADER + MUX_DATA];
    assert(mux_next_frame(m, bytes) == 16 && bytes[3] == MUX_RESET);
    assert(mux_poll_timeout(m) == -1);
    mux_server_free(m); close(wire[0]); close(wire[1]);
    puts("PASS idle event wait, early SOCKS data without spinning, queued peer cancellation");
}

static void test_credit_and_fin(void)
{
    int wire[2], peer[2]; pair(wire); pair(peer);
    struct mux_server *m = mux_server_new(NULL, NULL);
    m->streams[0] = (struct mux_stream){.id = 1, .fd = peer[0], .acknowledged = true};
    assert(send(peer[1], "data", 4, 0) == 4);
    struct pollfd fds[MUX_POLL_MAX];
    assert(mux_pollfds(m, wire[0], fds) == 1);
    assert(poll(fds, 1, 30) == 0);  /* No TCP readiness spin with zero credit. */
    m->streams[0].credit = 4;
    assert(mux_pollfds(m, wire[0], fds) == 2 && poll(fds, 2, 100) > 0);
    assert(mux_tcp_step(m) == 0);
    assert(mux_pollfds(m, wire[0], fds) == 1 && (fds[0].events & POLLOUT));
    uint8_t bytes[MUX_HEADER + MUX_DATA];
    assert(mux_next_frame(m, bytes) == 16 && bytes[3] == MUX_BYTES);
    m->streams[0].credit = 1;
    assert(shutdown(peer[1], SHUT_WR) == 0 && mux_tcp_step(m) == 0);
    assert(mux_pollfds(m, wire[0], fds) == 1 && (fds[0].events & POLLOUT));
    assert(mux_next_frame(m, bytes) == 12 && bytes[3] == MUX_FIN);
    assert(mux_pollfds(m, wire[0], fds) == 1 && fds[0].events == POLLIN);
    assert(mux_poll_timeout(m) == -1 && poll(fds, 1, 30) == 0);
    mux_server_free(m); close(peer[1]); close(wire[0]); close(wire[1]);
    puts("PASS credit backpressure and half-close return to an event-only wait");
}

static void test_queue_and_deadlines(void)
{
    int wire[2], peers[2][2]; pair(wire);
    struct mux_server *m = mux_server_new(NULL, NULL);
    assert(mux_enable_socks(m, 0) == 0);
    for (size_t i = 0; i < 2; ++i) {
        pair(peers[i]);
        m->pending[i] = (struct socks_pending){.fd = peers[i][0], .ready = true,
            .target = 10, .ticket = i + 1, .deadline = monotonic_time() + 30};
        m->pending[i].input[3] = 1;
        m->pending[i].input[9] = 80;
    }
    assert(mux_poll_timeout(m) == 0);
    assert(mux_pump(m, wire[0], false, 127) == 0 && mux_active(m) == 1);
    assert(mux_poll_timeout(m) == 0);  /* Another promotion needs no new socket event. */
    assert(mux_pump(m, wire[0], false, 127) == 0 && mux_active(m) == 2);
    int timeout = mux_poll_timeout(m);
    assert(timeout > 29000 && timeout <= 30000);
    struct mux_stream *first = find_stream(m, 2), *second = find_stream(m, 4);
    assert(first && second);
    first->deadline = 0;
    assert(mux_poll_timeout(m) == 0 && mux_tcp_step(m) == 0);
    assert(first->reset == MUX_TIMEOUT && !second->reset);
    uint8_t bytes[MUX_HEADER + MUX_DATA];
    assert(mux_next_frame(m, bytes) == 16 && bytes[3] == MUX_RESET);
    assert(mux_poll_timeout(m) > 0);
    mux_server_free(m);
    for (size_t i = 0; i < 2; ++i) close(peers[i][1]);
    close(wire[0]); close(wire[1]);
    puts("PASS immediate queue promotion and earliest deadline expiry isolate other streams");
}

int main(void)
{
    signal(SIGPIPE, SIG_IGN);
    test_idle_and_waiting_data(); test_credit_and_fin(); test_queue_and_deadlines();
    return 0;
}
