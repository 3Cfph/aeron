/*
 * Copyright 2014 - 2017 Real Logic Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#define _GNU_SOURCE

#include <stddef.h>
#include <string.h>
#include <stdio.h>
#include <stdlib.h>
#include <ftw.h>
#include <fcntl.h>
#include <unistd.h>
#include <inttypes.h>
#include <errno.h>
#include <math.h>
#include <limits.h>
#include "protocol/aeron_udp_protocol.h"
#include "util/aeron_fileutil.h"
#include "aeron_driver_context.h"
#include "aeron_alloc.h"
#include "concurrent/aeron_mpsc_rb.h"
#include "concurrent/aeron_broadcast_transmitter.h"
#include "aeron_agent.h"

inline static const char *tmp_dir()
{
#if defined(_MSC_VER)
    static char buff[MAX_PATH+1];

    if (GetTempPath(MAX_PATH, &buff[0]) > 0)
    {
        dir = buff;
    }

    return buff;
#else
    const char *dir = "/tmp";

    if (getenv("TMPDIR"))
    {
        dir = getenv("TMPDIR");
    }

    return dir;
#endif
}

inline static bool has_file_separator_at_end(const char *path)
{
#if defined(_MSC_VER)
    return path[strlen(path) - 1] == '\';
#else
    return path[strlen(path) - 1] == '/';
#endif
}

inline static const char *username()
{
    const char *username = getenv("USER");
#if (_MSC_VER)
    if (NULL == username)
    {
        username = getenv("USERNAME");
        if (NULL == username)
        {
             username = "default";
        }
    }
#else
    if (NULL == username)
    {
        username = "default";
    }
#endif
    return username;
}

bool aeron_config_parse_bool(const char *str, bool def)
{
    if (NULL != str)
    {
        if (strncmp(str, "1", 1) == 0 || strncmp(str, "on", 2) == 0 || strncmp(str, "true", 4) == 0)
        {
            return true;
        }

        if (strncmp(str, "0", 1) == 0 || strncmp(str, "off", 3) == 0 || strncmp(str, "false", 5) == 0)
        {
            return false;
        }
    }

    return def;
}

uint64_t aeron_config_parse_uint64(const char *str, uint64_t def, uint64_t min, uint64_t max)
{
    uint64_t result = def;

    if (NULL != str)
    {
        uint64_t value = strtoull(str, NULL, 0);

        if (0 == value && EINVAL == errno)
        {
            value = def;
        }

        result = value;
        result = (result > max) ? max : result;
        result = (result < min) ? min : result;
    }

    return result;
}

int aeron_driver_context_init(aeron_driver_context_t **context)
{
    aeron_driver_context_t *_context = NULL;

    if (NULL == context)
    {
        /* TODO: EINVAL */
        return -1;
    }

    if (aeron_alloc((void **)&_context, sizeof(aeron_driver_context_t)) < 0)
    {
        return -1;
    }

    _context->cnc_map.addr = NULL;
    _context->aeron_dir = NULL;
    _context->conductor_proxy = NULL;
    _context->sender_proxy = NULL;
    _context->receiver_proxy = NULL;

    if (aeron_alloc((void **)&_context->aeron_dir, AERON_MAX_PATH) < 0)
    {
        return -1;
    }

    if (aeron_spsc_concurrent_array_queue_init(&_context->sender_command_queue, AERON_COMMAND_QUEUE_CAPACITY) < 0)
    {
        return -1;
    }

    if (aeron_spsc_concurrent_array_queue_init(&_context->receiver_command_queue, AERON_COMMAND_QUEUE_CAPACITY) < 0)
    {
        return -1;
    }

    if (aeron_mpsc_concurrent_array_queue_init(&_context->conductor_command_queue, AERON_COMMAND_QUEUE_CAPACITY) < 0)
    {
        return -1;
    }

    if ((_context->unicast_flow_control_supplier_func =
        aeron_flow_control_strategy_supplier_load("aeron_unicast_flow_control_strategy_supplier")) == NULL)
    {
        return -1;
    }

    if ((_context->multicast_flow_control_supplier_func =
        aeron_flow_control_strategy_supplier_load("aeron_max_multicast_flow_control_strategy_supplier")) == NULL)
    {
        return -1;
    }

#if defined(__linux__)
    snprintf(_context->aeron_dir, AERON_MAX_PATH - 1, "/dev/shm/aeron-%s", username());
#elif (_MSC_VER)
    snprintf(_context->aeron_dir, AERON_MAX_PATH - 1, "%s%saeron-%s", tmp_dir(), has_file_separator_at_end(tmp_dir()) ? "" : "\", username());
#else
    snprintf(_context->aeron_dir, AERON_MAX_PATH - 1, "%s%saeron-%s", tmp_dir(), has_file_separator_at_end(tmp_dir()) ? "" : "/", username());
#endif

    _context->threading_mode = AERON_THREADING_MODE_DEDICATED;
    _context->dirs_delete_on_start = false;
    _context->warn_if_dirs_exist = true;
    _context->driver_timeout_ms = 10 * 1000;
    _context->to_driver_buffer_length = 1024 * 1024 + AERON_RB_TRAILER_LENGTH;
    _context->to_clients_buffer_length = 1024 * 1024 + AERON_BROADCAST_BUFFER_TRAILER_LENGTH;
    _context->counters_values_buffer_length = 1024 * 1024;
    _context->counters_metadata_buffer_length = _context->counters_values_buffer_length * 2;
    _context->error_buffer_length = 1024 * 1024;
    _context->client_liveness_timeout_ns = 5 * 1000 * 1000 * 1000L;
    _context->term_buffer_length = 16 * 1024 * 1024;
    _context->ipc_term_buffer_length = 64 * 1024 * 1024;
    _context->mtu_length = 4096;
    _context->ipc_publication_window_length = 0;
    _context->publication_window_length = 0;
    _context->publication_linger_timeout_ns = 5 * 1000 * 1000 * 1000L;
    _context->socket_rcvbuf = 128 * 1024;
    _context->socket_sndbuf = 0;
    _context->multicast_ttl = 0;
    _context->send_to_sm_poll_ratio = 4;
    _context->status_message_timeout_ns = 200 * 1000 * 1000L;

    /* set from env */
    char *value = NULL;

    if ((value = getenv(AERON_DIR_ENV_VAR)))
    {
        snprintf(_context->aeron_dir, AERON_MAX_PATH - 1, "%s", value);
    }

    if ((value = getenv(AERON_THREADING_MODE_ENV_VAR)))
    {
        if (strncmp(value, "SHARED", sizeof("SHARED")) == 0)
        {
            _context->threading_mode = AERON_THREADING_MODE_SHARED;
        }
        else if (strncmp(value, "SHARED_NETWORK", sizeof("SHARED_NETWORK")) == 0)
        {
            _context->threading_mode = AERON_THREADING_MODE_SHARED_NETWORK;
        }
        else if (strncmp(value, "DEDICATED", sizeof("DEDICATED")) == 0)
        {
            _context->threading_mode = AERON_THREADING_MODE_DEDICATED;
        }
    }

    _context->dirs_delete_on_start =
        aeron_config_parse_bool(
            getenv(AERON_DIR_DELETE_ON_START_ENV_VAR),
            _context->dirs_delete_on_start);

    _context->term_buffer_sparse_file =
        aeron_config_parse_bool(
            getenv(AERON_TERM_BUFFER_SPARSE_FILE_ENV_VAR),
            _context->term_buffer_sparse_file);

    _context->to_driver_buffer_length =
        aeron_config_parse_uint64(
            getenv(AERON_TO_CONDUCTOR_BUFFER_LENGTH_ENV_VAR),
            _context->to_driver_buffer_length,
            1024 + AERON_RB_TRAILER_LENGTH,
            INT32_MAX);

    _context->to_clients_buffer_length =
        aeron_config_parse_uint64(
            getenv(AERON_TO_CLIENTS_BUFFER_LENGTH_ENV_VAR),
            _context->to_clients_buffer_length,
            1024 + AERON_BROADCAST_BUFFER_TRAILER_LENGTH,
            INT32_MAX);

    _context->counters_values_buffer_length =
        aeron_config_parse_uint64(
            getenv(AERON_COUNTERS_VALUES_BUFFER_LENGTH_ENV_VAR),
            _context->counters_values_buffer_length,
            1024,
            INT32_MAX);

    _context->counters_metadata_buffer_length = _context->counters_values_buffer_length * 2;

    _context->error_buffer_length =
        aeron_config_parse_uint64(
            getenv(AERON_ERROR_BUFFER_LENGTH_ENV_VAR),
            _context->error_buffer_length,
            1024,
            INT32_MAX);

    _context->client_liveness_timeout_ns =
        aeron_config_parse_uint64(
            getenv(AERON_CLIENT_LIVENESS_TIMEOUT_ENV_VAR),
            _context->client_liveness_timeout_ns,
            1000,
            INT64_MAX);

    _context->publication_linger_timeout_ns =
        aeron_config_parse_uint64(
            getenv(AERON_PUBLICATION_LINGER_TIMEOUT_ENV_VAR),
            _context->publication_linger_timeout_ns,
            1000,
            INT64_MAX);

    _context->term_buffer_length =
        aeron_config_parse_uint64(
            getenv(AERON_TERM_BUFFER_LENGTH_ENV_VAR),
            _context->term_buffer_length,
            1024,
            INT32_MAX);

    _context->ipc_term_buffer_length =
        aeron_config_parse_uint64(
            getenv(AERON_IPC_TERM_BUFFER_LENGTH_ENV_VAR),
            _context->ipc_term_buffer_length,
            1024,
            INT32_MAX);

    _context->mtu_length =
        aeron_config_parse_uint64(
            getenv(AERON_MTU_LENGTH_ENV_VAR),
            _context->mtu_length,
            AERON_DATA_HEADER_LENGTH,
            AERON_MAX_UDP_PAYLOAD_LENGTH);

    _context->ipc_publication_window_length =
        aeron_config_parse_uint64(
            getenv(AERON_IPC_PUBLICATION_TERM_WINDOW_LENGTH_ENV_VAR),
            _context->ipc_publication_window_length,
            0,
            INT32_MAX);

    _context->publication_window_length =
        aeron_config_parse_uint64(
            getenv(AERON_PUBLICATION_TERM_WINDOW_LENGTH_ENV_VAR),
            _context->publication_window_length,
            0,
            INT32_MAX);

    _context->socket_rcvbuf =
        aeron_config_parse_uint64(
            getenv(AERON_SOCKET_SO_RCVBUF_ENV_VAR),
            _context->socket_rcvbuf,
            0,
            INT32_MAX);

    _context->socket_sndbuf =
        aeron_config_parse_uint64(
            getenv(AERON_SOCKET_SO_SNDBUF_ENV_VAR),
            _context->socket_sndbuf,
            0,
            INT32_MAX);

    _context->multicast_ttl =
        (uint8_t)aeron_config_parse_uint64(
            getenv(AERON_SOCKET_MULTICAST_TTL_ENV_VAR),
            _context->multicast_ttl,
            0,
            255);

    _context->send_to_sm_poll_ratio =
        (uint8_t)aeron_config_parse_uint64(
            getenv(AERON_SEND_TO_STATUS_POLL_RATIO_ENV_VAR),
            _context->send_to_sm_poll_ratio,
            1,
            INT32_MAX);

    _context->status_message_timeout_ns =
        aeron_config_parse_uint64(
            getenv(AERON_RCV_STATUS_MESSAGE_TIMEOUT_ENV_VAR),
            _context->status_message_timeout_ns,
            1000,
            INT64_MAX);

    _context->to_driver_buffer = NULL;
    _context->to_clients_buffer = NULL;
    _context->counters_values_buffer = NULL;
    _context->counters_metadata_buffer = NULL;
    _context->error_buffer = NULL;

    _context->nano_clock = aeron_nanoclock;
    _context->epoch_clock = aeron_epochclock;

    _context->conductor_idle_strategy_func =
        aeron_idle_strategy_load("yielding", &_context->conductor_idle_strategy_state);
    _context->shared_idle_strategy_func =
        aeron_idle_strategy_load("yielding", &_context->shared_idle_strategy_state);
    _context->shared_network_idle_strategy_func =
        aeron_idle_strategy_load("yielding", &_context->shared_network_idle_strategy_state);
    _context->sender_idle_strategy_func =
        aeron_idle_strategy_load("noop", &_context->sender_idle_strategy_state);
    _context->receiver_idle_strategy_func =
        aeron_idle_strategy_load("noop", &_context->receiver_idle_strategy_state);

    _context->usable_fs_space_func = aeron_usable_fs_space;
    _context->map_raw_log_func = aeron_map_raw_log;
    _context->map_raw_log_close_func = aeron_map_raw_log_close;

    *context = _context;
    return 0;
}

int aeron_driver_context_close(aeron_driver_context_t *context)
{
    if (NULL == context)
    {
        /* TODO: EINVAL */
        return -1;
    }

    aeron_mpsc_concurrent_array_queue_close(&context->conductor_command_queue);
    aeron_spsc_concurrent_array_queue_close(&context->sender_command_queue);
    aeron_spsc_concurrent_array_queue_close(&context->receiver_command_queue);

    aeron_unmap(&context->cnc_map);

    aeron_free((void *)context->aeron_dir);
    aeron_free(context->conductor_idle_strategy_state);
    aeron_free(context->shared_idle_strategy_state);
    aeron_free(context);
    return 0;
}

static int unlink_func(const char *path, const struct stat *sb, int type_flag, struct FTW *ftw)
{
    if (remove(path) != 0)
    {
        /* TODO: change to normal error handling */
        perror(path);
    }

    return 0; /* just continue */
}

int aeron_dir_delete(const char *dirname)
{
    return nftw(dirname, unlink_func, 64, FTW_DEPTH | FTW_PHYS);
}

bool aeron_is_driver_active_with_cnc(
    aeron_mapped_file_t *cnc_mmap, int64_t timeout, int64_t now, aeron_log_func_t log_func)
{
    char buffer[AERON_MAX_PATH];
    aeron_cnc_metadata_t *metadata = (aeron_cnc_metadata_t *)cnc_mmap->addr;

    if (AERON_CNC_VERSION != metadata->cnc_version)
    {
        snprintf(
            buffer,
            sizeof(buffer) - 1,
            "ERROR: aeron cnc file version not understood: version=%d",
            metadata->cnc_version);
        log_func(buffer);
    }
    else
    {
        aeron_mpsc_rb_t rb;

        if (aeron_mpsc_rb_init(
            &rb, aeron_cnc_to_driver_buffer(metadata), (size_t)metadata->to_driver_buffer_length) != 0)
        {
            snprintf(
                buffer, sizeof(buffer) - 1, "ERROR: aeron cnc file could not init to-driver buffer");
            log_func(buffer);
        }
        else
        {
            int64_t timestamp = aeron_mpsc_rb_consumer_heartbeat_time_value(&rb);

            int64_t diff = now - timestamp;

            snprintf(
                buffer, sizeof(buffer) - 1, "INFO: Aeron toDriver consumer heartbeat is %" PRId64 " ms old", diff);
            log_func(buffer);

            if (diff <= timeout)
            {
                return true;
            }
        }
    }

    return false;
}

bool aeron_is_driver_active(const char *dirname, int64_t timeout, int64_t now, aeron_log_func_t log_func)
{
    struct stat sb;
    char buffer[AERON_MAX_PATH];
    bool result = false;

    if (stat(dirname, &sb) == 0 && (S_ISDIR(sb.st_mode)))
    {
        aeron_mapped_file_t cnc_map = { NULL, 0 };

        snprintf(buffer, sizeof(buffer) - 1, "INFO: Aeron directory %s exists", dirname);
        log_func(buffer);

        snprintf(buffer, sizeof(buffer) - 1, "%s/%s", dirname, AERON_CNC_FILE);
        if (aeron_map_existing_file(&cnc_map, buffer) < 0)
        {
            /* TODO: EINVAL? or ESTATE? */
            snprintf(buffer, sizeof(buffer) - 1, "INFO: failed to mmap CnC file");
            log_func(buffer);
            return false;
        }

        snprintf(buffer, sizeof(buffer) - 1, "INFO: Aeron CnC file %s/%s exists", dirname, AERON_CNC_FILE);
        log_func(buffer);

        result = aeron_is_driver_active_with_cnc(&cnc_map, timeout, aeron_epochclock(), log_func);

        aeron_unmap(&cnc_map);
    }

    return result;
}

extern uint8_t *aeron_cnc_to_driver_buffer(aeron_cnc_metadata_t *metadata);
extern uint8_t *aeron_cnc_to_clients_buffer(aeron_cnc_metadata_t *metadata);
extern uint8_t *aeron_cnc_counters_metadata_buffer(aeron_cnc_metadata_t *metadata);
extern uint8_t *aeron_cnc_counters_values_buffer(aeron_cnc_metadata_t *metadata);
extern uint8_t *aeron_cnc_error_log_buffer(aeron_cnc_metadata_t *metadata);
extern size_t aeron_cnc_computed_length(size_t total_length_of_buffers);
extern size_t aeron_cnc_length(aeron_driver_context_t *context);

extern size_t aeron_ipc_publication_term_window_length(aeron_driver_context_t *context, size_t term_length);
extern size_t aeron_network_publication_term_window_length(aeron_driver_context_t *context, size_t term_length);

int aeron_driver_context_set(aeron_driver_context_t *context, const char *setting, const char *value)
{
    if (NULL == setting || NULL == value)
    {
        /* TODO: EINVAL */
        return -1;
    }

    /* TODO: */

    return -1;
}
