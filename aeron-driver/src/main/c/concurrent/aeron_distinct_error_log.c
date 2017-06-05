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

#include <string.h>
#include <stdio.h>
#include <stdatomic.h>
#include "aeron_alloc.h"
#include "aeron_distinct_error_log.h"
#include "aeron_atomic.h"
#include "aeron_driver_context.h"

typedef struct aeron_distinct_error_log_observations_pimpl_stct
{
    _Atomic(aeron_distinct_observation_t *) observations;
    atomic_size_t num_observations;
}
aeron_distinct_error_log_observations_pimpl_t;

int aeron_distinct_error_log_init(
    aeron_distinct_error_log_t *log,
    uint8_t *buffer,
    size_t buffer_size,
    aeron_clock_func_t clock,
    aeron_resource_linger_func_t linger)
{
    if (NULL == log || NULL == clock || NULL == linger)
    {
        /* TODO: EINVAL */
        return -1;
    }

    if (aeron_alloc((void **)&log->observations_pimpl, sizeof(aeron_distinct_error_log_observations_pimpl_t)) < 0)
    {
        return -1;
    }

    log->buffer = buffer;
    log->buffer_capacity = buffer_size;
    log->clock = clock;
    log->linger_resource = linger;
    log->next_offset = 0;
    atomic_store(&log->observations_pimpl->num_observations, 0);
    atomic_store(&log->observations_pimpl->observations, NULL);
    pthread_mutex_init(&log->mutex, NULL);

    return 0;
}

static aeron_distinct_observation_t *aeron_distinct_error_log_find_observation(
    aeron_distinct_observation_t *observations,
    size_t num_observations,
    int error_code,
    const char *description)
{
    for (size_t i = 0; i < num_observations; i++)
    {
        if (observations[i].error_code == error_code &&
            strncmp(observations[i].description, description, AERON_MAX_PATH) == 0)
        {
            return &observations[i];
        }
    }

    return NULL;
}

static aeron_distinct_observation_t *aeron_distinct_error_log_new_observation(
    aeron_distinct_error_log_t *log,
    size_t existing_num_observations,
    int64_t timestamp,
    int error_code,
    const char *description,
    const char *message)
{
    size_t num_observations = atomic_load(&log->observations_pimpl->num_observations);
    aeron_distinct_observation_t *observations = atomic_load(&log->observations_pimpl->observations);
    aeron_distinct_observation_t *observation = NULL;

    if ((observation = aeron_distinct_error_log_find_observation(
        observations, existing_num_observations, error_code, description)) == NULL)
    {
        char encoded_error[AERON_MAX_PATH];

        snprintf(encoded_error, sizeof(encoded_error) - 1, "%d: %s %s", error_code, description, message);

        size_t encoded_error_length = strlen(encoded_error);
        size_t length = AERON_ERROR_LOG_HEADER_LENGTH + encoded_error_length;
        aeron_distinct_observation_t *new_array = NULL;
        size_t offset = log->next_offset;
        aeron_error_log_entry_t *entry = (aeron_error_log_entry_t *)(log->buffer + offset);

        if ((offset + length) > log->buffer_capacity ||
            aeron_alloc((void **)&new_array, sizeof(aeron_distinct_observation_t) * (num_observations + 1)) < 0)
        {
            return NULL;
        }

        memcpy(log->buffer + offset + AERON_ERROR_LOG_HEADER_LENGTH, encoded_error, encoded_error_length);
        entry->first_observation_timestamp = timestamp;
        entry->observation_count = 0;

        log->next_offset = AERON_ALIGN(offset + length, AERON_ERROR_LOG_RECORD_ALIGNMENT);

        new_array[0].error_code = error_code;
        new_array[0].description = strndup(description, AERON_MAX_PATH);
        new_array[0].offset = offset;
        memcpy(&new_array[1], observations, sizeof(aeron_distinct_observation_t) * num_observations);

        atomic_store(&log->observations_pimpl->observations, new_array);
        atomic_store(&log->observations_pimpl->num_observations, num_observations + 1);

        AERON_PUT_ORDERED(entry->length, length);

        observation = &new_array[0];

        if (NULL != log->linger_resource)
        {
            log->linger_resource((uint8_t *)observations);
        }
    }

    return observation;
}

int aeron_distinct_error_log_record(
    aeron_distinct_error_log_t *log, int error_code, const char *description, const char *message)
{
    int64_t timestamp = 0;
    aeron_distinct_observation_t *observation = NULL;

    if (NULL == log)
    {
        /* TODO: EINVAL */
        return -1;
    }

    timestamp = log->clock();
    size_t num_observations = atomic_load(&log->observations_pimpl->num_observations);
    aeron_distinct_observation_t *observations = atomic_load(&log->observations_pimpl->observations);
    if ((observation = aeron_distinct_error_log_find_observation(
        observations, num_observations, error_code, description)) == NULL)
    {
        pthread_mutex_lock(&log->mutex);

        observation = aeron_distinct_error_log_new_observation(
            log, num_observations, timestamp, error_code, description, message);

        pthread_mutex_unlock(&log->mutex);

        if (NULL == observation)
        {
            // TODO: if no room, then ENOMEM
            return -1;
        }
    }

    aeron_error_log_entry_t *entry = (aeron_error_log_entry_t *)(log->buffer + observation->offset);

    int32_t dest;

    AERON_GET_AND_ADD_INT32(dest, entry->observation_count, 1);
    AERON_PUT_ORDERED(entry->last_observation_timestamp, timestamp);

    return 0;
}

bool aeron_error_log_exists(const uint8_t *buffer, size_t buffer_size)
{
    aeron_error_log_entry_t *entry = (aeron_error_log_entry_t *)buffer;
    int32_t length = 0;

    AERON_GET_VOLATILE(length, entry->length);
    return (0 != length);
}

size_t aeron_error_log_read(
    const uint8_t *buffer,
    size_t buffer_size,
    aeron_error_log_reader_func_t reader,
    void *clientd,
    int64_t since_timestamp)
{
    size_t entries = 0;
    size_t offset = 0;

    while (offset < buffer_size)
    {
        aeron_error_log_entry_t *entry = (aeron_error_log_entry_t *)(buffer + offset);
        int32_t length = 0;

        AERON_GET_VOLATILE(length, entry->length);

        if (0 == length)
        {
            break;
        }

        int64_t last_observation_timestamp = 0;
        AERON_GET_VOLATILE(last_observation_timestamp, entry->last_observation_timestamp);

        if (last_observation_timestamp >= since_timestamp)
        {
            ++entries;

            reader(
                entry->observation_count,
                entry->first_observation_timestamp,
                last_observation_timestamp,
                (const char *)(buffer + offset + AERON_ERROR_LOG_HEADER_LENGTH),
                length - AERON_ERROR_LOG_HEADER_LENGTH,
                clientd);
        }

        offset += AERON_ALIGN(length, AERON_ERROR_LOG_RECORD_ALIGNMENT);
    }

    return entries;
}

size_t aeron_distinct_error_log_num_observations(aeron_distinct_error_log_t *log)
{
    return atomic_load(&log->observations_pimpl->num_observations);
}
