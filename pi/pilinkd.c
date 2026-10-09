#include "echo.h"
#include "bridge.h"
#include "mux.h"

#include <bluetooth/bluetooth.h>
#include <bluetooth/l2cap.h>
#include <errno.h>
#include <fcntl.h>
#include <gio/gio.h>
#include <glib-unix.h>
#include <signal.h>
#include <stdio.h>
#include <string.h>
#include <sys/socket.h>
#include <unistd.h>

#define SERVICE_UUID "6f6d0001-8e6d-4c8a-a8bf-5b8a2a786a21"
#define VERSION_UUID "6f6d0002-8e6d-4c8a-a8bf-5b8a2a786a21"
#define PSM_UUID "6f6d0003-8e6d-4c8a-a8bf-5b8a2a786a21"
#define CAPS_UUID "6f6d0004-8e6d-4c8a-a8bf-5b8a2a786a21"
#define HOSTNAME_UUID "6f6d0005-8e6d-4c8a-a8bf-5b8a2a786a21"
#define ROOT_PATH "/org/pilink"
#define SERVICE_PATH ROOT_PATH "/service0"
#define AD_PATH ROOT_PATH "/advertisement0"
#define GATT_SERVICE "org.bluez.GattService1"
#define GATT_CHAR "org.bluez.GattCharacteristic1"
#define AD_IFACE "org.bluez.LEAdvertisement1"

static const char xml[] =
    "<node>"
    "<interface name='org.freedesktop.DBus.ObjectManager'>"
    "<method name='GetManagedObjects'><arg type='a{oa{sa{sv}}}' direction='out'/></method>"
    "</interface>"
    "<interface name='org.bluez.GattService1'>"
    "<property name='UUID' type='s' access='read'/>"
    "<property name='Primary' type='b' access='read'/>"
    "<property name='Includes' type='ao' access='read'/>"
    "</interface>"
    "<interface name='org.bluez.GattCharacteristic1'>"
    "<method name='ReadValue'><arg type='a{sv}' direction='in'/>"
    "<arg type='ay' direction='out'/></method>"
    "<property name='UUID' type='s' access='read'/>"
    "<property name='Service' type='o' access='read'/>"
    "<property name='Flags' type='as' access='read'/>"
    "<property name='Value' type='ay' access='read'/>"
    "</interface>"
    "<interface name='org.bluez.LEAdvertisement1'>"
    "<method name='Release'/>"
    "<property name='Type' type='s' access='read'/>"
    "<property name='ServiceUUIDs' type='as' access='read'/>"
    "<property name='LocalName' type='s' access='read'/>"
    "<property name='Discoverable' type='b' access='read'/>"
    "</interface>"
    "</node>";

struct daemon {
    GMainLoop *loop;
    GDBusConnection *bus;
    char *adapter;
    char *name;
    char *hostname;
    char *state_file;
    char *session;
    guint64 start_ticks;
    uint16_t psm;
    int listener;
    int client;
    int tcp;
    gboolean ssh_mode;
    gboolean mux_mode;
    gboolean internet_mode;
    uint16_t socks_port;
    struct mux_server *mux;
    guint mux_source;
    size_t mux_mtu;
    gboolean tcp_connecting;
    struct bridge bridge;
    guint tcp_source;
    guint tcp_timeout;
    guint client_source;
    guint listener_source;
    guint sigint_source;
    guint sigterm_source;
    struct echo_buffer echo;
    guint64 received;
    guint64 echoed;
    gboolean gatt_registered;
    gboolean ad_registered;
    int result;
};

static const char *char_paths[] = {
    SERVICE_PATH "/char0", SERVICE_PATH "/char1", SERVICE_PATH "/char2", SERVICE_PATH "/char3"
};
static const char *char_uuids[] = {VERSION_UUID, PSM_UUID, CAPS_UUID, HOSTNAME_UUID};

static int char_index(const char *path)
{
    for (size_t i = 0; i < G_N_ELEMENTS(char_paths); ++i)
        if (strcmp(path, char_paths[i]) == 0)
            return i;
    return -1;
}

static GVariant *char_value(struct daemon *d, int index)
{
    if (index == 3)
        return g_variant_new_fixed_array(G_VARIANT_TYPE_BYTE, d->hostname, strlen(d->hostname), 1);
    uint8_t version[] = {1, 0};
    uint8_t psm[] = {(uint8_t)d->psm, (uint8_t)(d->psm >> 8)};
    uint8_t caps[] = {d->internet_mode ? 8 : d->mux_mode ? 4 : d->ssh_mode ? 2 : 1, 0, 0, 0};
    const uint8_t *data = index == 0 ? version : index == 1 ? psm : caps;
    return g_variant_new_fixed_array(G_VARIANT_TYPE_BYTE, data, index == 2 ? 4 : 2, 1);
}

static GVariant *get_property(GDBusConnection *bus, const char *sender,
                             const char *path, const char *interface,
                             const char *property, GError **error, gpointer user_data)
{
    (void)bus; (void)sender;
    struct daemon *d = user_data;
    int index = char_index(path);
    if (strcmp(interface, GATT_SERVICE) == 0) {
        if (strcmp(property, "UUID") == 0) return g_variant_new_string(SERVICE_UUID);
        if (strcmp(property, "Primary") == 0) return g_variant_new_boolean(TRUE);
        if (strcmp(property, "Includes") == 0) return g_variant_new_objv(NULL, 0);
    } else if (strcmp(interface, GATT_CHAR) == 0 && index >= 0) {
        if (strcmp(property, "UUID") == 0) return g_variant_new_string(char_uuids[index]);
        if (strcmp(property, "Service") == 0) return g_variant_new_object_path(SERVICE_PATH);
        if (strcmp(property, "Flags") == 0) {
            const char *flags[] = {"read", NULL};
            return g_variant_new_strv(flags, -1);
        }
        if (strcmp(property, "Value") == 0) return char_value(d, index);
    } else if (strcmp(interface, AD_IFACE) == 0) {
        if (strcmp(property, "Type") == 0) return g_variant_new_string("peripheral");
        if (strcmp(property, "LocalName") == 0) return g_variant_new_string(d->name);
        if (strcmp(property, "Discoverable") == 0) return g_variant_new_boolean(TRUE);
        if (strcmp(property, "ServiceUUIDs") == 0) {
            const char *uuids[] = {SERVICE_UUID, NULL};
            return g_variant_new_strv(uuids, -1);
        }
    }
    g_set_error(error, G_DBUS_ERROR, G_DBUS_ERROR_UNKNOWN_PROPERTY, "Unknown property");
    return NULL;
}

static void add_object(GVariantBuilder *objects, struct daemon *d,
                       const char *path, const char *interface, const char **properties)
{
    GVariantBuilder props, interfaces;
    g_variant_builder_init(&props, G_VARIANT_TYPE("a{sv}"));
    for (int i = 0; properties[i]; ++i)
        g_variant_builder_add(&props, "{sv}", properties[i],
                             get_property(NULL, NULL, path, interface, properties[i], NULL, d));
    g_variant_builder_init(&interfaces, G_VARIANT_TYPE("a{sa{sv}}"));
    g_variant_builder_add(&interfaces, "{sa{sv}}", interface, &props);
    g_variant_builder_add(objects, "{oa{sa{sv}}}", path, &interfaces);
}

static void method_call(GDBusConnection *bus, const char *sender, const char *path,
                        const char *interface, const char *method, GVariant *parameters,
                        GDBusMethodInvocation *invocation, gpointer user_data)
{
    (void)bus; (void)sender; (void)interface;
    struct daemon *d = user_data;
    if (strcmp(method, "GetManagedObjects") == 0) {
        GVariantBuilder objects;
        g_variant_builder_init(&objects, G_VARIANT_TYPE("a{oa{sa{sv}}}"));
        const char *service_props[] = {"UUID", "Primary", "Includes", NULL};
        const char *char_props[] = {"UUID", "Service", "Flags", "Value", NULL};
        add_object(&objects, d, SERVICE_PATH, GATT_SERVICE, service_props);
        for (size_t i = 0; i < G_N_ELEMENTS(char_paths); ++i)
            add_object(&objects, d, char_paths[i], GATT_CHAR, char_props);
        g_dbus_method_invocation_return_value(invocation,
            g_variant_new("(a{oa{sa{sv}}})", &objects));
    } else if (strcmp(method, "ReadValue") == 0) {
        int index = char_index(path);
        if (index < 0) {
            g_dbus_method_invocation_return_dbus_error(invocation,
                "org.bluez.Error.NotSupported", "Unknown characteristic");
            return;
        }
        GVariant *options;
        guint16 offset = 0;
        g_variant_get(parameters, "(@a{sv})", &options);
        g_variant_lookup(options, "offset", "q", &offset);
        g_variant_unref(options);
        GVariant *value = g_variant_ref_sink(char_value(d, index));
        gsize length;
        const uint8_t *bytes = g_variant_get_fixed_array(value, &length, 1);
        if (offset > length) {
            g_dbus_method_invocation_return_dbus_error(invocation,
                "org.bluez.Error.InvalidOffset", "Offset exceeds value length");
        } else {
            g_dbus_method_invocation_return_value(invocation,
                g_variant_new("(@ay)", g_variant_new_fixed_array(
                    G_VARIANT_TYPE_BYTE, bytes + offset, length - offset, 1)));
        }
        g_variant_unref(value);
    } else if (strcmp(method, "Release") == 0) {
        d->ad_registered = FALSE;
        g_dbus_method_invocation_return_value(invocation, NULL);
        g_printerr("Advertisement released by BlueZ; stopping.\n");
        d->result = 1;
        g_main_loop_quit(d->loop);
    } else {
        g_dbus_method_invocation_return_dbus_error(invocation,
            "org.freedesktop.DBus.Error.UnknownMethod", "Unknown method");
    }
}

static const GDBusInterfaceVTable vtable = {
    .method_call = method_call, .get_property = get_property
};

static gboolean configure_fd(int fd)
{
    int flags = fcntl(fd, F_GETFL);
    return flags >= 0 && fcntl(fd, F_SETFL, flags | O_NONBLOCK) == 0 &&
           fcntl(fd, F_SETFD, FD_CLOEXEC) == 0;
}

static void publish_link_state(struct daemon *d, gboolean connected)
{
    if (!d->state_file) return;
    if (connected) {
        g_free(d->session);
        d->session = g_uuid_string_random();
    }
    char *contents = g_strdup_printf(
        "{\"connected\":%s,\"pid\":%ld,\"start_ticks\":%" G_GUINT64_FORMAT
        ",\"session\":\"%s\",\"socks_port\":%u}\n",
        connected ? "true" : "false", (long)getpid(), d->start_ticks,
        connected ? d->session : "", d->socks_port);
    GError *error = NULL;
    /* GLib replaces the file atomically so readers cannot see partial JSON. */
    if (!g_file_set_contents(d->state_file, contents, -1, &error)) {
        g_printerr("Link state: %s\n", error->message);
        g_clear_error(&error);
        unlink(d->state_file);
    }
    g_free(contents);
}

static void disconnect_client(struct daemon *d)
{
    publish_link_state(d, FALSE);
    if (d->client_source) g_source_remove(d->client_source);
    if (d->tcp_source) g_source_remove(d->tcp_source);
    if (d->tcp_timeout) g_source_remove(d->tcp_timeout);
    if (d->mux_source) g_source_remove(d->mux_source);
    d->mux_source = 0;
    d->client_source = d->tcp_source = d->tcp_timeout = 0;
    if (d->client >= 0) {
        if (d->mux_mode) {
            g_print("Multiplex transport disconnected; closing all streams.\n");
        } else if (d->ssh_mode) {
            g_print("SSH disconnected, BLE->TCP=%" G_GUINT64_FORMAT
                    " TCP->BLE=%" G_GUINT64_FORMAT " bytes.\n",
                    d->bridge.packet_to_tcp, d->bridge.tcp_to_packet);
        } else {
            g_print("Client disconnected, received=%" G_GUINT64_FORMAT
                    " echoed=%" G_GUINT64_FORMAT " bytes.\n", d->received, d->echoed);
        }
        close(d->client);
        d->client = -1;
    }
    if (d->tcp >= 0) { close(d->tcp); d->tcp = -1; }
    mux_server_free(d->mux);
    d->mux = NULL;
    d->tcp_connecting = FALSE;
    memset(&d->bridge, 0, sizeof(d->bridge));
    memset(&d->echo, 0, sizeof(d->echo));
}

static gboolean ssh_packet_ready(gint fd, GIOCondition condition, gpointer user_data);
static gboolean ssh_tcp_ready(gint fd, GIOCondition condition, gpointer user_data);

static GIOCondition bridge_conditions(unsigned events)
{
    return G_IO_ERR | G_IO_HUP | G_IO_NVAL |
        ((events & BRIDGE_READ) ? G_IO_IN : 0) | ((events & BRIDGE_WRITE) ? G_IO_OUT : 0);
}

static unsigned bridge_ready_events(GIOCondition condition)
{
    return ((condition & (G_IO_IN | G_IO_HUP)) ? BRIDGE_READ : 0) |
           ((condition & G_IO_OUT) ? BRIDGE_WRITE : 0);
}

static void watch_bridge(struct daemon *d)
{
    if (d->client_source) g_source_remove(d->client_source);
    if (d->tcp_source) g_source_remove(d->tcp_source);
    d->client_source = d->tcp_source = 0;
    if (bridge_finished(&d->bridge)) {
        disconnect_client(d);
        return;
    }
    unsigned packet_events = bridge_packet_events(&d->bridge);
    unsigned tcp_events = bridge_tcp_events(&d->bridge);
    if (packet_events)
        d->client_source = g_unix_fd_add(d->client, bridge_conditions(packet_events), ssh_packet_ready, d);
    if (tcp_events)
        d->tcp_source = g_unix_fd_add(d->tcp, bridge_conditions(tcp_events), ssh_tcp_ready, d);
}

static gboolean ssh_packet_ready(gint fd, GIOCondition condition, gpointer user_data)
{
    (void)fd;
    struct daemon *d = user_data;
    d->client_source = 0;
    if ((condition & (G_IO_ERR | G_IO_NVAL)) ||
        bridge_packet_step(&d->bridge, bridge_ready_events(condition)) < 0) {
        if (condition & (G_IO_ERR | G_IO_NVAL)) g_print("SSH BLE channel closed.\n");
        else g_printerr("SSH BLE I/O: %s\n", g_strerror(errno));
        disconnect_client(d);
    } else {
        watch_bridge(d);
    }
    return G_SOURCE_REMOVE;
}

static gboolean ssh_tcp_ready(gint fd, GIOCondition condition, gpointer user_data)
{
    struct daemon *d = user_data;
    d->tcp_source = 0;
    if (d->tcp_connecting) {
        int error = 0;
        socklen_t length = sizeof(error);
        if (getsockopt(fd, SOL_SOCKET, SO_ERROR, &error, &length) < 0 || error ||
            (condition & (G_IO_ERR | G_IO_HUP | G_IO_NVAL))) {
            g_printerr("SSH TCP connect: %s\n", g_strerror(error ? error : errno));
            disconnect_client(d);
            return G_SOURCE_REMOVE;
        }
        d->tcp_connecting = FALSE;
        if (d->tcp_timeout) g_source_remove(d->tcp_timeout);
        d->tcp_timeout = 0;
        g_print("SSH bridge connected to 127.0.0.1:22\n");
    } else if ((condition & (G_IO_ERR | G_IO_NVAL)) ||
               bridge_tcp_step(&d->bridge, bridge_ready_events(condition)) < 0) {
        g_printerr("SSH TCP I/O closed: %s\n", g_strerror(errno));
        disconnect_client(d);
        return G_SOURCE_REMOVE;
    }
    watch_bridge(d);
    return G_SOURCE_REMOVE;
}

static gboolean tcp_connect_timeout(gpointer user_data)
{
    struct daemon *d = user_data;
    d->tcp_timeout = 0;
    g_printerr("SSH TCP connect timed out.\n");
    disconnect_client(d);
    return G_SOURCE_REMOVE;
}

static void start_ssh_bridge(struct daemon *d, uint16_t mtu)
{
    bool connecting;
    d->tcp = mux_ssh_connect(0, &connecting, NULL);
    if (d->tcp < 0) {
        g_printerr("SSH TCP connect: %s\n", g_strerror(errno));
        disconnect_client(d);
        return;
    }
    bridge_init(&d->bridge, d->client, d->tcp, mtu);
    if (connecting) {
        d->tcp_connecting = TRUE;
        d->tcp_source = g_unix_fd_add(d->tcp, G_IO_OUT | G_IO_ERR | G_IO_HUP, ssh_tcp_ready, d);
        d->tcp_timeout = g_timeout_add_seconds(10, tcp_connect_timeout, d);
    } else {
        g_print("SSH bridge connected to 127.0.0.1:22\n");
        watch_bridge(d);
    }
}

static gboolean client_ready(gint fd, GIOCondition condition, gpointer user_data);

static void watch_client(struct daemon *d)
{
    GIOCondition condition = G_IO_ERR | G_IO_HUP | G_IO_NVAL;
    condition |= d->echo.length ? G_IO_OUT : G_IO_IN;
    d->client_source = g_unix_fd_add(d->client, condition, client_ready, d);
}

static gboolean client_ready(gint fd, GIOCondition condition, gpointer user_data)
{
    struct daemon *d = user_data;
    d->client_source = 0;
    if (condition & (G_IO_ERR | G_IO_HUP | G_IO_NVAL)) {
        disconnect_client(d);
        return G_SOURCE_REMOVE;
    }
    int result;
    if (d->echo.length) {
        size_t pending = d->echo.length - d->echo.offset;
        size_t count = MIN(pending, d->echo.send_mtu);
        result = echo_send(fd, &d->echo);
        if (result > 0) d->echoed += count;
    } else {
        result = echo_receive(fd, &d->echo);
        if (result > 0) d->received += d->echo.length;
    }
    if (result < 0) {
        if (result != -2) g_printerr("Echo I/O: %s\n", g_strerror(errno));
        disconnect_client(d);
    } else {
        watch_client(d);
    }
    return G_SOURCE_REMOVE;
}

struct mux_source {
    GSource source;
    struct daemon *daemon;
    GPollFD fds[MUX_POLL_MAX];
    size_t count;
};

static void mux_update_watch(GSource *source)
{
    struct mux_source *watch = (struct mux_source *)source;
    struct pollfd fds[MUX_POLL_MAX];
    size_t count = mux_pollfds(watch->daemon->mux, watch->daemon->client, fds);
    gboolean changed = count != watch->count;
    for (size_t i = 0; i < count && !changed; ++i)
        changed = watch->fds[i].fd != fds[i].fd || watch->fds[i].events != fds[i].events;
    if (!changed) return;
    for (size_t i = 0; i < watch->count; ++i) g_source_remove_poll(source, &watch->fds[i]);
    watch->count = count;
    for (size_t i = 0; i < watch->count; ++i) {
        watch->fds[i] = (GPollFD){.fd = fds[i].fd, .events = fds[i].events};
        g_source_add_poll(source, &watch->fds[i]);
    }
}

static gboolean mux_prepare(GSource *source, gint *timeout)
{
    /* Rebuilding watches here would wake GLib's own poll on every idle iteration. */
    *timeout = mux_poll_timeout(((struct mux_source *)source)->daemon->mux);
    return *timeout == 0;
}

static gboolean mux_check(GSource *source)
{
    struct mux_source *watch = (struct mux_source *)source;
    for (size_t i = 0; i < watch->count; ++i) if (watch->fds[i].revents) return TRUE;
    return mux_poll_timeout(watch->daemon->mux) == 0;
}

static gboolean mux_dispatch(GSource *source, GSourceFunc callback, gpointer user_data)
{
    (void)callback; (void)user_data;
    struct daemon *d = ((struct mux_source *)source)->daemon;
    if (mux_pump(d->mux, d->client, true, d->mux_mtu) < 0) {
        g_printerr("Multiplex transport: %s\n", g_strerror(errno));
        d->mux_source = 0;
        disconnect_client(d);
        return G_SOURCE_REMOVE;
    }
    mux_update_watch(source);
    return G_SOURCE_CONTINUE;
}

static guint watch_mux(struct daemon *d)
{
    static GSourceFuncs functions = {.prepare = mux_prepare, .check = mux_check, .dispatch = mux_dispatch};
    GSource *source = g_source_new(&functions, sizeof(struct mux_source));
    ((struct mux_source *)source)->daemon = d;
    mux_update_watch(source);
    g_source_set_name(source, "PiLink multiplex I/O");
    guint id = g_source_attach(source, g_main_loop_get_context(d->loop));
    g_source_unref(source);
    return id;
}

static gboolean accept_client(gint fd, GIOCondition condition, gpointer user_data)
{
    struct daemon *d = user_data;
    if (condition & (G_IO_ERR | G_IO_HUP | G_IO_NVAL)) {
        d->listener_source = 0;
        d->result = 1;
        g_main_loop_quit(d->loop);
        return G_SOURCE_REMOVE;
    }
    struct sockaddr_l2 peer = {0};
    socklen_t length = sizeof(peer);
    int client = accept(fd, (struct sockaddr *)&peer, &length);
    if (client < 0) {
        if (errno != EAGAIN && errno != EWOULDBLOCK && errno != EINTR)
            g_printerr("accept: %s\n", g_strerror(errno));
        return G_SOURCE_CONTINUE;
    }
    if (d->client >= 0 || !configure_fd(client)) {
        g_printerr("Rejecting extra client or invalid socket.\n");
        close(client);
        return G_SOURCE_CONTINUE;
    }
    uint16_t mtu = 0;
    length = sizeof(mtu);
    if (getsockopt(client, SOL_BLUETOOTH, BT_SNDMTU, &mtu, &length) < 0 || mtu == 0) {
        g_printerr("Cannot read negotiated send MTU: %s\n", g_strerror(errno));
        close(client);
        return G_SOURCE_CONTINUE;
    }
    char address[18];
    ba2str(&peer.l2_bdaddr, address);
    g_print("L2CAP connected: %s, send MTU=%u\n", address, mtu);
    d->client = client;
    d->echo.send_mtu = mtu;
    d->received = 0;
    d->echoed = 0;
    if (d->mux_mode) {
        d->mux = mux_server_new(mux_ssh_connect, NULL);
        d->mux_mtu = mtu;
        if (!d->mux) disconnect_client(d);
        else if (d->internet_mode && mux_enable_socks(d->mux, d->socks_port) < 0) {
            g_printerr("SOCKS5 listener: %s\n", g_strerror(errno));
            disconnect_client(d);
        } else {
            if (d->internet_mode) g_print("SOCKS5 READY 127.0.0.1:%u -> BLE -> Central TCP\n", d->socks_port);
            d->mux_source = watch_mux(d);
            if (d->internet_mode) publish_link_state(d, TRUE);
        }
    } else if (d->ssh_mode) start_ssh_bridge(d, mtu);
    else watch_client(d);
    return G_SOURCE_CONTINUE;
}

static int open_listener(const char *address, uint16_t *psm)
{
    int fd = socket(AF_BLUETOOTH, SOCK_SEQPACKET, BTPROTO_L2CAP);
    if (fd < 0) return -1;
    struct sockaddr_l2 local = {
        .l2_family = AF_BLUETOOTH, .l2_psm = htobs(*psm),
        .l2_bdaddr_type = BDADDR_LE_PUBLIC
    };
    if (str2ba(address, &local.l2_bdaddr) != 0) {
        errno = EINVAL;
        goto fail;
    }
    struct bt_security security = {.level = BT_SECURITY_LOW};
    uint8_t mode = BT_MODE_LE_FLOWCTL;
    uint16_t mtu = 16384;
    if (!configure_fd(fd) ||
        bind(fd, (struct sockaddr *)&local, sizeof(local)) < 0 ||
        setsockopt(fd, SOL_BLUETOOTH, BT_MODE, &mode, sizeof(mode)) < 0 ||
        setsockopt(fd, SOL_BLUETOOTH, BT_SECURITY, &security, sizeof(security)) < 0 ||
        setsockopt(fd, SOL_BLUETOOTH, BT_RCVMTU, &mtu, sizeof(mtu)) < 0 ||
        listen(fd, 1) < 0) goto fail;
    socklen_t length = sizeof(local);
    if (getsockname(fd, (struct sockaddr *)&local, &length) < 0) goto fail;
    *psm = btohs(local.l2_psm);
    if (*psm < 0x80 || *psm > 0xff) {
        errno = EPROTO;
        goto fail;
    }
    return fd;
fail:;
    int saved_errno = errno;
    close(fd);
    errno = saved_errno;
    return -1;
}

static void advertisement_registered(GObject *object, GAsyncResult *result, gpointer user_data)
{
    struct daemon *d = user_data;
    GError *error = NULL;
    GVariant *reply = g_dbus_connection_call_finish(G_DBUS_CONNECTION(object), result, &error);
    if (!reply) {
        g_printerr("RegisterAdvertisement: %s\n", error->message);
        g_error_free(error);
        d->result = 1;
        g_main_loop_quit(d->loop);
        return;
    }
    g_variant_unref(reply);
    d->ad_registered = TRUE;
    g_print("READY name=%s PSM=%u (0x%02x) service=%s mode=%s\n",
            d->name, d->psm, d->psm, SERVICE_UUID, d->internet_mode ? "internet" : d->mux_mode ? "mux" : d->ssh_mode ? "ssh" : "echo");
}

static void gatt_registered(GObject *object, GAsyncResult *result, gpointer user_data)
{
    struct daemon *d = user_data;
    GError *error = NULL;
    GVariant *reply = g_dbus_connection_call_finish(G_DBUS_CONNECTION(object), result, &error);
    if (!reply) {
        g_printerr("RegisterApplication: %s\n", error->message);
        g_error_free(error);
        d->result = 1;
        g_main_loop_quit(d->loop);
        return;
    }
    g_variant_unref(reply);
    d->gatt_registered = TRUE;
    g_dbus_connection_call(d->bus, "org.bluez", d->adapter,
        "org.bluez.LEAdvertisingManager1", "RegisterAdvertisement",
        g_variant_new("(oa{sv})", AD_PATH, NULL), NULL,
        G_DBUS_CALL_FLAGS_NONE, 15000, NULL, advertisement_registered, d);
}

static gboolean stop_daemon(gpointer user_data)
{
    struct daemon *d = user_data;
    g_main_loop_quit(d->loop);
    return G_SOURCE_CONTINUE;
}

static void bus_closed(GDBusConnection *bus, gboolean vanished, GError *error, gpointer user_data)
{
    (void)bus; (void)vanished;
    struct daemon *d = user_data;
    g_printerr("D-Bus disconnected: %s\n", error ? error->message : "closed");
    d->result = 1;
    g_main_loop_quit(d->loop);
}

static void bluez_vanished(GDBusConnection *bus, const char *name, gpointer user_data)
{
    (void)bus; (void)name;
    struct daemon *d = user_data;
    g_printerr("BlueZ is unavailable; stopping.\n");
    d->result = 1;
    g_main_loop_quit(d->loop);
}

int main(int argc, char **argv)
{
    setvbuf(stdout, NULL, _IOLBF, 0);
    char *adapter_name = NULL;
    char *local_name = NULL;
    char *mode = NULL;
    char *state_file = NULL;
    gint requested_psm = 0;
    gint socks_port = 1080;
    GOptionEntry entries[] = {
        {"adapter", 'a', 0, G_OPTION_ARG_STRING, &adapter_name, "BlueZ adapter (default: hci0)", "hciN"},
        {"name", 'n', 0, G_OPTION_ARG_STRING, &local_name, "Legacy BLE name override (default: system hostname)", "NAME"},
        {"psm", 'p', 0, G_OPTION_ARG_INT, &requested_psm, "LE PSM: 0 for automatic, or 128..255", "PSM"},
        {"mode", 'm', 0, G_OPTION_ARG_STRING, &mode, "Transport mode (default: internet)", "ssh|echo|mux|internet"},
        {"socks-port", 0, 0, G_OPTION_ARG_INT, &socks_port, "Internet mode loopback SOCKS5 port (default: 1080)", "PORT"},
        {"state-file", 0, 0, G_OPTION_ARG_FILENAME, &state_file, "Atomic link state for the optional network service", "PATH"},
        {NULL}
    };
    GError *error = NULL;
    GOptionContext *options = g_option_context_new("- PiLink BLE L2CAP server");
    g_option_context_add_main_entries(options, entries, NULL);
    if (!g_option_context_parse(options, &argc, &argv, &error)) {
        g_printerr("%s\n", error->message);
        g_error_free(error);
        g_option_context_free(options);
        return 2;
    }
    g_option_context_free(options);
    if (!mode) mode = g_strdup("internet");
    if (strcmp(mode, "ssh") && strcmp(mode, "echo") && strcmp(mode, "mux") && strcmp(mode, "internet")) {
        g_printerr("Mode must be ssh, echo, mux or internet.\n");
        return 2;
    }
    if (argc != 1 || socks_port < 1024 || socks_port > 65535 || (requested_psm != 0 && (requested_psm < 128 || requested_psm > 255)) ||
        (local_name && (strlen(local_name) == 0 || strlen(local_name) > 63 || !g_utf8_validate(local_name, -1, NULL)))) {
        g_printerr("Invalid arguments: PSM must be 0 or 128..255; BLE name must be 1..63 UTF-8 bytes.\n");
        return 2;
    }
    if (!adapter_name) adapter_name = g_strdup("hci0");
    if (!g_str_has_prefix(adapter_name, "hci") || strlen(adapter_name) <= 3 ||
        strspn(adapter_name + 3, "0123456789") != strlen(adapter_name + 3)) {
        g_printerr("Invalid adapter name.\n");
        return 2;
    }
    char *hostname = g_ascii_strdown(g_get_host_name(), -1);
    if (g_str_has_suffix(hostname, ".local")) hostname[strlen(hostname) - 6] = '\0';
    if (strlen(hostname) > 63 || !g_regex_match_simple("\\A[a-z0-9]([a-z0-9-]*[a-z0-9])?\\z", hostname, 0, 0)) {
        g_printerr("System hostname must be a DNS label of 1..63 characters.\n");
        g_free(hostname);
        return 2;
    }
    struct daemon d = {
        .loop = g_main_loop_new(NULL, FALSE),
        .adapter = g_strdup_printf("/org/bluez/%s", adapter_name),
        .name = local_name ? local_name : g_strdup(hostname),
        .hostname = hostname,
        .state_file = state_file,
        .psm = (uint16_t)requested_psm,
        .listener = -1, .client = -1, .tcp = -1, .result = 1,
        .ssh_mode = strcmp(mode, "ssh") == 0,
        .mux_mode = strcmp(mode, "mux") == 0 || strcmp(mode, "internet") == 0,
        .internet_mode = strcmp(mode, "internet") == 0,
        .socks_port = (uint16_t)socks_port
    };
    if (state_file) {
        char *stat = NULL;
        if (g_file_get_contents("/proc/self/stat", &stat, NULL, NULL)) {
            char *end = strrchr(stat, ')');
            if (end && end[1] == ' ') {
                char **fields = g_strsplit(end + 2, " ", 0);
                if (g_strv_length(fields) > 19)
                    d.start_ticks = g_ascii_strtoull(fields[19], NULL, 10);
                g_strfreev(fields);
            }
            g_free(stat);
        }
        publish_link_state(&d, FALSE);
    }
    GDBusNodeInfo *info = NULL;
    guint registrations[7] = {0};
    guint bluez_watch = 0;
    d.bus = g_bus_get_sync(G_BUS_TYPE_SYSTEM, NULL, &error);
    if (!d.bus) goto dbus_fail;
    GVariant *adapter_properties = g_dbus_connection_call_sync(d.bus, "org.bluez", d.adapter,
        "org.freedesktop.DBus.Properties", "GetAll", g_variant_new("(s)", "org.bluez.Adapter1"),
        G_VARIANT_TYPE("(a{sv})"), G_DBUS_CALL_FLAGS_NONE, 10000, NULL, &error);
    if (!adapter_properties) goto dbus_fail;
    GVariant *properties;
    g_variant_get(adapter_properties, "(@a{sv})", &properties);
    const char *address = NULL;
    gboolean powered = FALSE;
    g_variant_lookup(properties, "Address", "&s", &address);
    g_variant_lookup(properties, "Powered", "b", &powered);
    if (!address || !powered) {
        g_printerr("Adapter is missing or powered off. Use: sudo bluetoothctl power on\n");
        g_variant_unref(properties);
        g_variant_unref(adapter_properties);
        goto cleanup;
    }
    d.listener = open_listener(address, &d.psm);
    g_variant_unref(properties);
    g_variant_unref(adapter_properties);
    if (d.listener < 0) {
        g_printerr("LE L2CAP listener: %s\n", g_strerror(errno));
        goto cleanup;
    }
    info = g_dbus_node_info_new_for_xml(xml, &error);
    if (!info) goto dbus_fail;
    const char *paths[] = {ROOT_PATH, SERVICE_PATH, char_paths[0], char_paths[1], char_paths[2], char_paths[3], AD_PATH};
    const char *interfaces[] = {"org.freedesktop.DBus.ObjectManager", GATT_SERVICE,
                               GATT_CHAR, GATT_CHAR, GATT_CHAR, GATT_CHAR, AD_IFACE};
    for (size_t i = 0; i < G_N_ELEMENTS(paths); ++i) {
        registrations[i] = g_dbus_connection_register_object(d.bus, paths[i],
            g_dbus_node_info_lookup_interface(info, interfaces[i]), &vtable, &d, NULL, &error);
        if (!registrations[i]) goto dbus_fail;
    }
    g_dbus_connection_set_exit_on_close(d.bus, FALSE);
    g_signal_connect(d.bus, "closed", G_CALLBACK(bus_closed), &d);
    bluez_watch = g_bus_watch_name_on_connection(d.bus, "org.bluez", G_BUS_NAME_WATCHER_FLAGS_NONE,
        NULL, bluez_vanished, &d, NULL);
    d.listener_source = g_unix_fd_add(d.listener, G_IO_IN | G_IO_ERR | G_IO_HUP,
                                     accept_client, &d);
    d.sigint_source = g_unix_signal_add(SIGINT, stop_daemon, &d);
    d.sigterm_source = g_unix_signal_add(SIGTERM, stop_daemon, &d);
    d.result = 0;
    /* Registration must be asynchronous because BlueZ calls back into our object manager. */
    g_dbus_connection_call(d.bus, "org.bluez", d.adapter, "org.bluez.GattManager1", "RegisterApplication",
        g_variant_new("(oa{sv})", ROOT_PATH, NULL), NULL,
        G_DBUS_CALL_FLAGS_NONE, 15000, NULL, gatt_registered, &d);
    g_main_loop_run(d.loop);
    goto cleanup;
dbus_fail:
    g_printerr("D-Bus: %s\n", error ? error->message : "unknown error");
    g_clear_error(&error);
cleanup:
    if (bluez_watch) g_bus_unwatch_name(bluez_watch);
    if (d.listener_source) g_source_remove(d.listener_source);
    if (d.sigint_source) g_source_remove(d.sigint_source);
    if (d.sigterm_source) g_source_remove(d.sigterm_source);
    disconnect_client(&d);
    if (d.state_file) unlink(d.state_file);
    if (d.listener >= 0) close(d.listener);
    if (d.bus && !g_dbus_connection_is_closed(d.bus)) {
        if (d.ad_registered) {
            GVariant *reply = g_dbus_connection_call_sync(d.bus, "org.bluez", d.adapter,
                "org.bluez.LEAdvertisingManager1", "UnregisterAdvertisement",
                g_variant_new("(o)", AD_PATH), NULL, G_DBUS_CALL_FLAGS_NONE, 3000, NULL, NULL);
            if (reply) g_variant_unref(reply);
        }
        if (d.gatt_registered) {
            GVariant *reply = g_dbus_connection_call_sync(d.bus, "org.bluez", d.adapter,
                "org.bluez.GattManager1", "UnregisterApplication",
                g_variant_new("(o)", ROOT_PATH), NULL, G_DBUS_CALL_FLAGS_NONE, 3000, NULL, NULL);
            if (reply) g_variant_unref(reply);
        }
        for (size_t i = 0; i < G_N_ELEMENTS(registrations); ++i)
            if (registrations[i]) g_dbus_connection_unregister_object(d.bus, registrations[i]);
        /* Closing this bus connection also removes registrations interrupted during startup. */
        g_dbus_connection_close_sync(d.bus, NULL, NULL);
    }
    if (info) g_dbus_node_info_unref(info);
    if (d.bus) g_object_unref(d.bus);
    g_main_loop_unref(d.loop);
    g_free(d.adapter); g_free(d.name); g_free(d.hostname); g_free(adapter_name); g_free(mode);
    g_free(d.state_file); g_free(d.session);
    return d.result;
}
