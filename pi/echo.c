#include "echo.h"

#include <errno.h>
#include <sys/socket.h>

#ifndef MSG_NOSIGNAL
#define MSG_NOSIGNAL 0
#endif

int echo_receive(int fd, struct echo_buffer *buffer)
{
    if (buffer->length != 0) {
        errno = EBUSY;
        return -1;
    }
    struct iovec iov = {.iov_base = buffer->data, .iov_len = sizeof(buffer->data)};
    struct msghdr message = {.msg_iov = &iov, .msg_iovlen = 1};
    ssize_t count;
    do {
        count = recvmsg(fd, &message, 0);
    } while (count < 0 && errno == EINTR);
    if (count < 0)
        return (errno == EAGAIN || errno == EWOULDBLOCK) ? 0 : -1;
    if (count == 0)
        return -2;
    if (message.msg_flags & MSG_TRUNC) {
        errno = EMSGSIZE;
        return -1;
    }
    buffer->length = (size_t)count;
    buffer->offset = 0;
    return 1;
}

int echo_send(int fd, struct echo_buffer *buffer)
{
    if (buffer->send_mtu == 0 || buffer->offset > buffer->length) {
        errno = EINVAL;
        return -1;
    }
    if (buffer->length == 0)
        return 0;
    size_t count = buffer->length - buffer->offset;
    if (count > buffer->send_mtu)
        count = buffer->send_mtu;
    ssize_t written;
    do {
        written = send(fd, buffer->data + buffer->offset, count, MSG_NOSIGNAL);
    } while (written < 0 && errno == EINTR);
    if (written < 0)
        return (errno == EAGAIN || errno == EWOULDBLOCK) ? 0 : -1;
    /* A sequenced-packet socket must transmit the entire SDU atomically. */
    if ((size_t)written != count) {
        errno = EIO;
        return -1;
    }
    buffer->offset += count;
    if (buffer->offset == buffer->length) {
        buffer->length = 0;
        buffer->offset = 0;
    }
    return 1;
}
