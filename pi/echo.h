#ifndef RPI_BLE_TUNNEL_ECHO_H
#define RPI_BLE_TUNNEL_ECHO_H

#include <stddef.h>
#include <stdint.h>

/* One SDU is buffered at a time to apply backpressure without growing memory. */
struct echo_buffer {
    uint8_t data[65535];
    size_t length;
    size_t offset;
    size_t send_mtu;
};

/* Return 1 for progress, 0 for would-block, -1 for error, -2 for EOF. */
int echo_receive(int fd, struct echo_buffer *buffer);
int echo_send(int fd, struct echo_buffer *buffer);

#endif
