/*
 * Quasar: lightweight threads and actors for the JVM.
 * Copyright (C) 2013, Parallel Universe Software Co. All rights reserved.
 * 
 * This program and the accompanying materials are dual-licensed under
 * either the terms of the Eclipse Public License v1.0 as published by
 * the Eclipse Foundation
 *  
 *   or (per the licensee's choosing)
 *  
 * under the terms of the GNU Lesser General Public License version 3.0
 * as published by the Free Software Foundation.
 */
package co.paralleluniverse.strands.channels;

import co.paralleluniverse.fibers.SuspendExecution;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * <b>All methods of this interface must only be called by the channel's owner.</b>
 *
 * @author pron
 */
public interface LongReceivePort extends ReceivePort<Long> {
    long receiveLong() throws SuspendExecution, InterruptedException;

    long receiveLong(long timeout, TimeUnit unit) throws SuspendExecution, InterruptedException, TimeoutException;
}
