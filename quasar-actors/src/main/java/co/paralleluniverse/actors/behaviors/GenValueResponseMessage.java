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
package co.paralleluniverse.actors.behaviors;

import java.beans.ConstructorProperties;

/**
 *
 * @author pron
 */
public class GenValueResponseMessage<V> extends GenResponseMessage implements IdMessage {
    private final V value;
    
    @ConstructorProperties({"id", "value"})
    public GenValueResponseMessage(Object id, V value) {
        super(id);
        this.value = value;
    }

    public V getValue() {
        return value;
    }

    @Override
    protected String contentString() {
        return super.contentString() + " value: " + value;
    }
}
