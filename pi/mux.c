#include "mux.h"

#include <arpa/inet.h>
#include <errno.h>
#include <fcntl.h>
#include <netinet/tcp.h>
#include <poll.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <time.h>
#include <unistd.h>

#ifndef MSG_NOSIGNAL
#define MSG_NOSIGNAL 0
#endif

struct mux_stream {
    uint32_t id, credit, receive_credit, window, reset;
    int fd;
    bool connecting, acknowledged, local_fin, fin_sent, remote_fin, shutdown;
    double deadline;
    uint8_t incoming[MUX_WINDOW], outgoing[MUX_DATA];
    size_t incoming_length, incoming_offset, outgoing_length;
    bool initiator, open_sent, rejected;
    uint8_t destination[264], reply[10];
    size_t destination_length, reply_length, reply_offset;
};

struct socks_pending {
    int fd;
    unsigned stage;
    uint8_t input[264], reply[10];
    size_t length, target, reply_length, reply_offset;
    bool close_after_reply;
    bool ready;
    uint64_t ticket;
    double deadline;
};

struct mux_server {
    mux_connector connector;
    void *context;
    struct mux_stream streams[MUX_STREAMS];
    uint32_t highest_id, rejected[16], pong;
    size_t rejection_count, cursor;
    bool pong_pending;
    uint8_t frame[MUX_HEADER + MUX_DATA], output[MUX_HEADER + MUX_DATA];
    size_t frame_length, frame_target, output_length, output_offset;
    uint8_t wire_input[65535];
    int socks_listener;
    uint16_t socks_port;
    uint32_t next_even;
    struct socks_pending pending[MUX_SOCKS_PENDING];
    uint64_t next_ticket;
};

static double monotonic_time(void)
{
    struct timespec now;
    clock_gettime(CLOCK_MONOTONIC, &now);
    return now.tv_sec + now.tv_nsec / 1e9;
}

static uint32_t read32(const uint8_t *p)
{
    return (uint32_t)p[0] | (uint32_t)p[1] << 8 | (uint32_t)p[2] << 16 | (uint32_t)p[3] << 24;
}

static void write32(uint8_t *p, uint32_t value)
{
    for (int i = 0; i < 4; ++i) p[i] = (uint8_t)(value >> (i * 8));
}

static int protocol_error(void) { errno = EPROTO; return -1; }

static void release_stream(struct mux_stream *s)
{
    if (s->fd >= 0) close(s->fd);
    memset(s, 0, sizeof(*s));
    s->fd = -1;
}

static void reset_stream(struct mux_stream *s, uint32_t reason)
{
    if (s->initiator && !s->open_sent) { release_stream(s); return; }
    if (s->fd >= 0) close(s->fd);
    s->fd = -1;
    s->reset = reason;
    s->incoming_length = s->incoming_offset = s->outgoing_length = 0;
}

struct mux_server *mux_server_new(mux_connector connector, void *context)
{
    struct mux_server *m = calloc(1, sizeof(*m));
    if (!m) return NULL;
    m->connector = connector;
    m->context = context;
    m->frame_target = MUX_HEADER;
    m->socks_listener = -1;
    m->next_even = 2;
    for (size_t i = 0; i < MUX_STREAMS; ++i) m->streams[i].fd = -1;
    for (size_t i = 0; i < MUX_SOCKS_PENDING; ++i) m->pending[i].fd = -1;
    return m;
}

void mux_server_free(struct mux_server *m)
{
    if (!m) return;
    for (size_t i = 0; i < MUX_STREAMS; ++i) release_stream(&m->streams[i]);
    for (size_t i = 0; i < MUX_SOCKS_PENDING; ++i)
        if (m->pending[i].fd >= 0) close(m->pending[i].fd);
    if (m->socks_listener >= 0) close(m->socks_listener);
    free(m);
}

static int configure_socket(int fd)
{
    int flags = fcntl(fd, F_GETFL), enabled = 1;
    if (flags < 0 || fcntl(fd, F_SETFL, flags | O_NONBLOCK) < 0 ||
        fcntl(fd, F_SETFD, FD_CLOEXEC) < 0 ||
        setsockopt(fd, IPPROTO_TCP, TCP_NODELAY, &enabled, sizeof(enabled)) < 0) return -1;
#ifdef SO_NOSIGPIPE
    if (setsockopt(fd, SOL_SOCKET, SO_NOSIGPIPE, &enabled, sizeof(enabled)) < 0) return -1;
#endif
    return 0;
}

int mux_enable_socks(struct mux_server *m, uint16_t port)
{
    if (m->socks_listener >= 0) { errno = EALREADY; return -1; }
    int fd = socket(AF_INET, SOCK_STREAM, 0), enabled = 1;
    if (fd < 0) return -1;
    struct sockaddr_in address = {.sin_family = AF_INET, .sin_port = htons(port),
                                 .sin_addr.s_addr = htonl(INADDR_LOOPBACK)};
    socklen_t size = sizeof(address);
    if (configure_socket(fd) < 0 ||
        setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, &enabled, sizeof(enabled)) < 0 ||
        bind(fd, (struct sockaddr *)&address, size) < 0 || listen(fd, MUX_SOCKS_PENDING) < 0 ||
        getsockname(fd, (struct sockaddr *)&address, &size) < 0) { close(fd); return -1; }
    m->socks_listener = fd;
    m->socks_port = ntohs(address.sin_port);
    return 0;
}

uint16_t mux_socks_port(const struct mux_server *m) { return m->socks_port; }

static uint8_t socks_reason(uint32_t reason)
{
    switch (reason) {
    case MUX_DNS_ERROR: return 4;
    case MUX_REFUSED: return 5;
    case MUX_TIMEOUT: return 6;
    case MUX_UNREACHABLE: return 3;
    default: return 1;
    }
}

static void socks_reply(uint8_t reply[10], uint8_t reason)
{
    const uint8_t bytes[10] = {5, reason, 0, 1, 0, 0, 0, 0, 0, 0};
    memcpy(reply, bytes, 10);
}

int mux_ssh_connect(uint32_t id, bool *connecting, void *context)
{
    (void)id;
    /* Tests may override the loopback port; production always passes NULL. */
    uint16_t port = context ? *(uint16_t *)context : 22;
    int fd = socket(AF_INET, SOCK_STREAM, 0);
    if (fd < 0) return -1;
    int flags = fcntl(fd, F_GETFL), enabled = 1;
    struct sockaddr_in address = {.sin_family = AF_INET, .sin_port = htons(port),
                                 .sin_addr.s_addr = htonl(INADDR_LOOPBACK)};
    if (flags < 0 || fcntl(fd, F_SETFL, flags | O_NONBLOCK) < 0 ||
        fcntl(fd, F_SETFD, FD_CLOEXEC) < 0 ||
        setsockopt(fd, IPPROTO_TCP, TCP_NODELAY, &enabled, sizeof(enabled)) < 0) goto fail;
#ifdef SO_NOSIGPIPE
    if (setsockopt(fd, SOL_SOCKET, SO_NOSIGPIPE, &enabled, sizeof(enabled)) < 0) goto fail;
#endif
    int result = connect(fd, (struct sockaddr *)&address, sizeof(address));
    if (result < 0 && errno != EINPROGRESS) goto fail;
    *connecting = result < 0;
    return fd;
fail:
    close(fd);
    return -1;
}

static struct mux_stream *find_stream(struct mux_server *m, uint32_t id)
{
    for (size_t i = 0; i < MUX_STREAMS; ++i)
        if (m->streams[i].id == id) return &m->streams[i];
    return NULL;
}

static int accept_frame(struct mux_server *m)
{
    uint8_t type = m->frame[3];
    uint32_t id = read32(m->frame + 4), length = read32(m->frame + 8);
    const uint8_t *payload = m->frame + MUX_HEADER;
    if (type == MUX_PING || type == MUX_PONG) {
        if (id || length != 4) return protocol_error();
        if (type == MUX_PING) {
            if (m->pong_pending) return protocol_error();
            m->pong = read32(payload);
            m->pong_pending = true;
        }
        return 0;
    }
    if (!id || (!(id & 1) && m->socks_listener < 0)) return protocol_error();
    if (type == MUX_OPEN) {
        if (!(id & 1) || length != 4 || read32(payload) != MUX_WINDOW || id <= m->highest_id)
            return protocol_error();
        m->highest_id = id;
        struct mux_stream *s = NULL;
        for (size_t i = 0; i < MUX_STREAMS; ++i)
            if (!m->streams[i].id) { s = &m->streams[i]; break; }
        if (!s) {
            if (m->rejection_count == 16) return protocol_error();
            m->rejected[m->rejection_count++] = id;
            return 0;
        }
        s->id = id;
        s->credit = MUX_WINDOW;
        s->deadline = monotonic_time() + 10;
        s->fd = m->connector(id, &s->connecting, m->context);
        if (s->fd < 0) reset_stream(s, MUX_IO_ERROR);
        return 0;
    }
    if (type < MUX_OK || type > MUX_RESET || (type == MUX_OK && (id & 1))) return protocol_error();
    if ((type == MUX_BYTES && !length) || (type == MUX_FIN && length) ||
        ((type == MUX_OK || type == MUX_WINDOW_UPDATE || type == MUX_RESET) && length != 4)) return protocol_error();
    struct mux_stream *s = find_stream(m, id);
    /* Previously closed IDs may still have frames already in flight. */
    if (!s) return (id & 1 ? id <= m->highest_id : id < m->next_even) ? 0 : protocol_error();
    if (type == MUX_RESET) {
        if (s->initiator && !s->acknowledged && !s->reset && !s->rejected) {
            socks_reply(s->reply, socks_reason(read32(payload)));
            s->reply_length = 10;
            s->rejected = true;
        } else release_stream(s);
        return 0;
    }
    if (s->reset || s->rejected) return 0;
    switch (type) {
    case MUX_OK:
        if (!s->initiator || !s->open_sent || s->acknowledged || read32(payload) != MUX_WINDOW)
            return protocol_error();
        s->acknowledged = true;
        s->credit = MUX_WINDOW;
        socks_reply(s->reply, 0);
        s->reply_length = 10;
        break;
    case MUX_BYTES:
        if (!s->acknowledged || s->remote_fin || length > s->receive_credit) return protocol_error();
        if (s->incoming_offset) {
            memmove(s->incoming, s->incoming + s->incoming_offset, s->incoming_length - s->incoming_offset);
            s->incoming_length -= s->incoming_offset;
            s->incoming_offset = 0;
        }
        if (length > MUX_WINDOW - s->incoming_length) return protocol_error();
        memcpy(s->incoming + s->incoming_length, payload, length);
        s->incoming_length += length;
        s->receive_credit -= length;
        break;
    case MUX_WINDOW_UPDATE: {
        uint32_t credit = read32(payload);
        if (!s->acknowledged || !credit || credit > MUX_WINDOW - s->credit) return protocol_error();
        s->credit += credit;
        break;
    }
    case MUX_FIN:
        if (!s->acknowledged || s->remote_fin) return protocol_error();
        s->remote_fin = true;
        if (s->fin_sent && !s->incoming_length) release_stream(s);
        break;
    default: return protocol_error();
    }
    return 0;
}

int mux_feed(struct mux_server *m, const uint8_t *bytes, size_t length)
{
    while (length) {
        size_t count = m->frame_target - m->frame_length;
        if (count > length) count = length;
        memcpy(m->frame + m->frame_length, bytes, count);
        m->frame_length += count;
        bytes += count;
        length -= count;
        if (m->frame_length != m->frame_target) continue;
        if (m->frame_target == MUX_HEADER) {
            if (m->frame[0] != 'P' || m->frame[1] != 'L' || m->frame[2] != 1)
                return protocol_error();
            uint32_t payload = read32(m->frame + 8);
            if (payload > MUX_DATA) return protocol_error();
            m->frame_target += payload;
            if (payload) continue;
        }
        if (accept_frame(m) < 0) return -1;
        m->frame_length = 0;
        m->frame_target = MUX_HEADER;
    }
    return 0;
}

static void pending_release(struct socks_pending *p)
{
    if (p->fd >= 0) close(p->fd);
    memset(p, 0, sizeof(*p));
    p->fd = -1;
}

static void pending_fail(struct socks_pending *p, uint8_t reason)
{
    socks_reply(p->reply, reason);
    p->reply_length = 10;
    p->reply_offset = 0;
    p->close_after_reply = true;
}

static void pending_parse(struct mux_server *m, struct socks_pending *p)
{
    if (!p->stage) {
        if (p->target == 2 && p->input[0] == 5 && p->input[1]) {
            p->target += p->input[1];
            return;
        }
        bool supported = false;
        for (size_t n = 2; n < p->length; ++n) if (!p->input[n]) supported = true;
        p->reply[0] = 5;
        p->reply[1] = p->input[0] == 5 && supported ? 0 : 255;
        p->reply_length = 2;
        p->close_after_reply = p->reply[1] != 0;
        p->stage = 1;
        p->length = 0;
        p->target = 4;
        return;
    }
    if (p->target == 4) {
        if (p->input[0] != 5 || p->input[2]) { pending_fail(p, 1); return; }
        if (p->input[1] != 1) { pending_fail(p, 7); return; }
        switch (p->input[3]) {
        case 1: p->target = 10; break;
        case 3: p->target = 5; break;
        case 4: p->target = 22; break;
        default: pending_fail(p, 8); break;
        }
        return;
    }
    if (p->target == 5) {
        if (!p->input[4]) { pending_fail(p, 8); return; }
        p->target = 7 + p->input[4];
        return;
    }
    if (p->input[3] == 3) {
        for (size_t i = 5; i < p->target - 2; ++i)
            if (p->input[i] < 33 || p->input[i] > 126) { pending_fail(p, 8); return; }
    }
    if (!p->input[p->target - 2] && !p->input[p->target - 1]) { pending_fail(p, 1); return; }
    p->ready = true;
    p->ticket = ++m->next_ticket;
    p->deadline = monotonic_time() + MUX_SOCKS_WAIT;
}

static void pending_dispatch(struct mux_server *m)
{
    struct socks_pending *p = NULL;
    struct mux_stream *s = NULL;
    size_t external = 0;
    for (size_t i = 0; i < MUX_STREAMS; ++i) {
        if (!m->streams[i].id) s = &m->streams[i];
        else if (m->streams[i].initiator) external++;
    }
    /* Leave one slot available for an incoming SSH stream. */
    if (!s || external >= MUX_STREAMS - 1) return;
    for (size_t i = 0; i < MUX_SOCKS_PENDING; ++i) {
        struct socks_pending *candidate = &m->pending[i];
        if (candidate->fd >= 0 && candidate->ready &&
            (!p || candidate->ticket < p->ticket)) p = candidate;
    }
    if (!p) return;
    if (m->next_even >= UINT32_MAX - 1) { p->ready = false; pending_fail(p, 1); return; }
    s->id = m->next_even;
    m->next_even += 2;
    s->fd = p->fd;
    s->initiator = true;
    s->deadline = monotonic_time() + 30;
    write32(s->destination, MUX_WINDOW);
    memcpy(s->destination + 4, p->input + 3, p->target - 3);
    s->destination_length = p->target + 1;
    p->fd = -1;
    pending_release(p);
}

static int socks_step(struct mux_server *m)
{
    if (m->socks_listener < 0) return 0;
    for (size_t i = 0; i < MUX_SOCKS_PENDING; ++i) {
        struct socks_pending *p = &m->pending[i];
        if (p->fd < 0) continue;
        if (monotonic_time() > p->deadline) {
            if (p->ready) {
                p->ready = false;
                pending_fail(p, 6);
                p->deadline = monotonic_time() + 1;
            } else { pending_release(p); continue; }
        }
        if (p->reply_length) {
            ssize_t n = send(p->fd, p->reply + p->reply_offset,
                             p->reply_length - p->reply_offset, MSG_NOSIGNAL);
            if (n > 0) p->reply_offset += (size_t)n;
            else if (!n || (errno != EINTR && errno != EAGAIN && errno != EWOULDBLOCK)) {
                pending_release(p); continue;
            }
            if (p->reply_offset < p->reply_length) continue;
            p->reply_length = p->reply_offset = 0;
            if (p->close_after_reply) { pending_release(p); continue; }
        }
        if (p->ready) {
            uint8_t byte;
            ssize_t n = recv(p->fd, &byte, 1, MSG_PEEK);
            if (!n || (n < 0 && errno != EINTR && errno != EAGAIN && errno != EWOULDBLOCK))
                pending_release(p);
            continue;
        }
        ssize_t n = recv(p->fd, p->input + p->length, p->target - p->length, 0);
        if (n > 0) {
            p->length += (size_t)n;
            if (p->length == p->target) pending_parse(m, p);
        } else if (!n || (errno != EINTR && errno != EAGAIN && errno != EWOULDBLOCK)) pending_release(p);
    }
    for (size_t n = 0; n < 16; ++n) {
        int fd = accept(m->socks_listener, NULL, NULL);
        if (fd < 0) {
            if (errno == EINTR) continue;
            if (errno == EAGAIN || errno == EWOULDBLOCK) break;
            return -1;
        }
        struct socks_pending *p = NULL;
        for (size_t i = 0; i < MUX_SOCKS_PENDING; ++i)
            if (m->pending[i].fd < 0) { p = &m->pending[i]; break; }
        if (!p || configure_socket(fd) < 0) { close(fd); continue; }
        p->fd = fd;
        p->target = 2;
        p->deadline = monotonic_time() + 10;
    }
    pending_dispatch(m);
    return 0;
}

int mux_tcp_step(struct mux_server *m)
{
    for (size_t i = 0; i < MUX_STREAMS; ++i) {
        struct mux_stream *s = &m->streams[i];
        if (!s->id || s->fd < 0) continue;
        if (s->initiator && !s->acknowledged && !s->rejected && !s->reset &&
            monotonic_time() > s->deadline) {
            socks_reply(s->reply, 6);
            s->reply_length = 10;
            s->reset = MUX_TIMEOUT;
        }
        if (s->reply_length) {
            ssize_t n = send(s->fd, s->reply + s->reply_offset,
                             s->reply_length - s->reply_offset, MSG_NOSIGNAL);
            if (n > 0) s->reply_offset += (size_t)n;
            else if (!n || (errno != EINTR && errno != EAGAIN && errno != EWOULDBLOCK)) {
                reset_stream(s, MUX_CANCEL); s->reply_length = 0; continue;
            }
            if (s->reply_offset < s->reply_length) continue;
            s->reply_length = s->reply_offset = 0;
            if (s->rejected) { release_stream(s); continue; }
            if (s->reset) { reset_stream(s, s->reset); continue; }
        }
        if (s->initiator && !s->acknowledged) {
            uint8_t peek;
            ssize_t n = recv(s->fd, &peek, 1, MSG_PEEK);
            if (!n || (n < 0 && errno != EINTR && errno != EAGAIN && errno != EWOULDBLOCK))
                reset_stream(s, MUX_CANCEL);
            continue;
        }
        struct pollfd p = {.fd = s->fd};
        if (s->connecting || s->incoming_length > s->incoming_offset) p.events |= POLLOUT;
        if (s->acknowledged && !s->local_fin && !s->outgoing_length && s->credit) p.events |= POLLIN;
        if (poll(&p, 1, 0) < 0) { if (errno == EINTR) continue; return -1; }
        if (s->connecting) {
            if (monotonic_time() > s->deadline) { reset_stream(s, MUX_IO_ERROR); continue; }
            if (!(p.revents & (POLLOUT | POLLERR | POLLHUP))) continue;
            int error = 0;
            socklen_t size = sizeof(error);
            if (getsockopt(s->fd, SOL_SOCKET, SO_ERROR, &error, &size) < 0 || error)
                reset_stream(s, MUX_IO_ERROR);
            else s->connecting = false;
            continue;
        }
        if (p.revents & (POLLERR | POLLNVAL)) { reset_stream(s, MUX_IO_ERROR); continue; }
        if ((p.revents & POLLOUT) && s->incoming_length > s->incoming_offset) {
            ssize_t count = send(s->fd, s->incoming + s->incoming_offset,
                                 s->incoming_length - s->incoming_offset, MSG_NOSIGNAL);
            if (count > 0) { s->incoming_offset += (size_t)count; s->window += (uint32_t)count; }
            else if (!count || (errno != EAGAIN && errno != EWOULDBLOCK && errno != EINTR)) {
                reset_stream(s, MUX_IO_ERROR); continue;
            }
            if (s->incoming_offset == s->incoming_length) s->incoming_offset = s->incoming_length = 0;
        }
        if (s->remote_fin && !s->incoming_length && !s->shutdown) {
            if (shutdown(s->fd, SHUT_WR) < 0 && errno != ENOTCONN) { reset_stream(s, MUX_IO_ERROR); continue; }
            s->shutdown = true;
        }
        if ((p.revents & (POLLIN | POLLHUP)) && s->acknowledged && !s->local_fin &&
            !s->outgoing_length && s->credit) {
            size_t length = s->credit < MUX_DATA ? s->credit : MUX_DATA;
            ssize_t count = recv(s->fd, s->outgoing, length, 0);
            if (count > 0) s->outgoing_length = (size_t)count;
            else if (!count) s->local_fin = true;
            else if (errno != EAGAIN && errno != EWOULDBLOCK && errno != EINTR) reset_stream(s, MUX_IO_ERROR);
        }
        if (!s->reset && s->fin_sent && s->remote_fin && !s->incoming_length) release_stream(s);
    }
    return socks_step(m);
}

static size_t encode(uint8_t *p, uint8_t type, uint32_t id, const uint8_t *payload, size_t length)
{
    p[0] = 'P'; p[1] = 'L'; p[2] = 1; p[3] = type;
    write32(p + 4, id);
    write32(p + 8, (uint32_t)length);
    if (length) memcpy(p + MUX_HEADER, payload, length);
    return MUX_HEADER + length;
}

static size_t control(uint8_t *p, uint8_t type, uint32_t id, uint32_t value)
{
    uint8_t bytes[4];
    write32(bytes, value);
    return encode(p, type, id, bytes, sizeof(bytes));
}

size_t mux_next_frame(struct mux_server *m, uint8_t output[MUX_HEADER + MUX_DATA])
{
    if (m->rejection_count) {
        uint32_t id = m->rejected[0];
        memmove(m->rejected, m->rejected + 1, --m->rejection_count * sizeof(uint32_t));
        return control(output, MUX_RESET, id, MUX_LIMIT);
    }
    if (m->pong_pending) {
        m->pong_pending = false;
        return control(output, MUX_PONG, 0, m->pong);
    }
    /* OPEN IDs must stay monotonic even when slot scheduling wraps around. */
    struct mux_stream *opening = NULL;
    for (size_t i = 0; i < MUX_STREAMS; ++i) {
        struct mux_stream *s = &m->streams[i];
        if (s->id && s->initiator && !s->open_sent && !s->reset && !s->rejected &&
            (!opening || s->id < opening->id)) opening = s;
    }
    if (opening) {
        opening->open_sent = true;
        opening->receive_credit = MUX_WINDOW;
        m->cursor = ((size_t)(opening - m->streams) + 1) % MUX_STREAMS;
        return encode(output, MUX_OPEN_TCP, opening->id, opening->destination, opening->destination_length);
    }
    for (size_t n = 0; n < MUX_STREAMS; ++n) {
        size_t index = (m->cursor + n) % MUX_STREAMS;
        struct mux_stream *s = &m->streams[index];
        if (!s->id || s->rejected || (s->reset && s->reply_length)) continue;
        size_t length = 0;
        if (s->reset) {
            length = control(output, MUX_RESET, s->id, s->reset);
            release_stream(s);
        } else if (!s->initiator && !s->connecting && !s->acknowledged) {
            s->acknowledged = true;
            s->receive_credit = MUX_WINDOW;
            length = control(output, MUX_OK, s->id, MUX_WINDOW);
        } else if (s->window) {
            uint32_t credit = s->window;
            s->window = 0;
            s->receive_credit += credit;
            length = control(output, MUX_WINDOW_UPDATE, s->id, credit);
        } else if (s->outgoing_length) {
            length = encode(output, MUX_BYTES, s->id, s->outgoing, s->outgoing_length);
            s->credit -= (uint32_t)s->outgoing_length;
            s->outgoing_length = 0;
        } else if (s->local_fin && !s->fin_sent) {
            s->fin_sent = true;
            length = encode(output, MUX_FIN, s->id, NULL, 0);
            if (s->remote_fin && !s->incoming_length) release_stream(s);
        }
        if (length) { m->cursor = (index + 1) % MUX_STREAMS; return length; }
    }
    return 0;
}

size_t mux_active(const struct mux_server *m)
{
    size_t count = 0;
    for (size_t i = 0; i < MUX_STREAMS; ++i) if (m->streams[i].id) ++count;
    return count;
}

int mux_pump(struct mux_server *m, int wire, bool packet, size_t mtu)
{
    if (!mtu) { errno = EINVAL; return -1; }
    for (int n = 0; n < 16; ++n) {
        struct iovec iov = {.iov_base = m->wire_input, .iov_len = sizeof(m->wire_input)};
        struct msghdr message = {.msg_iov = &iov, .msg_iovlen = 1};
        ssize_t count = recvmsg(wire, &message, 0);
        if (count < 0) {
            if (errno == EINTR) continue;
            if (errno == EAGAIN || errno == EWOULDBLOCK) break;
            return -1;
        }
        if (!count) { errno = ECONNRESET; return -1; }
        if ((message.msg_flags & MSG_TRUNC) || mux_feed(m, m->wire_input, (size_t)count) < 0) return -1;
    }
    if (mux_tcp_step(m) < 0) return -1;
    for (int n = 0; n < 16; ++n) {
        if (!m->output_length) m->output_length = mux_next_frame(m, m->output);
        if (!m->output_length) break;
        size_t length = m->output_length - m->output_offset;
        if (length > mtu) length = mtu;
        ssize_t count = send(wire, m->output + m->output_offset, length, MSG_NOSIGNAL);
        if (count < 0) {
            if (errno == EINTR) continue;
            if (errno == EAGAIN || errno == EWOULDBLOCK) break;
            return -1;
        }
        if (!count || (packet && (size_t)count != length)) { errno = EIO; return -1; }
        m->output_offset += (size_t)count;
        if (m->output_offset == m->output_length) m->output_offset = m->output_length = 0;
    }
    return 0;
}
