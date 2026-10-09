#define main pilinkd_entry_for_test
#include "../pi/pilinkd.c"
#undef main
#include <assert.h>

static gboolean elapsed(gpointer data)
{
    *(gboolean *)data = TRUE;
    return G_SOURCE_REMOVE;
}

static void idle(GMainContext *context)
{
    gboolean done = FALSE;
    GSource *timer = g_timeout_source_new(100);
    g_source_set_callback(timer, elapsed, &done, NULL);
    g_source_attach(timer, context);
    unsigned iterations = 0;
    while (!done) { g_main_context_iteration(context, TRUE); assert(++iterations < 5); }
    g_source_unref(timer);
}

int main(void)
{
    int wire[2];
    assert(socketpair(AF_UNIX, SOCK_SEQPACKET, 0, wire) == 0);
    assert(configure_fd(wire[0]) && configure_fd(wire[1]));
    struct daemon d = {.loop = g_main_loop_new(NULL, FALSE), .client = wire[0],
        .tcp = -1, .listener = -1, .mux_mode = TRUE, .mux_mtu = 7,
        .mux = mux_server_new(NULL, NULL)};
    GMainContext *context = g_main_loop_get_context(d.loop);
    d.mux_source = watch_mux(&d);
    idle(context);
    uint8_t ping[16] = {'P', 'L', 1, MUX_PING, 0, 0, 0, 0, 4, 0, 0, 0, 42};
    assert(send(wire[1], ping, sizeof(ping), 0) == sizeof(ping));
    assert(g_main_context_iteration(context, TRUE));
    uint8_t pong[16]; size_t offset = 0;
    while (offset < sizeof(pong)) {
        ssize_t n = recv(wire[1], pong + offset, sizeof(pong) - offset, 0);
        assert(n > 0 && n <= 7); offset += (size_t)n;
    }
    assert(pong[3] == MUX_PONG && pong[12] == 42);
    idle(context);
    close(wire[1]);
    assert(g_main_context_iteration(context, TRUE));
    assert(d.client == -1 && d.mux == NULL && d.mux_source == 0);
    idle(context);
    g_main_loop_unref(d.loop);
    puts("PASS GLib idle sleep, packet readiness, SDU splitting, transport loss and source cleanup");
    return 0;
}
