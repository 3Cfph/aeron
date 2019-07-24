/*
 *  Copyright 2014-2019 Real Logic Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.aeron.exceptions;

/**
 * Generic timeout has occurred while waiting on some action or event.
 */
public class TimeoutException extends AeronException
{
    public TimeoutException(final Type type)
    {
        super(type);
    }

    public TimeoutException(final String message, final Type type)
    {
        super(message, type);
    }

    public TimeoutException(final String message, final Throwable cause, final Type type)
    {
        super(message, cause, type);
    }

    public TimeoutException(final Throwable cause, final Type type)
    {
        super(cause, type);
    }

    protected TimeoutException(
        final String message,
        final Throwable cause,
        final boolean enableSuppression,
        final boolean writableStackTrace,
        final Type type)
    {
        super(message, cause, enableSuppression, writableStackTrace, type);
    }
}
