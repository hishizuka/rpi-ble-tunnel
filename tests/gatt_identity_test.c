#define main rpi_ble_tunneld_entry_for_test
#include "../pi/rpi-ble-tunneld.c"
#undef main
#include <assert.h>

int main(void)
{
    char hostname[64];
    memset(hostname, 'a', 63);
    hostname[63] = '\0';
    struct daemon d = {.hostname = hostname, .name = hostname, .psm = 129, .internet_mode = TRUE};
    GVariant *value = g_variant_ref_sink(char_value(&d, 3));
    gsize length = 0;
    const uint8_t *bytes = g_variant_get_fixed_array(value, &length, 1);
    assert(length == 63 && memcmp(bytes, hostname, length) == 0);
    g_variant_unref(value);

    GVariant *uuid = g_variant_ref_sink(get_property(NULL, NULL, SERVICE_PATH "/char3", GATT_CHAR, "UUID", NULL, &d));
    assert(strcmp(g_variant_get_string(uuid, NULL), HOSTNAME_UUID) == 0);
    g_variant_unref(uuid);
    assert(char_index(SERVICE_PATH "/char3") == 3);
    assert(char_index(SERVICE_PATH "/char4") == -1);

    value = g_variant_ref_sink(char_value(&d, 0));
    bytes = g_variant_get_fixed_array(value, &length, 1);
    assert(length == 2 && bytes[0] == 1 && bytes[1] == 0);
    g_variant_unref(value);
    value = g_variant_ref_sink(char_value(&d, 1));
    bytes = g_variant_get_fixed_array(value, &length, 1);
    assert(length == 2 && bytes[0] == 129 && bytes[1] == 0);
    g_variant_unref(value);
    value = g_variant_ref_sink(char_value(&d, 2));
    bytes = g_variant_get_fixed_array(value, &length, 1);
    assert(length == 4 && bytes[0] == 8);
    g_variant_unref(value);
    puts("GATT full hostname and existing protocol metadata: PASS");
    char *directory = g_dir_make_tmp("rpi-ble-tunnel-state-XXXXXX", NULL);
    assert(directory);
    d.state_file = g_build_filename(directory, "link.json", NULL);
    d.start_ticks = 123;
    d.socks_port = 1080;
    publish_link_state(&d, TRUE);
    char *contents = NULL;
    assert(g_file_get_contents(d.state_file, &contents, NULL, NULL));
    assert(strstr(contents, "\"connected\":true") && strstr(contents, "\"start_ticks\":123"));
    assert(strstr(contents, d.session) && strstr(contents, "\"socks_port\":1080"));
    g_free(contents);
    char *first_session = g_strdup(d.session);
    publish_link_state(&d, FALSE);
    assert(g_file_get_contents(d.state_file, &contents, NULL, NULL));
    assert(strstr(contents, "\"connected\":false") && strstr(contents, "\"session\":\"\""));
    g_free(contents);
    publish_link_state(&d, TRUE);
    assert(strcmp(first_session, d.session));
    unlink(d.state_file);
    rmdir(directory);
    g_free(directory); g_free(d.state_file); g_free(d.session); g_free(first_session);
    puts("Atomic link state, disconnect and new session identity: PASS");
    return 0;
}
