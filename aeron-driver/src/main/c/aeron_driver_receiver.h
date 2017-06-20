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

#ifndef AERON_AERON_DRIVER_RECEIVER_H
#define AERON_AERON_DRIVER_RECEIVER_H

#include "aeron_driver_context.h"

typedef struct aeron_driver_receiver_stct
{
    aeron_driver_context_t *context;
    aeron_spsc_concurrent_array_queue_t *command_queue;
    aeron_mpsc_concurrent_array_queue_t *conductor_command_queue;
}
aeron_driver_receiver_t;

int aeron_driver_receiver_init(aeron_driver_receiver_t *receiver, aeron_driver_context_t *context);

int aeron_driver_receiver_do_work(void *clientd);
void aeron_driver_receiver_on_close(void *clientd);

#endif //AERON_AERON_DRIVER_RECEIVER_H
